package com.speedbet.api.sportsdata.odds;

import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.MmaDataService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * Persists pre-match and live moneyline odds for MMA/UFC bouts.
 *
 * ── How MMA persistence differs from OddsPersistenceService ─────────────
 *
 *   Football persistence uses Match (home/away teams, score, kick-off time).
 *   MMA persistence works from raw ESPN event maps — there is no "Match" entity
 *   for UFC events in the current data model. Instead, callers pass:
 *
 *     • eventId   — ESPN event ID (e.g. "600033284" for UFC 315)
 *     • boutIndex — 0-based index into competitions[] (0 = main event)
 *     • matchId   — the UUID of the corresponding Match row in the DB
 *                   (MMA matches must be pre-created by the match ingestion layer)
 *
 * ── Pre-match flow ───────────────────────────────────────────────────────
 *
 *   1. Resolves fighter names + records from MmaDataService scoreboard cache.
 *   2. Calls MmaOddsGeneratorService to produce bookmaker lines.
 *   3. Deletes existing "mma_moneyline" rows for the matchId, then saves fresh ones.
 *
 * ── Live flow ────────────────────────────────────────────────────────────
 *
 *   1. Calls MmaDataService.getEventSummary() (always fresh, never cached).
 *   2. Extracts the specific bout's current round + dominanceScore (see below).
 *   3. Calls MmaLiveOddsGeneratorService to produce live lines.
 *   4. Replaces only "mma_live_moneyline" rows for the matchId.
 *
 * ── dominanceScore derivation ────────────────────────────────────────────
 *
 *   ESPN summary provides compuStrikes (significant strikes landed/attempted)
 *   for each fighter.  If present:
 *
 *     dominanceScore = (f1Strikes - f2Strikes) / max(f1Strikes + f2Strikes, 1)
 *
 *   Clamped to [-1.0, +1.0].  Falls back to 0.0 (even fight) if stats are absent.
 *
 * ── Selection normalisation ──────────────────────────────────────────────
 *
 *   Fighter display names are mapped to "HOME" / "AWAY" on persist, matching
 *   the convention used by OddsPersistenceService for football.
 *
 * ── Markets persisted ────────────────────────────────────────────────────
 *
 *   Pre-match: "mma_moneyline"       — HOME / AWAY
 *   Live:      "mma_live_moneyline"  — HOME / AWAY
 *
 * ── Caching note ─────────────────────────────────────────────────────────
 *
 *   Pre-match generation uses MmaDataService.getEvents() (cached 5 min).
 *   Live generation uses MmaDataService.getEventSummary() (always fresh).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MmaOddsPersistenceService {

    private final OddsRepository           oddsRepository;
    private final MmaOddsGeneratorService  preMatchGenerator;
    private final MmaLiveOddsGeneratorService liveGenerator;
    private final MmaDataService           mmaDataService;

    // ═════════════════════════════════════════════════════════════════════
    //  PRE-MATCH
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generates and persists pre-match moneyline odds for a single UFC bout.
     *
     * <p>Resolves fighter info from the MmaDataService scoreboard cache; if the
     * event is not in the scoreboard the call is a no-op and a warning is logged.
     *
     * @param eventId   ESPN event ID (from MmaDataService.getEvents())
     * @param boutIndex 0-based index into the event's competitions[] array
     *                  (0 = main event by ESPN convention)
     * @param matchId   UUID of the pre-created Match row in the DB
     */
    @Transactional
    public void generateAndSavePreMatchOdds(String eventId, int boutIndex, UUID matchId) {
        BoutContext ctx = resolveBoutContext(eventId, boutIndex);
        if (ctx == null) {
            log.warn("generateAndSavePreMatchOdds: could not resolve bout eventId={} boutIndex={}",
                    eventId, boutIndex);
            return;
        }

        List<Map<String, Object>> raw = preMatchGenerator.generatePreMatchOdds(
                ctx.fighter1(), ctx.fighter2(),
                ctx.fighter1Record(), ctx.fighter2Record(),
                ctx.weightClass());

        List<Odds> entities = toEntities(raw, matchId, ctx.fighter1(), ctx.fighter2());

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of("mma_moneyline"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSavePreMatchOdds: matchId={} eventId={} bout={} fighter1='{}' fighter2='{}' — {} rows saved",
                matchId, eventId, boutIndex, ctx.fighter1(), ctx.fighter2(), entities.size());
    }

    /**
     * Convenience overload — generates pre-match odds for ALL bouts on a card.
     *
     * <p>Caller must supply a {@code boutMatchIds} map keyed by bout index (0-based)
     * to Match UUID, since each bout is a separate match row.
     *
     * @param eventId     ESPN event ID
     * @param boutMatchIds map of boutIndex → matchId
     */
    @Transactional
    public void generateAndSaveAllBoutsPreMatchOdds(String eventId,
                                                     Map<Integer, UUID> boutMatchIds) {
        for (Map.Entry<Integer, UUID> entry : boutMatchIds.entrySet()) {
            generateAndSavePreMatchOdds(eventId, entry.getKey(), entry.getValue());
        }
        log.info("generateAndSaveAllBoutsPreMatchOdds: eventId={} — processed {} bouts",
                eventId, boutMatchIds.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generates and persists live moneyline odds for an in-progress UFC bout.
     *
     * <p>Always fetches a fresh event summary (no cache) to read the current
     * round and compuStrike stats.
     *
     * @param eventId   ESPN event ID
     * @param boutIndex 0-based bout index (0 = main event)
     * @param matchId   UUID of the Match row in the DB
     */
    @Transactional
    public void generateAndSaveLiveOdds(String eventId, int boutIndex, UUID matchId) {
        // Always fresh — getEventSummary is never cached
        Map<String, Object> summary = mmaDataService.getEventSummary(eventId);
        if (summary.isEmpty()) {
            log.warn("generateAndSaveLiveOdds: empty summary for eventId={}", eventId);
            return;
        }

        // Resolve fighters from the live summary header / boxscore
        BoutContext ctx = resolveBoutContextFromSummary(summary, boutIndex);
        if (ctx == null) {
            log.warn("generateAndSaveLiveOdds: could not resolve bout boutIndex={} from summary eventId={}",
                    boutIndex, eventId);
            return;
        }

        int    roundsCompleted = extractRoundsCompleted(summary, boutIndex);
        int    totalRounds     = extractTotalRounds(summary, boutIndex);
        double dominanceScore  = extractDominanceScore(summary, boutIndex);

        List<Map<String, Object>> raw = liveGenerator.generateLiveOdds(
                ctx.fighter1(), ctx.fighter2(),
                roundsCompleted, totalRounds,
                dominanceScore);

        List<Odds> entities = toEntities(raw, matchId, ctx.fighter1(), ctx.fighter2());

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of("mma_live_moneyline"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveLiveOdds: matchId={} eventId={} bout={} round={}/{} dominance={} — {} rows saved",
                matchId, eventId, boutIndex, roundsCompleted, totalRounds,
                dominanceScore, entities.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  BOUT CONTEXT RESOLUTION
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Resolves fighter names, records, and weight class from the scoreboard cache.
     * Returns null if the event or bout cannot be found.
     */
    private BoutContext resolveBoutContext(String eventId, int boutIndex) {
        for (Map<String, Object> event : mmaDataService.getEvents()) {
            if (!eventId.equals(MmaDataService.extractEventId(event))) continue;

            List<Map<String, Object>> bouts = MmaDataService.extractBouts(event);
            if (boutIndex >= bouts.size()) {
                log.warn("resolveBoutContext: boutIndex={} out of range (event has {} bouts)",
                        boutIndex, bouts.size());
                return null;
            }

            Map<String, Object>       bout    = bouts.get(boutIndex);
            List<Map<String, Object>> fighters = MmaDataService.extractBoutFighters(bout);
            if (fighters.size() < 2) return null;

            return new BoutContext(
                    MmaDataService.extractFighterName(fighters.get(0)),
                    MmaDataService.extractFighterName(fighters.get(1)),
                    MmaDataService.extractFighterRecord(fighters.get(0)),
                    MmaDataService.extractFighterRecord(fighters.get(1)),
                    MmaDataService.extractWeightClass(bout)
            );
        }
        return null;
    }

    /**
     * Resolves fighter names from an event summary map (used in live flow).
     * Looks inside summary → header → competitions[boutIndex].
     */
    @SuppressWarnings("unchecked")
    private BoutContext resolveBoutContextFromSummary(Map<String, Object> summary, int boutIndex) {
        try {
            Map<String, Object> header = (Map<String, Object>) summary.get("header");
            if (header == null) return null;
            List<?> competitions = (List<?>) header.get("competitions");
            if (competitions == null || boutIndex >= competitions.size()) return null;

            Map<String, Object> comp = (Map<String, Object>) competitions.get(boutIndex);
            List<?> competitors = (List<?>) comp.get("competitors");
            if (competitors == null || competitors.size() < 2) return null;

            Map<String, Object> c1 = (Map<String, Object>) competitors.get(0);
            Map<String, Object> c2 = (Map<String, Object>) competitors.get(1);

            String weightClass = "";
            Object note = comp.get("note");
            if (note != null && !note.toString().isBlank()) weightClass = note.toString();

            return new BoutContext(
                    MmaDataService.extractFighterName(c1),
                    MmaDataService.extractFighterName(c2),
                    MmaDataService.extractFighterRecord(c1),
                    MmaDataService.extractFighterRecord(c2),
                    weightClass
            );
        } catch (ClassCastException e) {
            log.warn("resolveBoutContextFromSummary: cast error at boutIndex={} — {}", boutIndex, e.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE STAT EXTRACTION
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Extracts the number of fully completed rounds from the summary's boxscore.
     * Falls back to status.period - 1 if boxscore stats are absent.
     */
    @SuppressWarnings("unchecked")
    private int extractRoundsCompleted(Map<String, Object> summary, int boutIndex) {
        try {
            Map<String, Object> header   = (Map<String, Object>) summary.get("header");
            if (header == null) return 0;
            List<?> competitions = (List<?>) header.get("competitions");
            if (competitions == null || boutIndex >= competitions.size()) return 0;
            Map<String, Object> comp   = (Map<String, Object>) competitions.get(boutIndex);
            Map<String, Object> status = (Map<String, Object>) comp.get("status");
            if (status == null) return 0;
            Object period = status.get("period");
            if (period == null) return 0;
            // period = current round number; rounds completed = period - 1
            int currentRound = Integer.parseInt(period.toString());
            return Math.max(0, currentRound - 1);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Extracts scheduled total rounds from the summary (competition's notes or format).
     * Defaults to 3 if not determinable; title bouts return 5.
     */
    @SuppressWarnings("unchecked")
    private int extractTotalRounds(Map<String, Object> summary, int boutIndex) {
        try {
            Map<String, Object> header = (Map<String, Object>) summary.get("header");
            if (header == null) return 3;
            List<?> competitions = (List<?>) header.get("competitions");
            if (competitions == null || boutIndex >= competitions.size()) return 3;
            Map<String, Object> comp = (Map<String, Object>) competitions.get(boutIndex);
            // ESPN marks title bouts — title bouts are always 5 rounds
            Object titleBout = comp.get("titleBout");
            if (Boolean.TRUE.equals(titleBout) || "true".equalsIgnoreCase(
                    titleBout != null ? titleBout.toString() : "")) {
                return 5;
            }
            // Main event (boutIndex 0) is often 5 rounds even if not a title bout
            if (boutIndex == 0) return 5;
            return 3;
        } catch (Exception e) {
            return 3;
        }
    }

    /**
     * Derives a dominance score in [-1.0, +1.0] from compuStrike statistics.
     *
     * Formula: (f1Strikes - f2Strikes) / max(f1Strikes + f2Strikes, 1)
     *
     * Positive = fighter1 (home) ahead; negative = fighter2 (away) ahead.
     * Returns 0.0 if stats are absent or unparseable.
     */
    @SuppressWarnings("unchecked")
    private double extractDominanceScore(Map<String, Object> summary, int boutIndex) {
        try {
            // ESPN compuStrike data lives in boxscore → players[][statistics]
            Map<String, Object> boxscore = (Map<String, Object>) summary.get("boxscore");
            if (boxscore == null) return 0.0;

            List<?> playerGroups = (List<?>) boxscore.get("players");
            if (playerGroups == null || playerGroups.size() < 2) return 0.0;

            // Each entry in players[] corresponds to one competitor's stats block
            double f1Strikes = extractCompuStrikes((Map<String, Object>) playerGroups.get(0));
            double f2Strikes = extractCompuStrikes((Map<String, Object>) playerGroups.get(1));

            double total = f1Strikes + f2Strikes;
            if (total == 0) return 0.0;

            double rawScore = (f1Strikes - f2Strikes) / total;
            // Clamp to [-1.0, +1.0] (should already be within range, but defensive)
            return Math.max(-1.0, Math.min(1.0, rawScore));

        } catch (Exception e) {
            log.debug("extractDominanceScore: could not extract strikes for bout={} — {}",
                    boutIndex, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Extracts significant strikes landed from an ESPN player stats block.
     * Looks for a statistic with name "compuStrikes" or "sigStrikes".
     * Returns 0.0 if not found.
     */
    @SuppressWarnings("unchecked")
    private double extractCompuStrikes(Map<String, Object> playerGroup) {
        try {
            List<?> stats = (List<?>) playerGroup.get("statistics");
            if (stats == null) return 0.0;
            for (Object statObj : stats) {
                Map<String, Object> stat = (Map<String, Object>) statObj;
                String name = stat.getOrDefault("name", "").toString().toLowerCase();
                if (name.contains("compustrike") || name.contains("sigstrike")) {
                    Object val = stat.get("displayValue");
                    if (val == null) val = stat.get("value");
                    if (val != null) return Double.parseDouble(val.toString().replace(",", ""));
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ENTITY MAPPING
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Converts raw odds maps into {@link Odds} JPA entities.
     * Fighter display names are normalised to HOME / AWAY.
     * Rows with null/invalid odds values are skipped with a warning.
     */
    private List<Odds> toEntities(List<Map<String, Object>> rawOdds, UUID matchId,
                                  String fighter1, String fighter2) {
        Instant        now    = Instant.now();
        List<Odds>     result = new ArrayList<>();
        Set<String>    seen   = new HashSet<>(); // dedup within batch

        for (Map<String, Object> o : rawOdds) {
            Object rawOdd = o.get("odd");
            if (rawOdd == null) {
                log.warn("toEntities (MMA): matchId={} skipping null odd — selection={}",
                        matchId, o.get("selection"));
                continue;
            }

            BigDecimal oddValue;
            try {
                oddValue = parseOddValue(rawOdd.toString());
            } catch (Exception e) {
                log.warn("toEntities (MMA): matchId={} unparseable odd='{}' selection={} — {}",
                        matchId, rawOdd, o.get("selection"), e.getMessage());
                continue;
            }

            if (oddValue.compareTo(BigDecimal.ONE) < 0) {
                log.warn("toEntities (MMA): matchId={} odd={} < 1.0 — selection={} skipped",
                        matchId, oddValue, o.get("selection"));
                continue;
            }

            String market    = normalizeMarket((String) o.get("market"));
            String selection = normalizeSelection((String) o.get("selection"), fighter1, fighter2);
            String batchKey  = market + ":" + selection;

            if (!seen.add(batchKey)) {
                log.debug("toEntities (MMA): matchId={} duplicate {}/{} in batch — skipping",
                        matchId, market, selection);
                continue;
            }

            result.add(Odds.builder()
                    .matchId(matchId)
                    .market(market)
                    .selection(selection)
                    .value(oddValue)
                    .handicap(null) // MMA moneyline has no handicap
                    .capturedAt(now)
                    .build());
        }

        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  NORMALISATION HELPERS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Parses decimal or fractional odds strings to BigDecimal.
     *
     *   Decimal:    "1.85"  → 1.85
     *   Fractional: "3/1"   → 4.00  (numerator/denominator + 1)
     */
    private BigDecimal parseOddValue(String raw) {
        String s = raw.trim();
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length != 2) throw new NumberFormatException("Bad fractional odd: " + raw);
            BigDecimal num = new BigDecimal(parts[0].trim());
            BigDecimal den = new BigDecimal(parts[1].trim());
            if (den.compareTo(BigDecimal.ZERO) == 0)
                throw new ArithmeticException("Zero denominator: " + raw);
            return num.divide(den, MathContext.DECIMAL64)
                      .add(BigDecimal.ONE)
                      .setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(s);
    }

    private String normalizeMarket(String market) {
        if (market == null) return "UNKNOWN";
        return switch (market.toLowerCase()) {
            case "mma_moneyline"      -> "mma_moneyline";
            case "mma_live_moneyline" -> "mma_live_moneyline";
            default                   -> market.toUpperCase();
        };
    }

    /**
     * Maps fighter display names → HOME / AWAY.
     * Falls back to the raw selection string if it doesn't match either fighter.
     */
    private String normalizeSelection(String selection, String fighter1, String fighter2) {
        if (selection == null)              return "UNKNOWN";
        if (selection.equalsIgnoreCase(fighter1)) return "HOME";
        if (selection.equalsIgnoreCase(fighter2)) return "AWAY";
        // Partial match — last name comparison
        String lastF1 = lastName(fighter1);
        String lastF2 = lastName(fighter2);
        if (!lastF1.isBlank() && selection.toLowerCase().contains(lastF1.toLowerCase())) return "HOME";
        if (!lastF2.isBlank() && selection.toLowerCase().contains(lastF2.toLowerCase())) return "AWAY";
        return selection.toUpperCase();
    }

    private String lastName(String fullName) {
        if (fullName == null || !fullName.contains(" ")) return fullName != null ? fullName : "";
        return fullName.substring(fullName.lastIndexOf(' ') + 1);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  INTERNAL RECORD
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Lightweight value object carrying the resolved bout metadata needed
     * by both the pre-match and live generators.
     */
    private record BoutContext(
            String fighter1,
            String fighter2,
            String fighter1Record,
            String fighter2Record,
            String weightClass
    ) {}
}
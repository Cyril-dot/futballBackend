package com.speedbet.api.sportsdata.odds;

import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class OddsPersistenceService {

    /** Market name exactly as stored in the DB by normalizeMarket(). */
    private static final String MARKET_1X2 = "1X2";

    private final OddsRepository          oddsRepository;
    private final OddsGeneratorService     preMatchGenerator;
    private final LiveOddsGeneratorService liveGenerator;
    private final HalfTimeOddsService      halfTimeGenerator;
    private final HandicapOddsService      handicapGenerator;
    private final CorrectScoreOddsService  correctScoreGenerator;
    private final EntityManager            entityManager;

    // ── Pre-match: saves all markets (1X2, HT, handicap, correct score) ──

    /**
     * Generates and saves all pre-match markets for a match.
     * GUARANTEE: every match ends up with at least the 1X2 market. If any
     * secondary generator fails, the others still save. If 1X2 itself
     * produces nothing usable, it is regenerated with safe fallbacks.
     */
    @Transactional
    public void generateAndSaveAllOdds(Match match) {
        String home    = match.getHomeTeam();
        String away    = match.getAwayTeam();
        String league  = match.getLeague();
        UUID   matchId = match.getId();

        List<Map<String, Object>> allOdds = new ArrayList<>();

        // 1X2 is the core market — always attempt it first
        allOdds.addAll(safeGenerate("1x2", matchId,
                () -> preMatchGenerator.generatePreMatchOdds(home, away, league)));

        // Secondary markets — isolated so one failure doesn't lose the rest
        allOdds.addAll(safeGenerate("half_time", matchId,
                () -> halfTimeGenerator.generateHalfTimeOdds(home, away, league)));
        allOdds.addAll(safeGenerate("handicap", matchId,
                () -> handicapGenerator.generateHandicapOdds(home, away, league)));
        allOdds.addAll(safeGenerate("correct_score", matchId,
                () -> correctScoreGenerator.generateCorrectScoreOdds(home, away, league)));

        List<Odds> entities = toEntities(allOdds, matchId, home, away);

        // Last-resort safety net: if nothing valid came out, force 1X2 again
        if (entities.isEmpty()) {
            log.warn("generateAndSaveAllOdds: matchId={} produced 0 valid rows — forcing 1X2 fallback", matchId);
            entities = toEntities(
                    safeGenerate("1x2-fallback", matchId,
                            () -> preMatchGenerator.generatePreMatchOdds(home, away, league)),
                    matchId, home, away);
        }

        // Never wipe existing odds if we somehow still have nothing to replace them with
        if (entities.isEmpty()) {
            log.error("generateAndSaveAllOdds: matchId={} {} vs {} — could not generate any odds; keeping existing rows",
                    matchId, home, away);
            return;
        }

        oddsRepository.deleteByMatchId(matchId);
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveAllOdds: matchId={} {} vs {} — saved {} odds rows",
                matchId, home, away, entities.size());
    }

    /**
     * Generates odds ONLY if the match has no 1X2 odds yet. Cheap to call
     * repeatedly (e.g. on every fixture save) — matches that already have
     * 1X2 odds are skipped with a single EXISTS query.
     *
     * @return true if odds were generated, false if the match already had odds
     */
    @Transactional
    public boolean ensureOddsForMatch(Match match) {
        // A match that hasn't been persisted has no id to attach odds to
        if (match == null || match.getId() == null) {
            log.warn("ensureOddsForMatch: match is null or has no id yet — skipping");
            return false;
        }
        if (hasOdds(match.getId())) {
            return false;
        }
        log.info("ensureOddsForMatch: matchId={} {} vs {} has no 1X2 odds — generating",
                match.getId(), match.getHomeTeam(), match.getAwayTeam());
        generateAndSaveAllOdds(match);
        return true;
    }

    /**
     * Backfills odds for every match in the collection that has none.
     * Call this after fetching/syncing fixtures so no game is left out.
     * One bad match never stops the rest.
     *
     * @return number of matches that had odds generated
     */
    public int ensureOddsForAll(Collection<Match> matches) {
        if (matches == null || matches.isEmpty()) return 0;

        int generated = 0;
        for (Match match : matches) {
            try {
                if (ensureOddsForMatch(match)) generated++;
            } catch (Exception e) {
                log.error("ensureOddsForAll: matchId={} failed — {}", match.getId(), e.getMessage(), e);
            }
        }
        log.info("ensureOddsForAll: checked {} matches, generated odds for {}", matches.size(), generated);
        return generated;
    }

    // ── Live: replaces only 1X2 + asian_handicap rows ────────────────────

    @Transactional
    public void generateAndSaveLiveOdds(Match match) {
        String home      = match.getHomeTeam();
        String away      = match.getAwayTeam();
        int    scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
        int    scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
        int    minute    = extractMinute(match);
        UUID   matchId   = match.getId();

        List<Map<String, Object>> liveOdds = new ArrayList<>();
        liveOdds.addAll(safeGenerate("live_1x2", matchId,
                () -> liveGenerator.generateLiveOdds(home, away, scoreHome, scoreAway, minute)));
        liveOdds.addAll(safeGenerate("live_handicap", matchId,
                () -> handicapGenerator.generateLiveHandicapOdds(home, away, scoreHome, scoreAway, minute)));

        List<Odds> entities = toEntities(liveOdds, matchId, home, away);

        // Don't delete the existing live odds if we have nothing to replace them with
        if (entities.isEmpty()) {
            log.error("generateAndSaveLiveOdds: matchId={} — generated 0 rows; keeping existing odds", matchId);
            // But make sure the match isn't left with NO odds at all
            ensureOddsForMatch(match);
            return;
        }

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of(MARKET_1X2, "asian_handicap"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveLiveOdds: matchId={} score={}-{} min={} — saved {} rows",
                matchId, scoreHome, scoreAway, minute, entities.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs a generator and never lets it throw or return null.
     * A failing generator logs an error and contributes nothing, so the
     * other markets still get saved.
     */
    private List<Map<String, Object>> safeGenerate(String label, UUID matchId,
                                                   Supplier<List<Map<String, Object>>> generator) {
        try {
            List<Map<String, Object>> result = generator.get();
            return result != null ? result : List.of();
        } catch (Exception e) {
            log.error("safeGenerate: matchId={} generator '{}' failed — {}",
                    matchId, label, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * True if the match already has 1X2 odds stored. 1X2 is the core market:
     * a match with only half-time or correct-score rows still counts as
     * "no odds" and gets its full set regenerated.
     * Uses an EXISTS query, so no rows are loaded.
     */
    private boolean hasOdds(UUID matchId) {
        return oddsRepository.existsByMatchIdAndMarket(matchId, MARKET_1X2);
    }

    private int extractMinute(Match match) {
        if (match.getMetadata() != null) {
            Object min = match.getMetadata().get("minute");
            if (min != null) {
                try { return Integer.parseInt(min.toString()); } catch (NumberFormatException ignored) {}
            }
        }
        if (match.getKickoffAt() != null) {
            long elapsed = ChronoUnit.MINUTES.between(match.getKickoffAt(), Instant.now());
            return (int) Math.min(Math.max(elapsed, 0), 95);
        }
        return 45;
    }

    private List<Odds> toEntities(List<Map<String, Object>> odds, UUID matchId,
                                  String home, String away) {
        Instant now = Instant.now();
        List<Odds> result = new ArrayList<>();
        // O(1) duplicate check — replaces the O(n²) stream scan
        Set<String> seen = new HashSet<>();

        for (Map<String, Object> o : odds) {
            Object rawOdd = o.get("odd");
            if (rawOdd == null) {
                log.warn("toEntities: matchId={} skipping null odd — selection={}",
                        matchId, o.get("selection"));
                continue;
            }

            BigDecimal oddValue;
            try {
                oddValue = parseOddValue(rawOdd.toString());
            } catch (Exception e) {
                log.warn("toEntities: matchId={} unparseable odd='{}' selection={} — {}",
                        matchId, rawOdd, o.get("selection"), e.getMessage());
                continue;
            }

            if (oddValue.compareTo(BigDecimal.ONE) < 0) {
                log.warn("toEntities: matchId={} skipping odd={} < 1.0 for selection={}",
                        matchId, oddValue, o.get("selection"));
                continue;
            }

            BigDecimal handicapVal = null;
            if (o.get("handicap") != null) {
                try {
                    handicapVal = parseSpread(o.get("handicap").toString());
                } catch (Exception e) {
                    log.warn("toEntities: matchId={} could not parse handicap='{}' — setting null",
                            matchId, o.get("handicap"));
                }
            }

            String normalizedMarket    = normalizeMarket((String) o.get("market"));
            String normalizedSelection = normalizeSelection((String) o.get("selection"), home, away);

            String dedupeKey = normalizedMarket + "|" + normalizedSelection;
            if (!seen.add(dedupeKey)) {
                log.debug("toEntities: matchId={} skipping duplicate market={} selection={}",
                        matchId, normalizedMarket, normalizedSelection);
                continue;
            }

            result.add(Odds.builder()
                    .matchId(matchId)
                    .market(normalizedMarket)
                    .selection(normalizedSelection)
                    .value(oddValue)
                    .handicap(handicapVal)
                    .capturedAt(now)
                    .build());
        }

        return result;
    }

    /**
     * Parses an odds value that may arrive in multiple formats:
     *
     *   Decimal:    "1.85"   → BigDecimal("1.85")
     *   Fractional: "3/1"    → BigDecimal("4.00")   (numerator/denominator + 1)
     *   Integer:    "2"      → BigDecimal("2")
     */
    private BigDecimal parseOddValue(String raw) {
        String s = raw.trim();

        // Fractional format — e.g. "3/1", "11/2", "1/4"
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length != 2) {
                throw new NumberFormatException("Cannot parse fractional odd: " + raw);
            }
            BigDecimal numerator   = new BigDecimal(parts[0].trim());
            BigDecimal denominator = new BigDecimal(parts[1].trim());
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                throw new ArithmeticException("Zero denominator in fractional odd: " + raw);
            }
            return numerator
                    .divide(denominator, MathContext.DECIMAL64)
                    .add(BigDecimal.ONE)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return new BigDecimal(s);
    }

    /**
     * Parses spread/handicap values that may arrive as double-sided strings:
     *
     *   "+4/-4"   → BigDecimal("4")   (take the positive side)
     *   "-3/+3"   → BigDecimal("-3")  (take the first token)
     *   "+6.5"    → BigDecimal("6.5")
     *   "-2.5"    → BigDecimal("-2.5")
     */
    private BigDecimal parseSpread(String raw) {
        String s = raw.trim();
        // Double-sided format e.g. "+4/-4" — take the first token
        if (s.contains("/")) {
            s = s.split("/")[0].trim();
        }
        return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeMarket(String market) {
        if (market == null) return "UNKNOWN";
        return switch (market.toLowerCase()) {
            case "1x2", "match_result" -> MARKET_1X2;
            case "half_time"           -> "half_time";
            case "asian_handicap"      -> "asian_handicap";
            case "correct_score"       -> "correct_score";
            default                    -> market.toUpperCase();
        };
    }

    private String normalizeSelection(String selection, String homeTeam, String awayTeam) {
        if (selection == null) return "UNKNOWN";
        if (selection.equalsIgnoreCase("draw"))                    return "DRAW";
        if (selection.equalsIgnoreCase(homeTeam))                  return "HOME";
        if (selection.equalsIgnoreCase(awayTeam))                  return "AWAY";
        if (selection.equalsIgnoreCase("Push/Refund") ||
                selection.toLowerCase().contains("push"))          return "PUSH";
        return selection;
    }
}
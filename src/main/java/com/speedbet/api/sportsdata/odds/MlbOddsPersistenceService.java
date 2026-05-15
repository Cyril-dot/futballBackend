package com.speedbet.api.sportsdata.odds;

import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.BaseballDataService;
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
 * Persists pre-match and live moneyline odds for MLB games.
 *
 * ── Pre-match flow ───────────────────────────────────────────────────────
 *
 *   1. Resolves home/away team names, win-loss records, and starting pitcher
 *      ERAs from BaseballDataService.getTodayGames() (cached).
 *   2. Calls MlbOddsGeneratorService to produce bookmaker lines.
 *   3. Deletes existing "mlb_moneyline" rows for the matchId, saves fresh ones.
 *
 *   Starting pitcher ERA extraction:
 *     ESPN surfaces probable pitchers in competitions[0].probables when
 *     available (typically set hours before first pitch).  The ERA string
 *     is passed to MlbOddsGeneratorService to weight the probability model.
 *     If probables are absent, ERA defaults to null and the model falls back
 *     to name-hash + record strength.
 *
 * ── Live flow ────────────────────────────────────────────────────────────
 *
 *   1. Calls BaseballDataService.getLiveGames() (always fresh, no cache).
 *   2. Locates the game by ESPN game ID, extracts:
 *        - homeScore / awayScore (current runs)
 *        - inning + inningHalf   (e.g. inning=7, inningHalf="top")
 *        - outs                  (0, 1, or 2; -1 if unknown)
 *   3. Calls MlbLiveOddsGeneratorService to produce live lines.
 *   4. Replaces only "mlb_live_moneyline" rows for the matchId.
 *
 * ── No draw ──────────────────────────────────────────────────────────────
 *
 *   Baseball has no draw — only HOME and AWAY are persisted.
 *   Any "DRAW" selection that somehow appears in the raw odds list is
 *   silently dropped during normalisation.
 *
 * ── Markets persisted ────────────────────────────────────────────────────
 *
 *   Pre-match : "mlb_moneyline"       — HOME, AWAY
 *   Live      : "mlb_live_moneyline"  — HOME, AWAY
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MlbOddsPersistenceService {

    private final OddsRepository           oddsRepository;
    private final MlbOddsGeneratorService  preMatchGenerator;
    private final MlbLiveOddsGeneratorService liveGenerator;
    private final BaseballDataService      baseballDataService;

    // ═════════════════════════════════════════════════════════════════════
    //  PRE-MATCH
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generates and persists pre-match moneyline odds for a single MLB game.
     *
     * <p>Resolves team info and starting pitcher ERAs from today's cached
     * scoreboard.  Falls back to Match entity fields if the game is not found.
     *
     * @param match      the pre-created Match row for this MLB game
     * @param espnGameId ESPN event ID used to resolve live team details
     */
    @Transactional
    public void generateAndSavePreMatchOdds(Match match, String espnGameId) {
        GameContext ctx = resolveGameContext(espnGameId);

        String homeTeam   = ctx != null ? ctx.homeTeam()   : match.getHomeTeam();
        String awayTeam   = ctx != null ? ctx.awayTeam()   : match.getAwayTeam();
        String homeRecord = ctx != null ? ctx.homeRecord()  : "";
        String awayRecord = ctx != null ? ctx.awayRecord()  : "";
        String homeEra    = ctx != null ? ctx.homeEra()     : null;
        String awayEra    = ctx != null ? ctx.awayEra()     : null;
        String league     = match.getLeague();
        UUID   matchId    = match.getId();

        List<Map<String, Object>> raw = preMatchGenerator.generatePreMatchOdds(
                homeTeam, awayTeam,
                homeRecord, awayRecord,
                homeEra, awayEra,
                league);

        List<Odds> entities = toEntities(raw, matchId, homeTeam, awayTeam);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of("mlb_moneyline"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSavePreMatchOdds (MLB): matchId={} espnGameId={} home='{}' away='{}' " +
                        "homeEra={} awayEra={} — {} rows saved",
                matchId, espnGameId, homeTeam, awayTeam, homeEra, awayEra, entities.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generates and persists live moneyline odds for an in-progress MLB game.
     *
     * <p>Always fetches fresh live games (no cache) to read the current
     * score, inning, inning half, and outs.
     *
     * <p>If the game is not found in the live feed (between half-innings,
     * rain delay, or game just ended), the call is a no-op with a warning.
     *
     * @param match      the Match row for this game
     * @param espnGameId ESPN event ID
     */
    @Transactional
    public void generateAndSaveLiveOdds(Match match, String espnGameId) {
        Map<String, Object> liveGame = findLiveGame(espnGameId);

        if (liveGame == null) {
            log.warn("generateAndSaveLiveOdds (MLB): game not found in live feed — espnGameId={}", espnGameId);
            return;
        }

        Optional<Map<String, Object>> homeOpt = BaseballDataService.extractHomeCompetitor(liveGame);
        Optional<Map<String, Object>> awayOpt = BaseballDataService.extractAwayCompetitor(liveGame);

        if (homeOpt.isEmpty() || awayOpt.isEmpty()) {
            log.warn("generateAndSaveLiveOdds (MLB): could not resolve competitors espnGameId={}", espnGameId);
            return;
        }

        Map<String, Object> home = homeOpt.get();
        Map<String, Object> away = awayOpt.get();

        String homeTeam  = BaseballDataService.extractTeamName(home);
        String awayTeam  = BaseballDataService.extractTeamName(away);
        int    homeScore = parseScore(BaseballDataService.extractScore(home));
        int    awayScore = parseScore(BaseballDataService.extractScore(away));
        int    inning    = BaseballDataService.extractInning(liveGame);
        String half      = BaseballDataService.extractInningHalf(liveGame);
        int    outs      = BaseballDataService.extractOuts(liveGame);

        List<Map<String, Object>> raw = liveGenerator.generateLiveOdds(
                homeTeam, awayTeam,
                homeScore, awayScore,
                inning, half, outs);

        UUID       matchId  = match.getId();
        List<Odds> entities = toEntities(raw, matchId, homeTeam, awayTeam);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of("mlb_live_moneyline"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveLiveOdds (MLB): matchId={} espnGameId={} score={}-{} I{} {} {}outs — {} rows saved",
                matchId, espnGameId, homeScore, awayScore, inning, half, outs, entities.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME CONTEXT RESOLUTION
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Resolves team names, records, and starting pitcher ERAs from today's
     * cached scoreboard.  Returns null if the game is not found.
     */
    private GameContext resolveGameContext(String espnGameId) {
        for (Map<String, Object> game : baseballDataService.getTodayGames()) {
            if (!espnGameId.equals(BaseballDataService.extractGameId(game))) continue;

            Optional<Map<String, Object>> homeOpt = BaseballDataService.extractHomeCompetitor(game);
            Optional<Map<String, Object>> awayOpt = BaseballDataService.extractAwayCompetitor(game);
            if (homeOpt.isEmpty() || awayOpt.isEmpty()) return null;

            Map<String, Object> homeComp = homeOpt.get();
            Map<String, Object> awayComp = awayOpt.get();

            // Probable pitcher ERAs (null-safe — may not be set pre-game)
            Map<String, Object> homePitcher = BaseballDataService.extractStartingPitcher(game, "home");
            Map<String, Object> awayPitcher = BaseballDataService.extractStartingPitcher(game, "away");
            String homeEra = homePitcher.isEmpty() ? null : homePitcher.getOrDefault("era", "").toString();
            String awayEra = awayPitcher.isEmpty() ? null : awayPitcher.getOrDefault("era", "").toString();
            if (homeEra != null && homeEra.isBlank()) homeEra = null;
            if (awayEra != null && awayEra.isBlank()) awayEra = null;

            return new GameContext(
                    BaseballDataService.extractTeamName(homeComp),
                    BaseballDataService.extractTeamName(awayComp),
                    BaseballDataService.extractRecord(homeComp),
                    BaseballDataService.extractRecord(awayComp),
                    homeEra,
                    awayEra
            );
        }
        return null;
    }

    /**
     * Finds a game in the current live feed by ESPN game ID.
     * Returns null if the game is not currently in STATE_IN.
     */
    private Map<String, Object> findLiveGame(String espnGameId) {
        return baseballDataService.getLiveGames()
                .stream()
                .filter(g -> espnGameId.equals(BaseballDataService.extractGameId(g)))
                .findFirst()
                .orElse(null);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ENTITY MAPPING
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Converts raw odds maps into {@link Odds} JPA entities.
     * Team display names normalised to HOME / AWAY.
     * DRAW selections are dropped (baseball has no draw).
     * Sub-1.0 and null odds are skipped with a warning.
     */
    private List<Odds> toEntities(List<Map<String, Object>> rawOdds, UUID matchId,
                                  String homeTeam, String awayTeam) {
        Instant     now    = Instant.now();
        List<Odds>  result = new ArrayList<>();
        Set<String> seen   = new HashSet<>();

        for (Map<String, Object> o : rawOdds) {
            Object rawOdd = o.get("odd");
            if (rawOdd == null) {
                log.warn("toEntities (MLB): matchId={} null odd — selection={}", matchId, o.get("selection"));
                continue;
            }

            BigDecimal oddValue;
            try {
                oddValue = parseOddValue(rawOdd.toString());
            } catch (Exception e) {
                log.warn("toEntities (MLB): matchId={} unparseable odd='{}' selection={} — {}",
                        matchId, rawOdd, o.get("selection"), e.getMessage());
                continue;
            }

            if (oddValue.compareTo(BigDecimal.ONE) < 0) {
                log.warn("toEntities (MLB): matchId={} odd={} < 1.0 — selection={} skipped",
                        matchId, oddValue, o.get("selection"));
                continue;
            }

            String market    = normalizeMarket((String) o.get("market"));
            String selection = normalizeSelection((String) o.get("selection"), homeTeam, awayTeam);

            // Drop draw — baseball has no draw
            if ("DRAW".equals(selection)) {
                log.debug("toEntities (MLB): matchId={} dropping DRAW selection — baseball is two-way only", matchId);
                continue;
            }

            String batchKey = market + ":" + selection;
            if (!seen.add(batchKey)) {
                log.debug("toEntities (MLB): matchId={} duplicate {}/{} in batch — skipping",
                        matchId, market, selection);
                continue;
            }

            result.add(Odds.builder()
                    .matchId(matchId)
                    .market(market)
                    .selection(selection)
                    .value(oddValue)
                    .handicap(null)
                    .capturedAt(now)
                    .build());
        }

        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  NORMALISATION & PARSING HELPERS
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
            case "mlb_moneyline"      -> "mlb_moneyline";
            case "mlb_live_moneyline" -> "mlb_live_moneyline";
            default                   -> market.toUpperCase();
        };
    }

    /**
     * Maps team display names → HOME / AWAY.
     * Last-word partial match as fallback (e.g. "Yankees" matches "New York Yankees").
     * "draw" is left as "DRAW" so the caller can drop it explicitly.
     */
    private String normalizeSelection(String selection, String homeTeam, String awayTeam) {
        if (selection == null)                    return "UNKNOWN";
        if ("draw".equalsIgnoreCase(selection))   return "DRAW";
        if (selection.equalsIgnoreCase(homeTeam)) return "HOME";
        if (selection.equalsIgnoreCase(awayTeam)) return "AWAY";
        String lastHome = lastWord(homeTeam);
        String lastAway = lastWord(awayTeam);
        if (!lastHome.isBlank() && selection.toLowerCase().contains(lastHome.toLowerCase())) return "HOME";
        if (!lastAway.isBlank() && selection.toLowerCase().contains(lastAway.toLowerCase())) return "AWAY";
        return selection.toUpperCase();
    }

    private String lastWord(String name) {
        if (name == null || !name.contains(" ")) return name != null ? name : "";
        return name.substring(name.lastIndexOf(' ') + 1);
    }

    private int parseScore(String score) {
        if (score == null || score.isBlank()) return 0;
        try { return Integer.parseInt(score.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  INTERNAL RECORD
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Lightweight value object carrying resolved game metadata.
     * homeEra / awayEra may be null if ESPN has not published probable pitchers.
     */
    private record GameContext(
            String homeTeam,
            String awayTeam,
            String homeRecord,
            String awayRecord,
            String homeEra,   // nullable
            String awayEra    // nullable
    ) {}
}
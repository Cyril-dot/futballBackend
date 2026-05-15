package com.speedbet.api.sportsdata.odds;

import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.AmericanFootballDataService;
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
 * Persists pre-match and live moneyline odds for NFL games.
 *
 * ── Pre-match flow ───────────────────────────────────────────────────────
 *
 *   1. Resolves home/away team names and win-loss records from
 *      AmericanFootballDataService.getCurrentWeekGames() (cached).
 *   2. Calls NflOddsGeneratorService to produce bookmaker lines.
 *   3. Deletes existing "nfl_moneyline" rows for the matchId, then saves fresh ones.
 *
 * ── Live flow ────────────────────────────────────────────────────────────
 *
 *   1. Calls AmericanFootballDataService.getLiveGames() (always fresh).
 *   2. Locates the game by ESPN game ID, extracts:
 *        - homeScore / awayScore (current points)
 *        - quarter + clockSeconds (time remaining in quarter)
 *        - homePossession (ball possession flag)
 *        - homeRedZone / awayRedZone (red zone flags)
 *   3. Calls NflLiveOddsGeneratorService to produce live lines.
 *   4. Replaces only "nfl_live_moneyline" rows for the matchId.
 *
 * ── Clock parsing ─────────────────────────────────────────────────────────
 *
 *   ESPN returns clock as a display string, e.g. "8:42".
 *   This service parses it to total seconds remaining in the quarter.
 *   Falls back to 450 (mid-quarter) if the string is unparseable.
 *
 * ── Selection normalisation ──────────────────────────────────────────────
 *
 *   Team display names → HOME / AWAY / DRAW on persist.
 *   Consistent with OddsPersistenceService (football) convention.
 *
 * ── Markets persisted ────────────────────────────────────────────────────
 *
 *   Pre-match : "nfl_moneyline"       — HOME, DRAW, AWAY
 *   Live      : "nfl_live_moneyline"  — HOME, DRAW, AWAY
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NflOddsPersistenceService {

    private final OddsRepository              oddsRepository;
    private final NflOddsGeneratorService     preMatchGenerator;
    private final NflLiveOddsGeneratorService liveGenerator;
    private final AmericanFootballDataService nflDataService;

    // ═════════════════════════════════════════════════════════════════════
    //  PRE-MATCH
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generates and persists pre-match moneyline odds for a single NFL game.
     *
     * <p>Resolves team info from the current week's cached scoreboard.
     * If the game cannot be found in the current week, also checks
     * the Match entity fields directly as a fallback.
     *
     * @param match   the pre-created Match row for this NFL game;
     *                match.getHomeTeam() / match.getAwayTeam() used as fallback
     * @param espnGameId ESPN event ID used to resolve live team details
     */
    @Transactional
    public void generateAndSavePreMatchOdds(Match match, String espnGameId) {
        GameContext ctx = resolveGameContext(espnGameId);

        // Fallback to Match entity if scoreboard lookup failed
        String homeTeam   = ctx != null ? ctx.homeTeam()   : match.getHomeTeam();
        String awayTeam   = ctx != null ? ctx.awayTeam()   : match.getAwayTeam();
        String homeRecord = ctx != null ? ctx.homeRecord()  : "";
        String awayRecord = ctx != null ? ctx.awayRecord()  : "";
        String league     = match.getLeague();
        UUID   matchId    = match.getId();

        List<Map<String, Object>> raw = preMatchGenerator.generatePreMatchOdds(
                homeTeam, awayTeam, homeRecord, awayRecord, league);

        List<Odds> entities = toEntities(raw, matchId, homeTeam, awayTeam);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of("nfl_moneyline"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSavePreMatchOdds (NFL): matchId={} espnGameId={} home='{}' away='{}' — {} rows saved",
                matchId, espnGameId, homeTeam, awayTeam, entities.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generates and persists live moneyline odds for an in-progress NFL game.
     *
     * <p>Always fetches fresh live games (no cache) to read the current
     * score, quarter, clock, possession, and red zone state.
     *
     * <p>If the game is not found in the live feed (e.g. between quarters,
     * or the game just ended), the call is a no-op and a warning is logged.
     *
     * @param match      the Match row for this game
     * @param espnGameId ESPN event ID
     */
    @Transactional
    public void generateAndSaveLiveOdds(Match match, String espnGameId) {
        Map<String, Object> liveGame = findLiveGame(espnGameId);

        if (liveGame == null) {
            log.warn("generateAndSaveLiveOdds (NFL): game not found in live feed — espnGameId={}", espnGameId);
            return;
        }

        // Resolve competitors
        Optional<Map<String, Object>> homeOpt = AmericanFootballDataService.extractHomeCompetitor(liveGame);
        Optional<Map<String, Object>> awayOpt = AmericanFootballDataService.extractAwayCompetitor(liveGame);

        if (homeOpt.isEmpty() || awayOpt.isEmpty()) {
            log.warn("generateAndSaveLiveOdds (NFL): could not resolve competitors espnGameId={}", espnGameId);
            return;
        }

        Map<String, Object> home = homeOpt.get();
        Map<String, Object> away = awayOpt.get();

        String homeTeam = AmericanFootballDataService.extractTeamName(home);
        String awayTeam = AmericanFootballDataService.extractTeamName(away);

        int homeScore = parseScore(AmericanFootballDataService.extractScore(home));
        int awayScore = parseScore(AmericanFootballDataService.extractScore(away));

        int    quarter      = AmericanFootballDataService.extractQuarter(liveGame);
        int    clockSeconds = parseClockSeconds(AmericanFootballDataService.extractClock(liveGame));
        boolean homePossession = AmericanFootballDataService.hasPossession(home);
        boolean homeRedZone    = AmericanFootballDataService.isInRedZone(home);
        boolean awayRedZone    = AmericanFootballDataService.isInRedZone(away);

        List<Map<String, Object>> raw = liveGenerator.generateLiveOdds(
                homeTeam, awayTeam,
                homeScore, awayScore,
                quarter, clockSeconds,
                homePossession, homeRedZone, awayRedZone);

        UUID       matchId  = match.getId();
        List<Odds> entities = toEntities(raw, matchId, homeTeam, awayTeam);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of("nfl_live_moneyline"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveLiveOdds (NFL): matchId={} espnGameId={} score={}-{} Q{} clock={}s " +
                        "possession={} homeRZ={} awayRZ={} — {} rows saved",
                matchId, espnGameId, homeScore, awayScore, quarter, clockSeconds,
                homePossession ? "HOME" : "AWAY", homeRedZone, awayRedZone, entities.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME CONTEXT RESOLUTION
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Looks up home/away team names and records from the cached scoreboard.
     * Returns null if the game is not found (caller falls back to Match entity).
     */
    private GameContext resolveGameContext(String espnGameId) {
        for (Map<String, Object> game : nflDataService.getCurrentWeekGames()) {
            if (!espnGameId.equals(AmericanFootballDataService.extractGameId(game))) continue;

            Optional<Map<String, Object>> homeOpt = AmericanFootballDataService.extractHomeCompetitor(game);
            Optional<Map<String, Object>> awayOpt = AmericanFootballDataService.extractAwayCompetitor(game);
            if (homeOpt.isEmpty() || awayOpt.isEmpty()) return null;

            return new GameContext(
                    AmericanFootballDataService.extractTeamName(homeOpt.get()),
                    AmericanFootballDataService.extractTeamName(awayOpt.get()),
                    AmericanFootballDataService.extractRecord(homeOpt.get()),
                    AmericanFootballDataService.extractRecord(awayOpt.get())
            );
        }
        return null;
    }

    /**
     * Finds a game in the current live feed by ESPN game ID.
     * Returns null if the game is not currently in progress.
     */
    private Map<String, Object> findLiveGame(String espnGameId) {
        return nflDataService.getLiveGames()
                .stream()
                .filter(g -> espnGameId.equals(AmericanFootballDataService.extractGameId(g)))
                .findFirst()
                .orElse(null);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ENTITY MAPPING
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Converts raw odds maps into {@link Odds} JPA entities.
     * Team display names normalised to HOME / DRAW / AWAY.
     * Rows with null/invalid/sub-1.0 odds are skipped with a warning.
     */
    private List<Odds> toEntities(List<Map<String, Object>> rawOdds, UUID matchId,
                                  String homeTeam, String awayTeam) {
        Instant      now    = Instant.now();
        List<Odds>   result = new ArrayList<>();
        Set<String>  seen   = new HashSet<>();

        for (Map<String, Object> o : rawOdds) {
            Object rawOdd = o.get("odd");
            if (rawOdd == null) {
                log.warn("toEntities (NFL): matchId={} null odd — selection={}", matchId, o.get("selection"));
                continue;
            }

            BigDecimal oddValue;
            try {
                oddValue = parseOddValue(rawOdd.toString());
            } catch (Exception e) {
                log.warn("toEntities (NFL): matchId={} unparseable odd='{}' selection={} — {}",
                        matchId, rawOdd, o.get("selection"), e.getMessage());
                continue;
            }

            if (oddValue.compareTo(BigDecimal.ONE) < 0) {
                log.warn("toEntities (NFL): matchId={} odd={} < 1.0 — selection={} skipped",
                        matchId, oddValue, o.get("selection"));
                continue;
            }

            String market    = normalizeMarket((String) o.get("market"));
            String selection = normalizeSelection((String) o.get("selection"), homeTeam, awayTeam);
            String batchKey  = market + ":" + selection;

            if (!seen.add(batchKey)) {
                log.debug("toEntities (NFL): matchId={} duplicate {}/{} in batch — skipping",
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
            case "nfl_moneyline"      -> "nfl_moneyline";
            case "nfl_live_moneyline" -> "nfl_live_moneyline";
            default                   -> market.toUpperCase();
        };
    }

    /**
     * Maps team display names → HOME / AWAY / DRAW.
     * Partial last-word match used as fallback.
     */
    private String normalizeSelection(String selection, String homeTeam, String awayTeam) {
        if (selection == null)                    return "UNKNOWN";
        if ("draw".equalsIgnoreCase(selection))   return "DRAW";
        if (selection.equalsIgnoreCase(homeTeam)) return "HOME";
        if (selection.equalsIgnoreCase(awayTeam)) return "AWAY";
        // Partial match — last word of team name (e.g. "Chiefs", "Eagles")
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

    /**
     * Parses a score string like "24" → 24. Returns 0 on failure.
     */
    private int parseScore(String score) {
        if (score == null || score.isBlank()) return 0;
        try { return Integer.parseInt(score.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * Parses ESPN display clock string "M:SS" into total seconds remaining.
     * e.g. "8:42" → 522 seconds.
     * Falls back to 450 (mid-quarter) on any parse failure.
     */
    int parseClockSeconds(String clock) {
        if (clock == null || clock.isBlank()) return 450;
        try {
            String[] parts = clock.trim().split(":");
            if (parts.length == 2) {
                int minutes = Integer.parseInt(parts[0]);
                int seconds = Integer.parseInt(parts[1]);
                return minutes * 60 + seconds;
            }
        } catch (NumberFormatException ignored) {}
        return 450;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  INTERNAL RECORD
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Lightweight value object carrying resolved game metadata for the generators.
     */
    private record GameContext(
            String homeTeam,
            String awayTeam,
            String homeRecord,
            String awayRecord
    ) {}
}
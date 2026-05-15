package com.speedbet.api.sportsdata.odds;

import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.TennisDataService;
import com.speedbet.api.sportsdata.TennisDataService.Tour;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists pre-match and live win odds for tennis matches.
 *
 * Uses the same {@link Match} entity as the football {@link OddsPersistenceService}.
 * Tennis-specific fields are read as follows:
 *
 *   match.getHomeTeam()                    → player 1 name
 *   match.getAwayTeam()                    → player 2 name
 *   match.getLeague()                      → surface ("Grass" | "Clay" | "Hard")
 *   match.getId()                          → matchId (UUID)
 *   match.getScoreHome()                   → sets won by player 1  (live only)
 *   match.getScoreAway()                   → sets won by player 2  (live only)
 *   match.getMetadata().get("espnMatchId") → ESPN match ID for score fetch (live only)
 *   match.getMetadata().get("currentSet")  → current set number, 1-based (live only)
 *   match.getMetadata().get("bestOfFive")  → "true" for Grand Slams / Davis Cup (live only)
 *
 * Mirrors OddsPersistenceService exactly:
 *   - Generator services return List<Map<String, Object>> with keys
 *     { bookmaker, market, selection, odd }
 *   - toEntities() reuses the same parseOddValue / normalizeMarket /
 *     normalizeSelection / duplicate-dedup logic
 *   - DELETE → flush → INSERT within the same transaction
 *
 * Entry points:
 *   saveOdds()                  — called by TennisMatchService on every odds
 *                                 generation (live or pre-match); accepts the
 *                                 already-generated odds list and a market label.
 *   generateAndSavePreMatchOdds() — generates + persists pre-match odds in one call.
 *   generateAndSaveLiveOdds()     — generates + persists live odds in one call,
 *                                   optionally fetching a fresh ESPN score.
 *
 * Markets:
 *   tennis_match_winner     — pre-match HOME / AWAY
 *   tennis_match_winner_live — live HOME / AWAY
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TennisOddsPersistenceService {

    private static final String MARKET_PRE_MATCH = "tennis_match_winner";
    private static final String MARKET_LIVE      = "tennis_match_winner_live";

    private final OddsRepository            oddsRepository;
    private final TennisPreMatchOddsService preMatchGenerator;
    private final TennisLiveOddsService     liveGenerator;
    private final TennisDataService         tennisDataService;

    // ══════════════════════════════════════════════════════════════════════
    // PRIMARY ENTRY POINT — called by TennisMatchService
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Persists an already-generated odds list for the given market.
     *
     * <p>Called by {@code TennisMatchService.persistOddsAsync()} after every
     * odds generation. The market label is one of:
     * <ul>
     *   <li>{@code "tennis_match_winner"}      — pre-match odds</li>
     *   <li>{@code "tennis_match_winner_live"} — live odds</li>
     * </ul>
     *
     * <p>Follows the same DELETE → flush → INSERT pattern as
     * {@link OddsPersistenceService} to avoid unique-constraint violations.
     *
     * @param match  the match entity (homeTeam = p1, awayTeam = p2)
     * @param odds   the pre-generated odds list from the generator service
     * @param market the market label to replace
     */
    @Transactional
    public void saveOdds(Match match, List<Map<String, Object>> odds, String market) {
        if (odds == null || odds.isEmpty()) return;

        UUID   matchId = match.getId();
        String player1 = match.getHomeTeam();
        String player2 = match.getAwayTeam();

        String normalizedMarket = normalizeMarket(market);
        List<Odds> entities = toEntities(odds, matchId, player1, player2);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of(normalizedMarket));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("saveOdds: matchId={} market='{}' {} vs {} — saved {} rows",
                matchId, normalizedMarket, player1, player2, entities.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // GENERATE + SAVE CONVENIENCE METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate and persist pre-match win odds for a tennis match.
     * Replaces all existing {@code tennis_match_winner} rows for this matchId.
     *
     * @param match the match entity (homeTeam = p1, awayTeam = p2, league = surface)
     */
    @Transactional
    public void generateAndSavePreMatchOdds(Match match) {
        String player1 = match.getHomeTeam();
        String player2 = match.getAwayTeam();
        String surface = match.getLeague();
        UUID   matchId = match.getId();

        List<Map<String, Object>> allOdds = new ArrayList<>(
                preMatchGenerator.generatePreMatchOdds(player1, player2, surface)
        );

        List<Odds> entities = toEntities(allOdds, matchId, player1, player2);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of(MARKET_PRE_MATCH));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSavePreMatchOdds: matchId={} {} vs {} — saved {} rows",
                matchId, player1, player2, entities.size());
    }

    /**
     * Generate and persist live win odds for an in-progress tennis match.
     *
     * <p>Set scores are read from match.getScoreHome() / getScoreAway().
     * If an ESPN match ID is present in metadata, a fresh score snapshot is
     * fetched from TennisDataService to get the currentSet number; otherwise
     * currentSet falls back to metadata or defaults to 1.
     *
     * @param match the match entity
     * @param tour  Tour.ATP or Tour.WTA — used for the ESPN score fetch
     */
    @Transactional
    public void generateAndSaveLiveOdds(Match match, Tour tour) {
        String player1 = match.getHomeTeam();
        String player2 = match.getAwayTeam();
        UUID   matchId = match.getId();

        int     p1Sets     = match.getScoreHome() != null ? match.getScoreHome() : 0;
        int     p2Sets     = match.getScoreAway() != null ? match.getScoreAway() : 0;
        int     currentSet = extractCurrentSet(match, tour);
        boolean bestOfFive = extractBestOfFive(match);

        List<Map<String, Object>> liveOdds = new ArrayList<>(
                liveGenerator.generateLiveOdds(player1, player2, p1Sets, p2Sets, currentSet, bestOfFive)
        );

        List<Odds> entities = toEntities(liveOdds, matchId, player1, player2);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of(MARKET_LIVE));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveLiveOdds: matchId={} {} vs {} sets={}-{} set#{} bo{} — saved {} rows",
                matchId, player1, player2, p1Sets, p2Sets, currentSet, bestOfFive ? 5 : 3, entities.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS  (same pattern as OddsPersistenceService)
    // ══════════════════════════════════════════════════════════════════════

    private List<Odds> toEntities(List<Map<String, Object>> odds, UUID matchId,
                                  String player1, String player2) {
        Instant    now    = Instant.now();
        List<Odds> result = new ArrayList<>();

        for (Map<String, Object> o : odds) {
            Object rawOdd = o.get("odd");
            if (rawOdd == null) {
                log.warn("toEntities: matchId={} skipping row with null odd — selection={}",
                        matchId, o.get("selection"));
                continue;
            }

            BigDecimal oddValue;
            try {
                oddValue = parseOddValue(rawOdd.toString());
            } catch (Exception e) {
                log.warn("toEntities: matchId={} skipping unparseable odd='{}' selection={} — {}",
                        matchId, rawOdd, o.get("selection"), e.getMessage());
                continue;
            }

            if (oddValue.compareTo(BigDecimal.ONE) < 0) {
                log.warn("toEntities: matchId={} skipping odd={} < 1.0 for selection={}",
                        matchId, oddValue, o.get("selection"));
                continue;
            }

            String normalizedMarket    = normalizeMarket((String) o.get("market"));
            String normalizedSelection = normalizeSelection((String) o.get("selection"), player1, player2);

            boolean alreadyInBatch = result.stream().anyMatch(existing ->
                    existing.getMarket().equals(normalizedMarket) &&
                            existing.getSelection().equals(normalizedSelection));

            if (alreadyInBatch) {
                log.debug("toEntities: matchId={} skipping duplicate market={} selection={}",
                        matchId, normalizedMarket, normalizedSelection);
                continue;
            }

            result.add(Odds.builder()
                    .matchId(matchId)
                    .market(normalizedMarket)
                    .selection(normalizedSelection)
                    .value(oddValue)
                    .capturedAt(now)
                    .build());
        }

        return result;
    }

    /**
     * Resolves the current set number for a live match.
     * Priority:
     *   1. Fresh ESPN score via TennisDataService (if espnMatchId in metadata)
     *   2. metadata.get("currentSet")
     *   3. Default: 1
     */
    private int extractCurrentSet(Match match, Tour tour) {
        String espnMatchId = metaString(match, "espnMatchId");
        if (!espnMatchId.isBlank()) {
            try {
                Map<String, Object> score = tennisDataService.getMatchScore(espnMatchId, tour);
                Object cs = score.get("currentSet");
                if (cs != null) return Integer.parseInt(cs.toString());
            } catch (Exception e) {
                log.warn("extractCurrentSet: ESPN fetch failed for espnMatchId={} — {}",
                        espnMatchId, e.getMessage());
            }
        }
        String meta = metaString(match, "currentSet");
        if (!meta.isBlank()) {
            try { return Integer.parseInt(meta); } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    /**
     * Reads bestOfFive from metadata.get("bestOfFive").
     * Defaults to false (best of 3) if absent or unparseable.
     */
    private boolean extractBestOfFive(Match match) {
        return "true".equalsIgnoreCase(metaString(match, "bestOfFive"));
    }

    private String metaString(Match match, String key) {
        if (match.getMetadata() == null) return "";
        Object val = match.getMetadata().get(key);
        return val != null ? val.toString() : "";
    }

    /**
     * Parses an odds value that may arrive in multiple formats:
     *   Decimal:    "1.85"  → BigDecimal("1.85")
     *   Fractional: "3/1"   → BigDecimal("4.00")
     *   Integer:    "2"     → BigDecimal("2")
     */
    private BigDecimal parseOddValue(String raw) {
        String s = raw.trim();
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length != 2)
                throw new NumberFormatException("Cannot parse fractional odd: " + raw);
            BigDecimal numerator   = new BigDecimal(parts[0].trim());
            BigDecimal denominator = new BigDecimal(parts[1].trim());
            if (denominator.compareTo(BigDecimal.ZERO) == 0)
                throw new ArithmeticException("Zero denominator in fractional odd: " + raw);
            return numerator
                    .divide(denominator, MathContext.DECIMAL64)
                    .add(BigDecimal.ONE)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(s);
    }

    private String normalizeMarket(String market) {
        if (market == null) return "UNKNOWN";
        return switch (market.toLowerCase()) {
            case "tennis_match_winner"      -> MARKET_PRE_MATCH;
            case "tennis_match_winner_live" -> MARKET_LIVE;
            default                         -> market.toUpperCase();
        };
    }

    private String normalizeSelection(String selection, String player1, String player2) {
        if (selection == null)                   return "UNKNOWN";
        if (selection.equalsIgnoreCase(player1)) return "HOME";
        if (selection.equalsIgnoreCase(player2)) return "AWAY";
        return selection;
    }
}
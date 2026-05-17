package com.speedbet.api.sportsdata.odds;

import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence layer for all NBA basketball odds markets.
 *
 * Pre-match markets persisted:
 *  - moneyline         BasketballMoneylineOddsService
 *  - point_spread      BasketballPointSpreadService
 *  - game_total        BasketballTotalsService
 *  - winning_margin    BasketballWinningMarginService
 *  - overtime          BasketballWinningMarginService (bundled with margin)
 *  - q1_leader         BasketballQuarterService
 *  - halftime_leader   BasketballQuarterService
 *  - q3_leader         BasketballQuarterService
 *  - q1_total to q4_total  BasketballQuarterService
 *
 * Live markets refreshed (delete + re-insert):
 *  - moneyline, point_spread, game_total, winning_margin, overtime
 *  - q*_leader / halftime_leader (only unsettled periods)
 *  - q*_total (only current + future quarters)
 *
 * Market-key normalisation mirrors OddsPersistenceService so the same
 * downstream Odds entity and unique constraint work unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BasketballOddsPersistenceService {

    private final OddsRepository                oddsRepository;
    private final BasketballMoneylineOddsService moneylineService;
    private final BasketballPointSpreadService   spreadService;
    private final BasketballTotalsService        totalsService;
    private final BasketballWinningMarginService marginService;
    private final BasketballQuarterService       quarterService;

    private static final List<String> LIVE_MARKETS = List.of(
            "moneyline", "point_spread", "game_total",
            "winning_margin", "overtime",
            "q1_leader", "halftime_leader", "q3_leader",
            "q1_total", "q2_total", "q3_total", "q4_total"
    );

    // =========================================================================
    // PRE-MATCH
    // =========================================================================

    /**
     * Generates and saves the full suite of pre-match NBA odds for a fixture.
     * Any existing odds rows for the match are deleted first.
     */
    @Transactional
    public void generateAndSaveAllOdds(Match match) {
        String home    = match.getHomeTeam();
        String away    = match.getAwayTeam();
        UUID   matchId = match.getId();

        List<Map<String, Object>> allOdds = new ArrayList<>();
        allOdds.addAll(moneylineService.generatePreMatchOdds(home, away));
        allOdds.addAll(spreadService.generateSpreadOdds(home, away));
        allOdds.addAll(totalsService.generateTotalOdds(home, away));
        allOdds.addAll(marginService.generateMarginOdds(home, away));
        allOdds.addAll(quarterService.generateQuarterOdds(home, away));

        List<Odds> entities = toEntities(allOdds, matchId, home, away);

        oddsRepository.deleteByMatchId(matchId);
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveAllOdds [NBA]: matchId={} {} vs {} -- saved {} odds rows",
                matchId, home, away, entities.size());
    }

    // =========================================================================
    // LIVE
    // =========================================================================

    /**
     * Generates and saves live NBA odds, replacing all refreshable market rows.
     *
     * Quarter-leader and quarter-total markets for completed periods are omitted
     * by BasketballQuarterService, so settled rows are left untouched in the DB
     * (the delete is scoped only to LIVE_MARKETS).
     *
     * @param match  the live NBA Match entity
     * @param q1Home Q1 home score (0 if quarter not yet complete)
     * @param q1Away Q1 away score
     * @param q2Home Q2 home score (0 if quarter not yet complete)
     * @param q2Away Q2 away score
     * @param q3Home Q3 home score (0 if quarter not yet complete)
     * @param q3Away Q3 away score
     */
    @Transactional
    public void generateAndSaveLiveOdds(
            Match match,
            int q1Home, int q1Away,
            int q2Home, int q2Away,
            int q3Home, int q3Away) {

        String home      = match.getHomeTeam();
        String away      = match.getAwayTeam();
        int    scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
        int    scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
        int    minute    = extractMinute(match);
        UUID   matchId   = match.getId();

        List<Map<String, Object>> liveOdds = new ArrayList<>();
        liveOdds.addAll(moneylineService.generateLiveOdds(home, away, scoreHome, scoreAway, minute));
        liveOdds.addAll(spreadService.generateLiveSpreadOdds(home, away, scoreHome, scoreAway, minute));
        liveOdds.addAll(totalsService.generateLiveTotalOdds(home, away, scoreHome, scoreAway, minute));
        liveOdds.addAll(marginService.generateLiveMarginOdds(home, away, scoreHome, scoreAway, minute));
        liveOdds.addAll(quarterService.generateLiveQuarterOdds(
                home, away,
                scoreHome, scoreAway,
                q1Home, q1Away,
                q2Home, q2Away,
                q3Home, q3Away,
                minute));

        List<Odds> entities = toEntities(liveOdds, matchId, home, away);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, LIVE_MARKETS);
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveLiveOdds [NBA]: matchId={} {} vs {} | score={}-{} min={} -- saved {} rows",
                matchId, home, away, scoreHome, scoreAway, minute, entities.size());
    }

    /**
     * Convenience overload when per-quarter scores are not tracked separately.
     * BasketballQuarterService will still derive current-quarter context from
     * the aggregate score + minute, but Q1/HT/Q3 leader markets will have no
     * prior-quarter scoring context.
     */
    @Transactional
    public void generateAndSaveLiveOdds(Match match) {
        generateAndSaveLiveOdds(match, 0, 0, 0, 0, 0, 0);
    }

    // =========================================================================
    // ENTITY MAPPING
    // =========================================================================

    private List<Odds> toEntities(List<Map<String, Object>> odds, UUID matchId,
                                  String home, String away) {
        Instant    now    = Instant.now();
        List<Odds> result = new ArrayList<>();

        for (Map<String, Object> o : odds) {
            Object rawOdd = o.get("odd");
            if (rawOdd == null) {
                log.warn("toEntities [NBA]: matchId={} skipping null odd -- selection={}",
                        matchId, o.get("selection"));
                continue;
            }

            BigDecimal oddValue;
            try {
                oddValue = parseOddValue(rawOdd.toString());
            } catch (Exception e) {
                log.warn("toEntities [NBA]: matchId={} skipping unparseable odd='{}' selection={} -- {}",
                        matchId, rawOdd, o.get("selection"), e.getMessage());
                continue;
            }

            if (oddValue.compareTo(BigDecimal.ONE) < 0) {
                log.warn("toEntities [NBA]: matchId={} skipping odd={} < 1.0 for selection={}",
                        matchId, oddValue, o.get("selection"));
                continue;
            }

            // Spread lines stored in "spread" field; total lines in "total" field.
            // Both map to the `line` column on the Odds entity (numeric line value,
            // e.g. 5.5 or 224.5). `handicap` is left null — NBA markets don't use it.
            BigDecimal lineVal = parseOptionalDecimal(matchId, o, "spread");
            if (lineVal == null) {
                lineVal = parseOptionalDecimal(matchId, o, "total");
            }

            String normalizedMarket    = normalizeMarket((String) o.get("market"));
            String normalizedSelection = normalizeSelection(
                    (String) o.get("selection"), home, away, (String) o.get("market"), o);

            // Guard against within-batch duplicates to avoid violating the unique constraint.
            boolean alreadyInBatch = result.stream().anyMatch(existing ->
                    existing.getMarket().equals(normalizedMarket) &&
                    existing.getSelection().equals(normalizedSelection));

            if (alreadyInBatch) {
                log.debug("toEntities [NBA]: matchId={} skipping duplicate market={} selection={}",
                        matchId, normalizedMarket, normalizedSelection);
                continue;
            }

            result.add(Odds.builder()
                    .matchId(matchId)
                    .market(normalizedMarket)
                    .selection(normalizedSelection)
                    .value(oddValue)
                    .line(lineVal)       // spread value (e.g. -5.5) or total line (e.g. 224.5)
                    .handicap(null)      // unused for NBA markets
                    .capturedAt(now)
                    .build());
        }

        return result;
    }

    // =========================================================================
    // PARSING
    // =========================================================================

    /**
     * Parses decimal, fractional ("3/1"), or integer odd strings.
     * Fractional odds are converted to European decimal: (numerator / denominator) + 1.
     */
    private BigDecimal parseOddValue(String raw) {
        String s = raw.trim();
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length != 2) throw new NumberFormatException("Bad fractional: " + raw);
            BigDecimal num   = new BigDecimal(parts[0].trim());
            BigDecimal denom = new BigDecimal(parts[1].trim());
            if (denom.compareTo(BigDecimal.ZERO) == 0)
                throw new ArithmeticException("Zero denominator: " + raw);
            return num.divide(denom, MathContext.DECIMAL64)
                      .add(BigDecimal.ONE)
                      .setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(s);
    }

    /** Safely reads a named field and parses it as BigDecimal; returns null on any failure. */
    private BigDecimal parseOptionalDecimal(UUID matchId, Map<String, Object> o, String key) {
        Object raw = o.get(key);
        if (raw == null) return null;
        try {
            String s = raw.toString().trim();
            // Handle "home/away" spread format like "-2/+2" → take the first part
            if (s.contains("/")) {
                s = s.split("/")[0].trim();
            }
            // Strip leading "+" sign
            s = s.replace("+", "");
            return new BigDecimal(s);
        } catch (Exception e) {
            log.warn("toEntities [NBA]: matchId={} could not parse {}='{}' -- setting null",
                    matchId, key, raw);
            return null;
        }
    }

    // =========================================================================
    // NORMALISATION
    // =========================================================================

    private String normalizeMarket(String market) {
        if (market == null) return "UNKNOWN";
        return switch (market.toLowerCase()) {
            case "moneyline"       -> "moneyline";
            case "point_spread"    -> "point_spread";
            case "game_total"      -> "game_total";
            case "winning_margin"  -> "winning_margin";
            case "overtime"        -> "overtime";
            case "q1_leader"       -> "q1_leader";
            case "halftime_leader" -> "halftime_leader";
            case "q3_leader"       -> "q3_leader";
            case "q1_total"        -> "q1_total";
            case "q2_total"        -> "q2_total";
            case "q3_total"        -> "q3_total";
            case "q4_total"        -> "q4_total";
            default                -> market.toUpperCase();
        };
    }

    /**
     * Builds a unique selection key per row. For multi-line markets the line
     * value is appended to prevent collisions across spread/total lines.
     *
     * Examples:
     *   moneyline       -> "HOME" / "AWAY"
     *   point_spread    -> "HOME:-5.5" / "AWAY:+5.5" / "PUSH:-6/+6"
     *   game_total      -> "OVER:224.5" / "UNDER:224.5" / "PUSH:224"
     *   winning_margin  -> "Lakers by 1-5"  (verbatim -- already unique per band)
     *   overtime        -> "Yes" / "No"
     *   q*_leader       -> "HOME" / "AWAY"
     *   q*_total        -> "OVER:56.5" / "UNDER:56.5" / "PUSH:57"
     */
    private String normalizeSelection(String selection, String home, String away,
                                      String market, Map<String, Object> row) {
        if (selection == null) return "UNKNOWN";

        String sel = selection;
        if      (sel.equalsIgnoreCase(home)) sel = "HOME";
        else if (sel.equalsIgnoreCase(away)) sel = "AWAY";

        if (sel.equalsIgnoreCase("Push/Refund") || sel.toLowerCase().contains("push")) sel = "PUSH";

        String mkt = market == null ? "" : market.toLowerCase();

        if (mkt.equals("point_spread")) {
            Object spread = row.get("spread");
            if (spread != null) return sel + ":" + spread;
        }
        if (mkt.equals("game_total") || mkt.endsWith("_total")) {
            Object total = row.get("total");
            if (total != null) return sel + ":" + total;
        }

        // winning_margin bands and overtime Yes/No need no suffix.
        return sel;
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    /**
     * Extracts the current game minute from match metadata or derives it from
     * elapsed wall-clock time since tip-off. Clamped to [0, 53] to cover
     * regulation (48 min) plus a single OT period (5 min).
     */
    private int extractMinute(Match match) {
        if (match.getMetadata() != null) {
            Object min = match.getMetadata().get("minute");
            if (min != null) {
                try { return Integer.parseInt(min.toString()); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (match.getKickoffAt() != null) {
            long elapsed = ChronoUnit.MINUTES.between(match.getKickoffAt(), Instant.now());
            return (int) Math.min(Math.max(elapsed, 0), 53);
        }
        return 24; // default: halftime equivalent
    }
}
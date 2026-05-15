package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates Quarter and Half period markets for NBA basketball fixtures.
 *
 * Replaces HalfTimeOddsService for basketball. Key differences:
 * ─────────────────────────────────────────────────────────────────────────
 *  • NO DRAW — quarter/half leaders are always one team or the other.
 *    Basketball cannot be tied mid-period in market terms (ties mid-quarter
 *    exist in play but the "leader after Q1" bet is 2-way).
 *  • Four quarters + halftime = 5 checkpoints: Q1, Q2 (HT), Q3, Q4 (final).
 *    Q4 leader == moneyline, so is excluded here (use BasketballMoneylineOddsService).
 *  • Quarter totals: Over/Under per quarter, lines ~54.5–58.5.
 *
 * Probability model:
 * ─────────────────────────────────────────────────────────────────────────
 *  Each quarter is modelled as an independent sub-game with:
 *    Expected pts per team per quarter = full-game xp / 4
 *    xp range: Home 108–122, Away 104–118  →  per-quarter: 27–30.5 each side
 *
 *  Quarter leader probability:
 *    Leader after Qn = P(homeQnPoints > awayQnPoints)
 *    Quarter point difference ~ Normal(μ_q, σ_q) where:
 *      μ_q = (xpHome − xpAway) / 4
 *      σ_q = √((xpHome + xpAway) / 4)   ≈ 7–8 for NBA quarters
 *
 *  Halftime leader (Q1+Q2 combined):
 *    μ_ht = (xpHome − xpAway) / 2
 *    σ_ht = √((xpHome + xpAway) / 2)    ≈ 10–11
 *
 *  Quarter totals:
 *    Combined quarter total ~ Normal(μ_q_total, σ_q_total) where:
 *      μ_q_total = (xpHome + xpAway) / 4  ≈ 53–60
 *      σ_q_total = √((xpHome + xpAway) / 4) ≈ 7–8
 *    Lines: 54.5, 56.5, 58.5, 60.5 (half — no push) and 55, 57, 59 (whole)
 *
 * Live behaviour:
 *  • Quarter leader markets for completed quarters are marked settled (empty).
 *  • For the current in-progress quarter, remaining expected points scale
 *    with time left in that quarter; current quarter score shifts the
 *    leader probability.
 *  • Quarter total market for in-progress quarter shifts accordingly.
 *
 * Overrounds:
 *  • Quarter/half leader: 1.055  (2-way, tight)
 *  • Quarter totals half: 1.055  (2-way)
 *  • Quarter totals whole: 1.07  (3-way with push)
 *
 * Markets generated:
 *  • q1_leader     — home win Q1 / away win Q1
 *  • halftime_leader — home lead HT / away lead HT
 *  • q3_leader     — home win Q3 / away win Q3
 *  • q1_total      — Over/Under for Q1
 *  • q2_total      — Over/Under for Q2
 *  • q3_total      — Over/Under for Q3
 *  • q4_total      — Over/Under for Q4
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class BasketballQuarterService {

    // ── Overrounds ────────────────────────────────────────────────────────
    private static final double OVERROUND_LEADER       = 1.055;
    private static final double OVERROUND_TOTAL_HALF   = 1.055;
    private static final double OVERROUND_TOTAL_WHOLE  = 1.07;

    // ── Decimal odds bounds ───────────────────────────────────────────────
    private static final double MIN_ODD = 1.05;
    private static final double MAX_ODD = 15.0;

    // ── Expected-points base values ───────────────────────────────────────
    private static final double XP_HOME_BASE = 108.0;
    private static final double XP_AWAY_BASE = 104.0;
    private static final double XP_RANGE     = 14.0;

    // ── Quarter total lines ───────────────────────────────────────────────
    // Half lines (no push): 54.5–60.5
    private static final double[] Q_TOTAL_HALF_LINES  = { 54.5, 56.5, 58.5, 60.5 };
    // Whole lines (push ok): 55–59
    private static final double[] Q_TOTAL_WHOLE_LINES = { 55.0, 57.0, 59.0 };

    // ── NBA period definitions ────────────────────────────────────────────
    // Each quarter is 12 minutes; halftime after Q2 = minute 24
    private static final int MINUTES_PER_QUARTER = 12;

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API — PRE-MATCH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate all pre-match quarter and half period markets.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @return list of market entry maps
     */
    public List<Map<String, Object>> generateQuarterOdds(String homeTeam, String awayTeam) {
        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam);
        Random rng = new Random(seed);

        double xpHome = XP_HOME_BASE + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        double xpAway = XP_AWAY_BASE + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        xpHome += rng.nextDouble() * 2.0 - 1.0;
        xpAway += rng.nextDouble() * 2.0 - 1.0;
        xpHome = Math.max(90, xpHome);
        xpAway = Math.max(90, xpAway);

        List<Map<String, Object>> odds = new ArrayList<>();

        // ── Period leader markets ─────────────────────────────────────────
        // Q1 leader
        odds.addAll(buildLeaderMarket("q1_leader",      homeTeam, awayTeam,
                quarterMu(xpHome, xpAway, 1), quarterSigma(xpHome, xpAway, 1), rng));
        // Halftime leader (Q1+Q2 combined)
        odds.addAll(buildLeaderMarket("halftime_leader", homeTeam, awayTeam,
                quarterMu(xpHome, xpAway, 2), quarterSigma(xpHome, xpAway, 2), rng));
        // Q3 leader
        odds.addAll(buildLeaderMarket("q3_leader",      homeTeam, awayTeam,
                quarterMu(xpHome, xpAway, 1), quarterSigma(xpHome, xpAway, 1), rng));

        // ── Quarter total markets (Q1–Q4) ─────────────────────────────────
        double muQtotal    = (xpHome + xpAway) / 4.0;
        double sigmaQtotal = Math.sqrt((xpHome + xpAway) / 4.0);
        for (int q = 1; q <= 4; q++) {
            String market = "q" + q + "_total";
            odds.addAll(buildQuarterTotals(market, muQtotal, sigmaQtotal, rng));
        }

        log.debug("generateQuarterOdds: {} vs {} | xpHome={} xpAway={} | {} entries",
                homeTeam, awayTeam, round2(xpHome), round2(xpAway), odds.size());
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API — LIVE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate live quarter/half period markets reacting to clock + score.
     *
     * Markets for completed periods are omitted (settled).
     * Markets for the current in-progress period use remaining-time scaling.
     * Future period markets use full-quarter expectations.
     *
     * @param homeTeam         home team name
     * @param awayTeam         away team name
     * @param scoreHome        current total home score
     * @param scoreAway        current total away score
     * @param q1Home           Q1 home score (0 if not yet complete)
     * @param q1Away           Q1 away score (0 if not yet complete)
     * @param q2Home           Q2 home score (0 if not yet complete)
     * @param q2Away           Q2 away score (0 if not yet complete)
     * @param q3Home           Q3 home score (0 if not yet complete)
     * @param q3Away           Q3 away score (0 if not yet complete)
     * @param minutePlayed     approximate total minutes elapsed (0–48)
     * @return list of market entry maps (settled markets excluded)
     */
    public List<Map<String, Object>> generateLiveQuarterOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int q1Home, int q1Away,
            int q2Home, int q2Away,
            int q3Home, int q3Away,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();

        int currentQuarter = Math.min(4, minutePlayed / MINUTES_PER_QUARTER + 1);
        int minuteInQuarter = minutePlayed % MINUTES_PER_QUARTER;
        double fractionRemainingInQuarter = Math.max(0.0,
                (MINUTES_PER_QUARTER - minuteInQuarter) / (double) MINUTES_PER_QUARTER);

        // Base expected points per full quarter
        double xpHomePerQ = 110.0 / 4.0;  // ~27.5
        double xpAwayPerQ = 107.0 / 4.0;  // ~26.75

        Random rng = new Random(System.currentTimeMillis());
        List<Map<String, Object>> odds = new ArrayList<>();

        // ── Q1 leader ─────────────────────────────────────────────────────
        if (currentQuarter == 1) {
            // Q1 still in progress — remaining scoring determines leader
            int currentQ1Diff = q1Home - q1Away; // may be partial
            double muRemaining    = currentQ1Diff + (xpHomePerQ - xpAwayPerQ) * fractionRemainingInQuarter;
            double sigmaRemaining = Math.max(1.0, Math.sqrt((xpHomePerQ + xpAwayPerQ) * fractionRemainingInQuarter));
            odds.addAll(buildLeaderMarket("q1_leader", homeTeam, awayTeam, muRemaining, sigmaRemaining, rng));
        }
        // Q1 complete (currentQuarter > 1) → market settled, omit

        // ── Halftime leader ────────────────────────────────────────────────
        if (currentQuarter <= 2) {
            int htDiffSoFar = (q1Home - q1Away) + (q2Home - q2Away); // includes completed Q1
            double muRemainingHt    = htDiffSoFar + (xpHomePerQ - xpAwayPerQ) * fractionRemainingInQuarter;
            double sigmaRemainingHt = Math.max(1.0, Math.sqrt((xpHomePerQ + xpAwayPerQ) * fractionRemainingInQuarter));
            odds.addAll(buildLeaderMarket("halftime_leader", homeTeam, awayTeam, muRemainingHt, sigmaRemainingHt, rng));
        }
        // Halftime complete (currentQuarter > 2) → market settled, omit

        // ── Q3 leader ─────────────────────────────────────────────────────
        if (currentQuarter == 3) {
            int currentQ3Diff = q3Home - q3Away;
            double muRemainingQ3    = currentQ3Diff + (xpHomePerQ - xpAwayPerQ) * fractionRemainingInQuarter;
            double sigmaRemainingQ3 = Math.max(1.0, Math.sqrt((xpHomePerQ + xpAwayPerQ) * fractionRemainingInQuarter));
            odds.addAll(buildLeaderMarket("q3_leader", homeTeam, awayTeam, muRemainingQ3, sigmaRemainingQ3, rng));
        } else if (currentQuarter < 3) {
            // Q3 hasn't started — use full-quarter expectation
            odds.addAll(buildLeaderMarket("q3_leader", homeTeam, awayTeam,
                    xpHomePerQ - xpAwayPerQ, Math.sqrt(xpHomePerQ + xpAwayPerQ), rng));
        }
        // Q3 complete (currentQuarter > 3) → market settled, omit

        // ── Quarter totals (only for current + future quarters) ────────────
        for (int q = currentQuarter; q <= 4; q++) {
            String market = "q" + q + "_total";
            double fraction = (q == currentQuarter) ? fractionRemainingInQuarter : 1.0;
            double muTotal    = (xpHomePerQ + xpAwayPerQ) * fraction;
            double sigmaTotal = Math.max(1.0, Math.sqrt((xpHomePerQ + xpAwayPerQ) * fraction));

            // Shift live quarter total by points already scored this quarter
            if (q == currentQuarter) {
                int scoredThisQ = getCurrentQuarterScore(
                        q, scoreHome, scoreAway, q1Home, q1Away, q2Home, q2Away, q3Home, q3Away);
                muTotal += scoredThisQ;
            }
            odds.addAll(buildQuarterTotals(market, muTotal, sigmaTotal, rng));
        }

        log.debug("generateLiveQuarterOdds: {} vs {} | min={} quarter={} | {} entries",
                homeTeam, awayTeam, minutePlayed, currentQuarter, odds.size());
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 2-way leader market (no draw) for any period.
     * Home leads if period GD > 0; away leads if period GD < 0.
     * A true tie at period end is absorbed proportionally into both sides
     * (in reality it forces OT for HT-only markets, but market convention
     * settles on the team leading — any dead heat is voided by the operator).
     */
    private List<Map<String, Object>> buildLeaderMarket(
            String market, String homeTeam, String awayTeam,
            double mu, double sigma, Random rng) {

        // P(home leads) = P(GD > 0) under Normal(mu, sigma)
        // Tie mass P(GD == 0) is split 50/50 into home/away since the market
        // is 2-way — bookmakers void tied-period leader bets in practice, but
        // pricing reflects the tie-break probability splitting towards both sides.
        double pHomeLead = normalCdf(mu, sigma);  // P(X > 0)
        double pTie      = normalCdf(mu + 0.5, sigma) - normalCdf(mu - 0.5, sigma);
        double pHomeAdj  = Math.min(0.97, pHomeLead + pTie * 0.5);
        double pAwayAdj  = Math.max(0.03, 1.0 - pHomeAdj);

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise    = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double homeOdd  = clamp(applyMargin(pHomeAdj * noise, OVERROUND_LEADER));
            double awayOdd  = clamp(applyMargin(pAwayAdj / noise, OVERROUND_LEADER));
            result.add(buildEntry(bk, market, homeTeam, homeOdd));
            result.add(buildEntry(bk, market, awayTeam, awayOdd));
        }
        return result;
    }

    /**
     * Quarter totals Over/Under across both half and whole lines.
     */
    private List<Map<String, Object>> buildQuarterTotals(
            String market, double mu, double sigma, Random rng) {

        List<Map<String, Object>> result = new ArrayList<>();

        for (double line : Q_TOTAL_HALF_LINES) {
            double overProb  = normalCdf(mu - line, sigma);
            double underProb = 1.0 - overProb;
            for (String bk : BOOKMAKERS) {
                double noise    = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
                double overOdd  = clamp(applyMargin(overProb  * noise, OVERROUND_TOTAL_HALF));
                double underOdd = clamp(applyMargin(underProb / noise, OVERROUND_TOTAL_HALF));
                result.add(buildTotalEntry(bk, market, "Over",  formatLine(line), overOdd));
                result.add(buildTotalEntry(bk, market, "Under", formatLine(line), underOdd));
            }
        }

        for (double line : Q_TOTAL_WHOLE_LINES) {
            int intLine = (int) line;
            double overProb  = normalCdf(mu - intLine - 0.5, sigma);
            double underProb = normalCdf(-(mu - intLine) - 0.5, sigma);
            double pushProb  = Math.max(0.01, 1.0 - overProb - underProb);
            double total     = overProb + pushProb + underProb;
            overProb  /= total; pushProb /= total; underProb /= total;
            for (String bk : BOOKMAKERS) {
                double noise    = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
                double overOdd  = clamp(applyMargin(overProb  * noise, OVERROUND_TOTAL_WHOLE));
                double pushOdd  = clamp(applyMargin(pushProb,          OVERROUND_TOTAL_WHOLE));
                double underOdd = clamp(applyMargin(underProb / noise, OVERROUND_TOTAL_WHOLE));
                String formatted = formatLine(line);
                result.add(buildTotalEntry(bk, market, "Over",        formatted, overOdd));
                result.add(buildTotalEntry(bk, market, "Push/Refund", formatted, pushOdd));
                result.add(buildTotalEntry(bk, market, "Under",       formatted, underOdd));
            }
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // NORMAL DISTRIBUTION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * P(X > 0) where X ~ Normal(mu, sigma).
     * Abramowitz & Stegun rational approximation (max error 1.5e-7).
     */
    private double normalCdf(double mu, double sigma) {
        if (sigma <= 0) return mu > 0 ? 0.97 : 0.03;
        double z = mu / sigma;
        double p = 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
        return Math.max(0.03, Math.min(0.97, p));
    }

    /** Error function approximation (Abramowitz & Stegun 7.1.26). */
    private static double erf(double x) {
        boolean negative = x < 0;
        double ax = Math.abs(x);
        double t  = 1.0 / (1.0 + 0.3275911 * ax);
        double y  = 1.0 - (((((1.061405429  * t
                             - 1.453152027) * t)
                             + 1.421413741) * t
                             - 0.284496736) * t
                             + 0.254829592) * t * Math.exp(-ax * ax);
        return negative ? -y : y;
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════════════════

    /**
     * GD mean for N quarters combined.
     *   μ_N = (xpHome − xpAway) × N / 4
     */
    private double quarterMu(double xpHome, double xpAway, int quarters) {
        return (xpHome - xpAway) * quarters / 4.0;
    }

    /**
     * GD std-dev for N quarters combined (independent quarters → variances add).
     *   σ_N = √((xpHome + xpAway) × N / 4)
     */
    private double quarterSigma(double xpHome, double xpAway, int quarters) {
        return Math.sqrt((xpHome + xpAway) * quarters / 4.0);
    }

    /**
     * Derives the combined points scored so far in the current quarter
     * from the running per-quarter score accumulators.
     */
    private int getCurrentQuarterScore(int quarter,
            int scoreHome, int scoreAway,
            int q1Home, int q1Away,
            int q2Home, int q2Away,
            int q3Home, int q3Away) {

        return switch (quarter) {
            case 1 -> q1Home + q1Away;
            case 2 -> (scoreHome + scoreAway) - (q1Home + q1Away) - (q3Home + q3Away);
            case 3 -> q3Home + q3Away;
            // Q4: total minus Q1+Q2+Q3
            default -> (scoreHome + scoreAway) - (q1Home + q1Away)
                                               - (q2Home + q2Away)
                                               - (q3Home + q3Away);
        };
    }

    private double applyMargin(double trueProb, double overround) {
        if (trueProb <= 0) return MAX_ODD;
        return 1.0 / (trueProb * overround);
    }

    private double clamp(double odd) {
        return Math.max(MIN_ODD, Math.min(MAX_ODD, odd));
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String formatLine(double line) {
        return (line % 1 == 0)
                ? String.valueOf((int) line)
                : String.valueOf(round2(line));
    }

    private Map<String, Object> buildEntry(String bookmaker, String market,
                                           String selection, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  bookmaker);
        m.put("market",     market);
        m.put("selection",  selection);
        m.put("odd",        String.valueOf(round2(odd)));
        return m;
    }

    private Map<String, Object> buildTotalEntry(String bookmaker, String market,
                                                String selection, String total, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  bookmaker);
        m.put("market",     market);
        m.put("selection",  selection);
        m.put("total",      total);
        m.put("odd",        String.valueOf(round2(odd)));
        return m;
    }

    private long buildSeed(String homeTeam, String awayTeam) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase() + "|nba|quarters";
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates Game Total (Over/Under) odds for NBA basketball fixtures.
 *
 * This is the most popular NBA betting market — no football equivalent exists
 * in the current codebase.
 *
 * Total lines offered:
 * ─────────────────────────────────────────────────────────────────────────
 *  Half totals  (no push): 210.5, 213.5, 216.5, 219.5, 222.5, 225.5,
 *                          228.5, 231.5, 234.5, 237.5, 240.5
 *  Whole totals (push ok): 212,   215,   218,   221,   224,   227,
 *                          230,   233,   236,   239
 *  Spread mirrors: each line produces an Over AND Under selection.
 *
 * Probability model:
 * ─────────────────────────────────────────────────────────────────────────
 *  Combined NBA scoring is modelled as two independent Poisson processes.
 *  Because λ per team is ~105–115 (large), the sum of both teams' points
 *  (total score T) is approximated by a Normal distribution via CLT:
 *
 *    T ~ Normal(μ = xpHome + xpAway, σ = √(xpHome + xpAway))
 *
 *  Expected points per team:
 *    Home: 108 + (homeHash % 1000 / 1000.0) * 14  → 108–122
 *    Away: 104 + (awayHash % 1000 / 1000.0) * 14  → 104–118
 *    Combined μ range: 212–240 (matches real NBA totals market lines)
 *
 * Live totals:
 *  Remaining expected total scales with time left; current combined score
 *  shifts the distribution so lines tighten as the game progresses.
 *
 * Overrounds:
 *  • Half totals:  1.055  (2-way, efficient — most liquid NBA market)
 *  • Whole totals: 1.075  (3-way, push possible)
 *
 * Markets generated:
 *  • game_total — Over / Under (and Push/Refund for whole lines)
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class BasketballTotalsService {

    // ── Overrounds ────────────────────────────────────────────────────────
    private static final double OVERROUND_HALF  = 1.055;
    private static final double OVERROUND_WHOLE = 1.075;

    // ── Decimal odds bounds ───────────────────────────────────────────────
    private static final double MIN_ODD = 1.05;
    private static final double MAX_ODD = 15.0;

    // ── Expected-points base values ───────────────────────────────────────
    // NBA 2023-24 average: ~114 pts/game per team (228 combined)
    private static final double XP_HOME_BASE = 108.0;
    private static final double XP_AWAY_BASE = 104.0;
    private static final double XP_RANGE     = 14.0;

    // ── Total lines to generate ───────────────────────────────────────────
    // Half lines: no push, 2-way market
    private static final double[] HALF_LINES = {
            210.5, 213.5, 216.5, 219.5, 222.5, 225.5,
            228.5, 231.5, 234.5, 237.5, 240.5
    };
    // Whole lines: push possible when combined score == line
    private static final double[] WHOLE_LINES = {
            212.0, 215.0, 218.0, 221.0, 224.0, 227.0,
            230.0, 233.0, 236.0, 239.0
    };

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate pre-match game total Over/Under odds for an NBA fixture.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @return list of { bookmaker, market, selection, total, odd } maps
     */
    public List<Map<String, Object>> generateTotalOdds(String homeTeam, String awayTeam) {
        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam);
        Random rng = new Random(seed);

        double xpHome = XP_HOME_BASE + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        double xpAway = XP_AWAY_BASE + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        xpHome += rng.nextDouble() * 2.0 - 1.0;
        xpAway += rng.nextDouble() * 2.0 - 1.0;
        xpHome = Math.max(90, xpHome);
        xpAway = Math.max(90, xpAway);

        // Combined total follows Normal(μ, σ)
        double mu    = xpHome + xpAway;
        double sigma = Math.sqrt(xpHome + xpAway); // ~14–16

        List<Map<String, Object>> odds = new ArrayList<>();
        for (double line : HALF_LINES)  odds.addAll(buildHalfTotal(line, mu, sigma, rng));
        for (double line : WHOLE_LINES) odds.addAll(buildWholeTotal(line, mu, sigma, rng));

        log.debug("generateTotalOdds: {} vs {} | xpHome={} xpAway={} mu={} sigma={} | {} entries",
                homeTeam, awayTeam, round2(xpHome), round2(xpAway),
                round2(mu), round2(sigma), odds.size());
        return odds;
    }

    /**
     * Generate live game total odds reacting to current combined score + clock.
     *
     * @param homeTeam     home team name
     * @param awayTeam     away team name
     * @param scoreHome    current home points
     * @param scoreAway    current away points
     * @param minutePlayed approximate minutes elapsed (0–48)
     * @return list of { bookmaker, market, selection, total, odd } maps
     */
    public List<Map<String, Object>> generateLiveTotalOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();

        double remainingFraction = Math.max(0.0, (48.0 - minutePlayed) / 48.0);
        int currentCombined = scoreHome + scoreAway;

        // Remaining expected combined points scale with time left
        double xpRemainingHome = Math.max(5.0, 110.0 * remainingFraction);
        double xpRemainingAway = Math.max(5.0, 107.0 * remainingFraction);

        // Distribution for final combined score = current + remaining
        double mu    = currentCombined + xpRemainingHome + xpRemainingAway;
        double sigma = Math.max(2.0, Math.sqrt(xpRemainingHome + xpRemainingAway));

        Random rng = new Random(System.currentTimeMillis());
        List<Map<String, Object>> odds = new ArrayList<>();
        for (double line : HALF_LINES)  odds.addAll(buildHalfTotal(line, mu, sigma, rng));
        for (double line : WHOLE_LINES) odds.addAll(buildWholeTotal(line, mu, sigma, rng));

        log.debug("generateLiveTotalOdds: {} vs {} | score={}-{} min={} | mu={} sigma={} | {} entries",
                homeTeam, awayTeam, scoreHome, scoreAway, minutePlayed,
                round2(mu), round2(sigma), odds.size());
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TOTAL LINE BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Half total line (e.g. 224.5) — strictly 2-way: Over or Under, no push.
     * Over wins if combined score > line; Under wins if combined score < line.
     */
    private List<Map<String, Object>> buildHalfTotal(
            double line, double mu, double sigma, Random rng) {

        // P(T > line) where T ~ Normal(mu, sigma)
        double overProb  = normalCdf(mu - line, sigma);   // P(T - line > 0)
        double underProb = 1.0 - overProb;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise   = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double overOdd  = clamp(applyMargin(overProb  * noise, OVERROUND_HALF));
            double underOdd = clamp(applyMargin(underProb / noise, OVERROUND_HALF));
            result.add(buildEntry(bk, "Over",  formatLine(line), overOdd));
            result.add(buildEntry(bk, "Under", formatLine(line), underOdd));
        }
        return result;
    }

    /**
     * Whole total line (e.g. 224) — 3-way: Over / Push (Refund) / Under.
     * Push when combined score == line exactly.
     */
    private List<Map<String, Object>> buildWholeTotal(
            double line, double mu, double sigma, Random rng) {

        int intLine = (int) line;

        // P(T > intLine): over covers
        double overProb  = normalCdf(mu - intLine - 0.5, sigma);
        // P(T < intLine): under covers
        double underProb = normalCdf(-(mu - intLine) - 0.5, sigma);
        // P(T == intLine): push — discrete mass approximated via Normal PDF
        double pushProb  = Math.max(0.01, 1.0 - overProb - underProb);

        double total = overProb + pushProb + underProb;
        overProb  /= total;
        pushProb  /= total;
        underProb /= total;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise    = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double overOdd  = clamp(applyMargin(overProb  * noise, OVERROUND_WHOLE));
            double pushOdd  = clamp(applyMargin(pushProb,          OVERROUND_WHOLE));
            double underOdd = clamp(applyMargin(underProb / noise, OVERROUND_WHOLE));
            String formatted = formatLine(line);
            result.add(buildEntry(bk, "Over",        formatted, overOdd));
            result.add(buildEntry(bk, "Push/Refund", formatted, pushOdd));
            result.add(buildEntry(bk, "Under",       formatted, underOdd));
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // NORMAL DISTRIBUTION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * P(X > 0) where X ~ Normal(mu, sigma).
     * Uses Abramowitz & Stegun rational approximation (max error 1.5e-7).
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

    private Map<String, Object> buildEntry(String bookmaker, String selection,
                                           String total, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  bookmaker);
        m.put("market",     "game_total");
        m.put("selection",  selection);   // "Over" | "Under" | "Push/Refund"
        m.put("total",      total);       // e.g. "224.5" or "224"
        m.put("odd",        String.valueOf(round2(odd)));
        return m;
    }

    private long buildSeed(String homeTeam, String awayTeam) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase() + "|nba|totals";
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
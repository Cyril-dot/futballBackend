package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates Point Spread odds for NBA basketball fixtures.
 *
 * NBA-calibrated replacement for HandicapOddsService.
 *
 * Spread lines offered:
 * ─────────────────────────────────────────────────────────────────────────
 *  Half spreads  (no push): -1.5, -3.5, -5.5, -7.5, -10.5, -13.5
 *  Whole spreads (push ok): -2,   -4,   -6,   -8,   -11,   -14
 *  Quarter spreads (split): -2.5, -4.5, -6.5, -9.5, -12.5
 *  Positive mirror of each line is also generated for the away side.
 *
 * Probability model:
 * ─────────────────────────────────────────────────────────────────────────
 *  NBA scoring is modelled as two independent Poisson processes with λ
 *  (expected points) in the 100–120 range per team. Goal-difference (point
 *  spread) is approximated over a GD range of -40 to +40.
 *
 *  Expected points per team:
 *    Home: 108 + (homeHash % 1000 / 1000.0) * 14  → 108–122
 *    Away: 104 + (awayHash % 1000 / 1000.0) * 14  → 104–118
 *
 *  Because exact Poisson with λ~110 is computationally expensive (k up to
 *  150+), the point-spread distribution is instead approximated via a
 *  Normal distribution (Central Limit Theorem):
 *    GD ~ Normal(μ = xpHome − xpAway, σ = √(xpHome + xpAway))
 *  This is an excellent approximation for large λ and matches actual NBA
 *  point-spread market efficiency well.
 *
 * Live spreads:
 *  Remaining expected points scale with time left; current score gap shifts
 *  the GD distribution, so spread lines update as the game progresses.
 *
 * Overrounds:
 *  • Half / quarter spreads: 1.06  (2-way, no push)
 *  • Whole spreads:          1.08  (3-way, push possible)
 *
 * Markets generated:
 *  • point_spread — home covers / away covers (and push for whole lines)
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class BasketballPointSpreadService {

    // ── Overrounds ────────────────────────────────────────────────────────
    private static final double OVERROUND_HALF  = 1.06;
    private static final double OVERROUND_WHOLE = 1.08;

    // ── Decimal odds bounds ───────────────────────────────────────────────
    private static final double MIN_ODD = 1.05;
    private static final double MAX_ODD = 15.0;

    // ── Expected-points base values ───────────────────────────────────────
    // NBA teams average ~108–115 points per game (2023–24 season).
    private static final double XP_HOME_BASE = 108.0;
    private static final double XP_AWAY_BASE = 104.0;
    private static final double XP_RANGE     = 14.0;   // hash-driven variance

    // ── Spread lines to generate (home perspective, negative = home favoured) ─
    private static final double[] SPREAD_LINES = {
            -1.5, -2.0, -2.5,
            -3.5, -4.0, -4.5,
            -5.5, -6.0, -6.5,
            -7.5, -8.0,
            -10.5, -11.0, -12.5,
            -13.5, -14.0,
            // Positive mirrors (away favoured / home underdog)
             1.5,  2.0,  2.5,
             3.5,  4.0,  4.5,
             5.5,  6.0,  6.5,
             7.5,  8.0,
            10.5, 11.0, 12.5,
            13.5, 14.0
    };

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate pre-match point spread odds for an NBA fixture.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @return list of { bookmaker, market, selection, spread, odd } maps
     */
    public List<Map<String, Object>> generateSpreadOdds(String homeTeam, String awayTeam) {
        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam);
        Random rng = new Random(seed);

        double xpHome = XP_HOME_BASE + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        double xpAway = XP_AWAY_BASE + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        xpHome += rng.nextDouble() * 2.0 - 1.0; // ±1 pt jitter
        xpAway += rng.nextDouble() * 2.0 - 1.0;
        xpHome = Math.max(90, xpHome);
        xpAway = Math.max(90, xpAway);

        // Normal distribution parameters for point-spread
        double mu    = xpHome - xpAway;
        double sigma = Math.sqrt(xpHome + xpAway); // ~14–16 for NBA

        List<Map<String, Object>> odds = new ArrayList<>();

        for (double line : SPREAD_LINES) {
            SpreadType type = classifyLine(line);
            switch (type) {
                case HALF    -> odds.addAll(buildHalfSpread(homeTeam, awayTeam, line, mu, sigma, rng));
                case WHOLE   -> odds.addAll(buildWholeSpread(homeTeam, awayTeam, line, mu, sigma, rng));
                case QUARTER -> odds.addAll(buildQuarterSpread(homeTeam, awayTeam, line, mu, sigma, rng));
            }
        }

        log.debug("generateSpreadOdds: {} vs {} | xpHome={} xpAway={} mu={} sigma={} | {} lines",
                homeTeam, awayTeam, round2(xpHome), round2(xpAway),
                round2(mu), round2(sigma), odds.size() / BOOKMAKERS.size());
        return odds;
    }

    /**
     * Generate live point spread odds reacting to current score + game clock.
     *
     * @param homeTeam     home team name
     * @param awayTeam     away team name
     * @param scoreHome    current home points
     * @param scoreAway    current away points
     * @param minutePlayed approximate minutes elapsed (0–48)
     * @return list of { bookmaker, market, selection, spread, odd } maps
     */
    public List<Map<String, Object>> generateLiveSpreadOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();

        double remainingFraction = Math.max(0.0, (48.0 - minutePlayed) / 48.0);
        int currentDiff = scoreHome - scoreAway;

        // Remaining expected points scale with time left
        double xpHomeRemaining = Math.max(5.0, 110.0 * remainingFraction);
        double xpAwayRemaining = Math.max(5.0, 107.0 * remainingFraction);

        // GD distribution for remaining game, then shifted by current score
        double mu    = xpHomeRemaining - xpAwayRemaining + currentDiff;
        double sigma = Math.max(2.0, Math.sqrt(xpHomeRemaining + xpAwayRemaining));

        Random rng = new Random(System.currentTimeMillis());
        List<Map<String, Object>> odds = new ArrayList<>();

        for (double line : SPREAD_LINES) {
            SpreadType type = classifyLine(line);
            switch (type) {
                case HALF    -> odds.addAll(buildHalfSpread(homeTeam, awayTeam, line, mu, sigma, rng));
                case WHOLE   -> odds.addAll(buildWholeSpread(homeTeam, awayTeam, line, mu, sigma, rng));
                case QUARTER -> odds.addAll(buildQuarterSpread(homeTeam, awayTeam, line, mu, sigma, rng));
            }
        }

        log.debug("generateLiveSpreadOdds: {} vs {} | score={}-{} min={} | mu={} sigma={} | {} entries",
                homeTeam, awayTeam, scoreHome, scoreAway, minutePlayed,
                round2(mu), round2(sigma), odds.size());
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // SPREAD LINE BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Half spread (e.g. -5.5 / +5.5) — strictly 2-way, no push.
     * Home covers if final point diff > |line|.
     */
    private List<Map<String, Object>> buildHalfSpread(
            String homeTeam, String awayTeam,
            double line, double mu, double sigma, Random rng) {

        double homeCoversProb = normalCdf(mu - line, sigma); // P(GD - line > 0)
        double awayCoversProb = 1.0 - homeCoversProb;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double homeOdd = clamp(applyMargin(homeCoversProb * noise, OVERROUND_HALF));
            double awayOdd = clamp(applyMargin(awayCoversProb / noise, OVERROUND_HALF));
            result.add(buildEntry(bk, homeTeam, formatSpread(line),  homeOdd));
            result.add(buildEntry(bk, awayTeam, formatSpread(-line), awayOdd));
        }
        return result;
    }

    /**
     * Whole spread (e.g. -6 / +6) — 3-way with push possible.
     * Push occurs when final point diff == |line|.
     */
    private List<Map<String, Object>> buildWholeSpread(
            String homeTeam, String awayTeam,
            double line, double mu, double sigma, Random rng) {

        int intLine = (int) line;
        // P(GD > intLine)  → home covers
        // P(GD == intLine) → push (use discrete approximation via Normal PDF)
        // P(GD < intLine)  → away covers
        double homeCoversProb = normalCdf(mu - intLine - 0.5, sigma);
        double awayCoversProb = normalCdf(-(mu - intLine) - 0.5, sigma);
        double pushProb       = Math.max(0.01, 1.0 - homeCoversProb - awayCoversProb);

        double total = homeCoversProb + pushProb + awayCoversProb;
        homeCoversProb /= total;
        pushProb       /= total;
        awayCoversProb /= total;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double homeOdd = clamp(applyMargin(homeCoversProb * noise, OVERROUND_WHOLE));
            double pushOdd = clamp(applyMargin(pushProb,               OVERROUND_WHOLE));
            double awayOdd = clamp(applyMargin(awayCoversProb / noise, OVERROUND_WHOLE));
            String homeSp  = formatSpread(line);
            String awaySp  = formatSpread(-line);
            result.add(buildEntry(bk, homeTeam,      homeSp,              homeOdd));
            result.add(buildEntry(bk, "Push/Refund", homeSp + "/" + awaySp, pushOdd));
            result.add(buildEntry(bk, awayTeam,      awaySp,              awayOdd));
        }
        return result;
    }

    /**
     * Quarter spread (e.g. -2.5 / +2.5 when it sits between whole lines) —
     * stake split across two adjacent lines, blended probability.
     * In NBA this is less common but offered on some markets.
     */
    private List<Map<String, Object>> buildQuarterSpread(
            String homeTeam, String awayTeam,
            double line, double mu, double sigma, Random rng) {

        double lower = Math.floor(line * 2) / 2.0;
        double upper = Math.ceil(line * 2)  / 2.0;

        double homeLower = isWholeNumber(lower)
                ? normalCdf(mu - (int) lower - 0.5, sigma)
                : normalCdf(mu - lower, sigma);
        double homeUpper = isWholeNumber(upper)
                ? normalCdf(mu - (int) upper - 0.5, sigma)
                : normalCdf(mu - upper, sigma);

        double homeCoversProb = (homeLower + homeUpper) / 2.0;
        double awayCoversProb = 1.0 - homeCoversProb;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double homeOdd = clamp(applyMargin(homeCoversProb * noise, OVERROUND_HALF));
            double awayOdd = clamp(applyMargin(awayCoversProb / noise, OVERROUND_HALF));
            result.add(buildEntry(bk, homeTeam, formatSpread(line),  homeOdd));
            result.add(buildEntry(bk, awayTeam, formatSpread(-line), awayOdd));
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // NORMAL DISTRIBUTION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * P(X > 0) where X ~ Normal(mu, sigma).
     * i.e. the probability that the home point margin exceeds the spread line.
     * Uses Abramowitz & Stegun rational approximation (max error 1.5e-7).
     */
    private double normalCdf(double mu, double sigma) {
        if (sigma <= 0) return mu > 0 ? 0.97 : 0.03;
        double z = mu / sigma;
        double p = 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
        return Math.max(0.03, Math.min(0.97, p));
    }

    /**
     * Error function approximation (Abramowitz & Stegun 7.1.26).
     */
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

    private enum SpreadType { HALF, WHOLE, QUARTER }

    private SpreadType classifyLine(double line) {
        double abs  = Math.abs(line);
        double frac = abs - Math.floor(abs);
        if (Math.abs(frac - 0.25) < 0.01 || Math.abs(frac - 0.75) < 0.01) return SpreadType.QUARTER;
        if (Math.abs(frac - 0.5)  < 0.01)                                   return SpreadType.HALF;
        return SpreadType.WHOLE;
    }

    private boolean isWholeNumber(double v) {
        return Math.abs(v - Math.round(v)) < 0.01;
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

    private String formatSpread(double line) {
        String formatted = (line % 1 == 0)
                ? String.valueOf((int) line)
                : String.valueOf(round2(line));
        return (line > 0 ? "+" : "") + formatted;
    }

    private Map<String, Object> buildEntry(String bookmaker, String selection, String spread, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  bookmaker);
        m.put("market",     "point_spread");
        m.put("selection",  selection);
        m.put("spread",     spread);
        m.put("odd",        String.valueOf(round2(odd)));
        return m;
    }

    private long buildSeed(String homeTeam, String awayTeam) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase() + "|nba";
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
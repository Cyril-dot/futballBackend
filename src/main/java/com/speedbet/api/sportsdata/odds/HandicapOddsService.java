package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates Asian Handicap odds for a fixture.
 *
 * Handicap types generated:
 * ─────────────────────────────────────────────────────────────────────────
 *  • Half handicaps  (-0.5, +0.5, -1.5, +1.5, -2.5, +2.5)
 *    → Binary outcome only (win or lose, no push/refund possible)
 *
 *  • Whole handicaps (-1, +1, -2, +2)
 *    → Three outcomes: win / push (refund) / lose
 *
 *  • Quarter handicaps (-0.25, +0.25, -0.75, +0.75, -1.25, +1.25, -1.75, +1.75)
 *    → Stake split into two adjacent half/whole lines
 *    → Can result in: full win, half win, half loss, full loss
 *
 * Handicap lines offered per fixture:
 *   -0.5 / +0.5   (must win / win or draw)
 *   -1   / +1     (whole — includes push)
 *   -1.5 / +1.5   (win by 2+ / lose by ≤1)
 *   -2   / +2     (whole — includes push)
 *   -2.5 / +2.5   (win by 3+ / lose by ≤2)
 *   -0.25/ +0.25  (quarter — split 0 and -0.5)
 *   -0.75/ +0.75  (quarter — split -0.5 and -1)
 *   -1.25/ +1.25  (quarter — split -1 and -1.5)
 *   -1.75/ +1.75  (quarter — split -1.5 and -2)
 *
 * Probability model:
 * ─────────────────────────────────────────────────────────────────────────
 *  Uses the same team-strength hash as OddsGeneratorService for consistency.
 *  For each handicap line H, the adjusted win probability for the home side is:
 *
 *    P(home covers H) = P(home goals − away goals > H)
 *
 *  Goal difference distribution is approximated as a discrete distribution
 *  derived from two independent Poisson random variables (xG home, xG away).
 *  GD runs from -5 to +5; probabilities beyond that are folded into the tails.
 *
 * Overround:
 *  • Half / quarter handicaps: ~106% (2-outcome market, tighter)
 *  • Whole handicaps:          ~108% (3-outcome market, includes push line)
 *
 * Markets generated:
 *  • asian_handicap — home covers / away covers (and push where applicable)
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class HandicapOddsService {

    // ── Overrounds ────────────────────────────────────────────────────────
    private static final double OVERROUND_HALF    = 1.06;  // half / quarter (2-way)
    private static final double OVERROUND_WHOLE   = 1.08;  // whole (3-way with push)

    // ── Decimal odds bounds ───────────────────────────────────────────────
    private static final double MIN_ODD = 1.05;
    private static final double MAX_ODD = 15.0;

    // ── Expected-goals range ──────────────────────────────────────────────
    // Kept consistent with CorrectScoreOddsService
    private static final double XG_HOME_BASE = 1.2;
    private static final double XG_AWAY_BASE = 0.8;
    private static final double XG_RANGE     = 1.0; // added on top of base via hash

    // ── Goal-difference range for Poisson table ───────────────────────────
    private static final int MIN_GD = -5;
    private static final int MAX_GD =  5;

    // ── Handicap lines to generate (home perspective) ─────────────────────
    // Positive = away team spotted goals (home favourite)
    // Negative = home team spotted goals (away favourite — rare but included)
    private static final double[] HANDICAP_LINES = {
            -0.25, -0.5, -0.75,
            -1.0,  -1.25, -1.5, -1.75,
            -2.0,  -2.5,
             0.25,  0.5,  0.75,
             1.0,   1.25, 1.5,  1.75,
             2.0,   2.5
    };

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate pre-match Asian Handicap odds for a fixture.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @param league   competition name (incorporated into seed)
     * @return list of { bookmaker, market, selection, handicap, odd } maps
     */
    public List<Map<String, Object>> generateHandicapOdds(
            String homeTeam, String awayTeam, String league) {

        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam, league);
        Random rng = new Random(seed);

        // Derive expected goals — same hash approach as CorrectScoreOddsService
        double xgHome = XG_HOME_BASE + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * XG_RANGE;
        double xgAway = XG_AWAY_BASE + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * XG_RANGE;
        // Small jitter so very similar team names diverge
        xgHome += rng.nextDouble() * 0.2 - 0.1;
        xgAway += rng.nextDouble() * 0.2 - 0.1;
        xgHome = Math.max(0.5, xgHome);
        xgAway = Math.max(0.5, xgAway);

        // Build GD probability table once — reused for every handicap line
        double[] gdProbs = buildGdProbTable(xgHome, xgAway);

        List<Map<String, Object>> odds = new ArrayList<>();

        for (double line : HANDICAP_LINES) {
            HandicapType type = classifyLine(line);

            switch (type) {
                case HALF    -> odds.addAll(buildHalfHandicap(homeTeam, awayTeam, line, gdProbs, rng));
                case WHOLE   -> odds.addAll(buildWholeHandicap(homeTeam, awayTeam, line, gdProbs, rng));
                case QUARTER -> odds.addAll(buildQuarterHandicap(homeTeam, awayTeam, line, gdProbs, rng));
            }
        }

        log.debug("generateHandicapOdds: {} vs {} | xgHome={} xgAway={} | {} lines",
                homeTeam, awayTeam, round2(xgHome), round2(xgAway), odds.size() / BOOKMAKERS.size());
        return odds;
    }

    /**
     * Generate live Asian Handicap odds reacting to current score + minute.
     * The model adjusts xG estimates based on the current scoreline so the
     * handicap lines shift naturally as goals go in.
     *
     * @param homeTeam     home team name
     * @param awayTeam     away team name
     * @param scoreHome    current home goals
     * @param scoreAway    current away goals
     * @param minutePlayed approximate match minute (0–90+)
     * @return list of { bookmaker, market, selection, handicap, odd } maps
     */
    public List<Map<String, Object>> generateLiveHandicapOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();

        double remainingFraction = Math.max(0, (90 - minutePlayed) / 90.0);

        // Remaining expected goals scale down with time left
        double xgHomeRemaining = Math.max(0.1, 1.3 * remainingFraction);
        double xgAwayRemaining = Math.max(0.1, 1.0 * remainingFraction);

        // Build GD table for remaining goals, then shift by current score diff
        double[] remainingGdProbs = buildGdProbTable(xgHomeRemaining, xgAwayRemaining);
        int currentGd = scoreHome - scoreAway;
        double[] shiftedGdProbs = shiftGdTable(remainingGdProbs, currentGd);

        Random rng = new Random(System.currentTimeMillis());
        List<Map<String, Object>> odds = new ArrayList<>();

        for (double line : HANDICAP_LINES) {
            HandicapType type = classifyLine(line);
            switch (type) {
                case HALF    -> odds.addAll(buildHalfHandicap(homeTeam, awayTeam, line, shiftedGdProbs, rng));
                case WHOLE   -> odds.addAll(buildWholeHandicap(homeTeam, awayTeam, line, shiftedGdProbs, rng));
                case QUARTER -> odds.addAll(buildQuarterHandicap(homeTeam, awayTeam, line, shiftedGdProbs, rng));
            }
        }

        log.debug("generateLiveHandicapOdds: {} vs {} | score={}-{} min={} | {} entries",
                homeTeam, awayTeam, scoreHome, scoreAway, minutePlayed, odds.size());
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // HANDICAP LINE BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Half handicap (e.g. -1.5 / +1.5) — strictly 2-way, no push possible.
     *
     * P(home covers) = P(GD > |line|)  when line < 0  (home gives goals)
     *                  P(GD >= -line)  when line > 0  (home receives goals)
     *
     * Example: home -1.5
     *   → home covers if GD >= 2  (wins by 2+)
     *   → away covers if GD <= 1
     */
    private List<Map<String, Object>> buildHalfHandicap(
            String homeTeam, String awayTeam,
            double line, double[] gdProbs, Random rng) {

        double homeCoversProb = computeCoverProbHalf(gdProbs, line);
        double awayCoversProb = 1.0 - homeCoversProb;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double homeOdd = clamp(applyMargin(homeCoversProb * noise, OVERROUND_HALF));
            double awayOdd = clamp(applyMargin(awayCoversProb / noise, OVERROUND_HALF));
            String homeLine = formatLine(line);
            String awayLine = formatLine(-line);
            result.add(buildEntry(bk, homeTeam, homeLine, homeOdd));
            result.add(buildEntry(bk, awayTeam, awayLine, awayOdd));
        }
        return result;
    }

    /**
     * Whole handicap (e.g. -1 / +1) — 3 outcomes: home covers / push / away covers.
     * Push (refund) occurs when the adjusted margin is exactly 0.
     *
     * Example: home -1
     *   → home covers  if GD >= 2   (wins by 2+)
     *   → push/refund  if GD == 1   (wins by exactly 1)
     *   → away covers  if GD <= 0
     */
    private List<Map<String, Object>> buildWholeHandicap(
            String homeTeam, String awayTeam,
            double line, double[] gdProbs, Random rng) {

        int intLine = (int) line; // line is a whole number here
        double homeCoversProb = computeCoverProbWhole(gdProbs, intLine, true);
        double pushProb        = computePushProbWhole(gdProbs, intLine);
        double awayCoversProb  = computeCoverProbWhole(gdProbs, intLine, false);

        // Normalise in case of rounding drift
        double total = homeCoversProb + pushProb + awayCoversProb;
        homeCoversProb /= total;
        pushProb       /= total;
        awayCoversProb /= total;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double homeOdd  = clamp(applyMargin(homeCoversProb * noise, OVERROUND_WHOLE));
            double pushOdd  = clamp(applyMargin(pushProb,               OVERROUND_WHOLE));
            double awayOdd  = clamp(applyMargin(awayCoversProb / noise, OVERROUND_WHOLE));
            String homeLine = formatLine(line);
            String awayLine = formatLine(-line);
            result.add(buildEntry(bk, homeTeam,  homeLine, homeOdd));
            result.add(buildEntry(bk, "Push/Refund", homeLine + "/" + awayLine, pushOdd));
            result.add(buildEntry(bk, awayTeam,  awayLine, awayOdd));
        }
        return result;
    }

    /**
     * Quarter handicap (e.g. -0.25 / +0.25) — stake split across two adjacent lines.
     *
     * A quarter handicap of X is priced as the average of:
     *   • half-stake on floor(X*2)/2  (lower half-line)
     *   • half-stake on ceil(X*2)/2   (upper half-line)
     *
     * The quoted odd therefore represents a blended 2-way market with possible
     * half-win / half-loss / half-refund outcomes:
     *
     * Example: home -0.25
     *   → Split into: half on 0 (whole) + half on -0.5 (half)
     *   → If GD == 0: half bet pushes (refund), other half loses → net half-loss
     *   → If GD == 1+: both halves win → full win
     *   → If GD <= -1: both halves lose → full loss
     *
     * Odds reflect the blended EV across both sub-lines.
     */
    private List<Map<String, Object>> buildQuarterHandicap(
            String homeTeam, String awayTeam,
            double line, double[] gdProbs, Random rng) {

        // Decompose into lower and upper sub-lines
        double lower = Math.floor(line * 2) / 2.0;
        double upper = Math.ceil(line * 2) / 2.0;

        // Get cover prob for each sub-line
        double homeLower = isWholeNumber(lower)
                ? computeCoverProbWhole(gdProbs, (int) lower, true)
                : computeCoverProbHalf(gdProbs, lower);
        double homeUpper = isWholeNumber(upper)
                ? computeCoverProbWhole(gdProbs, (int) upper, true)
                : computeCoverProbHalf(gdProbs, upper);

        // Blended probability (equal-weight average of both sub-lines)
        double homeCoversProb = (homeLower + homeUpper) / 2.0;
        double awayCoversProb  = 1.0 - homeCoversProb;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double homeOdd = clamp(applyMargin(homeCoversProb * noise, OVERROUND_HALF));
            double awayOdd = clamp(applyMargin(awayCoversProb / noise, OVERROUND_HALF));
            String homeLine = formatLine(line);
            String awayLine = formatLine(-line);
            result.add(buildEntry(bk, homeTeam, homeLine, homeOdd));
            result.add(buildEntry(bk, awayTeam, awayLine, awayOdd));
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROBABILITY ENGINE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds a probability table for goal difference (GD = homeGoals − awayGoals)
     * using independent Poisson distributions for each team's xG.
     * GD is clamped to [MIN_GD, MAX_GD]; tails are folded in.
     */
    private double[] buildGdProbTable(double xgHome, double xgAway) {
        int range = MAX_GD - MIN_GD + 1;
        double[] probs = new double[range];

        for (int h = 0; h <= 8; h++) {
            for (int a = 0; a <= 8; a++) {
                int gd = clampGd(h - a);
                int idx = gd - MIN_GD;
                probs[idx] += poissonProb(xgHome, h) * poissonProb(xgAway, a);
            }
        }

        // Normalise (rounding from truncating at 8 goals)
        double sum = 0;
        for (double p : probs) sum += p;
        if (sum > 0) for (int i = 0; i < probs.length; i++) probs[i] /= sum;

        return probs;
    }

    /**
     * Shifts the GD probability table by the current goal difference.
     * Used for live handicap: remaining goals are centred around 0,
     * but the final GD must add the existing score gap.
     */
    private double[] shiftGdTable(double[] remainingGdProbs, int currentGd) {
        int range = MAX_GD - MIN_GD + 1;
        double[] shifted = new double[range];
        for (int i = 0; i < remainingGdProbs.length; i++) {
            int rawGd = (MIN_GD + i) + currentGd;
            int clampedGd = clampGd(rawGd);
            shifted[clampedGd - MIN_GD] += remainingGdProbs[i];
        }
        return shifted;
    }

    /**
     * P(home covers) for a half handicap line.
     *
     * For a negative line (home gives goals, e.g. -1.5):
     *   home covers if GD > |line| — i.e. GD >= ceil(|line|) = 2
     *
     * For a positive line (home receives goals, e.g. +1.5):
     *   home covers if GD >= -floor(line) — i.e. GD >= -1
     */
    private double computeCoverProbHalf(double[] gdProbs, double line) {
        // Threshold: the minimum GD for home to cover
        int threshold = (int) Math.ceil(-line + 0.001); // +0.001 avoids floating point edge
        double prob = 0;
        for (int gd = threshold; gd <= MAX_GD; gd++) {
            prob += gdProb(gdProbs, gd);
        }
        return Math.min(0.97, Math.max(0.03, prob));
    }

    /**
     * P(home covers) or P(away covers) for a whole-number handicap line.
     * Push outcome is excluded from this call — use computePushProbWhole separately.
     *
     * For home -1:
     *   home covers if GD >= 2
     *   away covers if GD <= 0
     *   push        if GD == 1
     */
    private double computeCoverProbWhole(double[] gdProbs, int line, boolean homeCovers) {
        if (homeCovers) {
            // home covers if GD > |line| (strictly greater → wins by more than line)
            int threshold = Math.abs(line) + 1;
            if (line > 0) threshold = -(line - 1); // home receiving goals
            double prob = 0;
            for (int gd = threshold; gd <= MAX_GD; gd++) prob += gdProb(gdProbs, gd);
            return Math.min(0.95, Math.max(0.02, prob));
        } else {
            // away covers if GD < -(|line| - 1) — i.e. home fails to cover even push
            int threshold = (line < 0) ? (line + 1) : -(Math.abs(line));
            double prob = 0;
            for (int gd = MIN_GD; gd < threshold; gd++) prob += gdProb(gdProbs, gd);
            return Math.min(0.95, Math.max(0.02, prob));
        }
    }

    /**
     * P(push) for a whole-number handicap line.
     * Push occurs when home wins by exactly |line| goals (when home is favourite)
     * or loses by exactly |line| goals (when away is favourite).
     *
     * For home -1: push if GD == 1
     * For home +1: push if GD == -1
     */
    private double computePushProbWhole(double[] gdProbs, int line) {
        int pushGd = (line < 0) ? Math.abs(line) : -Math.abs(line);
        return Math.min(0.20, Math.max(0.01, gdProb(gdProbs, pushGd)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════════════════

    private enum HandicapType { HALF, WHOLE, QUARTER }

    private HandicapType classifyLine(double line) {
        double abs = Math.abs(line);
        double frac = abs - Math.floor(abs);
        if (Math.abs(frac - 0.25) < 0.01 || Math.abs(frac - 0.75) < 0.01) return HandicapType.QUARTER;
        if (Math.abs(frac - 0.5)  < 0.01)                                   return HandicapType.HALF;
        return HandicapType.WHOLE;
    }

    private boolean isWholeNumber(double v) {
        return Math.abs(v - Math.round(v)) < 0.01;
    }

    private double gdProb(double[] gdProbs, int gd) {
        int idx = clampGd(gd) - MIN_GD;
        return gdProbs[idx];
    }

    private int clampGd(int gd) {
        return Math.max(MIN_GD, Math.min(MAX_GD, gd));
    }

    /** Poisson PMF: P(X = k) = e^(−λ) × λ^k / k! */
    private static double poissonProb(double lambda, int k) {
        if (lambda <= 0) return k == 0 ? 1.0 : 0.0;
        double logP = -lambda + k * Math.log(lambda) - logFactorial(k);
        return Math.exp(logP);
    }

    private static double logFactorial(int n) {
        double r = 0;
        for (int i = 2; i <= n; i++) r += Math.log(i);
        return r;
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

    /**
     * Formats a handicap line for display.
     * -1.5 → "-1.5", +0.5 → "+0.5", -0.25 → "-0.25"
     */
    private String formatLine(double line) {
        String formatted = (line % 1 == 0)
                ? String.valueOf((int) line)
                : String.valueOf(round2(line));
        return (line > 0 ? "+" : "") + formatted;
    }

    private Map<String, Object> buildEntry(
            String bookmaker, String selection, String handicap, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  bookmaker);
        m.put("market",     "asian_handicap");
        m.put("selection",  selection);
        m.put("handicap",   handicap);
        m.put("odd",        String.valueOf(round2(odd)));
        return m;
    }

    private long buildSeed(String homeTeam, String awayTeam, String league) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase()
                + "|" + (league != null ? league.toLowerCase() : "");
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
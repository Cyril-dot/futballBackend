package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates Winning Margin odds for NBA basketball fixtures.
 *
 * This replaces CorrectScoreOddsService for basketball. An exact scoreline
 * market makes no sense in NBA (too many permutations with scores in the
 * 100s), so margin bands are the standard equivalent.
 *
 * Margin bands offered:
 * ─────────────────────────────────────────────────────────────────────────
 *  For each side (home win / away win):
 *    1–5 pts   (very close game)
 *    6–10 pts  (comfortable win)
 *    11–15 pts (convincing win)
 *    16–20 pts (dominant win)
 *    21+ pts   (blowout)
 *
 *  Total selections: 10 outcome bands + optional "Overtime" line (2-way,
 *  whether the game goes to OT, derived from the closeness of the contest).
 *
 * Probability model:
 * ─────────────────────────────────────────────────────────────────────────
 *  Point-spread (GD = homePoints − awayPoints) is approximated via a
 *  Normal distribution consistent with BasketballPointSpreadService:
 *
 *    GD ~ Normal(μ = xpHome − xpAway, σ = √(xpHome + xpAway))
 *
 *  Each margin band is then:
 *    P(home wins by 1–5)  = P(1  ≤ GD ≤ 5)  = Φ((5  − μ)/σ) − Φ((0  − μ)/σ)
 *    P(home wins by 6–10) = P(6  ≤ GD ≤ 10) = Φ((10 − μ)/σ) − Φ((5  − μ)/σ)
 *    etc.
 *
 *  Overtime: approximated from the probability mass within the ±3 pt band;
 *  empirically ~6–8% of NBA games go to OT, compressed from the narrow
 *  near-tied distribution.
 *
 * Overrounds:
 *  • Margin bands: 1.18  (10-way market — wider than 2-way, same as correct_score)
 *  • OT market:    1.06  (2-way, similar to half-spread)
 *
 * Markets generated:
 *  • winning_margin — 10 band selections (home/away × 5 ranges)
 *  • overtime       — Yes / No
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class BasketballWinningMarginService {

    // ── Overrounds ────────────────────────────────────────────────────────
    private static final double OVERROUND_MARGIN = 1.18;
    private static final double OVERROUND_OT     = 1.06;

    // ── Decimal odds bounds ───────────────────────────────────────────────
    private static final double MIN_ODD_MARGIN = 2.0;   // margins are never near-certainties
    private static final double MAX_ODD_MARGIN = 101.0;
    private static final double MIN_ODD_OT     = 1.05;
    private static final double MAX_ODD_OT     = 20.0;

    // ── Expected-points base values (same as BasketballPointSpreadService) ─
    private static final double XP_HOME_BASE = 108.0;
    private static final double XP_AWAY_BASE = 104.0;
    private static final double XP_RANGE     = 14.0;

    // ── Margin band boundaries (inclusive) ───────────────────────────────
    // { lower, upper } — upper of Integer.MAX_VALUE means open-ended (21+)
    private static final int[][] BANDS = {
            {  1,  5 },
            {  6, 10 },
            { 11, 15 },
            { 16, 20 },
            { 21, Integer.MAX_VALUE }
    };

    private static final String[] BAND_LABELS = {
            "1-5", "6-10", "11-15", "16-20", "21+"
    };

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet"
    );

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate pre-match winning margin odds for an NBA fixture.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateMarginOdds(String homeTeam, String awayTeam) {
        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam);
        Random rng = new Random(seed);

        double xpHome = XP_HOME_BASE + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        double xpAway = XP_AWAY_BASE + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * XP_RANGE;
        xpHome += rng.nextDouble() * 2.0 - 1.0;
        xpAway += rng.nextDouble() * 2.0 - 1.0;
        xpHome = Math.max(90, xpHome);
        xpAway = Math.max(90, xpAway);

        double mu    = xpHome - xpAway;   // expected point differential
        double sigma = Math.sqrt(xpHome + xpAway); // ~14–16 for NBA

        List<Map<String, Object>> odds = new ArrayList<>();
        odds.addAll(buildMarginOdds(homeTeam, awayTeam, mu, sigma, rng));
        odds.addAll(buildOvertimeOdds(mu, sigma, rng));

        log.debug("generateMarginOdds: {} vs {} | mu={} sigma={} | {} entries",
                homeTeam, awayTeam, round2(mu), round2(sigma), odds.size());
        return odds;
    }

    /**
     * Generate live winning margin odds. The remaining GD distribution is
     * derived from time left and current score, then bands are re-evaluated
     * against the projected final GD.
     *
     * @param homeTeam     home team name
     * @param awayTeam     away team name
     * @param scoreHome    current home points
     * @param scoreAway    current away points
     * @param minutePlayed approximate minutes elapsed (0–48)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateLiveMarginOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();

        double remainingFraction = Math.max(0.0, (48.0 - minutePlayed) / 48.0);
        int currentGd = scoreHome - scoreAway;

        double xpHomeRemaining = Math.max(5.0, 110.0 * remainingFraction);
        double xpAwayRemaining = Math.max(5.0, 107.0 * remainingFraction);

        // Projected final GD = current GD + expected remaining differential
        double mu    = currentGd + (xpHomeRemaining - xpAwayRemaining);
        double sigma = Math.max(2.0, Math.sqrt(xpHomeRemaining + xpAwayRemaining));

        Random rng = new Random(System.currentTimeMillis());
        List<Map<String, Object>> odds = new ArrayList<>();
        odds.addAll(buildMarginOdds(homeTeam, awayTeam, mu, sigma, rng));
        odds.addAll(buildOvertimeOdds(mu, sigma, rng));

        log.debug("generateLiveMarginOdds: {} vs {} | score={}-{} min={} | mu={} sigma={}",
                homeTeam, awayTeam, scoreHome, scoreAway, minutePlayed, round2(mu), round2(sigma));
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the 10 winning-margin band selections (home × 5 + away × 5).
     * Raw probabilities are normalised before applying overround so the
     * market implied book sums correctly.
     */
    private List<Map<String, Object>> buildMarginOdds(
            String homeTeam, String awayTeam,
            double mu, double sigma, Random rng) {

        // Compute raw band probabilities for home (positive GD) and away (negative GD)
        double[] homeProbs = new double[BANDS.length];
        double[] awayProbs = new double[BANDS.length];

        for (int i = 0; i < BANDS.length; i++) {
            int lo = BANDS[i][0];
            int hi = BANDS[i][1];

            // Home wins by [lo, hi]: P(lo ≤ GD ≤ hi)
            homeProbs[i] = bandProb(mu, sigma, lo, hi);
            // Away wins by [lo, hi]: P(-hi ≤ GD ≤ -lo) → symmetric
            awayProbs[i] = bandProb(-mu, sigma, lo, hi);
        }

        // Normalise across all 10 outcomes (they should sum near 1, minus OT mass)
        double totalMass = 0;
        for (double p : homeProbs) totalMass += p;
        for (double p : awayProbs) totalMass += p;
        if (totalMass > 0) {
            for (int i = 0; i < homeProbs.length; i++) {
                homeProbs[i] /= totalMass;
                awayProbs[i] /= totalMass;
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            for (int i = 0; i < BANDS.length; i++) {
                double homeOdd = clampMargin(applyMargin(homeProbs[i] * noise, OVERROUND_MARGIN));
                double awayOdd = clampMargin(applyMargin(awayProbs[i] / noise, OVERROUND_MARGIN));
                String homeLabel = homeTeam + " by " + BAND_LABELS[i];
                String awayLabel = awayTeam + " by " + BAND_LABELS[i];
                result.add(buildMarginEntry(bk, homeLabel, homeOdd));
                result.add(buildMarginEntry(bk, awayLabel, awayOdd));
            }
        }
        return result;
    }

    /**
     * Builds the OT Yes/No market.
     *
     * OT occurs when the regulation GD is 0. We approximate P(GD == 0) using
     * the Normal PDF at 0, scaled by the discrete probability mass of a 1-point
     * band (since scoring increments are 1 in basketball):
     *   P(OT) ≈ normalPdf(0; mu, sigma) × 1  (one discrete unit width)
     *
     * This tends to produce OT probabilities of 5–9%, consistent with the
     * empirical ~6.5% OT rate in NBA regular season games.
     */
    private List<Map<String, Object>> buildOvertimeOdds(
            double mu, double sigma, Random rng) {

        // Approximate P(OT) = P(-0.5 ≤ GD ≤ 0.5)
        double pOt  = Math.max(0.03, Math.min(0.20,
                normalCdf(-mu + 0.5, sigma) - normalCdf(-mu - 0.5, sigma)));
        double pNoOt = 1.0 - pOt;

        List<Map<String, Object>> result = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise  = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
            double otOdd  = clampOt(applyMargin(pOt   * noise, OVERROUND_OT));
            double regOdd = clampOt(applyMargin(pNoOt / noise, OVERROUND_OT));
            result.add(buildOtEntry(bk, "Yes", otOdd));
            result.add(buildOtEntry(bk, "No",  regOdd));
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // NORMAL DISTRIBUTION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * P(lo ≤ X ≤ hi) where X ~ Normal(mu, sigma).
     * When hi == Integer.MAX_VALUE the upper tail is used (open-ended band).
     */
    private double bandProb(double mu, double sigma, int lo, int hi) {
        if (sigma <= 0) {
            // Degenerate: mu is the certain outcome
            boolean inBand = (mu >= lo) && (hi == Integer.MAX_VALUE || mu <= hi);
            return inBand ? 1.0 : 0.0;
        }
        double lower = normalCdf(mu - lo + 0.5, sigma);   // P(X ≥ lo)
        double upper = (hi == Integer.MAX_VALUE)
                ? 1.0
                : normalCdf(mu - hi - 0.5, sigma);        // P(X ≥ hi+1)
        return Math.max(0.0, lower - upper);
    }

    /**
     * P(X > 0) where X ~ Normal(mu, sigma).
     * i.e. the probability that the point differential exceeds zero.
     */
    private double normalCdf(double mu, double sigma) {
        if (sigma <= 0) return mu > 0 ? 0.97 : 0.03;
        double z = mu / sigma;
        double p = 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
        return Math.max(0.001, Math.min(0.999, p));
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
        if (trueProb <= 0) return MAX_ODD_MARGIN;
        return 1.0 / (trueProb * overround);
    }

    private double clampMargin(double odd) {
        return Math.max(MIN_ODD_MARGIN, Math.min(MAX_ODD_MARGIN, odd));
    }

    private double clampOt(double odd) {
        return Math.max(MIN_ODD_OT, Math.min(MAX_ODD_OT, odd));
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Map<String, Object> buildMarginEntry(String bookmaker, String selection, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  bookmaker);
        m.put("market",     "winning_margin");
        m.put("selection",  selection);
        m.put("odd",        String.valueOf(round2(odd)));
        return m;
    }

    private Map<String, Object> buildOtEntry(String bookmaker, String selection, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  bookmaker);
        m.put("market",     "overtime");
        m.put("selection",  selection);
        m.put("odd",        String.valueOf(round2(odd)));
        return m;
    }

    private long buildSeed(String homeTeam, String awayTeam) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase() + "|nba|margin";
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates Moneyline (2-way) odds for NBA basketball fixtures.
 *
 * Key differences from football 1X2:
 * ─────────────────────────────────────────────────────────────────────────
 *  • NO draw outcome — basketball games always have a winner (OT if needed).
 *  • Home court advantage in NBA is real but smaller than football (~3–4%).
 *  • Strength hash range kept identical to OddsGeneratorService for
 *    consistency across sports (same seed pattern, different sport field).
 *
 * Pre-match:
 *  • Deterministic seed from team names — same fixture always produces the
 *    same odds within a server restart.
 *  • Overround ~105.5% (NBA moneyline markets are very efficient / tight).
 *
 * Live:
 *  • Reacts to current score differential + time remaining (48 min game).
 *  • Time factor based on NBA game length (4 × 12 min quarters = 48 min).
 *  • Comeback probability curve is less aggressive than football — NBA
 *    games see frequent large fourth-quarter swings.
 *  • Random noise (±2%) added per bookmaker to simulate live market tick.
 *
 * Overrounds:
 *  • Pre-match: 1.055  (tight, efficient NBA market)
 *  • Live:      1.065  (slightly wider in-play spread)
 *
 * Markets generated:
 *  • moneyline — home win / away win (2-way, no draw)
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class BasketballMoneylineOddsService {

    private static final double OVERROUND_PRE  = 1.055;
    private static final double OVERROUND_LIVE = 1.065;

    private static final double MIN_ODD = 1.03;
    private static final double MAX_ODD = 20.0;

    /**
     * Home court advantage multiplier.
     * NBA home teams win ~58–60% of games historically.
     */
    private static final double HOME_ADVANTAGE = 1.06;

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // ══════════════════════════════════════════════════════════════════════
    // PRE-MATCH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate pre-match moneyline odds for an NBA fixture.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generatePreMatchOdds(String homeTeam, String awayTeam) {
        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam);
        Random rng = new Random(seed);

        double[] probs = computePreMatchProbs(homeTeam, awayTeam, rng);
        double homeProb = probs[0];
        double awayProb = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();

        for (String bk : BOOKMAKERS) {
            double spread = 1.0 + (rng.nextDouble() * 0.03 - 0.015); // ±1.5% per bookmaker
            double homeOdd = clamp(applyMargin(homeProb * spread, OVERROUND_PRE));
            double awayOdd = clamp(applyMargin(awayProb / spread, OVERROUND_PRE));
            odds.add(buildEntry(bk, "moneyline", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "moneyline", awayTeam, awayOdd));
        }

        log.debug("generatePreMatchOdds [moneyline]: {} vs {} — home={} away={}",
                homeTeam, awayTeam,
                round2(applyMargin(homeProb, OVERROUND_PRE)),
                round2(applyMargin(awayProb, OVERROUND_PRE)));
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate live moneyline odds reacting to current score + game clock.
     *
     * @param homeTeam     home team name
     * @param awayTeam     away team name
     * @param scoreHome    current home points
     * @param scoreAway    current away points
     * @param minutePlayed approximate minutes elapsed (0–48; OT minutes > 48)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateLiveOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();

        int pointDiff   = scoreHome - scoreAway;
        double timeFactor = buildTimeFactor(minutePlayed);

        double[] probs  = computeLiveProbs(pointDiff, timeFactor);
        double homeProb = probs[0];
        double awayProb = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();
        Random rng = new Random(System.currentTimeMillis());

        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.04 - 0.02); // ±2%
            double homeOdd = clamp(applyMargin(homeProb * noise, OVERROUND_LIVE));
            double awayOdd = clamp(applyMargin(awayProb / noise, OVERROUND_LIVE));
            odds.add(buildEntry(bk, "moneyline", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "moneyline", awayTeam, awayOdd));
        }

        log.debug("generateLiveOdds [moneyline]: {} vs {} | score={}-{} min={} | diff={} timeFactor={}",
                homeTeam, awayTeam, scoreHome, scoreAway, minutePlayed,
                pointDiff, round2(timeFactor));
        return odds;
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROBABILITY ENGINES
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Derives pre-match home/away probability pair (sums to 1.0).
     * Uses the same name-hash strength approach as OddsGeneratorService
     * but with NO draw and a smaller home advantage factor.
     */
    private double[] computePreMatchProbs(String homeTeam, String awayTeam, Random rng) {
        double homeStrength = 0.35 + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * 0.45;
        double awayStrength = 0.35 + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * 0.45;
        homeStrength *= HOME_ADVANTAGE;

        double total    = homeStrength + awayStrength;
        double homeProb = homeStrength / total;
        double awayProb = awayStrength / total;

        // Tiny jitter so very similar team names diverge slightly
        double jitter = (rng.nextDouble() * 0.04 - 0.02);
        homeProb = Math.max(0.10, Math.min(0.90, homeProb + jitter));
        awayProb = 1.0 - homeProb;

        return new double[]{ homeProb, awayProb };
    }

    /**
     * Maps point differential + time elapsed into a 2-way probability pair.
     *
     * NBA-specific calibration:
     *  • A 5-point lead with 5 min left is roughly equivalent to a 1-goal
     *    lead in football at 75 min — moderate favourite.
     *  • Large leads (15+) late are near-certain; small leads early are close
     *    to 50/50.
     *  • Time factor based on 48 regulation minutes (not 90).
     *  • Less aggressive compression than football because NBA comebacks
     *    are much more common (faster scoring rate).
     *
     * Point-lead to shift mapping (approximate NBA win-probability research):
     *   1–3 pts   → small shift   (±10–20%)
     *   4–8 pts   → medium shift  (±25–40%)
     *   9–14 pts  → large shift   (±45–60%)
     *   15+ pts   → very large    (±65–85%)
     */
    double[] computeLiveProbs(int pointDiff, double timeFactor) {
        if (pointDiff == 0) {
            // Tied game — genuine 50/50 with tiny home-court edge preserved
            double homeProb = 0.50 + HOME_ADVANTAGE * 0.01; // ~51%
            return new double[]{ homeProb, 1.0 - homeProb };
        }

        boolean homeLeading = pointDiff > 0;
        int absDiff = Math.abs(pointDiff);

        // Base shift from point lead
        double baseShift;
        if      (absDiff <= 3)  baseShift = 0.10 + absDiff * 0.03;   // 0.13–0.19
        else if (absDiff <= 8)  baseShift = 0.20 + absDiff * 0.025;  // 0.30–0.40
        else if (absDiff <= 14) baseShift = 0.42 + absDiff * 0.015;  // 0.555–0.63
        else                    baseShift = 0.65 + Math.min(absDiff - 14, 10) * 0.02; // up to 0.85

        // Time amplification — less aggressive than football (0.20 vs 0.30)
        double shift = baseShift * (1.0 + timeFactor * 0.20);
        shift = Math.min(shift, 0.90); // never make a comeback impossible

        double leaderProb  = Math.min(0.97, 0.50 + shift * 0.50);
        double trailerProb = Math.max(0.03, 1.0 - leaderProb);

        if (homeLeading) return new double[]{ leaderProb, trailerProb };
        else             return new double[]{ trailerProb, leaderProb };
    }

    /**
     * Time factor: 0.0 at tip-off → 1.0 at end of regulation (48 min).
     * OT minutes beyond 48 are clamped to 1.0.
     */
    private double buildTimeFactor(int minutePlayed) {
        if (minutePlayed <= 0)  return 0.0;
        if (minutePlayed >= 48) return 1.0;
        return minutePlayed / 48.0;
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
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

    private Map<String, Object> buildEntry(String bookmaker, String market, String selection, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker", bookmaker);
        m.put("market",    market);
        m.put("selection", selection);
        m.put("odd",       String.valueOf(round2(odd)));
        return m;
    }

    private long buildSeed(String homeTeam, String awayTeam) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase() + "|nba";
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
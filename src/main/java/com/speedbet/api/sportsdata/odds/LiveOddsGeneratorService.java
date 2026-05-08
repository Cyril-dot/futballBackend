package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates live in-play 1X2 odds that react to the current scoreline.
 *
 * Core logic:
 * ─────────────────────────────────────────────────────────────────────────
 *  Goal difference (GD) = scoreHome − scoreAway
 *
 *  GD > 0  → Home winning  → home odds DROP, away odds SPIKE
 *  GD < 0  → Away winning  → away odds DROP, home odds SPIKE
 *  GD = 0  → Draw          → odds stay close, small random fluctuation
 *
 *  The bigger the |GD|, the more extreme the swing:
 *    |GD| = 1  → moderate shift  (~15–25%)
 *    |GD| = 2  → large shift     (~35–50%)
 *    |GD| = 3+ → very high shift (~60–80%)
 *
 *  A time-pressure factor is also applied: later in the match, the winning
 *  team's odds compress further (it's more certain they'll hold on), while
 *  the losing team's odds expand significantly.
 *
 *  Each call adds a small random noise (±2%) to simulate live market movement.
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Markets generated:
 *  • Match Result (1X2)  — always present
 *
 * Update cadence (recommended): every 2 minutes while LIVE.
 */
@Slf4j
@Service
public class LiveOddsGeneratorService {

    private static final double OVERROUND  = 1.08;   // slightly higher in-play margin
    private static final double MIN_ODD    = 1.03;
    private static final double MAX_ODD    = 50.0;   // losing team can spike very high

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate live 1X2 odds.
     *
     * @param homeTeam   home team name
     * @param awayTeam   away team name
     * @param scoreHome  current home goals
     * @param scoreAway  current away goals
     * @param minutePlayed approximate match minute (0–90+), used for time pressure
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateLiveOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();

        int gd = scoreHome - scoreAway;
        double timeFactor = buildTimeFactor(minutePlayed);

        // Base true probabilities given current scoreline
        double[] probs = computeLiveProbs(gd, timeFactor);
        double homeProb = probs[0];
        double drawProb = probs[1];
        double awayProb = probs[2];

        List<Map<String, Object>> odds = new ArrayList<>();
        Random rng = new Random(System.currentTimeMillis()); // non-deterministic for live noise

        for (String bk : BOOKMAKERS) {
            // ±2% noise per bookmaker per call
            double noise = 1.0 + (rng.nextDouble() * 0.04 - 0.02);

            double homeOdd = clamp(applyMargin(homeProb * noise));
            double drawOdd = clamp(applyMargin(drawProb));
            double awayOdd = clamp(applyMargin(awayProb / noise));

            odds.add(buildEntry(bk, "match_result", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "match_result", "Draw",   drawOdd));
            odds.add(buildEntry(bk, "match_result", awayTeam, awayOdd));
        }

        log.debug("generateLiveOdds: {} vs {} | score={}-{} min={} | GD={} timeFactor={}",
                homeTeam, awayTeam, scoreHome, scoreAway, minutePlayed, gd, round2(timeFactor));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Maps goal difference + time remaining into a probability triple.
     *
     * Time factor: 0.0 at kick-off → 1.0 at full time.
     * Higher timeFactor = less likely a comeback = more extreme odds.
     */
    double[] computeLiveProbs(int gd, double timeFactor) {

        if (gd == 0) {
            // Draw — small random-like fluctuation around equal probs
            double drawBoost = 0.30 + timeFactor * 0.12; // draw more likely as time goes on at 0-0
            double remaining = 1.0 - drawBoost;
            double homeProb = remaining * 0.50;
            double awayProb = remaining * 0.50;
            return new double[]{ homeProb, drawBoost, awayProb };
        }

        boolean homeLeading = gd > 0;
        int absGD = Math.abs(gd);

        // Shift factor: how much to move probs based on GD magnitude
        double baseShift = switch (absGD) {
            case 1 -> 0.20;
            case 2 -> 0.45;
            default -> 0.68; // 3+
        };

        // Time amplifies certainty: at 90min the shift is 30% larger than at kick-off
        double shift = baseShift * (1.0 + timeFactor * 0.30);
        shift = Math.min(shift, 0.88); // cap: never make comeback impossible

        // Leaders probability rises sharply; trailers' drops
        double leaderProb  = 0.50 + shift * 0.50;
        double trailerProb = Math.max(0.02, (1.0 - leaderProb) * 0.60);

        // Draw: compresses as GD grows and time runs out
        double drawProb = Math.max(0.02, 1.0 - leaderProb - trailerProb);
        drawProb *= (1.0 - timeFactor * 0.40); // at 90min draw very unlikely if someone's leading
        drawProb = Math.max(0.02, drawProb);

        // Re-normalise to 1.0
        double total = leaderProb + drawProb + trailerProb;
        leaderProb  /= total;
        drawProb    /= total;
        trailerProb /= total;

        if (homeLeading) return new double[]{ leaderProb, drawProb, trailerProb };
        else             return new double[]{ trailerProb, drawProb, leaderProb };
    }

    /**
     * Returns a 0.0–1.0 value representing how far through the match we are.
     * Minutes beyond 90 (extra time) still cap at 1.0.
     */
    private double buildTimeFactor(int minutePlayed) {
        if (minutePlayed <= 0)  return 0.0;
        if (minutePlayed >= 90) return 1.0;
        return minutePlayed / 90.0;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private double applyMargin(double trueProb) {
        if (trueProb <= 0) return MAX_ODD;
        return 1.0 / (trueProb * OVERROUND);
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
        m.put("market", market);
        m.put("selection", selection);
        m.put("odd", String.valueOf(round2(odd)));
        return m;
    }
}
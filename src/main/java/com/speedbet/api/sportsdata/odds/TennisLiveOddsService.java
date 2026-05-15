package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates live in-play win odds for a tennis match that react to the
 * current set score.
 *
 * Core logic:
 * ─────────────────────────────────────────────────────────────────────────
 *  Set difference (SD) = setsPlayer1 − setsPlayer2
 *
 *  SD > 0  → Player 1 leading → player1 odds DROP,  player2 odds SPIKE
 *  SD < 0  → Player 2 leading → player2 odds DROP,  player1 odds SPIKE
 *  SD = 0  → Level            → odds stay close, pre-match strength retained
 *
 *  The bigger the |SD|, the more extreme the swing:
 *    |SD| = 1  → moderate shift  (~28%)
 *    |SD| = 2  → large shift     (~55%)
 *
 *  A time-pressure factor is applied from currentSet / maxSets:
 *  later in the match the leading player's odds compress further (comeback
 *  becomes less likely). If the leader needs only 1 more set to win, an
 *  additional certainty boost is applied.
 *
 *  Each call adds ±2% random noise per bookmaker to simulate live market
 *  movement (non-deterministic — uses System.currentTimeMillis() seed).
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Caller convention (Match entity fields):
 *   match.getHomeTeam()              → player 1
 *   match.getAwayTeam()              → player 2
 *   match.getScoreHome()             → sets won by player 1
 *   match.getScoreAway()             → sets won by player 2
 *   match.getMetadata("currentSet")  → current set number (1-based)
 *   match.getMetadata("bestOfFive")  → "true" for Grand Slams / Davis Cup
 *
 * Tennis has no draw — only HOME (player 1) and AWAY (player 2) selections.
 *
 * Markets generated:
 *  • tennis_match_winner  — always present
 *
 * Update cadence (recommended): every 30–60 seconds while LIVE.
 */
@Slf4j
@Service
public class TennisLiveOddsService {

    private static final double OVERROUND = 1.08;   // slightly higher in-play margin
    private static final double MIN_ODD   = 1.03;
    private static final double MAX_ODD   = 25.0;

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate live win odds for a tennis match.
     *
     * @param player1    player 1 name  (match.getHomeTeam())
     * @param player2    player 2 name  (match.getAwayTeam())
     * @param p1Sets     sets won by player 1  (match.getScoreHome())
     * @param p2Sets     sets won by player 2  (match.getScoreAway())
     * @param currentSet current set number, 1-based  (match.getMetadata("currentSet"))
     * @param bestOfFive true for Grand Slams / Davis Cup  (match.getMetadata("bestOfFive"))
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateLiveOdds(
            String player1, String player2,
            int p1Sets, int p2Sets,
            int currentSet, boolean bestOfFive) {

        if (player1 == null || player2 == null) return List.of();

        double timeFactor = buildTimeFactor(currentSet, bestOfFive);
        double[] probs    = computeLiveProbs(p1Sets, p2Sets, bestOfFive, timeFactor);
        double p1Prob     = probs[0];
        double p2Prob     = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();
        Random rng = new Random(System.currentTimeMillis()); // non-deterministic for live noise

        for (String bk : BOOKMAKERS) {
            // ±2% noise per bookmaker per call
            double noise = 1.0 + (rng.nextDouble() * 0.04 - 0.02);

            double p1Odd = clamp(applyMargin(p1Prob * noise));
            double p2Odd = clamp(applyMargin(p2Prob / noise));

            odds.add(buildEntry(bk, "tennis_match_winner", player1, p1Odd));
            odds.add(buildEntry(bk, "tennis_match_winner", player2, p2Odd));
        }

        log.debug("generateLiveOdds: {} vs {} | sets={}-{} set#{} bo{} | timeFactor={}",
                player1, player2, p1Sets, p2Sets, currentSet,
                bestOfFive ? 5 : 3, round2(timeFactor));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Maps set difference + time pressure into a probability pair.
     *
     * Time factor: 0.0 at start of match → 1.0 when the final set is being played.
     * Higher timeFactor = comeback less likely = more extreme odds.
     */
    double[] computeLiveProbs(int p1Sets, int p2Sets, boolean bestOfFive, double timeFactor) {
        int setsToWin = bestOfFive ? 3 : 2;
        int setDiff   = p1Sets - p2Sets;

        if (setDiff == 0) {
            // Level — 50/50 base; time slightly compresses any edge
            return new double[]{ 0.50, 0.50 };
        }

        boolean p1Leading  = setDiff > 0;
        int absSetDiff     = Math.abs(setDiff);

        double baseShift = switch (absSetDiff) {
            case 1 -> 0.28;
            case 2 -> 0.55;
            default -> 0.75; // 3+
        };

        // Extra boost when leader needs only 1 more set to close out the match
        int leaderSets      = p1Leading ? p1Sets : p2Sets;
        int leaderRemaining = setsToWin - leaderSets;
        if (leaderRemaining == 1) {
            baseShift = Math.min(0.88, baseShift + 0.12);
        }

        // Time amplification: up to +30% more certain at end of match
        double shift = baseShift * (1.0 + timeFactor * 0.30);
        shift = Math.min(shift, 0.90);

        double leaderProb  = Math.min(0.96, 0.50 + shift * 0.50);
        double trailerProb = Math.max(0.04, 1.0 - leaderProb);

        // Normalise
        double total  = leaderProb + trailerProb;
        leaderProb   /= total;
        trailerProb  /= total;

        return p1Leading
                ? new double[]{ leaderProb, trailerProb }
                : new double[]{ trailerProb, leaderProb };
    }

    /**
     * Returns 0.0–1.0 representing how far through the match we are.
     * currentSet=1 → 0.0; final possible set being played → 1.0.
     */
    private double buildTimeFactor(int currentSet, boolean bestOfFive) {
        int maxSets = bestOfFive ? 5 : 3;
        if (currentSet <= 1) return 0.0;
        return Math.min(1.0, (double)(currentSet - 1) / (maxSets - 1));
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
        m.put("market",    market);
        m.put("selection", selection);
        m.put("odd",       String.valueOf(round2(odd)));
        return m;
    }
}
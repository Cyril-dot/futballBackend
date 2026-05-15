package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates live in-play moneyline odds for a UFC/MMA bout.
 *
 * ── MMA-specific live model ───────────────────────────────────────────────
 *
 *   Unlike football, MMA has no score — the "live state" is communicated via:
 *
 *     • roundsCompleted  — how many rounds have finished (0 = fight ongoing in R1)
 *     • totalRounds      — scheduled length (3 for non-main, 5 for title/main)
 *     • dominanceScore   — caller-supplied float in [-1.0, +1.0]:
 *                            > 0  → fighter1 dominating
 *                            < 0  → fighter2 dominating
 *                            = 0  → even fight
 *
 *   dominanceScore is intended to be derived from compuStrike / takedown stats
 *   if available, or defaulted to 0.0 when the fight just started.
 *
 * ── Probability model ────────────────────────────────────────────────────
 *
 *   Base: each fighter starts at 50/50 when dominance = 0.
 *
 *   Dominance shifts: a score of ±1.0 moves the leader to ~75% probability.
 *   The shift scales linearly with |dominanceScore|.
 *
 *   Time pressure: later rounds tighten odds for the leader
 *   (comeback less likely).  timeFactor = roundsCompleted / totalRounds.
 *
 *   Finish pressure: if many rounds have gone by without a finish, later
 *   rounds have a "going to decision" pressure which makes odds tighten
 *   toward the dominant fighter but less dramatically.
 *
 *   Each call adds ±2% noise per bookmaker to simulate live market movement.
 *
 * ── Markets generated ────────────────────────────────────────────────────
 *
 *   "mma_live_moneyline" — fighter1 / fighter2 (HOME / AWAY on persist)
 *
 * ── Update cadence ───────────────────────────────────────────────────────
 *
 *   Call every 30–60 seconds while the fight is STATE_IN.
 *   dominanceScore should be refreshed from ESPN compuStrike stats each call.
 */
@Slf4j
@Service
public class MmaLiveOddsGeneratorService {

    // Slightly higher in-play margin
    private static final double OVERROUND = 1.08;

    private static final double MIN_ODD = 1.03;
    private static final double MAX_ODD = 20.0;  // not as extreme as football — no scoreline lock-in

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate live moneyline odds for an active MMA bout.
     *
     * @param fighter1        display name of fighter1 (ESPN "home" side)
     * @param fighter2        display name of fighter2 (ESPN "away" side)
     * @param roundsCompleted rounds fully completed (0 = still in round 1)
     * @param totalRounds     scheduled bout length — 3 or 5
     * @param dominanceScore  float in [-1.0, +1.0]; positive = fighter1 ahead,
     *                        negative = fighter2 ahead, 0 = even
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateLiveOdds(
            String fighter1, String fighter2,
            int roundsCompleted, int totalRounds,
            double dominanceScore) {

        if (fighter1 == null || fighter2 == null
                || fighter1.isBlank() || fighter2.isBlank()) {
            return List.of();
        }

        // Clamp inputs
        int    safeTotal     = totalRounds <= 0 ? 3 : totalRounds;
        int    safeRounds    = Math.max(0, Math.min(roundsCompleted, safeTotal));
        double safeDominance = Math.max(-1.0, Math.min(1.0, dominanceScore));

        double timeFactor = (double) safeRounds / safeTotal;

        double[] probs = computeLiveProbs(safeDominance, timeFactor);
        double f1Prob  = probs[0];
        double f2Prob  = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();
        Random rng = new Random(System.currentTimeMillis()); // non-deterministic for live noise

        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.04 - 0.02); // ±2% noise

            double f1Odd = clamp(applyMargin(f1Prob * noise));
            double f2Odd = clamp(applyMargin(f2Prob / noise));

            odds.add(buildEntry(bk, "mma_live_moneyline", fighter1, f1Odd));
            odds.add(buildEntry(bk, "mma_live_moneyline", fighter2, f2Odd));
        }

        log.debug("generateLiveOdds (MMA): {} vs {} | rounds={}/{} dominance={} timeFactor={}",
                fighter1, fighter2, safeRounds, safeTotal,
                round2(safeDominance), round2(timeFactor));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Maps dominance + time into a two-outcome probability pair.
     *
     * dominanceScore in [-1, +1]:
     *   -1.0 → fighter2 has full control → f2Prob up to ~0.85
     *   +1.0 → fighter1 has full control → f1Prob up to ~0.85
     *    0.0 → even fight                → 50 / 50
     *
     * timeFactor in [0, 1]:
     *   amplifies the dominance shift — the leader's advantage gets bigger
     *   in later rounds because there's less time for a reversal.
     */
    double[] computeLiveProbs(double dominanceScore, double timeFactor) {

        // Maximum probability shift at full dominance (±1.0)
        // Scales with time: ranges from 0.25 (early) to 0.40 (late rounds)
        double maxShift = 0.25 + timeFactor * 0.15;

        // Linear mapping: dominance 0→0 shift, ±1 → ±maxShift
        double shift = dominanceScore * maxShift;

        double f1Prob = Math.max(0.08, Math.min(0.92, 0.50 + shift));
        double f2Prob = 1.0 - f1Prob;

        return new double[]{ f1Prob, f2Prob };
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

    private Map<String, Object> buildEntry(String bookmaker, String market,
                                            String selection, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker", bookmaker);
        m.put("market",    market);
        m.put("selection", selection);
        m.put("odd",       String.valueOf(round2(odd)));
        return m;
    }
}
package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates live in-play moneyline odds for an MLB game.
 *
 * ── Baseball live model — key differences ────────────────────────────────
 *
 *   Baseball has no clock and no draw.  The live state is defined by:
 *
 *     runDiff      — homeRuns − awayRuns (can be large, but usually < 6 in live games)
 *     inning       — 1–9 (extra innings = 10+)
 *     inningHalf   — "top" or "bot"
 *     outs         — 0, 1, or 2 within the current half-inning
 *
 * ── Time model (innings as time) ─────────────────────────────────────────
 *
 *   There is no clock — instead we use game progress as a fraction of the
 *   full 9-inning game.  Each half-inning is 1/18 of the game (9 × 2 halves).
 *
 *   timeFactor = elapsedHalfInnings / 18.0
 *
 *   Where elapsedHalfInnings = (inning - 1) × 2 + (inningHalf == "bot" ? 1 : 0)
 *
 *   Extra innings (inning > 9): timeFactor capped at 0.98 — always tense.
 *   Outs within a half-inning add a fractional sub-step (outs / 3 * 1/18).
 *
 * ── Run scoring differences from football/NFL ────────────────────────────
 *
 *   In football a 1-goal lead is significant (~35% probability swing).
 *   In MLB a 1-run lead is meaningful but less decisive — a single hit can
 *   erase it.  The meaningful run breakpoints are:
 *
 *     |diff| = 1  — very tight — one hit ties it
 *     |diff| = 2  — meaningful — needs 2+ runs to tie
 *     |diff| = 3  — comfortable — requires a big inning or homer barrage
 *     |diff| = 4  — large lead  — comeback possible but uncommon
 *     |diff| ≥ 5  — near-certain — statistically difficult to overcome
 *
 * ── No draw in baseball ──────────────────────────────────────────────────
 *
 *   Market is two-way: HOME / AWAY only.
 *   Extra innings make ties impossible — someone must win.
 *
 * ── Markets generated ────────────────────────────────────────────────────
 *
 *   "mlb_live_moneyline" → HOME, AWAY
 *
 * ── Update cadence ───────────────────────────────────────────────────────
 *
 *   Call every 30–60 seconds while STATE_IN.
 *   Refresh inning, inningHalf, outs, and scores from BaseballDataService
 *   each call.
 */
@Slf4j
@Service
public class MlbLiveOddsGeneratorService {

    private static final double OVERROUND = 1.07; // slightly higher in-play

    private static final double MIN_ODD = 1.02;
    private static final double MAX_ODD = 30.0; // baseball comebacks rarer late, but not impossible

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // Baseball run-difference breakpoints
    private static final int RUN_TIGHT  = 1;
    private static final int RUN_AHEAD  = 2;
    private static final int RUN_SOLID  = 3;
    private static final int RUN_LARGE  = 4;
    // ≥ 5 = near-certain

    /**
     * Generate live moneyline odds for an in-progress MLB game.
     *
     * @param homeTeam   home team display name
     * @param awayTeam   away team display name
     * @param homeScore  current home runs
     * @param awayScore  current away runs
     * @param inning     current inning (1–9; 10+ for extra innings)
     * @param inningHalf "top" (away batting) or "bot" (home batting)
     * @param outs       current outs in the half-inning (0, 1, or 2; -1 if unknown)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateLiveOdds(
            String homeTeam, String awayTeam,
            int homeScore, int awayScore,
            int inning, String inningHalf, int outs) {

        if (homeTeam == null || awayTeam == null
                || homeTeam.isBlank() || awayTeam.isBlank()) {
            return List.of();
        }

        int    safeInning = Math.max(1, inning);
        int    safeOuts   = Math.max(0, Math.min(2, outs < 0 ? 0 : outs));
        boolean isBot     = "bot".equalsIgnoreCase(inningHalf);
        int    runDiff    = homeScore - awayScore;

        double timeFactor = buildTimeFactor(safeInning, isBot, safeOuts);

        double[] probs = computeLiveProbs(runDiff, timeFactor, isBot);
        double homeProb = probs[0];
        double awayProb = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();
        Random rng = new Random(System.currentTimeMillis());

        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.04 - 0.02); // ±2%

            double homeOdd = clamp(applyMargin(homeProb * noise));
            double awayOdd = clamp(applyMargin(awayProb / noise));

            odds.add(buildEntry(bk, "mlb_live_moneyline", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "mlb_live_moneyline", awayTeam, awayOdd));
        }

        log.debug("generateLiveOdds (MLB): {} vs {} | score={}-{} I{} {} {} outs | timeFactor={}",
                homeTeam, awayTeam, homeScore, awayScore,
                safeInning, inningHalf, safeOuts, round2(timeFactor));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Maps run difference + game progress into a two-outcome probability pair.
     *
     * Baseball-specific: when it's the bottom of the inning and the home team
     * is trailing, the trailing team still has the bat — the "walk-off" factor
     * gives the home team a slightly inflated probability in bot-inning situations
     * where they're behind (last chance effect).
     */
    double[] computeLiveProbs(int runDiff, double timeFactor, boolean isBot) {

        int absRD = Math.abs(runDiff);

        // ── Base probability shift from run difference ────────────────────
        double baseShift;
        if (absRD == 0) {
            baseShift = 0.0;
        } else if (absRD == RUN_TIGHT) {
            baseShift = 0.15; // 1 run — very open
        } else if (absRD == RUN_AHEAD) {
            baseShift = 0.32; // 2 runs — meaningful
        } else if (absRD == RUN_SOLID) {
            baseShift = 0.52; // 3 runs — comfortable
        } else if (absRD == RUN_LARGE) {
            baseShift = 0.68; // 4 runs — large lead
        } else {
            baseShift = 0.80; // 5+ runs — near-certain
        }

        // Time amplifies certainty — later innings compress odds further for the leader
        double shift = baseShift * (1.0 + timeFactor * 0.40);
        shift = Math.min(shift, 0.92);

        // ── Base home/away probabilities ─────────────────────────────────
        double homeProb, awayProb;
        if (runDiff == 0) {
            homeProb = 0.54; // slight home field edge even at 0-0 (walk-off advantage)
            awayProb = 0.46;
        } else if (runDiff > 0) {
            homeProb = 0.50 + shift * 0.50;
            awayProb = Math.max(0.03, 1.0 - homeProb);
        } else {
            awayProb = 0.50 + shift * 0.50;
            homeProb = Math.max(0.03, 1.0 - awayProb);
        }

        // ── Bottom-of-inning walk-off factor ─────────────────────────────
        //
        // In the bottom half, if the home team is trailing, they have the bat
        // right now — a slight probability boost to home (last chance effect).
        // This is most pronounced in late innings (timeFactor >= 0.75).
        if (isBot && runDiff < 0 && timeFactor >= 0.60) {
            double walkOffBoost = 0.03 * timeFactor;
            homeProb = Math.min(0.90, homeProb + walkOffBoost);
            awayProb = Math.max(0.05, 1.0 - homeProb);
        }

        // ── Re-normalise ─────────────────────────────────────────────────
        double total = homeProb + awayProb;
        return new double[]{ homeProb / total, awayProb / total };
    }

    /**
     * Converts inning + half + outs into a 0.0–1.0 game progress factor.
     *
     * Full game = 18 half-innings (9 innings × 2 halves).
     * Each half-inning contributes 1/18.
     * Outs contribute a fractional sub-step within the half-inning (outs/3 × 1/18).
     * Extra innings (inning > 9) cap at 0.98.
     */
    double buildTimeFactor(int inning, boolean isBot, int outs) {
        if (inning > 9) return 0.98; // extra innings — always tense

        int halfInningsElapsed = (inning - 1) * 2 + (isBot ? 1 : 0);
        double outsFraction    = (outs / 3.0) * (1.0 / 18.0);
        double raw             = halfInningsElapsed / 18.0 + outsFraction;
        return Math.min(0.99, raw);
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
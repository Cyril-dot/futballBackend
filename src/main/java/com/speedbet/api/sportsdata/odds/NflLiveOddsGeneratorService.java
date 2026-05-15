package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates live in-play 1X2 odds for an NFL game.
 *
 * ── NFL live model specifics ─────────────────────────────────────────────
 *
 *   NFL live state is richer than football: score, quarter, clock,
 *   possession, and red zone are all available from AmericanFootballDataService.
 *
 *   This generator uses:
 *
 *     scoreDiff      — homeScore − awayScore (can be large: TD = 7 pts)
 *     quarter        — 1–4 (overtime = 5), used for time pressure
 *     clockSeconds   — seconds remaining in the current quarter (0–900)
 *     possession     — which team has the ball (small probability nudge)
 *     redZone        — team in opponent's red zone (extra nudge — scoring imminent)
 *
 * ── Scoring scale differences from football ──────────────────────────────
 *
 *   In football (soccer) a 1-goal lead is significant.
 *   In NFL a 1-point lead means almost nothing — a single TD+XP (7 pts)
 *   erases it instantly.  The meaningful breakpoints are:
 *
 *     |diff| ≤  3  — field goal lead — game very open
 *     |diff| ≤  8  — one-score game  — TD + 2pt keeps it close
 *     |diff| ≤ 16  — two-score game  — requires two TDs to catch up
 *     |diff| ≥ 17  — comfortable lead — comeback possible but unlikely late
 *     |diff| ≥ 25  — very safe lead  — 4th quarter maths very hard
 *
 * ── Time model ───────────────────────────────────────────────────────────
 *
 *   timeFactor in [0.0, 1.0] represents how much of the game has elapsed.
 *   Total seconds = 4 × 900 = 3600.
 *   timeFactor = elapsedSeconds / 3600.
 *   Overtime (quarter 5) treated as timeFactor = 0.95 (very late game).
 *
 * ── Draw model ────────────────────────────────────────────────────────────
 *
 *   Tie becomes only possible in the final quarter at 0 score diff.
 *   It is always a tiny probability — modelled explicitly and kept < 5%.
 *
 * ── Markets generated ────────────────────────────────────────────────────
 *
 *   "nfl_live_moneyline" → HOME, DRAW, AWAY
 *
 * ── Update cadence ───────────────────────────────────────────────────────
 *
 *   Call every 30–60 seconds while STATE_IN.
 *   Refresh possession + redZone from AmericanFootballDataService.getLiveGames()
 *   each call for the most accurate nudge.
 */
@Slf4j
@Service
public class NflLiveOddsGeneratorService {

    private static final double OVERROUND = 1.08;

    private static final double MIN_ODD = 1.02;
    private static final double MAX_ODD = 50.0;

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // NFL scoring breakpoints (absolute score difference)
    private static final int FIELD_GOAL_LEAD = 3;
    private static final int ONE_SCORE_LEAD  = 8;
    private static final int TWO_SCORE_LEAD  = 16;
    private static final int SAFE_LEAD       = 24;

    /**
     * Generate live moneyline odds for an in-progress NFL game.
     *
     * @param homeTeam      home team display name
     * @param awayTeam      away team display name
     * @param homeScore     current home score (points)
     * @param awayScore     current away score (points)
     * @param quarter       current quarter (1–4; 5 = overtime)
     * @param clockSeconds  seconds remaining in the current quarter (0–900)
     * @param homePossession true if the home team currently has the ball
     * @param homeRedZone    true if the home team is in the red zone
     * @param awayRedZone    true if the away team is in the red zone
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateLiveOdds(
            String homeTeam, String awayTeam,
            int homeScore, int awayScore,
            int quarter, int clockSeconds,
            boolean homePossession, boolean homeRedZone, boolean awayRedZone) {

        if (homeTeam == null || awayTeam == null
                || homeTeam.isBlank() || awayTeam.isBlank()) {
            return List.of();
        }

        int    safeQuarter = Math.max(1, Math.min(5, quarter));
        int    safeClock   = Math.max(0, Math.min(900, clockSeconds));
        int    scoreDiff   = homeScore - awayScore;
        double timeFactor  = buildTimeFactor(safeQuarter, safeClock);

        double[] probs = computeLiveProbs(scoreDiff, timeFactor,
                                          homePossession, homeRedZone, awayRedZone);
        double homeProb = probs[0];
        double drawProb = probs[1];
        double awayProb = probs[2];

        List<Map<String, Object>> odds = new ArrayList<>();
        Random rng = new Random(System.currentTimeMillis());

        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.04 - 0.02); // ±2%

            double homeOdd = clamp(applyMargin(homeProb * noise));
            double drawOdd = clamp(applyMargin(drawProb));
            double awayOdd = clamp(applyMargin(awayProb / noise));

            odds.add(buildEntry(bk, "nfl_live_moneyline", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "nfl_live_moneyline", "Draw",   drawOdd));
            odds.add(buildEntry(bk, "nfl_live_moneyline", awayTeam, awayOdd));
        }

        log.debug("generateLiveOdds (NFL): {} vs {} | score={}-{} Q{} clock={}s timeFactor={} | homeP={} homeRZ={} awayRZ={}",
                homeTeam, awayTeam, homeScore, awayScore,
                safeQuarter, safeClock, round2(timeFactor),
                homePossession, homeRedZone, awayRedZone);
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Maps NFL-specific state into a probability triple [home, draw, away].
     *
     * NFL scoring breakpoints replace football goal-difference bands.
     * timeFactor amplifies the effect for the leading team.
     * Possession and red zone apply a small secondary nudge.
     */
    double[] computeLiveProbs(int scoreDiff, double timeFactor,
                               boolean homePossession,
                               boolean homeRedZone, boolean awayRedZone) {

        int absGD = Math.abs(scoreDiff);

        // ── Base shift from score difference ────────────────────────────
        double baseShift;
        if (absGD == 0) {
            baseShift = 0.0;
        } else if (absGD <= FIELD_GOAL_LEAD) {
            baseShift = 0.08; // FG lead — very open
        } else if (absGD <= ONE_SCORE_LEAD) {
            baseShift = 0.20; // one-score lead — meaningful
        } else if (absGD <= TWO_SCORE_LEAD) {
            baseShift = 0.42; // two-score gap
        } else if (absGD <= SAFE_LEAD) {
            baseShift = 0.62; // comfortable
        } else {
            baseShift = 0.78; // blowout territory
        }

        // Time amplifies certainty — at 0.0 (start) very little shift; at 1.0 (end) 30% more
        double shift = baseShift * (1.0 + timeFactor * 0.35);
        shift = Math.min(shift, 0.90);

        // ── Draw probability ─────────────────────────────────────────────
        // Only remotely possible late in game at 0 diff; otherwise negligible
        double drawProb;
        if (absGD == 0 && timeFactor >= 0.75) {
            drawProb = 0.04 * (1.0 - timeFactor * 0.5); // shrinks as game ends even at 0 diff
        } else if (absGD == 0) {
            drawProb = 0.02;
        } else {
            drawProb = 0.005; // essentially 0 when a team is leading
        }

        // ── Home / away base probabilities ──────────────────────────────
        double homeBase, awayBase;
        if (scoreDiff == 0) {
            homeBase = 0.50;
            awayBase = 0.50;
        } else if (scoreDiff > 0) {
            homeBase = 0.50 + shift * 0.50;
            awayBase = Math.max(0.03, (1.0 - homeBase - drawProb));
        } else {
            awayBase = 0.50 + shift * 0.50;
            homeBase = Math.max(0.03, (1.0 - awayBase - drawProb));
        }

        // ── Possession nudge (+2% to team with ball) ────────────────────
        if (homePossession) {
            homeBase = Math.min(0.95, homeBase + 0.02);
            awayBase = Math.max(0.01, awayBase - 0.02);
        } else {
            // Assume away has possession if home doesn't (or neither — no change)
            awayBase = Math.min(0.95, awayBase + 0.01);
            homeBase = Math.max(0.01, homeBase - 0.01);
        }

        // ── Red zone nudge (+3% to team in red zone — scoring very likely) ─
        if (homeRedZone) {
            homeBase = Math.min(0.95, homeBase + 0.03);
            awayBase = Math.max(0.01, awayBase - 0.03);
        } else if (awayRedZone) {
            awayBase = Math.min(0.95, awayBase + 0.03);
            homeBase = Math.max(0.01, homeBase - 0.03);
        }

        // ── Re-normalise to 1.0 ────────────────────────────────────────
        double total = homeBase + drawProb + awayBase;
        return new double[]{ homeBase / total, drawProb / total, awayBase / total };
    }

    /**
     * Converts quarter + clock into a 0.0–1.0 time factor.
     * Total NFL regular time = 4 × 900s = 3600s.
     * OT (quarter 5) treated as 95% elapsed.
     */
    double buildTimeFactor(int quarter, int clockSeconds) {
        if (quarter >= 5) return 0.95; // overtime
        int elapsedInCurrentQuarter = 900 - clockSeconds;
        int totalElapsed = (quarter - 1) * 900 + elapsedInCurrentQuarter;
        return Math.min(1.0, totalElapsed / 3600.0);
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
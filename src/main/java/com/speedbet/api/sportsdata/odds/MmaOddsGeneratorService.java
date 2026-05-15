package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates realistic pre-match 1X2 odds for a UFC/MMA bout.
 *
 * ── MMA vs. Football differences ─────────────────────────────────────────
 *
 *   MMA has NO draw outcome — moneyline is always two-way (home/away = fighter1/fighter2).
 *   Therefore the market is "mma_moneyline" and only HOME and AWAY selections are produced.
 *
 *   "Home" = fighter1 (listed first / higher-ranked by ESPN convention).
 *   "Away" = fighter2 (challenger / opponent).
 *
 * ── Probability model ────────────────────────────────────────────────────
 *
 *   Each fighter is assigned a pseudo-strength (0.35–0.80) derived from a
 *   deterministic seed built from both fighter names.  The same matchup always
 *   produces the same base odds within a server restart, mimicking stable
 *   pre-fight lines.
 *
 *   Fight record adjustments:
 *     - Record strings (e.g. "26-1-0") are parsed to derive a win-ratio bonus.
 *     - A fighter with a high win ratio gets a small probability boost.
 *     - If records are absent/unparseable, the name-hash strength is used alone.
 *
 *   Overround: ~106% (slightly lower than football — MMA markets are tighter
 *   because there is no draw to distribute margin across).
 *
 * ── Returned list structure ───────────────────────────────────────────────
 *
 *   Each map: { bookmaker, market, selection, odd }
 *   market    → "mma_moneyline"
 *   selection → fighter display name  (normalised to "HOME" / "AWAY" on persist)
 *
 * ── Usage ────────────────────────────────────────────────────────────────
 *
 *   Called by MmaOddsPersistenceService for pre-fight odds generation.
 *   Cached results are fine — odds do not change until the fight goes live.
 */
@Slf4j
@Service
public class MmaOddsGeneratorService {

    // MMA moneyline overround — no draw means margin spread across 2 outcomes only
    private static final double OVERROUND = 1.06;

    private static final double MIN_ODD = 1.05;
    private static final double MAX_ODD = 12.0;

    // Simulated bookmakers
    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate pre-match moneyline odds for a single MMA bout.
     *
     * @param fighter1     display name of fighter1 (ESPN "home" side)
     * @param fighter2     display name of fighter2 (ESPN "away" side)
     * @param fighter1Record win-loss-draw record string, e.g. "26-1-0" (may be null/blank)
     * @param fighter2Record win-loss-draw record string, e.g. "29-8-0" (may be null/blank)
     * @param weightClass  weight class name, used to vary the seed (may be null)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generatePreMatchOdds(
            String fighter1, String fighter2,
            String fighter1Record, String fighter2Record,
            String weightClass) {

        if (fighter1 == null || fighter2 == null
                || fighter1.isBlank() || fighter2.isBlank()) {
            return List.of();
        }

        long   seed = buildSeed(fighter1, fighter2, weightClass);
        Random rng  = new Random(seed);

        double[] probs = computeTrueProbs(fighter1, fighter2,
                                          fighter1Record, fighter2Record, rng);
        double f1Prob = probs[0];
        double f2Prob = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();

        for (String bk : BOOKMAKERS) {
            // ±1.5% bookmaker spread to mimic market variation
            double spread = 1.0 + (rng.nextDouble() * 0.03 - 0.015);

            double f1Odd = clamp(applyMargin(f1Prob * spread));
            double f2Odd = clamp(applyMargin(f2Prob / spread));

            odds.add(buildEntry(bk, "mma_moneyline", fighter1, f1Odd));
            odds.add(buildEntry(bk, "mma_moneyline", fighter2, f2Odd));
        }

        log.debug("generatePreMatchOdds (MMA): {} vs {} | f1Prob={} f2Prob={}",
                fighter1, fighter2, round2(f1Prob), round2(f2Prob));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Derives true win probabilities for each fighter.
     *
     * Base strength: name hash → 0.35–0.80 range.
     * Record bonus:  parsed win-ratio adds up to ±0.10 to the base strength.
     * Normalised to sum = 1.0 (two-outcome market).
     */
    double[] computeTrueProbs(String fighter1, String fighter2,
                               String fighter1Record, String fighter2Record,
                               Random rng) {

        double s1 = nameHashStrength(fighter1);
        double s2 = nameHashStrength(fighter2);

        // Record bonus — win ratio above 0.70 gives a small lift
        double r1Bonus = recordBonus(fighter1Record);
        double r2Bonus = recordBonus(fighter2Record);

        s1 = Math.min(0.85, s1 + r1Bonus);
        s2 = Math.min(0.85, s2 + r2Bonus);

        double total = s1 + s2;
        double f1Prob = s1 / total;
        double f2Prob = s2 / total;

        // Small random jitter (±3%) so the same matchup has some variance across events
        double jitter = rng.nextDouble() * 0.06 - 0.03;
        f1Prob = Math.max(0.10, Math.min(0.90, f1Prob + jitter));
        f2Prob = 1.0 - f1Prob;

        return new double[]{ f1Prob, f2Prob };
    }

    /**
     * Maps a fighter name hash to a strength score in [0.35, 0.80].
     */
    private double nameHashStrength(String name) {
        return 0.35 + (Math.abs(name.hashCode() % 1000) / 1000.0) * 0.45;
    }

    /**
     * Parses "W-L-D" record string and returns a bonus in [-0.08, +0.08].
     * Returns 0.0 if the record is null/blank/unparseable.
     */
    double recordBonus(String record) {
        if (record == null || record.isBlank()) return 0.0;
        try {
            String[] parts = record.split("-");
            if (parts.length < 2) return 0.0;
            double wins   = Double.parseDouble(parts[0].trim());
            double losses = Double.parseDouble(parts[1].trim());
            double total  = wins + losses;
            if (total == 0) return 0.0;
            double winRatio = wins / total;
            // Bonus: +0.08 for undefeated, −0.08 for 50% or below
            return (winRatio - 0.70) * 0.25; // centred at 70% win rate
        } catch (NumberFormatException e) {
            return 0.0;
        }
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

    /**
     * Deterministic seed: fighter names + weight class.
     * Weight class is optional — null-safe.
     */
    private long buildSeed(String fighter1, String fighter2, String weightClass) {
        String key = fighter1.toLowerCase() + "|" + fighter2.toLowerCase()
                + "|" + (weightClass != null ? weightClass.toLowerCase() : "");
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
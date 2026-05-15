package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates realistic pre-match win odds for a tennis fixture.
 *
 * Logic:
 *  - Tennis has no draw — only HOME (player 1) and AWAY (player 2) selections.
 *  - Each player is assigned a strength score (0.0–1.0) derived pseudo-randomly
 *    from a deterministic seed built from player names + surface, so the same
 *    fixture always produces the same base odds within a server restart.
 *  - True probabilities are derived from the strength ratio, then a bookmaker
 *    margin (overround ~107.5%) is applied to get the final decimal odds.
 *  - A surface modifier reflects real-world dynamics:
 *      Grass  → 1.15 spread (serve dominance amplifies strength differences)
 *      Clay   → 0.88 spread (equalises players)
 *      Hard   → 1.00 spread (neutral)
 *
 * Caller convention (Match entity fields):
 *   match.getHomeTeam() → player 1
 *   match.getAwayTeam() → player 2
 *   match.getLeague()   → surface ("Grass" | "Clay" | "Hard")
 *
 * Returned map structure (identical to OddsGeneratorService shape):
 *   [ { bookmaker, market, selection, odd }, ... ]
 *
 * Update cadence: once at match creation — never changes pre-match.
 */
@Slf4j
@Service
public class TennisPreMatchOddsService {

    private static final double OVERROUND = 1.075;
    private static final double MIN_ODD   = 1.05;
    private static final double MAX_ODD   = 15.0;

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate pre-match win odds for a tennis match.
     *
     * @param player1 player 1 name  (match.getHomeTeam())
     * @param player2 player 2 name  (match.getAwayTeam())
     * @param surface court surface  (match.getLeague()) — "Grass" | "Clay" | "Hard"
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generatePreMatchOdds(String player1, String player2, String surface) {
        if (player1 == null || player2 == null) return List.of();

        long   seed = buildSeed(player1, player2, surface);
        Random rng  = new Random(seed);

        double[] probs  = generateTrueProbs(player1, player2, surface, rng);
        double homeProb = probs[0];
        double awayProb = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();

        for (String bk : BOOKMAKERS) {
            // ±1.5% spread per bookmaker to mimic real market variation
            double spread = 1.0 + (rng.nextDouble() * 0.03 - 0.015);

            double homeOdd = clamp(applyMargin(homeProb * spread));
            double awayOdd = clamp(applyMargin(awayProb / spread));

            odds.add(buildEntry(bk, "tennis_match_winner", player1, homeOdd));
            odds.add(buildEntry(bk, "tennis_match_winner", player2, awayOdd));
        }

        log.debug("generatePreMatchOdds: {} vs {} surface={} — home={} away={}",
                player1, player2, surface,
                round(applyMargin(homeProb)), round(applyMargin(awayProb)));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Derives a probability pair [player1WinProb, player2WinProb] that sums to 1.0.
     * Surface spread modifier amplifies or compresses the strength difference.
     */
    double[] generateTrueProbs(String player1, String player2, String surface, Random rng) {
        double s1 = 0.35 + (Math.abs(player1.hashCode() % 1000) / 1000.0) * 0.45;
        double s2 = 0.35 + (Math.abs(player2.hashCode() % 1000) / 1000.0) * 0.45;

        double surfaceSpread = buildSurfaceSpread(surface);
        double diff  = (s1 - s2) * surfaceSpread;
        double rawP1 = Math.max(0.08, Math.min(0.92, 0.50 + diff * 0.50));
        double rawP2 = 1.0 - rawP1;

        return new double[]{ rawP1, rawP2 };
    }

    /**
     * Surface spread modifier.
     * Grass  → 1.15  serve advantage amplifies strength gap
     * Clay   → 0.88  baseline parity compresses strength gap
     * Hard   → 1.00  neutral
     */
    private double buildSurfaceSpread(String surface) {
        if (surface == null) return 1.0;
        return switch (surface.toLowerCase()) {
            case "grass"          -> 1.15;
            case "clay"           -> 0.88;
            case "hard", "indoor" -> 1.00;
            default               -> 1.00;
        };
    }

    private double applyMargin(double trueProb) {
        if (trueProb <= 0) return MAX_ODD;
        return 1.0 / (trueProb * OVERROUND);
    }

    private double clamp(double odd) {
        return Math.max(MIN_ODD, Math.min(MAX_ODD, odd));
    }

    private double round(double odd) {
        return BigDecimal.valueOf(odd).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Map<String, Object> buildEntry(String bookmaker, String market, String selection, double odd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker", bookmaker);
        m.put("market",    market);
        m.put("selection", selection);
        m.put("odd",       String.valueOf(round(odd)));
        return m;
    }

    private long buildSeed(String player1, String player2, String surface) {
        String key = player1.toLowerCase() + "|" + player2.toLowerCase()
                + "|" + (surface != null ? surface.toLowerCase() : "");
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
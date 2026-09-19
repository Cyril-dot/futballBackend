package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates realistic pre-match 1X2 odds for a fixture.
 *
 * Guarantee: EVERY fixture gets odds. If the input is missing/blank/null,
 * safe fallback names are used so the method never returns an empty list.
 *
 * Logic:
 *  - Each team is assigned a "strength" score (0.0–1.0) derived pseudo-randomly
 *    from a deterministic seed built from team names, so the same fixture always
 *    produces the same pre-match odds.
 *  - True probabilities are derived from strength ratio, then a bookmaker margin
 *    (overround ~107.5%) is applied to get the final decimal odds.
 *  - Draw probability is kept in a realistic 20–30% band.
 *
 * Returned list structure:
 *   [ { bookmaker, market, selection, odd }, ... ]
 */
@Slf4j
@Service
public class OddsGeneratorService {

    // Bookmaker margin: spread across 1X2 — total implied prob ~1.075
    private static final double OVERROUND = 1.075;

    // Decimal odds bounds to keep things sane
    private static final double MIN_ODD = 1.10;
    private static final double MAX_ODD = 15.0;

    // Fallback names used when a fixture has missing team data
    private static final String FALLBACK_HOME = "Home Team";
    private static final String FALLBACK_AWAY = "Away Team";

    // Simulated bookmaker names shown in the response
    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate pre-match 1X2 odds for a fixture.
     * NEVER returns an empty list — always produces odds for every fixture.
     *
     * @param homeTeam home team name (null/blank-safe)
     * @param awayTeam away team name (null/blank-safe)
     * @param league   competition name (null-safe, used to vary the seed)
     * @return list of { bookmaker, market, selection, odd } maps (never empty)
     */
    public List<Map<String, Object>> generatePreMatchOdds(String homeTeam, String awayTeam, String league) {
        // Never skip a game: substitute safe defaults for missing names
        String home = isBlank(homeTeam) ? FALLBACK_HOME : homeTeam.trim();
        String away = isBlank(awayTeam) ? FALLBACK_AWAY : awayTeam.trim();

        if (isBlank(homeTeam) || isBlank(awayTeam)) {
            log.warn("generatePreMatchOdds: missing team name(s) (home='{}', away='{}') — using fallbacks",
                    homeTeam, awayTeam);
        }

        // Deterministic seed so the same fixture always gets the same base odds
        long seed = buildSeed(home, away, league);
        Random rng = new Random(seed);

        double[] probs = generateTrueProbs(home, away, rng);
        double homeProb = probs[0];
        double drawProb = probs[1];
        double awayProb = probs[2];

        List<Map<String, Object>> odds = new ArrayList<>();

        for (String bk : BOOKMAKERS) {
            // Tiny random spread per bookmaker (±1.5%) to mimic real market variation
            double spread = 1.0 + (rng.nextDouble() * 0.03 - 0.015);

            double homeOdd = clamp(applyMargin(homeProb * spread));
            double drawOdd = clamp(applyMargin(drawProb));
            double awayOdd = clamp(applyMargin(awayProb / spread));

            odds.add(buildEntry(bk, "1x2", home, homeOdd));
            odds.add(buildEntry(bk, "1x2", "Draw", drawOdd));
            odds.add(buildEntry(bk, "1x2", away, awayOdd));
        }

        log.debug("generatePreMatchOdds: {} vs {} — home={} draw={} away={}",
                home, away,
                round(applyMargin(homeProb)), round(applyMargin(drawProb)), round(applyMargin(awayProb)));
        return odds;
    }

    /**
     * Convenience: returns the existing odds if present, otherwise generates them.
     * Use this when you already have odds from an upstream source and only want
     * to fill in the gaps, so no game is ever left without odds.
     *
     * @param existingOdds odds already attached to the fixture (may be null/empty)
     * @return existing odds if non-empty, otherwise freshly generated odds
     */
    public List<Map<String, Object>> ensureOdds(List<Map<String, Object>> existingOdds,
                                                String homeTeam,
                                                String awayTeam,
                                                String league) {
        if (existingOdds != null && !existingOdds.isEmpty()) {
            return existingOdds;
        }
        log.debug("ensureOdds: no odds for {} vs {} — generating", homeTeam, awayTeam);
        return generatePreMatchOdds(homeTeam, awayTeam, league);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Derives a probability triple [home, draw, away] that sums to 1.0.
     * Home advantage is baked in: home team gets a ~5–10% boost.
     */
    double[] generateTrueProbs(String homeTeam, String awayTeam, Random rng) {
        // Pseudo-strength from name hash — stable per fixture.
        // Math.floorMod avoids the Math.abs(Integer.MIN_VALUE) negative edge case.
        double homeStrength = 0.35 + (Math.floorMod(homeTeam.hashCode(), 1000) / 1000.0) * 0.45;
        double awayStrength = 0.35 + (Math.floorMod(awayTeam.hashCode(), 1000) / 1000.0) * 0.45;

        // Apply home advantage
        homeStrength *= 1.08;

        double total = homeStrength + awayStrength;
        double rawHome = homeStrength / total;
        double rawAway = awayStrength / total;

        // Draw: 20–30% of the time, taken proportionally from home/away
        double drawProb = 0.20 + rng.nextDouble() * 0.10;
        double homeProb = rawHome * (1.0 - drawProb);
        double awayProb = rawAway * (1.0 - drawProb);

        return new double[]{ homeProb, drawProb, awayProb };
    }

    /** Convert true probability → decimal odd with overround applied. */
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
        m.put("market", market);
        m.put("selection", selection);
        m.put("odd", String.valueOf(round(odd)));
        return m;
    }

    /**
     * Deterministic seed: combines team name chars + league chars.
     * League is optional — null-safe.
     */
    private long buildSeed(String homeTeam, String awayTeam, String league) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase()
                + "|" + (league != null ? league.toLowerCase() : "");
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
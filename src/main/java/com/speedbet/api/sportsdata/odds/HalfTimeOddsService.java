package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates half-time result (HT 1X2) odds for a match.
 *
 * Logic:
 * ─────────────────────────────────────────────────────────────────────────
 *  Half-time odds follow the same favourite/underdog relationship as
 *  full-time but with:
 *   • Larger draw probability (draws are much more common at HT ~30–40%)
 *   • Compressed swing magnitude (only 45 min, so less separation)
 *   • Slightly higher overround (typical for HT market ~108–112%)
 *
 * For LIVE matches the HT odds can also be called for "HT/FT double"
 * style display — the scoreline at the time of calling is used.
 *
 * Markets generated:
 *  • half_time — half-time result (home / draw / away)
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class HalfTimeOddsService {

    private static final double OVERROUND = 1.09;
    private static final double MIN_ODD   = 1.15;
    private static final double MAX_ODD   = 12.0;

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    // ─────────────────────────────────────────────────────────────────────
    // PRE-MATCH — deterministic, same odds every time for same fixture
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generate pre-match HT 1X2 odds.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @param league   competition name (used in seed)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateHalfTimeOdds(
            String homeTeam, String awayTeam, String league) {

        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam, league);
        Random rng = new Random(seed);

        double[] probs = computePreMatchHtProbs(homeTeam, awayTeam, rng);
        return buildOddsEntries(homeTeam, awayTeam, probs, rng);
    }

    // ─────────────────────────────────────────────────────────────────────
    // LIVE — reacts to current HT scoreline (if it's past 45 min, lock it)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generate live HT odds for a match currently in the first half.
     * If minutePlayed >= 45 the market is considered settled — returns empty list.
     *
     * @param homeTeam     home team name
     * @param awayTeam     away team name
     * @param scoreHome    current score (home)
     * @param scoreAway    current score (away)
     * @param minutePlayed approximate minute in the match
     * @return list of { bookmaker, market, selection, odd } maps, or empty if HT passed
     */
    public List<Map<String, Object>> generateLiveHalfTimeOdds(
            String homeTeam, String awayTeam,
            int scoreHome, int scoreAway,
            int minutePlayed) {

        if (homeTeam == null || awayTeam == null) return List.of();
        if (minutePlayed >= 45) {
            log.debug("generateLiveHalfTimeOdds: minute={} — HT market settled, skipping", minutePlayed);
            return List.of();
        }

        int gd = scoreHome - scoreAway;
        double timeInHalf = minutePlayed / 45.0; // 0–1 within first half

        double[] probs = computeLiveHtProbs(gd, timeInHalf);

        Random rng = new Random(System.currentTimeMillis());
        return buildOddsEntries(homeTeam, awayTeam, probs, rng);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engines
    // ─────────────────────────────────────────────────────────────────────

    private double[] computePreMatchHtProbs(String homeTeam, String awayTeam, Random rng) {
        // HT draw is very common: 30–40%
        double drawProb = 0.30 + rng.nextDouble() * 0.10;

        // Same strength hash as OddsGeneratorService for consistency
        double homeStrength = 0.35 + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * 0.45;
        double awayStrength = 0.35 + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * 0.45;
        homeStrength *= 1.06; // slight home advantage

        double total = homeStrength + awayStrength;
        double homeProb = (homeStrength / total) * (1.0 - drawProb);
        double awayProb = (awayStrength / total) * (1.0 - drawProb);

        return new double[]{ homeProb, drawProb, awayProb };
    }

    private double[] computeLiveHtProbs(int gd, double timeInHalf) {
        if (gd == 0) {
            // No goal yet — draw very likely at HT, rising as time passes
            double drawProb = 0.38 + timeInHalf * 0.15;
            double remaining = 1.0 - drawProb;
            return new double[]{ remaining * 0.50, drawProb, remaining * 0.50 };
        }

        boolean homeLeading = gd > 0;
        int absGD = Math.abs(gd);

        // Smaller swing than full-match (only half the time left)
        double baseShift = switch (absGD) {
            case 1 -> 0.15;
            case 2 -> 0.35;
            default -> 0.55;
        };
        double shift = baseShift * (1.0 + timeInHalf * 0.25);
        shift = Math.min(shift, 0.75);

        double leaderProb  = 0.45 + shift * 0.50;
        double drawProb    = Math.max(0.05, 0.30 * (1.0 - timeInHalf * 0.40));
        double trailerProb = Math.max(0.02, 1.0 - leaderProb - drawProb);

        double tot = leaderProb + drawProb + trailerProb;
        leaderProb /= tot; drawProb /= tot; trailerProb /= tot;

        if (homeLeading) return new double[]{ leaderProb, drawProb, trailerProb };
        else             return new double[]{ trailerProb, drawProb, leaderProb };
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shared builder
    // ─────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildOddsEntries(
            String homeTeam, String awayTeam,
            double[] probs, Random rng) {

        double homeProb = probs[0];
        double drawProb = probs[1];
        double awayProb = probs[2];

        List<Map<String, Object>> odds = new ArrayList<>();
        for (String bk : BOOKMAKERS) {
            double noise = 1.0 + (rng.nextDouble() * 0.02 - 0.01);

            double homeOdd = clamp(applyMargin(homeProb * noise));
            double drawOdd = clamp(applyMargin(drawProb));
            double awayOdd = clamp(applyMargin(awayProb / noise));

            odds.add(buildEntry(bk, "half_time", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "half_time", "Draw",   drawOdd));
            odds.add(buildEntry(bk, "half_time", awayTeam, awayOdd));
        }
        return odds;
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

    private long buildSeed(String homeTeam, String awayTeam, String league) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase()
                + "|" + (league != null ? league.toLowerCase() : "");
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
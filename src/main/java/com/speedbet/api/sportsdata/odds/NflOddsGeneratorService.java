package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates realistic pre-match 1X2 odds for an NFL game.
 *
 * ── NFL vs. football/MMA differences ────────────────────────────────────
 *
 *   NFL is technically a two-outcome market (no draw in regulation) but
 *   overtime CAN end in a tie in the regular season — so a draw selection
 *   is included at a very high odd (draws are extremely rare, ~1–2% of games).
 *
 *   Markets generated:
 *     "nfl_moneyline" → HOME, DRAW, AWAY
 *
 *   HOME = home team (homeAway == "home" in ESPN competitor map).
 *   AWAY = visiting team.
 *   DRAW = tie (used in regular season OT edge case only — omit if desired
 *          by filtering on persist).
 *
 * ── Probability model ────────────────────────────────────────────────────
 *
 *   Base strength: deterministic name hash → 0.35–0.80 range.
 *   Home field advantage: home team gets a ~5–8% probability boost
 *   (NFL home field is significant — crowd noise, travel fatigue, familiarity).
 *
 *   Win record bonus: caller may pass record strings (e.g. "12-5").
 *   Win ratio above 0.65 gets a small positive bonus; below 0.35 a penalty.
 *
 *   Draw probability: fixed at 1.5–2.5% (realistic NFL tie rate), remainder
 *   split between home/away in proportion to their relative strength.
 *
 *   Overround: ~107.5% — standard for a near-two-outcome market.
 *
 * ── Returned list structure ───────────────────────────────────────────────
 *
 *   Each map: { bookmaker, market, selection, odd }
 *   selection → team display name (normalised HOME / DRAW / AWAY on persist)
 */
@Slf4j
@Service
public class NflOddsGeneratorService {

    private static final double OVERROUND = 1.075;

    private static final double MIN_ODD = 1.05;
    private static final double MAX_ODD = 15.0;

    // NFL tie probability: rare but real in regular season OT
    private static final double DRAW_PROB_BASE = 0.018; // ~1.8%

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate pre-match moneyline odds for a single NFL game.
     *
     * @param homeTeam   home team display name (e.g. "Kansas City Chiefs")
     * @param awayTeam   away team display name (e.g. "Philadelphia Eagles")
     * @param homeRecord win-loss record string, e.g. "12-5" (may be null/blank)
     * @param awayRecord win-loss record string, e.g. "10-7" (may be null/blank)
     * @param league     league/conference string for seed variance (may be null)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generatePreMatchOdds(
            String homeTeam, String awayTeam,
            String homeRecord, String awayRecord,
            String league) {

        if (homeTeam == null || awayTeam == null
                || homeTeam.isBlank() || awayTeam.isBlank()) {
            return List.of();
        }

        long   seed = buildSeed(homeTeam, awayTeam, league);
        Random rng  = new Random(seed);

        double[] probs = computeTrueProbs(homeTeam, awayTeam, homeRecord, awayRecord, rng);
        double homeProb = probs[0];
        double drawProb = probs[1];
        double awayProb = probs[2];

        List<Map<String, Object>> odds = new ArrayList<>();

        for (String bk : BOOKMAKERS) {
            // ±1.5% bookmaker spread to mimic real market variation
            double spread = 1.0 + (rng.nextDouble() * 0.03 - 0.015);

            double homeOdd = clamp(applyMargin(homeProb * spread));
            double drawOdd = clamp(applyMargin(drawProb));
            double awayOdd = clamp(applyMargin(awayProb / spread));

            odds.add(buildEntry(bk, "nfl_moneyline", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "nfl_moneyline", "Draw",   drawOdd));
            odds.add(buildEntry(bk, "nfl_moneyline", awayTeam, awayOdd));
        }

        log.debug("generatePreMatchOdds (NFL): {} vs {} | homeProb={} drawProb={} awayProb={}",
                homeTeam, awayTeam, round2(homeProb), round2(drawProb), round2(awayProb));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Derives true win/draw/win probabilities.
     *
     * Strength: name hash (0.35–0.80) + home advantage boost + record bonus.
     * Draw: fixed low rate, carved out of combined strength proportionally.
     */
    double[] computeTrueProbs(String homeTeam, String awayTeam,
                               String homeRecord, String awayRecord,
                               Random rng) {

        double sHome = nameHashStrength(homeTeam) * 1.07; // home field boost
        double sAway = nameHashStrength(awayTeam);

        sHome = Math.min(0.88, sHome + recordBonus(homeRecord));
        sAway = Math.min(0.88, sAway + recordBonus(awayRecord));

        double total   = sHome + sAway;
        double rawHome = sHome / total;
        double rawAway = sAway / total;

        // Draw: slight jitter around base probability
        double drawProb = DRAW_PROB_BASE + (rng.nextDouble() * 0.01 - 0.005);
        drawProb = Math.max(0.005, drawProb);

        double homeProb = rawHome * (1.0 - drawProb);
        double awayProb = rawAway * (1.0 - drawProb);

        return new double[]{ homeProb, drawProb, awayProb };
    }

    private double nameHashStrength(String name) {
        return 0.35 + (Math.abs(name.hashCode() % 1000) / 1000.0) * 0.45;
    }

    /**
     * Parses "W-L" or "W-L-T" record and returns a bonus in [-0.07, +0.07].
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
            // Centred at 0.55 (slight home-field bias already applied separately)
            return (winRatio - 0.55) * 0.20;
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

    private long buildSeed(String homeTeam, String awayTeam, String league) {
        String key = homeTeam.toLowerCase() + "|" + awayTeam.toLowerCase()
                + "|" + (league != null ? league.toLowerCase() : "");
        long hash = 0;
        for (char c : key.toCharArray()) hash = hash * 31 + c;
        return hash;
    }
}
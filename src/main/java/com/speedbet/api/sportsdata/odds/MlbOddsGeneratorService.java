package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates realistic pre-match moneyline odds for an MLB game.
 *
 * ── Baseball vs. other sports ────────────────────────────────────────────
 *
 *   Baseball has NO draw — the game goes to extra innings until someone wins.
 *   Therefore only HOME and AWAY selections are produced (two-way market).
 *
 *   Market: "mlb_moneyline" → HOME, AWAY
 *
 * ── Probability model ────────────────────────────────────────────────────
 *
 *   Base strength: deterministic name hash → 0.35–0.80 range.
 *
 *   Home field advantage: home team gets a ~4–6% boost.
 *   MLB home field is real but smaller than NFL — it's mostly about
 *   familiarity with ballpark dimensions and avoiding travel fatigue.
 *
 *   Win record bonus: caller may pass record strings (e.g. "72-45").
 *   Win ratio well above .500 gets a small positive bonus.
 *   An 80-win team is strong in baseball — .500 is already decent.
 *
 *   Starting pitcher quality: a dominant pitcher (ERA-proxy) can shift
 *   probability by up to ±8%.  Caller may pass ERA strings; lower ERA
 *   → bigger boost for that team.  Falls back to 0.0 if absent.
 *
 *   Overround: ~106% — baseball two-way markets are tight because there
 *   is no draw to spread margin across.
 *
 * ── Returned list structure ───────────────────────────────────────────────
 *
 *   Each map: { bookmaker, market, selection, odd }
 *   selection → team display name (normalised HOME / AWAY on persist)
 */
@Slf4j
@Service
public class MlbOddsGeneratorService {

    private static final double OVERROUND = 1.06;

    private static final double MIN_ODD = 1.05;
    private static final double MAX_ODD = 10.0; // baseball lines are tighter than NFL/MMA

    private static final List<String> BOOKMAKERS = List.of(
            "SpeedBet", "BetKing", "SportyBet", "1xBet", "Betway"
    );

    /**
     * Generate pre-match moneyline odds for a single MLB game.
     *
     * @param homeTeam     home team display name (e.g. "New York Yankees")
     * @param awayTeam     away team display name (e.g. "Boston Red Sox")
     * @param homeRecord   win-loss record string, e.g. "72-45" (may be null/blank)
     * @param awayRecord   win-loss record string, e.g. "60-57" (may be null/blank)
     * @param homeEra      starting pitcher ERA string, e.g. "2.85" (may be null/blank)
     * @param awayEra      starting pitcher ERA string, e.g. "4.10" (may be null/blank)
     * @param league       league/division string for seed variance (may be null)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generatePreMatchOdds(
            String homeTeam, String awayTeam,
            String homeRecord, String awayRecord,
            String homeEra, String awayEra,
            String league) {

        if (homeTeam == null || awayTeam == null
                || homeTeam.isBlank() || awayTeam.isBlank()) {
            return List.of();
        }

        long   seed = buildSeed(homeTeam, awayTeam, league);
        Random rng  = new Random(seed);

        double[] probs = computeTrueProbs(homeTeam, awayTeam,
                                          homeRecord, awayRecord,
                                          homeEra, awayEra, rng);
        double homeProb = probs[0];
        double awayProb = probs[1];

        List<Map<String, Object>> odds = new ArrayList<>();

        for (String bk : BOOKMAKERS) {
            // ±1.5% bookmaker spread
            double spread = 1.0 + (rng.nextDouble() * 0.03 - 0.015);

            double homeOdd = clamp(applyMargin(homeProb * spread));
            double awayOdd = clamp(applyMargin(awayProb / spread));

            odds.add(buildEntry(bk, "mlb_moneyline", homeTeam, homeOdd));
            odds.add(buildEntry(bk, "mlb_moneyline", awayTeam, awayOdd));
        }

        log.debug("generatePreMatchOdds (MLB): {} vs {} | homeProb={} awayProb={}",
                homeTeam, awayTeam, round2(homeProb), round2(awayProb));
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probability engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Derives true win probabilities for each team.
     *
     * Components:
     *   1. Name hash strength (deterministic, stable per matchup)
     *   2. Home field advantage (+5% to home)
     *   3. Win record bonus (win ratio vs .500 baseline)
     *   4. Starting pitcher ERA bonus (lower ERA → better)
     */
    double[] computeTrueProbs(String homeTeam, String awayTeam,
                               String homeRecord, String awayRecord,
                               String homeEra, String awayEra,
                               Random rng) {

        double sHome = nameHashStrength(homeTeam) * 1.05; // home field
        double sAway = nameHashStrength(awayTeam);

        sHome = Math.min(0.88, sHome + recordBonus(homeRecord) + pitcherBonus(homeEra));
        sAway = Math.min(0.88, sAway + recordBonus(awayRecord) + pitcherBonus(awayEra));

        double total = sHome + sAway;
        double homeProb = sHome / total;
        double awayProb = sAway / total;

        // Small jitter (±2.5%) — same matchup can vary slightly across games
        double jitter = rng.nextDouble() * 0.05 - 0.025;
        homeProb = Math.max(0.10, Math.min(0.90, homeProb + jitter));
        awayProb = 1.0 - homeProb;

        return new double[]{ homeProb, awayProb };
    }

    private double nameHashStrength(String name) {
        return 0.35 + (Math.abs(name.hashCode() % 1000) / 1000.0) * 0.45;
    }

    /**
     * Parses "W-L" record and returns bonus in [-0.07, +0.07].
     * Centred at .500 — the average MLB team wins exactly half their games.
     * Returns 0.0 if null/blank/unparseable.
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
            double winRate = wins / total;
            // Centred at .500; a 100-win team (~.617) gets ~+0.08 bonus
            return (winRate - 0.500) * 0.60;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Converts a pitcher's ERA to a probability bonus/penalty in [-0.08, +0.08].
     *
     * ERA scale:
     *   ≤ 2.00 → elite (+0.08)
     *   ~3.50  → average (0.0)
     *   ≥ 5.50 → poor (-0.08)
     *
     * Returns 0.0 if null/blank/unparseable.
     */
    double pitcherBonus(String era) {
        if (era == null || era.isBlank()) return 0.0;
        try {
            double e = Double.parseDouble(era.trim());
            // Centred at 3.50 (roughly league-average ERA)
            double bonus = (3.50 - e) * 0.038;
            return Math.max(-0.08, Math.min(0.08, bonus));
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
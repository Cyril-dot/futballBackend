package com.speedbet.api.sportsdata.odds;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Generates correct score odds for a match.
 *
 * Logic:
 * ─────────────────────────────────────────────────────────────────────────
 *  Uses a Poisson-like approximation to assign probability to each score
 *  line (0–0 through 4–4). Expected goals (xG) per team are derived from
 *  the same strength hash used in OddsGeneratorService so the scores are
 *  consistent with the 1X2 favourite/underdog relationship.
 *
 *  • xG home: 1.2–2.2 (home advantage baked in)
 *  • xG away: 0.8–1.8
 *  • Score probabilities: P(h,a) ∝ Poisson(xGh,h) × Poisson(xGa,a)
 *  • Overround: ~115–120% (typical for correct score markets)
 *
 *  Scores with very low probability (<0.3%) are omitted to keep the list
 *  manageable.
 *
 * Markets generated:
 *  • correct_score — full-time scoreline
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class CorrectScoreOddsService {

    private static final double OVERROUND    = 1.18;
    private static final double MIN_ODD      = 3.0;
    private static final double MAX_ODD      = 201.0;
    private static final double MIN_PROB_THRESHOLD = 0.003; // drop anything below 0.3%
    private static final int    MAX_GOALS    = 5; // 0..5 per team

    private static final List<String> BOOKMAKERS = List.of("SpeedBet", "BetKing", "SportyBet");

    /**
     * Generate correct score odds for a fixture.
     *
     * @param homeTeam home team name
     * @param awayTeam away team name
     * @param league   competition (used in seed)
     * @return list of { bookmaker, market, selection, odd } maps
     */
    public List<Map<String, Object>> generateCorrectScoreOdds(
            String homeTeam, String awayTeam, String league) {

        if (homeTeam == null || awayTeam == null) return List.of();

        long seed = buildSeed(homeTeam, awayTeam, league);
        Random rng = new Random(seed);

        double xgHome = 1.2 + (Math.abs(homeTeam.hashCode() % 1000) / 1000.0) * 1.0; // 1.2–2.2
        double xgAway = 0.8 + (Math.abs(awayTeam.hashCode() % 1000) / 1000.0) * 1.0; // 0.8–1.8

        // Small random jitter so fixtures with similar names differ slightly
        xgHome += rng.nextDouble() * 0.2 - 0.1;
        xgAway += rng.nextDouble() * 0.2 - 0.1;

        List<Map<String, Object>> odds = new ArrayList<>();

        for (String bk : BOOKMAKERS) {
            for (int h = 0; h <= MAX_GOALS; h++) {
                for (int a = 0; a <= MAX_GOALS; a++) {
                    double prob = poissonProb(xgHome, h) * poissonProb(xgAway, a);
                    if (prob < MIN_PROB_THRESHOLD) continue;

                    // Per-bookmaker tiny spread (±1%)
                    double spread = 1.0 + (rng.nextDouble() * 0.02 - 0.01);
                    double odd = clamp(applyMargin(prob * spread));

                    String selection = h + "-" + a;
                    odds.add(buildEntry(bk, "correct_score", selection, odd));
                }
            }
        }

        log.debug("generateCorrectScoreOdds: {} vs {} | xgHome={} xgAway={} | {} lines generated",
                homeTeam, awayTeam, round2(xgHome), round2(xgAway), odds.size() / BOOKMAKERS.size());
        return odds;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Poisson PMF: P(X = k) = e^(-λ) * λ^k / k!
     */
    private static double poissonProb(double lambda, int k) {
        if (lambda <= 0) return k == 0 ? 1.0 : 0.0;
        double logP = -lambda + k * Math.log(lambda) - logFactorial(k);
        return Math.exp(logP);
    }

    private static double logFactorial(int n) {
        double result = 0;
        for (int i = 2; i <= n; i++) result += Math.log(i);
        return result;
    }

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
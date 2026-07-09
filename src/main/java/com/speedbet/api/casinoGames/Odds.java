package com.speedbet.api.casinoGames;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

public record Odds(BigDecimal home, BigDecimal draw, BigDecimal away, BigDecimal over, BigDecimal under) {

    private static final BigDecimal MARGIN_FACTOR = BigDecimal.valueOf(0.93); // ~7% house margin

    /** Generates a fresh odds set for a new round. Independent of team strength on purpose —
     *  strength only feeds the simulator, never the priced odds, so payout math stays honest. */
    public static Odds generate() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double homeWinProb = 0.38 + r.nextDouble() * 0.12;
        double drawProb = 0.27 + r.nextDouble() * 0.06;
        double awayWinProb = 1 - homeWinProb - drawProb;

        return new Odds(
            toOdds(homeWinProb),
            toOdds(drawProb),
            toOdds(awayWinProb),
            round(1.70 + r.nextDouble() * 0.40),
            round(1.90 + r.nextDouble() * 0.50)
        );
    }

    private static BigDecimal toOdds(double prob) {
        return MARGIN_FACTOR.divide(BigDecimal.valueOf(prob), 4, RoundingMode.HALF_UP)
                             .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal forBetType(BetType type) {
        return switch (type) {
            case HOME -> home;
            case DRAW -> draw;
            case AWAY -> away;
            case OVER -> over;
            case UNDER -> under;
        };
    }
}
package com.speedbet.api.casinoGames;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Produces the final outcome for a round the instant a bet is placed —
 * not while it's "running" on the client. The result is fixed server-side
 * at creation time; the client only learns it via settle(). There is no
 * path by which a client-supplied score can influence payout.
 */
public final class MatchSimulator {

    private MatchSimulator() {}

    public static MatchOutcome simulate(Team home, Team away) {
        ThreadLocalRandom r = ThreadLocalRandom.current();

        double homeLambda = Math.max(0.2, 1.1 + (home.strength() - away.strength()) * 1.5);
        double awayLambda = Math.max(0.2, 0.9 + (away.strength() - home.strength()) * 1.5);

        int homeScore = poisson(homeLambda, r);
        int awayScore = poisson(awayLambda, r);

        return new MatchOutcome(home, away, homeScore, awayScore);
    }

    private static int poisson(double lambda, ThreadLocalRandom r) {
        double l = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return Math.min(k - 1, 6); // cap goals for sanity
    }

    public record MatchOutcome(Team home, Team away, int homeScore, int awayScore) {
        public boolean isOver2_5() {
            return (homeScore + awayScore) > 2.5;
        }

        public boolean wins(BetType betType) {
            return switch (betType) {
                case HOME -> homeScore > awayScore;
                case DRAW -> homeScore == awayScore;
                case AWAY -> homeScore < awayScore;
                case OVER -> isOver2_5();
                case UNDER -> !isOver2_5();
            };
        }
    }
}
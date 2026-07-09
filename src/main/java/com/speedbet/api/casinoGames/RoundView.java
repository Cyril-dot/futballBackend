package com.speedbet.api.casinoGames;

import java.math.BigDecimal;

public record RoundView(
    String roundId,
    String homeTeam,
    String awayTeam,
    BigDecimal stake,
    BetType betType,
    BigDecimal odds,
    boolean settled
) {
    public static RoundView of(FootballRound r) {
        return new RoundView(r.id, r.home.name(), r.away.name(), r.stake, r.betType, r.oddsAtBet, r.isSettled());
    }
}
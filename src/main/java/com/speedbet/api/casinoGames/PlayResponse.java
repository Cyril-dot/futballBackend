package com.speedbet.api.casinoGames;

import java.math.BigDecimal;

public record PlayResponse(
    String roundId,
    BigDecimal walletBalance,
    BigDecimal stake,
    BetType betType,
    BigDecimal odds,
    String homeTeam,
    String awayTeam,
    int matchDurationSeconds
) {}
package com.speedbet.api.casinoGames;

import java.math.BigDecimal;

public record SettleResponse(
    boolean won,
    int homeScore,
    int awayScore,
    BigDecimal payout,
    BigDecimal newBalance
) {}
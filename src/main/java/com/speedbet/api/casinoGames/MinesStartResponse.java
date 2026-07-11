package com.speedbet.api.casinoGames;

import java.math.BigDecimal;

public record MinesStartResponse(
        String roundId,
        String commitHash,
        BigDecimal walletBalance,
        BigDecimal stake,
        int bombCount,
        int diamondsFound,
        BigDecimal multiplier,
        BigDecimal potentialPayout
) {}
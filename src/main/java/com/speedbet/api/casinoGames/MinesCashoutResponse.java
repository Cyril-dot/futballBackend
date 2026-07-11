package com.speedbet.api.casinoGames;

import java.math.BigDecimal;
import java.util.List;

public record MinesCashoutResponse(
        BigDecimal payout,
        BigDecimal multiplier,
        List<Integer> bombPositions,
        String serverSeed,
        BigDecimal newBalance
) {}
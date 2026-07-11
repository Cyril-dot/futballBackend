package com.speedbet.api.casinoGames;

import java.math.BigDecimal;
import java.time.Instant;

public record MinesHistoryEntry(
        int bombCount,
        int diamondsFound,
        boolean won,
        BigDecimal profit,
        Instant settledAt
) {}
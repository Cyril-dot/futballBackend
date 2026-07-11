package com.speedbet.api.casinoGames;

public record MinesRevealRequest(
        String roundId,
        Integer tileIdx
) {}
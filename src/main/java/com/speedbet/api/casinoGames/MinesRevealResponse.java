package com.speedbet.api.casinoGames;

import java.math.BigDecimal;
import java.util.List;

/**
 * bombPositions and serverSeed are null on a SAFE reveal (nothing to
 * confess yet) and populated on BOMB / AUTO_WIN, same idea as
 * res.serverSeed in the football/mines frontends — the client can hash
 * serverSeed + userId + roundId and compare it against the commitHash
 * it was given at start() to confirm the layout wasn't changed after
 * the fact.
 */
public record MinesRevealResponse(
        RevealResult result,
        int diamondsFound,
        BigDecimal multiplier,
        BigDecimal potentialPayout,
        List<Integer> bombPositions,
        String serverSeed,
        BigDecimal payout,
        BigDecimal newBalance
) {}
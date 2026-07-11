package com.speedbet.api.casinoGames;

import java.math.BigDecimal;

/**
 * firstTileIdx is OPTIONAL:
 *   - present  → that tile is guaranteed not to be a bomb and is
 *                revealed immediately (the game.js "pick a tile to
 *                start" flow).
 *   - absent   → the round opens with nothing revealed yet, for UIs
 *                with a separate "Place Bet" step before any tile
 *                click (the React lobby widget's flow).
 */
public record MinesStartRequest(
        BigDecimal stake,
        Integer bombCount,
        Integer firstTileIdx
) {}
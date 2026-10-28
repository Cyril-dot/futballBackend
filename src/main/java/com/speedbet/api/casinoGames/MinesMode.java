package com.speedbet.api.casinoGames;

/**
 * Mines bomb-layout mode, set by a super admin.
 * AUTOMATIC: every round draws its bombs randomly (the original behavior).
 * MANUAL: every round uses the stored fixed bomb positions.
 */
public enum MinesMode {
    AUTOMATIC,
    MANUAL
}

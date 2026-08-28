package com.speedbet.api.bet.cashout;

/**
 * Whether cashout is currently available for a given bet.
 *
 * AVAILABLE   — user can cash out (full or partial)
 * LOCKED      — temporarily unavailable (odds refresh, goal event, etc.)
 * UNAVAILABLE — permanently unavailable (match ended, bet settled, etc.)
 */
public enum CashoutStatus {
    AVAILABLE,
    LOCKED,
    UNAVAILABLE
}
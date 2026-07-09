package com.speedbet.api.casinoGames;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class FootballRound {

    public final String id;              // UUID string, doubles as the wallet providerRef suffix
    public final UUID userId;
    public final BigDecimal stake;
    public final BetType betType;
    public final BigDecimal oddsAtBet;
    public final Team home;
    public final Team away;
    public final MatchSimulator.MatchOutcome outcome; // fixed at creation, hidden until settle
    public final Instant createdAt;

    private volatile boolean settled = false;

    public FootballRound(String id, UUID userId, BigDecimal stake, BetType betType,
                          BigDecimal oddsAtBet, Team home, Team away,
                          MatchSimulator.MatchOutcome outcome) {
        this.id = id;
        this.userId = userId;
        this.stake = stake;
        this.betType = betType;
        this.oddsAtBet = oddsAtBet;
        this.home = home;
        this.away = away;
        this.outcome = outcome;
        this.createdAt = Instant.now();
    }

    public boolean isSettled() {
        return settled;
    }

    /** Flips settled atomically; returns false if it was already settled
     *  (so the service layer can reject a second settle attempt outright,
     *  before it even reaches the wallet's own providerRef idempotency check). */
    public synchronized boolean markSettled() {
        if (settled) return false;
        settled = true;
        return true;
    }
}
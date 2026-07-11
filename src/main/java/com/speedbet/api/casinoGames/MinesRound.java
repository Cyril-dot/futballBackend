package com.speedbet.api.casinoGames;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One in-flight (or just-finished) mines round. Mirrors FootballRound:
 * the entire bomb layout is decided and frozen at start() time — reveal()
 * and cashout() only ever uncover what was already decided, never
 * re-roll anything.
 */
public class MinesRound {

    public final String id;
    public final UUID userId;
    public final BigDecimal stake;
    public final int bombCount;
    public final Set<Integer> bombPositions;
    public final String serverSeed;
    public final String commitHash;
    public final Instant createdAt = Instant.now();

    private final Set<Integer> revealed = new HashSet<>();
    private final AtomicBoolean settled = new AtomicBoolean(false);

    public MinesRound(String id, UUID userId, BigDecimal stake, int bombCount,
                       Set<Integer> bombPositions, String serverSeed, String commitHash) {
        this.id = id;
        this.userId = userId;
        this.stake = stake;
        this.bombCount = bombCount;
        this.bombPositions = bombPositions;
        this.serverSeed = serverSeed;
        this.commitHash = commitHash;
    }

    public synchronized boolean isRevealed(int idx) {
        return revealed.contains(idx);
    }

    public synchronized void addRevealed(int idx) {
        revealed.add(idx);
    }

    public synchronized int diamondsFound() {
        return revealed.size();
    }

    public boolean isBomb(int idx) {
        return bombPositions.contains(idx);
    }

    /**
     * Same race-guard pattern as FootballRound.markSettled() — whichever
     * caller (reveal, cashout, or the abandoned-round sweep) wins the
     * race does the settling; everyone else gets false / skips.
     */
    public boolean markSettled() {
        return settled.compareAndSet(false, true);
    }

    public boolean isSettled() {
        return settled.get();
    }
}
package com.speedbet.api.casinoGames;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Same shape as football's RoundStore. Swap InMemoryMinesRoundStore for
 * a JPA-backed implementation if RoundStore is persistent — this
 * interface is the seam to do that without touching MinesGameService.
 */
public interface MinesRoundStore {
    void save(MinesRound round);

    Optional<MinesRound> find(String roundId);

    List<MinesRound> findStaleOpenRounds(Duration olderThan);

    void addHistory(UUID userId, MinesHistoryEntry entry);

    List<MinesHistoryEntry> history(UUID userId, int limit);
}
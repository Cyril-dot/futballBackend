package com.speedbet.api.casinoGames;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal in-memory MinesRoundStore so everything compiles and runs
 * out of the box. This is NOT durable across restarts or multiple
 * instances — replace with a JPA-backed store (same shape as whatever
 * actually backs football's RoundStore) before relying on it in
 * production. Kept intentionally dumb so the swap is a one-file change.
 */
@Component
public class InMemoryMinesRoundStore implements MinesRoundStore {

    private final ConcurrentHashMap<String, MinesRound> rounds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<MinesHistoryEntry>> historyByUser = new ConcurrentHashMap<>();

    @Override
    public void save(MinesRound round) {
        rounds.put(round.id, round);
    }

    @Override
    public Optional<MinesRound> find(String roundId) {
        return Optional.ofNullable(rounds.get(roundId));
    }

    @Override
    public List<MinesRound> findStaleOpenRounds(Duration olderThan) {
        Instant cutoff = Instant.now().minus(olderThan);
        return rounds.values().stream()
                .filter(r -> !r.isSettled() && r.createdAt.isBefore(cutoff))
                .toList();
    }

    @Override
    public void addHistory(UUID userId, MinesHistoryEntry entry) {
        historyByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(0, entry);
    }

    @Override
    public List<MinesHistoryEntry> history(UUID userId, int limit) {
        var list = historyByUser.getOrDefault(userId, new CopyOnWriteArrayList<>());
        return list.subList(0, Math.min(limit, list.size()));
    }
}
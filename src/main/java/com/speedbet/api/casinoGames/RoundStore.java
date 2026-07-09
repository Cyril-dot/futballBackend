package com.speedbet.api.casinoGames;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory round + per-user history. No JPA entities, per requirement —
 * the wallet ledger (Transaction table) is already your source of truth
 * for money movement; this store only tracks the ephemeral "which match
 * is this round about, and what did it resolve to" state needed between
 * play() and settle(). If you later want rounds to survive a restart,
 * swap the two maps below for Redis — the call sites don't change.
 */
@Component
public class RoundStore {

    private final Map<String, FootballRound> rounds = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<HistoryEntry>> historyByUser = new ConcurrentHashMap<>();

    public void save(FootballRound round) {
        rounds.put(round.id, round);
    }

    public Optional<FootballRound> find(String roundId) {
        return Optional.ofNullable(rounds.get(roundId));
    }

    public Optional<FootballRound> findLatestOpenForUser(UUID userId) {
        return rounds.values().stream()
            .filter(r -> r.userId.equals(userId) && !r.isSettled())
            .max((a, b) -> a.createdAt.compareTo(b.createdAt));
    }

    public void addHistory(UUID userId, HistoryEntry entry) {
        var list = historyByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(0, entry);
        while (list.size() > 50) list.remove(list.size() - 1);
    }

    public List<HistoryEntry> history(UUID userId, int limit) {
        return historyByUser.getOrDefault(userId, new CopyOnWriteArrayList<>())
            .stream().limit(limit).toList();
    }

    /** Sweep settled rounds older than an hour so the map doesn't grow unbounded.
     *  Unsettled rounds are deliberately left alone here — see README for the
     *  auto-loss-settle job you should add before going live. */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    void sweepStale() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        rounds.values().removeIf(r -> r.isSettled() && r.createdAt.isBefore(cutoff));
    }

    public record HistoryEntry(String home, String away, String score, boolean won, Instant at) {}
}
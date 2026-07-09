package com.speedbet.api.casinoGames;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** ALL open rounds for a user, newest first — used to resume every
     *  live bet after a page reload, not just the latest one. */
    public List<FootballRound> findAllOpenForUser(UUID userId) {
        return rounds.values().stream()
                .filter(r -> r.userId.equals(userId) && !r.isSettled())
                .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                .toList();
    }

    /** Open rounds older than maxAge, across ALL users — feeds the
     *  auto-settle job in FootballGameService so an abandoned bet
     *  (tab closed, app crashed, client never calls settle()) doesn't
     *  sit forever with its stake already debited and nothing resolved. */
    public List<FootballRound> findStaleOpenRounds(java.time.Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        return rounds.values().stream()
                .filter(r -> !r.isSettled() && r.createdAt.isBefore(cutoff))
                .toList();
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

    /** Sweep settled rounds older than an hour so the map doesn't grow
     *  unbounded. Safe to also catch anything the auto-settle job below
     *  handles, since by the time it's an hour old it'll be settled either
     *  way (auto-settle runs on a much shorter grace period). */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    void sweepStale() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        rounds.values().removeIf(r -> r.isSettled() && r.createdAt.isBefore(cutoff));
    }

    public record HistoryEntry(String home, String away, String score, boolean won, Instant at) {}
}
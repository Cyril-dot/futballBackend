package com.speedbet.api.livescore;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.match.Sport;
import com.speedbet.api.sportsdata.EspnFootballDataService;
import com.speedbet.api.sportsdata.EspnFootballDataService.EspnLeague;
import com.speedbet.api.sportsdata.EspnFootballDataService.EspnCup;
import com.speedbet.api.sportsdata.odds.OddsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveScorePoller {

    private final EspnFootballDataService espnService;
    private final MatchService            matchService;
    private final OddsPersistenceService  oddsPersistenceService;
    private final CacheManager            cacheManager;

    private static final long FOUR_HOURS_MS                  = 4 * 60 * 60_000L;
    private static final long SKIP_FINISHED_OLDER_THAN_HOURS = 24;
    private static final int  ODDS_CHUNK_SIZE                 = 20;

    // ── Champions League circuit breaker ──────────────────────────────────
    private final AtomicInteger clFailureCount = new AtomicInteger(0);
    private volatile Instant    clBackoffUntil = Instant.EPOCH;
    private static final int    CL_FAILURE_THRESH  = 3;
    private static final long   CL_BACKOFF_MINUTES = 10;

    // ── Poll re-entrancy guard ────────────────────────────────────────────
    private final AtomicInteger activePollCount = new AtomicInteger(0);

    // ── Bounded Caffeine caches (self-limiting even if sweep crashes) ─────
    private final Cache<String, Boolean> confirmedFinishedIds =
            Caffeine.newBuilder()
                    .maximumSize(500)
                    .expireAfterWrite(15, TimeUnit.MINUTES)
                    .build();

    private final Cache<String, Boolean> oddsPersistedIds =
            Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(10, TimeUnit.MINUTES)
                    .build();

    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long msSinceKickoff = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return msSinceKickoff >= 0 && msSinceKickoff <= FOUR_HOURS_MS;
    }

    private static boolean isStaleHistoricalFinished(Match m) {
        if (!"FINISHED".equals(m.getStatus())) return false;
        if (m.getKickoffAt() == null) return true;
        return m.getKickoffAt().isBefore(
                Instant.now().minus(SKIP_FINISHED_OLDER_THAN_HOURS, ChronoUnit.HOURS));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEAGUE / CUP NAME RESOLVER
    // ═══════════════════════════════════════════════════════════════════════

    private static String resolveLeagueName(Map<String, Object> event) {
        String rawName = EspnFootballDataService.extractCompetitionName(event);
        for (EspnLeague league : EspnLeague.values()) {
            if (league.displayName().equalsIgnoreCase(rawName)) return league.displayName();
        }
        for (EspnCup cup : EspnCup.values()) {
            if (cup.displayName().equalsIgnoreCase(rawName)) return cup.displayName();
        }
        return rawName;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER — Champions League
    // ═══════════════════════════════════════════════════════════════════════

    private boolean isChampionsLeagueBlocked(boolean callFailed) {
        if (callFailed) {
            int failures = clFailureCount.incrementAndGet();
            if (failures >= CL_FAILURE_THRESH && clBackoffUntil.isBefore(Instant.now())) {
                clBackoffUntil = Instant.now().plus(CL_BACKOFF_MINUTES, ChronoUnit.MINUTES);
                log.warn("CL circuit breaker: {} consecutive failures — backing off {}min until {}",
                        failures, CL_BACKOFF_MINUTES, clBackoffUntil);
            }
        } else {
            if (clFailureCount.get() > 0) {
                log.info("CL circuit breaker: reset after successful call");
                clFailureCount.set(0);
                clBackoffUntil = Instant.EPOCH;
            }
        }
        return clBackoffUntil.isAfter(Instant.now());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. LIVE SCORES — every 30 seconds
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        if (activePollCount.get() > 0) {
            log.warn("Live poll: previous poll still running — skipping this tick");
            return;
        }
        activePollCount.incrementAndGet();
        try {
            pollLiveScoresInternal();
        } finally {
            activePollCount.decrementAndGet();
        }
    }

    private void pollLiveScoresInternal() {
        log.debug("=== Live score poll starting ===");
        try {
            List<Map<String, Object>> allLive = new ArrayList<>(espnService.getAllLiveMatchesToday());
            log.info("Live poll: primary returned {} live event(s)", allLive.size());

            if (allLive.isEmpty()) {
                log.debug("Live poll: primary empty — falling back to per-league/cup calls");

                for (EspnLeague league : EspnLeague.values()) {
                    if (isChampionsLeagueCup(league) && isChampionsLeagueBlocked(false)) continue;
                    try {
                        List<Map<String, Object>> leagueLive = espnService.getLiveMatches(league);
                        if (isChampionsLeagueCup(league)) isChampionsLeagueBlocked(false);
                        if (!leagueLive.isEmpty()) allLive.addAll(leagueLive);
                    } catch (Exception e) {
                        if (isChampionsLeagueCup(league)) isChampionsLeagueBlocked(true);
                        log.warn("Live poll [fallback]: error fetching {} — {}", league.displayName(), e.getMessage());
                    }
                }

                for (EspnCup cup : EspnCup.values()) {
                    if (isChampionsLeagueCup(cup) && isChampionsLeagueBlocked(false)) continue;
                    try {
                        List<Map<String, Object>> cupLive = espnService.getCupLiveMatches(cup);
                        if (isChampionsLeagueCup(cup)) isChampionsLeagueBlocked(false);
                        if (!cupLive.isEmpty()) allLive.addAll(cupLive);
                    } catch (Exception e) {
                        if (isChampionsLeagueCup(cup)) isChampionsLeagueBlocked(true);
                        log.warn("Live poll [fallback]: error fetching {} — {}", cup.displayName(), e.getMessage());
                    }
                }
            }

            allLive = deduplicateByEventId(allLive);

            if (allLive.isEmpty()) {
                log.info("Live poll: no live matches found.");
            } else {
                log.info("Live poll: {} deduplicated event(s) to classify.", allLive.size());
                int updated = 0, skipped = 0, demoted = 0;

                for (Map<String, Object> event : allLive) {
                    String rawId = "espn-" + EspnFootballDataService.extractEventId(event);
                    if (confirmedFinishedIds.getIfPresent(rawId) != null) { skipped++; continue; }
                    try {
                        Match m = mapEspnEventToMatch(event);
                        if (m != null) {
                            Match persisted = matchService.saveOrUpdate(m);
                            if ("LIVE".equals(m.getStatus()) && "FINISHED".equals(persisted.getStatus())) {
                                confirmedFinishedIds.put(m.getExternalId(), true);
                                skipped++;
                            } else if ("LIVE".equals(persisted.getStatus())) {
                                updated++;
                            } else if ("FINISHED".equals(persisted.getStatus())) {
                                demoted++;
                            }
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Live poll: failed event id={} — {}",
                                EspnFootballDataService.extractEventId(event), e.getMessage());
                    }
                }

                log.info("Live poll: done — live={}, demoted={}, skipped={}.", updated, demoted, skipped);
                if (demoted > 0) evictMatchCaches();
            }
        } catch (Exception e) {
            log.error("Live poll: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== Live score poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. TODAY'S FIXTURES — every 15 minutes
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 10_000L)
    public void pollTodaysFixtures() {
        log.info("=== Today's fixtures poll starting for date={} ===", LocalDate.now());
        try {
            Map<String, List<Map<String, Object>>> byStatus = espnService.getAllMatchesTodayByStatus();

            List<Map<String, Object>> allToday = deduplicateByEventId(
                    java.util.stream.Stream.of(
                                    byStatus.getOrDefault("live",     List.of()),
                                    byStatus.getOrDefault("upcoming", List.of()),
                                    byStatus.getOrDefault("finished", List.of()))
                            .flatMap(Collection::stream)
                            .collect(java.util.stream.Collectors.toList()));

            log.info("Today poll: {} deduplicated event(s) to process.", allToday.size());

            int saved = 0, skipped = 0;
            for (Map<String, Object> event : allToday) {
                try {
                    Match m = mapEspnEventToMatch(event);
                    if (m == null) { skipped++; continue; }
                    if (isStaleHistoricalFinished(m)) { skipped++; continue; }
                    Match persisted = matchService.saveOrUpdate(m);
                    if ("UPCOMING".equals(persisted.getStatus()) || "SCHEDULED".equals(persisted.getStatus())) {
                        persistOddsIfNeeded(persisted, "Today poll");
                    }
                    saved++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("Today poll: failed event id={} — {}",
                            EspnFootballDataService.extractEventId(event), e.getMessage());
                }
            }

            log.info("Today poll: done — saved={}, skipped={}.", saved, skipped);
            evictMatchCaches();
        } catch (Exception e) {
            log.error("Today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Today's fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. UPCOMING FIXTURES (next 3 days) — every hour
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 5 * 60_000L)
    public void pollUpcomingFixtures() {
        log.info("=== Upcoming fixtures poll starting ===");
        try {
            Map<String, List<Map<String, Object>>> byDate = espnService.getUpcomingFixturesNext7Days();
            log.info("Upcoming poll: {} date(s) with fixtures", byDate.size());

            int totalSaved = 0, totalSkipped = 0, oddsGenerated = 0;

            for (Map.Entry<String, List<Map<String, Object>>> entry : byDate.entrySet()) {
                List<List<Map<String, Object>>> chunks = partition(entry.getValue(), ODDS_CHUNK_SIZE);
                for (List<Map<String, Object>> chunk : chunks) {
                    for (Map<String, Object> event : chunk) {
                        try {
                            Match m = mapEspnFixtureToMatch(event);
                            if (m != null && m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                                Match persisted = matchService.saveOrUpdate(m);
                                persistOddsIfNeeded(persisted, "Upcoming poll");
                                oddsGenerated++;
                                totalSaved++;
                            } else {
                                totalSkipped++;
                            }
                        } catch (Exception e) {
                            totalSkipped++;
                            log.warn("Upcoming poll: failed fixture id={} — {}",
                                    EspnFootballDataService.extractEventId(event), e.getMessage());
                        }
                    }
                    Thread.yield();
                }
            }

            log.info("Upcoming poll: done — saved={}, skipped={}, oddsGenerated={}",
                    totalSaved, totalSkipped, oddsGenerated);
            evictUpcomingCaches();
            evictMatchCaches();
        } catch (Exception e) {
            log.error("Upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Upcoming fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. STALE LIVE SWEEP — every 10 minutes
    //    Also drives automatic ESPN cache management on each cycle.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 5 * 60_000L)
    public void sweepStaleLiveMatches() {
        log.debug("=== Stale LIVE sweep starting ===");
        try {
            // Force-finish any matches stuck in LIVE for >4 hours
            Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
            int closed = matchService.finishStaleLiveMatches(cutoff);
            if (closed > 0) {
                log.info("Stale sweep: force-finished {} LIVE match(es).", closed);
                evictMatchCaches();
            } else {
                log.debug("Stale sweep: no stale LIVE matches found.");
            }

            // Clear the full ESPN Caffeine cache every sweep cycle
            clearEspnCache();

            // Selectively invalidate the highest-churn keys so they're
            // re-fetched fresh on the next poll rather than served stale
            invalidateHighChurnKeys();

            // Explicitly free bounded caches sooner than their TTL
            long confirmedSize = confirmedFinishedIds.estimatedSize();
            if (confirmedSize > 0) {
                confirmedFinishedIds.invalidateAll();
                log.info("Stale sweep: cleared ~{} confirmed-finished id(s).", confirmedSize);
            }

            long oddsSize = oddsPersistedIds.estimatedSize();
            if (oddsSize > 0) {
                oddsPersistedIds.invalidateAll();
                log.debug("Stale sweep: cleared ~{} oddsPersistedIds entries.", oddsSize);
            }

        } catch (Exception e) {
            log.error("Stale sweep: error — {}", e.getMessage(), e);
        }
        log.debug("=== Stale LIVE sweep complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. LIVE ODDS REFRESH — every 2 minutes
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 2 * 60_000L, initialDelay = 15_000L)
    public void refreshLiveOdds() {
        log.debug("=== Live odds refresh starting ===");
        try {
            List<Match> liveMatches = matchService.getLiveMatches();
            if (liveMatches.isEmpty()) { log.debug("Live odds refresh: no live matches."); return; }

            log.info("Live odds refresh: {} live match(es).", liveMatches.size());
            matchService.refreshLiveOddsCache(liveMatches);

            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                try {
                    oddsPersistenceService.generateAndSaveLiveOdds(match);
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Live odds refresh: DB save failed matchId={} — {}", match.getId(), e.getMessage());
                }
            }
            log.info("Live odds refresh: persisted={}/{}, failed={}", persisted, liveMatches.size(), failed);
        } catch (Exception e) {
            log.warn("Live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== Live odds refresh complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CACHE MANAGEMENT — called automatically by sweepStaleLiveMatches
    // Also public for manual admin endpoint use if needed
    // ═══════════════════════════════════════════════════════════════════════

    public void clearEspnCache() {
        log.info("clearEspnCache: clearing all ESPN cache entries");
        espnService.clearCache();
        log.info("clearEspnCache: done");
    }

    public void invalidateEspnCacheKey(String key) {
        log.info("invalidateEspnCacheKey: key='{}'", key);
        espnService.invalidateCache(key);
        log.info("invalidateEspnCacheKey: done for key='{}'", key);
    }

    private void invalidateHighChurnKeys() {
        List<String> keys = List.of(
                "live:top6:all",
                "live:top6cups:all",
                "live:uefa-clubs:all",
                "today:all:live",
                "today:all:upcoming",
                "today:all:finished",
                "today:top6:all",
                "today:all-leagues",
                "today:all-cups",
                "upcoming:top6:all",
                "upcoming:next3days:flat"
        );
        int invalidated = 0;
        for (String key : keys) {
            try { espnService.invalidateCache(key); invalidated++; }
            catch (Exception e) { log.debug("invalidateHighChurnKeys: failed key='{}' — {}", key, e.getMessage()); }
        }
        log.info("invalidateHighChurnKeys: invalidated {}/{} keys", invalidated, keys.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CACHE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private void evictUpcomingCaches() {
        List<String> names = List.of("matches", "futureMatches");
        int cleared = 0;
        for (String name : names) {
            org.springframework.cache.Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("evictUpcomingCaches: {}/{} caches cleared.", cleared, names.size());
    }

    private void evictMatchCaches() {
        List<String> names = List.of("matches", "todayMatches", "futureMatches", "featuredMatches");
        int cleared = 0;
        for (String name : names) {
            org.springframework.cache.Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("evictMatchCaches: {}/{} caches cleared.", cleared, names.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ODDS PERSISTENCE HELPER
    // ═══════════════════════════════════════════════════════════════════════

    private void persistOddsIfNeeded(Match persisted, String caller) {
        String key = persisted.getExternalId() != null
                ? persisted.getExternalId()
                : persisted.getId().toString();
        if (oddsPersistedIds.getIfPresent(key) != null) {
            log.debug("{}: odds already persisted this cycle for externalId={} — skipping", caller, key);
            return;
        }
        try {
            oddsPersistenceService.generateAndSaveAllOdds(persisted);
            oddsPersistedIds.put(key, true);
        } catch (Exception oe) {
            log.warn("{}: odds save failed matchId={} — {}", caller, persisted.getId(), oe.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Map<String, Object>> deduplicateByEventId(List<Map<String, Object>> events) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String id = EspnFootballDataService.extractEventId(event);
            if (!id.isBlank() && seen.add(id)) result.add(event);
        }
        return result;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size)
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        return parts;
    }

    private static boolean isChampionsLeagueCup(EspnLeague league) {
        return league.displayName().toLowerCase().contains("champions league");
    }

    private static boolean isChampionsLeagueCup(EspnCup cup) {
        return cup.displayName().toLowerCase().contains("champions league");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ═══════════════════════════════════════════════════════════════════════

    private Match mapEspnEventToMatch(Map<String, Object> event) {
        if (event == null) return null;
        String externalId = EspnFootballDataService.extractEventId(event);
        if (externalId == null || externalId.isBlank()) return null;
        if (EspnFootballDataService.isUpcoming(event)) return null;

        Match match = new Match();
        match.setExternalId("espn-" + externalId);
        match.setSource(MatchSource.ESPN);
        match.setSportEnum(Sport.FOOTBALL);
        match.setSport("football");

        Instant kickoff = parseKickoff(event);
        match.setKickoffAt(kickoff);

        if (EspnFootballDataService.isFinished(event)) {
            match.setStatus("FINISHED");
        } else if (EspnFootballDataService.isLive(event)) {
            if (kickoff == null || isGenuinelyLive(kickoff)) {
                match.setStatus("LIVE");
            } else {
                log.warn("mapEspnEventToMatch: demoting stale LIVE to FINISHED espn-{} {} vs {} kickoff={}",
                        externalId,
                        EspnFootballDataService.extractHomeName(event),
                        EspnFootballDataService.extractAwayName(event),
                        kickoff);
                match.setStatus("FINISHED");
            }
        } else {
            match.setStatus("UPCOMING");
        }

        match.setHomeTeam(EspnFootballDataService.extractHomeName(event));
        match.setAwayTeam(EspnFootballDataService.extractAwayName(event));
        match.setHomeLogo(EspnFootballDataService.extractHomeLogo(event));
        match.setAwayLogo(EspnFootballDataService.extractAwayLogo(event));
        match.setLeague(resolveLeagueName(event));

        String scoreStr = EspnFootballDataService.extractScore(event);
        if (scoreStr.contains("-")) {
            String[] parts = scoreStr.split("-");
            if (parts.length == 2) {
                try { match.setScoreHome(Integer.parseInt(parts[0].trim())); } catch (NumberFormatException ignored) {}
                try { match.setScoreAway(Integer.parseInt(parts[1].trim())); } catch (NumberFormatException ignored) {}
            }
        }

        return match;
    }

    private Match mapEspnFixtureToMatch(Map<String, Object> event) {
        if (event == null) return null;
        String externalId = EspnFootballDataService.extractEventId(event);
        if (externalId == null || externalId.isBlank()) return null;

        String homeName = EspnFootballDataService.extractHomeName(event);
        String awayName = EspnFootballDataService.extractAwayName(event);
        if (homeName.isBlank() || awayName.isBlank()) return null;

        Match match = new Match();
        match.setExternalId("espn-" + externalId);
        match.setSource(MatchSource.ESPN);
        match.setSport("football");
        match.setStatus("UPCOMING");
        match.setSportEnum(Sport.FOOTBALL);
        match.setHomeTeam(homeName);
        match.setAwayTeam(awayName);
        match.setHomeLogo(EspnFootballDataService.extractHomeLogo(event));
        match.setAwayLogo(EspnFootballDataService.extractAwayLogo(event));
        match.setLeague(resolveLeagueName(event));
        match.setKickoffAt(parseKickoff(event));
        return match;
    }

    private static Instant parseKickoff(Map<String, Object> event) {
        String dateStr = EspnFootballDataService.extractKickoffTime(event);
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            try { return Instant.parse(dateStr.replace("Z", ":00Z")); }
            catch (Exception e2) { return null; }
        }
    }
}
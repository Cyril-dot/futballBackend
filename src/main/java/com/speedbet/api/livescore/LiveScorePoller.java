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
    private static final int  UPCOMING_DAYS_WINDOW            = 3;

    // ── Champions League circuit breaker ──────────────────────────────────
    private final AtomicInteger clFailureCount = new AtomicInteger(0);
    private volatile Instant    clBackoffUntil = Instant.EPOCH;
    private static final int    CL_FAILURE_THRESH  = 3;
    private static final long   CL_BACKOFF_MINUTES = 10;

    // ── Shared re-entrancy guard (live + today + upcoming polls) ──────────
    private final AtomicInteger activePollCount = new AtomicInteger(0);

    // ── confirmedFinishedIds: tracks matches we know are done this cycle ───
    private final Cache<String, Boolean> confirmedFinishedIds =
            Caffeine.newBuilder()
                    .maximumSize(500)
                    .expireAfterWrite(15, TimeUnit.MINUTES)
                    .build();

    // ── oddsPersistedIds: 5-min TTL so the next poll cycle retries odds
    //    quickly after a failed or missing attempt, while still preventing
    //    redundant saves within the same 15-min today-poll window.
    //    Do NOT clear this in the stale sweep — doing so was the root cause
    //    of upcoming matches losing their odds between poll cycles.
    private final Cache<String, Boolean> oddsPersistedIds =
            Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

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
                allLive = null;
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
        if (activePollCount.get() > 0) {
            log.warn("Today poll: live poll still running — skipping this tick");
            return;
        }
        activePollCount.incrementAndGet();
        try {
            pollTodaysFixturesInternal();
        } finally {
            activePollCount.decrementAndGet();
        }
    }

    private void pollTodaysFixturesInternal() {
        log.info("=== Today's fixtures poll starting for date={} ===", LocalDate.now());
        try {
            Map<String, List<Map<String, Object>>> byStatus = espnService.getAllMatchesTodayByStatus();

            List<Map<String, Object>> allToday = new ArrayList<>();
            allToday.addAll(byStatus.getOrDefault("live",     List.of()));
            allToday.addAll(byStatus.getOrDefault("upcoming", List.of()));
            allToday.addAll(byStatus.getOrDefault("finished", List.of()));
            allToday = deduplicateByEventId(allToday);

            byStatus = null;

            log.info("Today poll: {} deduplicated event(s) to process.", allToday.size());

            int saved = 0, skipped = 0;
            for (Map<String, Object> event : allToday) {
                try {
                    final Match m;
                    if (EspnFootballDataService.isUpcoming(event)) {
                        m = mapEspnFixtureToMatch(event);
                    } else {
                        m = mapEspnEventToMatch(event);
                    }

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
            allToday = null;
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
        // FIX: upstream polls (live + today) increment activePollCount.
        // The upcoming poll must also participate in the same guard so it
        // cannot race against either of them and cause dirty reads / duplicate
        // odds writes on shared state (oddsPersistedIds, DB).
        if (activePollCount.get() > 0) {
            log.warn("Upcoming poll: another poll still running — skipping this tick");
            return;
        }
        activePollCount.incrementAndGet();
        try {
            pollUpcomingFixturesInternal();
        } finally {
            activePollCount.decrementAndGet();
        }
    }

    private void pollUpcomingFixturesInternal() {
        log.info("=== Upcoming fixtures poll starting ({}d window) ===", UPCOMING_DAYS_WINDOW);
        try {
            int totalSaved = 0, totalSkipped = 0, oddsAttempted = 0;

            for (int dayOffset = 1; dayOffset <= UPCOMING_DAYS_WINDOW; dayOffset++) {
                LocalDate targetDate = LocalDate.now().plusDays(dayOffset);
                log.debug("Upcoming poll: fetching fixtures for date={}", targetDate);

                List<Map<String, Object>> dayFixtures;
                try {
                    dayFixtures = espnService.getFixturesForDate(targetDate);
                } catch (Exception e) {
                    log.warn("Upcoming poll: failed to fetch date={} — {}", targetDate, e.getMessage());
                    continue;
                }

                log.debug("Upcoming poll: {} event(s) for date={}", dayFixtures.size(), targetDate);

                for (Map<String, Object> event : dayFixtures) {
                    try {
                        Match m = mapEspnFixtureToMatch(event);

                        if (m == null) {
                            String evId = EspnFootballDataService.extractEventId(event);
                            log.debug("Upcoming poll: mapEspnFixtureToMatch returned null for event id={} date={}",
                                    evId, targetDate);
                            totalSkipped++;
                            continue;
                        }

                        // Only skip events whose kickoff is definitively in the past.
                        // Fixtures with a null kickoff are still saved — odds can be
                        // generated later once the date is resolved.
                        if (m.getKickoffAt() != null && !m.getKickoffAt().isAfter(Instant.now())) {
                            log.debug("Upcoming poll: skipping past fixture externalId={} kickoffAt={}",
                                    m.getExternalId(), m.getKickoffAt());
                            totalSkipped++;
                            continue;
                        }

                        Match persisted = matchService.saveOrUpdate(m);
                        totalSaved++;

                        persistOddsIfNeeded(persisted, "Upcoming poll");
                        oddsAttempted++;

                    } catch (Exception e) {
                        totalSkipped++;
                        log.warn("Upcoming poll: failed fixture id={} date={} — {}",
                                EspnFootballDataService.extractEventId(event), targetDate, e.getMessage());
                    }
                }

                dayFixtures = null;
            }

            log.info("Upcoming poll: done — saved={}, skipped={}, oddsAttempted={}",
                    totalSaved, totalSkipped, oddsAttempted);
            evictUpcomingCaches();
            evictMatchCaches();
        } catch (Exception e) {
            log.error("Upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Upcoming fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. STALE LIVE SWEEP — every 10 minutes
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 5 * 60_000L)
    public void sweepStaleLiveMatches() {
        log.debug("=== Stale LIVE sweep starting ===");
        try {
            Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
            int closed = matchService.finishStaleLiveMatches(cutoff);
            if (closed > 0) {
                log.info("Stale sweep: force-finished {} LIVE match(es).", closed);
                evictMatchCaches();
            } else {
                log.debug("Stale sweep: no stale LIVE matches found.");
            }

            if (activePollCount.get() == 0) {
                clearEspnCache();
                invalidateHighChurnKeys();
                log.info("Stale sweep: ESPN cache cleared (no active poll).");
            } else {
                log.info("Stale sweep: skipping ESPN cache clear — poll is active.");
            }

            // Only clear confirmedFinishedIds here.
            // oddsPersistedIds is intentionally NOT cleared — its 5-min Caffeine
            // TTL handles its own expiry.  Clearing it here was the root cause of
            // upcoming matches losing their odds between poll cycles.
            long confirmedSize = confirmedFinishedIds.estimatedSize();
            if (confirmedSize > 0) {
                confirmedFinishedIds.invalidateAll();
                log.info("Stale sweep: cleared ~{} confirmed-finished id(s).", confirmedSize);
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
    // CACHE MANAGEMENT
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

    /**
     * Generates and saves all odds for a match if not already done this cycle.
     *
     * <p>The deduplication key is the match's externalId (e.g. "espn-740963")
     * or its internal UUID as a fallback. The oddsPersistedIds cache has a
     * 5-minute TTL so that:
     * <ul>
     *   <li>Odds are not regenerated on every 30-sec live-poll tick (cache hit)</li>
     *   <li>Odds ARE retried quickly (within 5 min) after any failure</li>
     *   <li>The stale sweep no longer invalidates this cache, removing the root
     *       cause of upcoming matches losing their odds between poll cycles</li>
     * </ul>
     *
     * <p>A failed odds save does NOT put a "true" entry into oddsPersistedIds,
     * so the next poll cycle will retry correctly.
     */
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
            log.debug("{}: odds persisted for externalId={}", caller, key);
        } catch (Exception oe) {
            log.warn("{}: odds save failed matchId={} externalId={} — {}",
                    caller, persisted.getId(), key, oe.getMessage());
            // Do NOT put a "true" entry on failure — allow retry on next poll cycle.
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

    private static boolean isChampionsLeagueCup(EspnLeague league) {
        return league.displayName().toLowerCase().contains("champions league");
    }

    private static boolean isChampionsLeagueCup(EspnCup cup) {
        return cup.displayName().toLowerCase().contains("champions league");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maps a live or finished ESPN event to a Match.
     * Returns null for upcoming (state=pre) events — use mapEspnFixtureToMatch for those.
     */
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

    /**
     * Maps any ESPN event (including upcoming/pre-match) to a Match with status UPCOMING.
     * Used for today's upcoming fixtures and the multi-day upcoming poll.
     */
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
            catch (Exception e2) {
                log.warn("parseKickoff: could not parse date '{}' — fixture will be saved with null kickoff", dateStr);
                return null;
            }
        }
    }
}
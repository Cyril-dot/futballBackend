package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.match.BaseballMatchService;
import com.speedbet.api.match.Sport;
import com.speedbet.api.sportsdata.BaseballDataService;
import com.speedbet.api.sportsdata.odds.MlbOddsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled poller for MLB baseball.
 *
 * ── Poll schedule ─────────────────────────────────────────────────────────
 *
 *   pollLiveScores()       — every 30 seconds  (live game updates)
 *   pollTodaysGames()      — every 15 minutes  (today's full scoreboard)
 *   pollUpcomingGames()    — every hour         (next 3 days, pre-match)
 *   sweepStaleLiveGames()  — every 10 minutes  (force-finish stale LIVE rows)
 *   refreshLiveOdds()      — every 2 minutes   (in-play moneyline odds)
 *
 * ── Game duration guard ───────────────────────────────────────────────────
 *
 *   A game claiming to be LIVE is only accepted as genuinely live if its
 *   first-pitch time (kickoffAt) was less than 6 hours ago.  MLB games
 *   including extra innings rarely exceed 5 hours; 6 hours gives margin.
 *   Games with a null kickoff are accepted with a WARN — the stale sweep
 *   acts as the safety net.
 *
 * ── Confirmed-finished suppression ───────────────────────────────────────
 *
 *   External IDs that the DB has confirmed FINISHED but ESPN still reports
 *   as IN PLAY are added to {@code confirmedFinishedIds} and skipped on
 *   subsequent poll cycles.  The set is cleared every 10 minutes by
 *   {@link #sweepStaleLiveGames()} so genuinely rescheduled games are not
 *   suppressed indefinitely.
 *
 * ── External ID convention ────────────────────────────────────────────────
 *
 *   All ESPN MLB events are stored with prefix "espn-mlb-" + espnEventId.
 *
 * ── No draw ──────────────────────────────────────────────────────────────
 *
 *   Baseball has no draw — live odds are two-way (HOME / AWAY) only.
 *   {@link MlbOddsPersistenceService} enforces this and silently drops any
 *   DRAW selection.
 *
 * ── Memory fixes ─────────────────────────────────────────────────────────
 *
 *   - activePollCount guard applied to both live and today polls to prevent
 *     concurrent execution and double memory usage.
 *   - pollUpcomingGames fetches one day at a time (3-day window) and nulls
 *     each day's list before fetching the next, capping peak allocation.
 *   - Large lists are explicitly nulled after processing to release memory
 *     before the next cache eviction or ESPN fetch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaseballLiveScorePoller {

    private static final String  SPORT            = "baseball";
    private static final String  EXT_ID_PREFIX    = "espn-mlb-";
    private static final String  LEAGUE_NAME      = "MLB";

    /**
     * Maximum duration we consider a baseball game "genuinely live".
     * 6 hours covers a 9-inning game plus extra innings with rain delays.
     */
    private static final long SIX_HOURS_MS = 6 * 60 * 60_000L;

    // FIX: Reduced from 7 to 3 days — cuts the largest single allocation
    private static final int UPCOMING_DAYS_WINDOW = 3;

    private final BaseballDataService       baseballDataService;
    private final BaseballMatchService      baseballMatchService;
    private final MlbOddsPersistenceService mlbOddsPersistenceService;
    private final CacheManager              cacheManager;

    /**
     * ESPN event IDs confirmed FINISHED in our DB but still reported LIVE by ESPN.
     * Cleared every 10 minutes by {@link #sweepStaleLiveGames()}.
     */
    private final Set<String> confirmedFinishedIds = ConcurrentHashMap.newKeySet();

    // FIX: Shared re-entrancy guard — prevents live poll and today poll running simultaneously
    private final AtomicInteger activePollCount = new AtomicInteger(0);

    // ═════════════════════════════════════════════════════════════════════
    //  GENUINELY-LIVE GUARD
    // ═════════════════════════════════════════════════════════════════════

    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long msSince = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return msSince >= 0 && msSince <= SIX_HOURS_MS;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  1. LIVE SCORES — every 30 seconds
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        if (activePollCount.get() > 0) {
            log.warn("MLB live poll: previous poll still running — skipping this tick");
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
        log.debug("=== MLB live score poll starting ===");
        try {
            List<Map<String, Object>> liveGames = baseballDataService.getLiveGames();

            if (liveGames.isEmpty()) {
                log.info("MLB live poll: no games in progress.");
                log.debug("=== MLB live score poll complete ===");
                return;
            }

            log.info("MLB live poll: {} game(s) in progress from ESPN.", liveGames.size());

            // Diagnostic log — raw status for each event
            for (Map<String, Object> game : liveGames) {
                log.info("MLB LIVE EVENT raw: id='{}' detail='{}' home='{}' away='{}'",
                        BaseballDataService.extractGameId(game),
                        BaseballDataService.extractStatusDetail(game),
                        BaseballDataService.extractHomeCompetitor(game)
                                .map(BaseballDataService::extractTeamName).orElse("?"),
                        BaseballDataService.extractAwayCompetitor(game)
                                .map(BaseballDataService::extractTeamName).orElse("?"));
            }

            int updated = 0, skipped = 0, demoted = 0;
            for (Map<String, Object> game : liveGames) {
                String espnId     = BaseballDataService.extractGameId(game);
                String externalId = EXT_ID_PREFIX + espnId;

                if (confirmedFinishedIds.contains(externalId)) {
                    log.debug("MLB live poll: skipping confirmed-finished externalId={}", externalId);
                    skipped++;
                    continue;
                }

                try {
                    Match m = mapLiveGameToMatch(game);
                    if (m == null) { skipped++; continue; }

                    Match persisted = baseballMatchService.saveOrUpdate(m);

                    if ("LIVE".equals(m.getStatus()) && "FINISHED".equals(persisted.getStatus())) {
                        confirmedFinishedIds.add(externalId);
                        log.info("MLB live poll: confirmed-finished suppression added for externalId={} " +
                                "— ESPN feed is stuck on stale LIVE.", externalId);
                        skipped++;
                    } else if ("LIVE".equals(persisted.getStatus())) {
                        updated++;
                    } else if ("FINISHED".equals(persisted.getStatus())) {
                        demoted++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("MLB live poll: failed game espnId={} — {}", espnId, e.getMessage());
                }
            }

            log.info("MLB live poll: done — live={}, demoted-to-finished={}, skipped={}.",
                    updated, demoted, skipped);

            // FIX: null large list before cache eviction to release memory sooner
            liveGames = null;

            if (demoted > 0) {
                evictMatchCaches();
                log.info("MLB live poll: evicted match caches after {} demotion(s).", demoted);
            }

        } catch (Exception e) {
            log.error("MLB live poll: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== MLB live score poll complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  2. TODAY'S GAMES — every 15 minutes
    //     FIX: guarded by activePollCount to prevent overlap with live poll
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 10_000L)
    public void pollTodaysGames() {
        // FIX: skip if live poll is still running to prevent memory overlap
        if (activePollCount.get() > 0) {
            log.warn("MLB today poll: live poll still running — skipping this tick");
            return;
        }
        activePollCount.incrementAndGet();
        try {
            pollTodaysGamesInternal();
        } finally {
            activePollCount.decrementAndGet();
        }
    }

    private void pollTodaysGamesInternal() {
        log.info("=== MLB today's games poll starting for date={} ===", LocalDate.now());
        try {
            List<Map<String, Object>> todayGames = baseballDataService.getTodayGames();

            if (todayGames.isEmpty()) {
                log.info("MLB today poll: no games on today's scoreboard.");
                log.info("=== MLB today's games poll complete ===");
                return;
            }

            log.info("MLB today poll: {} game(s) on today's scoreboard.", todayGames.size());

            int saved = 0, skipped = 0;
            for (Map<String, Object> game : todayGames) {
                try {
                    Match m = mapGameToMatch(game);
                    if (m == null) { skipped++; continue; }

                    Match persisted = baseballMatchService.saveOrUpdate(m);

                    // Generate pre-match odds for upcoming games
                    if ("UPCOMING".equals(persisted.getStatus()) || "SCHEDULED".equals(persisted.getStatus())) {
                        String espnId = BaseballDataService.extractGameId(game);
                        try {
                            mlbOddsPersistenceService.generateAndSavePreMatchOdds(persisted, espnId);
                        } catch (Exception oe) {
                            log.warn("MLB today poll: odds save failed matchId={} espnId={} — {}",
                                    persisted.getId(), espnId, oe.getMessage());
                        }
                    }
                    saved++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("MLB today poll: failed game espnId={} — {}",
                            BaseballDataService.extractGameId(game), e.getMessage());
                }
            }

            log.info("MLB today poll: done — saved={}, skipped={}.", saved, skipped);
            // FIX: null large list before cache eviction
            todayGames = null;
            evictMatchCaches();

        } catch (Exception e) {
            log.error("MLB today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== MLB today's games poll complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  3. UPCOMING GAMES (next 3 days) — every hour
    //     FIX: window reduced from 7 → 3 days; fetch one day at a time and
    //     null each day's list before fetching the next to cap peak memory.
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 30_000L)
    public void pollUpcomingGames() {
        log.info("=== MLB upcoming games poll starting ({}d window) ===", UPCOMING_DAYS_WINDOW);
        try {
            int saved = 0, skipped = 0;
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

            // FIX: fetch one day at a time; null each batch before fetching the next
            for (int dayOffset = 1; dayOffset <= UPCOMING_DAYS_WINDOW; dayOffset++) {
                String yyyymmdd = LocalDate.now().plusDays(dayOffset).format(fmt);

                List<Map<String, Object>> dayGames;
                try {
                    dayGames = baseballDataService.getGamesByDate(yyyymmdd);
                } catch (Exception e) {
                    log.warn("MLB upcoming poll: failed to fetch date={} — {}", yyyymmdd, e.getMessage());
                    continue;
                }

                log.debug("MLB upcoming poll: {} game(s) for date={}", dayGames.size(), yyyymmdd);

                for (Map<String, Object> game : dayGames) {
                    // Only persist truly upcoming (pre-game) events
                    if (!BaseballDataService.isUpcoming(game)) continue;

                    try {
                        Match m = mapGameToMatch(game);
                        if (m == null || m.getKickoffAt() == null
                                || !m.getKickoffAt().isAfter(Instant.now())) {
                            skipped++;
                            continue;
                        }

                        Match persisted = baseballMatchService.saveOrUpdate(m);
                        String espnId  = BaseballDataService.extractGameId(game);
                        try {
                            mlbOddsPersistenceService.generateAndSavePreMatchOdds(persisted, espnId);
                        } catch (Exception oe) {
                            log.warn("MLB upcoming poll: odds failed matchId={} espnId={} — {}",
                                    persisted.getId(), espnId, oe.getMessage());
                        }
                        saved++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("MLB upcoming poll: failed game espnId={} — {}",
                                BaseballDataService.extractGameId(game), e.getMessage());
                    }
                }

                // FIX: explicitly null each day's data before fetching the next
                dayGames = null;
            }

            log.info("MLB upcoming poll: done — saved={}, skipped={}.", saved, skipped);
            evictUpcomingCaches();

        } catch (Exception e) {
            log.error("MLB upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== MLB upcoming games poll complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  4. STALE LIVE SWEEP — every 10 minutes
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 5 * 60_000L)
    public void sweepStaleLiveGames() {
        log.debug("=== MLB stale LIVE sweep starting ===");
        try {
            Instant cutoff = Instant.now().minus(6, ChronoUnit.HOURS);
            int closed = baseballMatchService.finishStaleLiveMatches(cutoff);
            if (closed > 0) {
                log.info("MLB stale sweep: force-finished {} LIVE game(s).", closed);
                evictMatchCaches();
            } else {
                log.debug("MLB stale sweep: no stale LIVE games found.");
            }

            if (!confirmedFinishedIds.isEmpty()) {
                log.info("MLB stale sweep: clearing {} confirmed-finished suppression id(s).",
                        confirmedFinishedIds.size());
                confirmedFinishedIds.clear();
            }
        } catch (Exception e) {
            log.error("MLB stale sweep: error — {}", e.getMessage(), e);
        }
        log.debug("=== MLB stale LIVE sweep complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  5. LIVE ODDS REFRESH — every 2 minutes
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 2 * 60_000L, initialDelay = 15_000L)
    public void refreshLiveOdds() {
        log.debug("=== MLB live odds refresh starting ===");
        try {
            List<Match> liveMatches = baseballMatchService.getLiveMatches();
            if (liveMatches.isEmpty()) {
                log.debug("MLB live odds refresh: no live matches, skipping.");
                log.debug("=== MLB live odds refresh complete ===");
                return;
            }

            log.info("MLB live odds refresh: {} live match(es).", liveMatches.size());

            // Refresh in-memory cache
            baseballMatchService.refreshLiveOddsCache(liveMatches);

            // Persist live odds to DB
            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                String espnId = BaseballMatchService.stripMlbPrefix(match.getExternalId());
                try {
                    mlbOddsPersistenceService.generateAndSaveLiveOdds(match, espnId);
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("MLB live odds refresh: DB save failed matchId={} espnId={} — {}",
                            match.getId(), espnId, e.getMessage());
                }
            }

            log.info("MLB live odds refresh: persisted={}/{}, failed={}",
                    persisted, liveMatches.size(), failed);

        } catch (Exception e) {
            log.warn("MLB live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== MLB live odds refresh complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CACHE HELPERS
    // ═════════════════════════════════════════════════════════════════════

    private void evictMatchCaches() {
        List<String> names = List.of("mlbTodayMatches", "mlbUpcomingMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("MLB evictMatchCaches: {}/{} caches cleared.", cleared, names.size());
    }

    private void evictUpcomingCaches() {
        Cache cache = cacheManager.getCache("mlbUpcomingMatches");
        if (cache != null) {
            cache.clear();
            log.info("MLB evictUpcomingCaches: mlbUpcomingMatches cleared.");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MAPPERS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Maps a raw ESPN MLB game event to a Match entity.
     *
     * ── Status resolution ─────────────────────────────────────────────────
     *
     *   ESPN state "post" → FINISHED  (terminal; always wins)
     *   ESPN state "in"
     *     + kickoff null or within 6h → LIVE    (warn if null)
     *     + kickoff older than 6h     → FINISHED (stale demotion + WARN)
     *   ESPN state "pre"              → UPCOMING
     *
     * Score (runs) is extracted from each competitor's score field.
     * Game time (inning + detail) is stored in metadata for use by the
     * odds generators and the display layer.
     */
    private Match mapGameToMatch(Map<String, Object> game) {
        if (game == null) return null;

        String espnId = BaseballDataService.extractGameId(game);
        if (espnId == null || espnId.isBlank()) return null;

        Optional<Map<String, Object>> homeOpt = BaseballDataService.extractHomeCompetitor(game);
        Optional<Map<String, Object>> awayOpt = BaseballDataService.extractAwayCompetitor(game);

        String homeName = homeOpt.map(BaseballDataService::extractTeamName).orElse("");
        String awayName = awayOpt.map(BaseballDataService::extractTeamName).orElse("");

        if (homeName.isBlank() || awayName.isBlank()) {
            log.debug("mapGameToMatch (MLB): blank team names for espnId={}, skipping.", espnId);
            return null;
        }

        Match match = new Match();
        match.setExternalId(EXT_ID_PREFIX + espnId);
        match.setSource(MatchSource.ESPN);
        match.setSport(SPORT);
        match.setSportEnum(Sport.BASEBALL);
        match.setLeague(LEAGUE_NAME);
        match.setHomeTeam(homeName);
        match.setAwayTeam(awayName);

        homeOpt.ifPresent(h -> match.setHomeLogo(BaseballDataService.extractTeamLogo(h)));
        awayOpt.ifPresent(a -> match.setAwayLogo(BaseballDataService.extractTeamLogo(a)));

        // ── Kickoff ───────────────────────────────────────────────────────
        String dateStr = BaseballDataService.extractGameDate(game);
        Instant kickoff = parseKickoff(dateStr, espnId);
        match.setKickoffAt(kickoff);

        // ── Status ────────────────────────────────────────────────────────
        if (BaseballDataService.isFinished(game)) {
            match.setStatus("FINISHED");

        } else if (BaseballDataService.isLive(game)) {
            if (kickoff == null || isGenuinelyLive(kickoff)) {
                if (kickoff == null) {
                    log.warn("mapGameToMatch (MLB): accepting LIVE with null kickoff espnId={} {} vs {} — stale sweep is safety net",
                            espnId, homeName, awayName);
                }
                match.setStatus("LIVE");
            } else {
                log.warn("mapGameToMatch (MLB): demoting stale LIVE to FINISHED espnId={} {} vs {} kickoff={}",
                        espnId, homeName, awayName, kickoff);
                match.setStatus("FINISHED");
            }

        } else {
            match.setStatus("UPCOMING");
        }

        // ── Score ─────────────────────────────────────────────────────────
        if (!BaseballDataService.isUpcoming(game)) {
            homeOpt.ifPresent(h -> {
                String s = BaseballDataService.extractScore(h);
                if (!s.isBlank()) {
                    try { match.setScoreHome(Integer.parseInt(s)); }
                    catch (NumberFormatException ignored) {}
                }
            });
            awayOpt.ifPresent(a -> {
                String s = BaseballDataService.extractScore(a);
                if (!s.isBlank()) {
                    try { match.setScoreAway(Integer.parseInt(s)); }
                    catch (NumberFormatException ignored) {}
                }
            });
        }

        // ── Metadata — game state for odds generators ─────────────────────
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("inning",       BaseballDataService.extractInning(game));
        metadata.put("inningHalf",   BaseballDataService.extractInningHalf(game));
        metadata.put("outs",         BaseballDataService.extractOuts(game));
        metadata.put("statusDetail", BaseballDataService.extractStatusDetail(game));
        String situation = BaseballDataService.extractSituation(game);
        if (!situation.isBlank()) metadata.put("situation", situation);
        match.setMetadata(metadata);

        return match;
    }

    /**
     * Maps a raw ESPN MLB game specifically from the live feed.
     * Identical to {@link #mapGameToMatch(Map)} but enforces that the game
     * is in STATE_IN — pre/post events from a live endpoint are skipped.
     */
    private Match mapLiveGameToMatch(Map<String, Object> game) {
        if (game == null) return null;

        // The live endpoint should only return in-progress games, but guard anyway
        if (BaseballDataService.isUpcoming(game)) {
            log.debug("mapLiveGameToMatch (MLB): skipping PRE game espnId={}",
                    BaseballDataService.extractGameId(game));
            return null;
        }

        return mapGameToMatch(game);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UTILITY
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Parses an ISO-8601 kickoff string from the ESPN event "date" field.
     * Returns null and logs a debug message if the string is absent or
     * unparseable.
     */
    private static Instant parseKickoff(String dateStr, String espnId) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            // Handle truncated ISO-8601 e.g. "2026-05-16T17:10Z" missing seconds
            try {
                return Instant.parse(dateStr.replace("Z", ":00Z"));
            } catch (Exception e2) {
                log.debug("parseKickoff (MLB): could not parse '{}' for espnId={} — {}",
                        dateStr, espnId, e2.getMessage());
                return null;
            }
        }
    }
}
package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.match.Sport;
import com.speedbet.api.sportsdata.BasketballDataService;
import com.speedbet.api.sportsdata.odds.BasketballOddsPersistenceService;
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

/**
 * Polling component for NBA live scores, today's games, upcoming fixtures,
 * stale-live cleanup, and live odds refresh.
 *
 * ── Schedule overview ────────────────────────────────────────────────────
 *
 *   pollLiveScores()       every 30 s   — live game state + score updates
 *   pollTodaysGames()      every 15 min — today's full slate (saves odds for UPCOMING)
 *   pollUpcomingFixtures() every 60 min — next 7 days per-day sweep + general sweep
 *   sweepStaleLiveGames()  every 10 min — force-finish games stuck as LIVE > 3.5 h
 *   refreshLiveOdds()      every 2 min  — regenerate odds for all live games
 *
 * ── Status mapping (ESPN → internal) ────────────────────────────────────
 *
 *   "pre"  (upcoming)  → UPCOMING  (fixture mapper) / skipped (live mapper)
 *   "in"   (live)      → LIVE      if kickoff ≤ 3.5 h ago, else FINISHED (stale demotion)
 *   "post" (finished)  → FINISHED
 *
 * ── Stale-live guard ────────────────────────────────────────────────────
 *
 *   NBA games rarely exceed 3 hours (including OT). The stale cutoff is set to
 *   3.5 hours — slightly shorter than the 4-hour football constant — so that a
 *   hung ESPN feed is caught sooner. confirmedFinishedIds is cleared by
 *   sweepStaleLiveGames() every 10 minutes so corrected fixtures are picked up.
 *
 * ── Thread safety ────────────────────────────────────────────────────────
 *
 *   confirmedFinishedIds is a ConcurrentHashMap-backed set; all scheduled
 *   methods run on Spring's single-threaded task scheduler by default, but the
 *   set is safe for any future parallel scheduling configuration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BasketballLiveScorePoller {

    private final BasketballDataService           basketballDataService;
    private final MatchService                    matchService;
    private final BasketballOddsPersistenceService oddsPersistenceService;
    private final CacheManager                    cacheManager;

    /**
     * NBA games (including OT) rarely run longer than ~3 hours.
     * Using 3.5 h as the stale-live guard; shorter than football's 4 h.
     */
    private static final long THREE_HALF_HOURS_MS = 3 * 60 * 60_000L + 30 * 60_000L;

    /** Sport discriminator used when querying the shared matches table. */
    private static final String SPORT = "basketball";

    /**
     * ESPN event IDs confirmed FINISHED in our DB but still reported LIVE
     * by ESPN's feed. Cleared by sweepStaleLiveGames() every 10 minutes.
     */
    private final Set<String> confirmedFinishedIds = ConcurrentHashMap.newKeySet();

    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long msSinceKickoff = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return msSinceKickoff >= 0 && msSinceKickoff <= THREE_HALF_HOURS_MS;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. LIVE SCORES — every 30 seconds
    //
    // Source: BasketballDataService.getLiveGames() (short-TTL cache, ~1 min).
    //
    // "pre" events are skipped — mapEspnGameToMatch returns null for upcoming
    // games so we never overwrite fixture data with a blank score.
    //
    // Confirmed-finished suppression: IDs in confirmedFinishedIds are skipped
    // immediately to prevent WARN spam when ESPN is stuck reporting a finished
    // game as LIVE.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 8_000L)
    public void pollLiveScores() {
        log.debug("=== Basketball live score poll starting ===");
        try {
            List<Map<String, Object>> liveGames = basketballDataService.getLiveGames();

            if (liveGames.isEmpty()) {
                log.info("Basketball live poll: no live games found.");
                return;
            }

            log.info("Basketball live poll: {} live game(s) to classify.", liveGames.size());

            // DIAGNOSTIC: raw status for every live event
            for (Map<String, Object> game : liveGames) {
                log.info("BASKETBALL LIVE raw: id='{}' state='{}' shortName='{}'",
                        BasketballDataService.extractGameId(game),
                        BasketballDataService.extractState(game),
                        BasketballDataService.extractShortName(game));
            }

            int updated = 0, skipped = 0, demoted = 0;

            for (Map<String, Object> game : liveGames) {
                String rawId = "espn-nba-" + BasketballDataService.extractGameId(game);

                // Skip IDs already confirmed FINISHED in our DB
                if (confirmedFinishedIds.contains(rawId)) {
                    log.debug("Basketball live poll: skipping confirmed-finished externalId={}", rawId);
                    skipped++;
                    continue;
                }

                try {
                    Match m = mapEspnGameToMatch(game);
                    if (m != null) {
                        Match persisted = matchService.saveOrUpdate(m);

                        // Detect ESPN-stuck LIVE being rejected by the DB guard
                        if ("LIVE".equals(m.getStatus()) && "FINISHED".equals(persisted.getStatus())) {
                            confirmedFinishedIds.add(m.getExternalId());
                            log.info("Basketball live poll: confirmed-finished suppression added for " +
                                            "externalId={} — ESPN feed is stuck; will skip until next stale sweep.",
                                    m.getExternalId());
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
                    log.warn("Basketball live poll: failed game id={} — {}",
                            BasketballDataService.extractGameId(game), e.getMessage());
                }
            }

            log.info("Basketball live poll: done — live={}, demoted-to-finished={}, skipped={}.",
                    updated, demoted, skipped);

            if (demoted > 0) {
                evictMatchCaches();
                log.info("Basketball live poll: evicted match caches after {} demotion(s).", demoted);
            }

        } catch (Exception e) {
            log.error("Basketball live poll: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== Basketball live score poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. TODAY'S GAMES — every 15 minutes
    //
    // Source: BasketballDataService.getTodayGames() (5-min cache TTL; poll
    // interval is 15 min so we get a fresh list on each cycle).
    //
    // Odds are generated and persisted for any UPCOMING / SCHEDULED games.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 12_000L)
    public void pollTodaysGames() {
        log.info("=== Basketball today's games poll starting for date={} ===", LocalDate.now());
        try {
            List<Map<String, Object>> todayGames = basketballDataService.getTodayGames();

            if (todayGames.isEmpty()) {
                log.info("Basketball today poll: no games returned.");
                return;
            }

            log.info("Basketball today poll: {} game(s) to process.", todayGames.size());

            int saved = 0, skipped = 0;
            for (Map<String, Object> game : todayGames) {
                try {
                    Match m = mapEspnGameToMatch(game);
                    if (m != null) {
                        Match persisted = matchService.saveOrUpdate(m);
                        if ("UPCOMING".equals(persisted.getStatus()) ||
                                "SCHEDULED".equals(persisted.getStatus())) {
                            try {
                                oddsPersistenceService.generateAndSaveAllOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Basketball today poll: odds save failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                        }
                        saved++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("Basketball today poll: failed game id={} — {}",
                            BasketballDataService.extractGameId(game), e.getMessage());
                }
            }

            log.info("Basketball today poll: done — saved={}, skipped={}.", saved, skipped);
            evictMatchCaches();

        } catch (Exception e) {
            log.error("Basketball today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Basketball today's games poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. UPCOMING FIXTURES (next 7 days) — every hour
    //
    // [A] Per-day sweep: iterates the next 7 calendar days, fetching the NBA
    //     scoreboard for each date via BasketballDataService.getGamesByDate().
    //     Only future kickoffs are persisted; odds are generated immediately.
    //
    // [B] General sweep: getUpcomingGames() as a broad pass to catch anything
    //     missed by [A] (e.g. games added to the schedule after the per-day
    //     cache was built).
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 35_000L)
    public void pollUpcomingFixtures() {
        log.info("=== Basketball upcoming fixtures poll starting ===");
        try {

            // ── [A] Per-day sweep for next 7 days ────────────────────────
            log.info("Basketball upcoming poll [A]: fetching per-day fixtures for next 7 days...");
            int daySaved = 0, daySkipped = 0;

            for (String yyyymmdd : buildNext7DayStrings()) {
                List<Map<String, Object>> fixtures = basketballDataService.getGamesByDate(yyyymmdd);
                log.debug("Basketball upcoming poll [A]: {} fixture(s) on {}", fixtures.size(), yyyymmdd);

                for (Map<String, Object> game : fixtures) {
                    try {
                        Match m = mapEspnFixtureToMatch(game);
                        if (m != null && m.getKickoffAt() != null
                                && m.getKickoffAt().isAfter(Instant.now())) {
                            Match persisted = matchService.saveOrUpdate(m);
                            try {
                                oddsPersistenceService.generateAndSaveAllOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Basketball upcoming poll [A]: odds failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            daySaved++;
                        } else {
                            daySkipped++;
                        }
                    } catch (Exception e) {
                        daySkipped++;
                        log.warn("Basketball upcoming poll [A]: failed game id={} — {}",
                                BasketballDataService.extractGameId(game), e.getMessage());
                    }
                }
            }

            log.info("Basketball upcoming poll [A]: done — saved={}, skipped={}", daySaved, daySkipped);
            evictUpcomingCaches();

            // ── [B] General upcoming sweep ────────────────────────────────
            log.info("Basketball upcoming poll [B]: fetching general upcoming games...");

            List<Map<String, Object>> upcoming = basketballDataService.getUpcomingGames();

            if (upcoming.isEmpty()) {
                log.info("Basketball upcoming poll [B]: no upcoming games from general endpoint.");
            } else {
                log.info("Basketball upcoming poll [B]: {} game(s) to process.", upcoming.size());

                int saved = 0, skipped = 0;
                for (Map<String, Object> game : upcoming) {
                    try {
                        Match m = mapEspnFixtureToMatch(game);
                        if (m != null && m.getKickoffAt() != null
                                && m.getKickoffAt().isAfter(Instant.now())) {
                            Match persisted = matchService.saveOrUpdate(m);
                            try {
                                oddsPersistenceService.generateAndSaveAllOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Basketball upcoming poll [B]: odds failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            saved++;
                        } else {
                            log.debug("Basketball upcoming poll [B]: skipping past/null kickoff externalId={}",
                                    m != null ? m.getExternalId() : "null");
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Basketball upcoming poll [B]: failed game id={} — {}",
                                BasketballDataService.extractGameId(game), e.getMessage());
                    }
                }

                log.info("Basketball upcoming poll [B]: done — saved={}, skipped={}.", saved, skipped);
            }

            evictMatchCaches();

        } catch (Exception e) {
            log.error("Basketball upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Basketball upcoming fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. STALE LIVE SWEEP — every 10 minutes
    //
    // Force-finishes any LIVE basketball game whose kickoff was more than
    // 3.5 hours ago. Delegates to MatchService.finishStaleLiveMatches(cutoff,
    // sport) which scopes the query to sport="basketball" so football LIVE rows
    // are never touched.
    //
    // Also clears confirmedFinishedIds so that fixtures ESPN eventually corrects
    // are picked up again on the next poll cycle.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 6 * 60_000L)
    public void sweepStaleLiveGames() {
        log.debug("=== Basketball stale LIVE sweep starting ===");
        try {
            Instant cutoff = Instant.now().minus(3, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES);
            // finishStaleLiveMatches(cutoff, sport) — sport-scoped variant
            int closed = matchService.finishStaleLiveMatches(cutoff, SPORT);
            if (closed > 0) {
                log.info("Basketball stale sweep: force-finished {} LIVE game(s).", closed);
                evictMatchCaches();
            } else {
                log.debug("Basketball stale sweep: no stale LIVE games found.");
            }

            if (!confirmedFinishedIds.isEmpty()) {
                log.info("Basketball stale sweep: clearing {} confirmed-finished suppression id(s).",
                        confirmedFinishedIds.size());
                confirmedFinishedIds.clear();
            }
        } catch (Exception e) {
            log.error("Basketball stale sweep: error — {}", e.getMessage(), e);
        }
        log.debug("=== Basketball stale LIVE sweep complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. LIVE ODDS REFRESH — every 2 minutes
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 2 * 60_000L, initialDelay = 20_000L)
    public void refreshLiveOdds() {
        log.debug("=== Basketball live odds refresh starting ===");
        try {
            // getLiveMatches(sport) — sport-scoped variant
            List<Match> liveMatches = matchService.getLiveMatches(SPORT);
            if (liveMatches.isEmpty()) {
                log.debug("Basketball live odds refresh: no live games, skipping.");
                return;
            }
            log.info("Basketball live odds refresh: {} live game(s).", liveMatches.size());
            matchService.refreshLiveOddsCache(liveMatches);

            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                try {
                    oddsPersistenceService.generateAndSaveLiveOdds(match);
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Basketball live odds refresh: DB save failed matchId={} — {}",
                            match.getId(), e.getMessage());
                }
            }
            log.info("Basketball live odds refresh: persisted={}/{}, failed={}",
                    persisted, liveMatches.size(), failed);
        } catch (Exception e) {
            log.warn("Basketball live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== Basketball live odds refresh complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CACHE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private void evictUpcomingCaches() {
        List<String> names = List.of("matches", "futureMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("Basketball evictUpcomingCaches: {}/{} caches cleared.", cleared, names.size());
    }

    private void evictMatchCaches() {
        List<String> names = List.of("matches", "todayMatches", "futureMatches", "featuredMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            log.debug("Basketball evictMatchCaches: cache='{}' found={}", name, cache != null);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("Basketball evictMatchCaches: {}/{} caches cleared.", cleared, names.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    private static List<String> buildNext7DayStrings() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
        List<String> dates = new ArrayList<>(7);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            dates.add(today.plusDays(i).format(fmt));
        }
        return dates;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maps a raw ESPN game event to a Match entity for live/today polling.
     *
     * ── Status resolution priority ──────────────────────────────────────
     *
     *   0. isUpcoming() (state == "pre")  → return null    (skip; preserve fixture)
     *   1. isFinished() (state == "post") → FINISHED
     *   2. isLive()     (state == "in")
     *        + null kickoff               → LIVE  (trust ESPN; stale sweep is safety net)
     *        + kickoff within 3.5 hours   → LIVE
     *        + kickoff > 3.5 hours ago    → FINISHED  (stale demotion + WARN)
     *   3. Otherwise                      → UPCOMING
     *
     * The externalId uses prefix "espn-nba-" to namespace basketball events
     * away from football events (prefix "espn-") in the shared matches table.
     */
    private Match mapEspnGameToMatch(Map<String, Object> game) {
        if (game == null) return null;

        String espnId = BasketballDataService.extractGameId(game);
        if (espnId == null || espnId.isBlank()) return null;

        // Skip NOT STARTED (pre) events from the live endpoint
        if (BasketballDataService.isUpcoming(game)) {
            log.debug("mapEspnGameToMatch: skipping PRE (not started) espn-nba-{} '{}'",
                    espnId, BasketballDataService.extractShortName(game));
            return null;
        }

        Match match = new Match();
        match.setExternalId("espn-nba-" + espnId);
        match.setSource(MatchSource.ESPN);
        match.setSport(SPORT);
        match.setSportEnum(Sport.BASKETBALL);

        // ── Kickoff ───────────────────────────────────────────────────────
        Instant kickoff = parseKickoff(game);
        match.setKickoffAt(kickoff);

        // ── Status resolution ─────────────────────────────────────────────
        if (BasketballDataService.isFinished(game)) {
            match.setStatus("FINISHED");

        } else if (BasketballDataService.isLive(game)) {
            if (kickoff == null || isGenuinelyLive(kickoff)) {
                if (kickoff == null) {
                    log.warn("mapEspnGameToMatch: accepting LIVE with null kickoff " +
                                    "espn-nba-{} '{}' state='{}' — stale sweep is safety net",
                            espnId,
                            BasketballDataService.extractShortName(game),
                            BasketballDataService.extractState(game));
                }
                match.setStatus("LIVE");
            } else {
                log.warn("mapEspnGameToMatch: demoting stale LIVE to FINISHED " +
                                "espn-nba-{} '{}' kickoff={}",
                        espnId,
                        BasketballDataService.extractShortName(game),
                        kickoff);
                match.setStatus("FINISHED");
            }

        } else {
            match.setStatus("UPCOMING");
        }

        // ── Teams + scores ────────────────────────────────────────────────
        BasketballDataService.extractHomeCompetitor(game).ifPresent(home -> {
            match.setHomeTeam(BasketballDataService.extractTeamName(home));
            match.setHomeLogo(BasketballDataService.extractTeamLogo(home));
            String s = BasketballDataService.extractScore(home);
            if (!s.isBlank()) {
                try { match.setScoreHome(Integer.parseInt(s)); }
                catch (NumberFormatException ignored) {}
            }
        });

        BasketballDataService.extractAwayCompetitor(game).ifPresent(away -> {
            match.setAwayTeam(BasketballDataService.extractTeamName(away));
            match.setAwayLogo(BasketballDataService.extractTeamLogo(away));
            String s = BasketballDataService.extractScore(away);
            if (!s.isBlank()) {
                try { match.setScoreAway(Integer.parseInt(s)); }
                catch (NumberFormatException ignored) {}
            }
        });

        match.setLeague("NBA");
        return match;
    }

    /**
     * Maps a raw ESPN game event to a Match entity for the upcoming fixtures
     * poller. Status is always UPCOMING. Events with blank team names are
     * skipped. The externalId prefix "espn-nba-" namespaces basketball away
     * from football.
     */
    private Match mapEspnFixtureToMatch(Map<String, Object> game) {
        if (game == null) return null;

        String espnId = BasketballDataService.extractGameId(game);
        if (espnId == null || espnId.isBlank()) return null;

        String homeName = BasketballDataService.extractHomeCompetitor(game)
                .map(BasketballDataService::extractTeamName).orElse("");
        String awayName = BasketballDataService.extractAwayCompetitor(game)
                .map(BasketballDataService::extractTeamName).orElse("");

        if (homeName.isBlank() || awayName.isBlank()) {
            log.debug("mapEspnFixtureToMatch: blank team names for espn-nba-{}, skipping", espnId);
            return null;
        }

        Match match = new Match();
        match.setExternalId("espn-nba-" + espnId);
        match.setSource(MatchSource.ESPN);
        match.setSport(SPORT);
        match.setStatus("UPCOMING");
        match.setLeague("NBA");
        match.setSportEnum(Sport.BASKETBALL);
        match.setHomeTeam(homeName);
        match.setAwayTeam(awayName);

        BasketballDataService.extractHomeCompetitor(game)
                .ifPresent(home -> match.setHomeLogo(BasketballDataService.extractTeamLogo(home)));
        BasketballDataService.extractAwayCompetitor(game)
                .ifPresent(away -> match.setAwayLogo(BasketballDataService.extractTeamLogo(away)));

        Instant kickoff = parseKickoff(game);
        match.setKickoffAt(kickoff);

        if (kickoff != null) {
            log.debug("mapEspnFixtureToMatch: espn-nba-{} {} vs {} kickoff={}",
                    espnId, homeName, awayName, kickoff);
        } else {
            log.warn("mapEspnFixtureToMatch: espn-nba-{} {} vs {} — could not parse kickoff",
                    espnId, homeName, awayName);
        }

        return match;
    }

    /**
     * Parses the kickoff Instant from the ESPN event's "date" field.
     * ESPN returns ISO-8601 UTC strings, e.g. "2026-05-14T00:30:00Z".
     * Returns null if the field is absent or cannot be parsed.
     */
    @SuppressWarnings("unchecked")
    private static Instant parseKickoff(Map<String, Object> game) {
        // Try root level first
        Object dateObj = game.get("date");

        // Fallback: competitions[0].date
        if (dateObj == null) {
            try {
                List<?> competitions = (List<?>) game.get("competitions");
                if (competitions != null && !competitions.isEmpty()) {
                    Map<String, Object> comp = (Map<String, Object>) competitions.get(0);
                    dateObj = comp.get("date");
                }
            } catch (ClassCastException ignored) {}
        }

        if (dateObj == null) return null;
        String dateStr = dateObj.toString().trim();
        if (dateStr.isBlank()) return null;
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            log.debug("Basketball parseKickoff: could not parse '{}' — {}", dateStr, e.getMessage());
            return null;
        }
    }
}
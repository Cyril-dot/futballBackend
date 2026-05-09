package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.sportsdata.CompetitionIds;
import com.speedbet.api.sportsdata.LiveScoreApiClient;
import com.speedbet.api.sportsdata.TeamLogoCache;
import com.speedbet.api.sportsdata.odds.OddsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveScorePoller {

    private final LiveScoreApiClient     liveScoreApiClient;
    private final MatchService           matchService;
    private final OddsPersistenceService oddsPersistenceService;
    private final CacheManager           cacheManager;
    private final TeamLogoCache          teamLogoCache;

    /**
     * A match claiming to be LIVE is only genuinely live if:
     *   1. It has a kickoff time (no kickoffAt = API stuck on stale LIVE status)
     *   2. The kickoff was less than 4 hours ago (no match runs longer than ~4h)
     *
     * FIX #2: When kickoff is null we can no longer use time-based staleness
     * detection.  The call site (mapLiveScoreApiMatchToMatch) now trusts the
     * provider's live status when kickoff is null rather than demoting to
     * FINISHED.  isGenuinelyLive() itself is unchanged — it still returns false
     * for null, and the call site handles that case explicitly.
     */
    private static final long FOUR_HOURS_MS = 4 * 60 * 60_000L;

    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long msSinceKickoff = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return msSinceKickoff >= 0 && msSinceKickoff <= FOUR_HOURS_MS;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEAGUE NAME RESOLVER
    // ═══════════════════════════════════════════════════════════════════════

    private static String resolveLeagueName(Map<String, Object> event) {
        Object compIdObj = event.get("competition_id");

        if (compIdObj == null) {
            Object comp = event.get("competition");
            if (comp instanceof Map) {
                compIdObj = ((Map<?, ?>) comp).get("id");
            }
        }

        if (compIdObj != null) {
            try {
                int compId = Integer.parseInt(compIdObj.toString());
                String resolved = CompetitionIds.displayNameForId(compId).orElse(null);
                if (resolved != null) {
                    log.debug("resolveLeagueName: compId={} → '{}'", compId, resolved);
                    return resolved;
                }
                log.debug("resolveLeagueName: compId={} not in registry — using raw API name", compId);
            } catch (NumberFormatException e) {
                log.debug("resolveLeagueName: could not parse competition_id='{}' — using raw API name", compIdObj);
            }
        } else {
            log.debug("resolveLeagueName: no competition_id in event — using raw API name");
        }

        return LiveScoreApiClient.extractCompetitionName(event);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 0. LOGO CACHE WARM — runs at startup (delay=2s) then every 6 hours
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 6 * 60 * 60_000L, initialDelay = 2_000L)
    public void warmLogoCache() {
        log.info("=== Logo cache warm starting ===");
        try {
            List<Map<String, Object>> allTop6 = new ArrayList<>();
            for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                List<Map<String, Object>> fixtures = liveScoreApiClient.getFixturesByLeague(league);
                allTop6.addAll(fixtures);
                log.debug("Logo warm: {} fixture(s) for {}", fixtures.size(), league.displayName());
            }
            teamLogoCache.ingest(allTop6);
            log.info("Logo warm: ingested {} top-6 fixtures", allTop6.size());

            List<Map<String, Object>> allCups = new ArrayList<>();
            for (CompetitionIds.CupCompetition cup : CompetitionIds.CupCompetition.top6Related()) {
                List<Map<String, Object>> fixtures = liveScoreApiClient.getFixturesByCup(cup);
                allCups.addAll(fixtures);
                log.debug("Logo warm: {} fixture(s) for {}", fixtures.size(), cup.displayName());
            }
            teamLogoCache.ingest(allCups);
            log.info("Logo warm: ingested {} cup fixtures", allCups.size());

            List<Map<String, Object>> live = liveScoreApiClient.getLiveScores();
            teamLogoCache.ingest(live);

            String today = java.time.LocalDate.now().toString();
            List<Map<String, Object>> todayMatches = liveScoreApiClient.getMatchesByDate(today);
            teamLogoCache.ingest(todayMatches);

            log.info("Logo warm complete — cache size={}", teamLogoCache.size());
        } catch (Exception e) {
            log.warn("Logo warm: error — {}", e.getMessage());
        }
        log.info("=== Logo cache warm complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. LIVE SCORES — every 30 seconds
    //
    // FIX #2 + #3: Use uncached *Fresh() methods so the 30s interval actually
    // hits the API each time (cached variants have a 5-min TTL that defeats
    // the purpose of a 30s poll).
    //
    // FIX #3: Try the single all-competitions endpoint first (one API call).
    // Only fall back to per-league calls if the general endpoint returns nothing.
    // This reduces rate-limit pressure significantly.
    //
    // FIX #5: Events with status "NOT STARTED" are now skipped inside
    // mapLiveScoreApiMatchToMatch — they appear in the live endpoint response
    // but must not overwrite scheduled fixture data.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        log.debug("=== Live score poll starting ===");
        try {
            List<Map<String, Object>> allLive = new ArrayList<>();

            // FIX #3: Attempt the single all-competitions live endpoint first.
            // This is one API call vs N per-league calls — far less rate-limit risk.
            List<Map<String, Object>> generalLive = liveScoreApiClient.getLiveScores();
            if (!generalLive.isEmpty()) {
                log.info("Live poll: {} match(es) from general live endpoint.", generalLive.size());
                allLive.addAll(generalLive);
            } else {
                // General endpoint returned nothing — fall back to per-league fresh calls.
                // FIX #2: Use *Fresh() variants (no cache) so we actually hit the API.
                log.debug("Live poll: general endpoint empty — falling back to per-league fresh calls.");

                for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                    List<Map<String, Object>> leagueLive = liveScoreApiClient.getLiveScoresByLeagueFresh(league);
                    if (!leagueLive.isEmpty()) {
                        log.debug("Live poll: {} live match(es) for {}", leagueLive.size(), league.displayName());
                        allLive.addAll(leagueLive);
                    }
                }

                for (CompetitionIds.CupCompetition cup : CompetitionIds.CupCompetition.top6Related()) {
                    List<Map<String, Object>> cupLive = liveScoreApiClient.getLiveScoresByCupFresh(cup);
                    if (!cupLive.isEmpty()) {
                        log.debug("Live poll: {} live match(es) for {}", cupLive.size(), cup.displayName());
                        allLive.addAll(cupLive);
                    }
                }
            }

            if (allLive.isEmpty()) {
                log.info("Live poll: no live matches found.");
            } else {
                teamLogoCache.ingest(allLive);
                log.info("Live poll: {} total match(es) from API to classify.", allLive.size());

                // ── DIAGNOSTIC: log raw status/time from provider for every event ──────
                // Remove or lower to DEBUG once the live-status issue is confirmed fixed.
                for (Map<String, Object> event : allLive) {
                    log.info("LIVE EVENT raw: id='{}' status='{}' time='{}' home='{}' away='{}'",
                            LiveScoreApiClient.extractMatchId(event),
                            LiveScoreApiClient.extractStatus(event),
                            LiveScoreApiClient.extractMatchTime(event),
                            LiveScoreApiClient.extractHomeName(event),
                            LiveScoreApiClient.extractAwayName(event));
                }
                // ────────────────────────────────────────────────────────────────────────

                int updated = 0, skipped = 0, demoted = 0, notStarted = 0;
                for (Map<String, Object> event : allLive) {
                    try {
                        Match m = mapLiveScoreApiMatchToMatch(event, false);
                        if (m != null) {
                            enrichLogos(m);
                            matchService.saveOrUpdate(m);
                            if ("LIVE".equals(m.getStatus()))     updated++;
                            else if ("FINISHED".equals(m.getStatus())) demoted++;
                        } else {
                            // null return means either NOT STARTED (counted separately
                            // via the notStarted log in the mapper) or a parse error.
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Live poll: failed event id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Live poll: done — live={}, demoted-to-finished={}, skipped={} (includes not-started).",
                        updated, demoted, skipped);

                if (demoted > 0) {
                    evictMatchCaches();
                    log.info("Live poll: evicted match caches after {} demotion(s).", demoted);
                }
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
        String today = java.time.LocalDate.now().toString();
        log.info("=== Today's fixtures poll starting for date={} ===", today);
        try {
            List<Map<String, Object>> allToday = new ArrayList<>();

            for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                Map<String, Object> raw = liveScoreApiClient.callWithFallbackPublic(
                        "matches/history.json?from=" + today + "&to=" + today
                                + "&competition_id=" + league.id());
                if (raw != null) {
                    List<Map<String, Object>> matches =
                            liveScoreApiClient.extractByPathPublic(raw, "data", "match");
                    if (!matches.isEmpty()) {
                        log.debug("Today poll: {} match(es) for {}", matches.size(), league.displayName());
                        allToday.addAll(matches);
                    }
                }
            }

            for (CompetitionIds.CupCompetition cup : CompetitionIds.CupCompetition.top6Related()) {
                Map<String, Object> raw = liveScoreApiClient.callWithFallbackPublic(
                        "matches/history.json?from=" + today + "&to=" + today
                                + "&competition_id=" + cup.id());
                if (raw != null) {
                    List<Map<String, Object>> matches =
                            liveScoreApiClient.extractByPathPublic(raw, "data", "match");
                    if (!matches.isEmpty()) {
                        log.debug("Today poll: {} match(es) for {}", matches.size(), cup.displayName());
                        allToday.addAll(matches);
                    }
                }
            }

            List<Map<String, Object>> generalToday = liveScoreApiClient.getTodayMatches();
            if (!generalToday.isEmpty()) {
                log.debug("Today poll: {} match(es) from general today endpoint", generalToday.size());
                allToday.addAll(generalToday);
            }

            if (allToday.isEmpty()) {
                log.info("Today poll: no matches returned for date={}.", today);
            } else {
                teamLogoCache.ingest(allToday);
                int saved = 0, skipped = 0;
                for (Map<String, Object> event : allToday) {
                    try {
                        Match m = mapLiveScoreApiMatchToMatch(event, false);
                        if (m != null) {
                            Match persisted = matchService.saveOrUpdate(m);
                            if ("UPCOMING".equals(persisted.getStatus()) ||
                                    "SCHEDULED".equals(persisted.getStatus())) {
                                try {
                                    oddsPersistenceService.generateAndSaveAllOdds(persisted);
                                } catch (Exception oe) {
                                    log.warn("Today poll: odds save failed matchId={} — {}",
                                            persisted.getId(), oe.getMessage());
                                }
                            }
                            saved++;
                        } else skipped++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Today poll: failed event id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Today poll: done — saved={}, skipped={}.", saved, skipped);
                evictMatchCaches();
            }
        } catch (Exception e) {
            log.error("Today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Today's fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. UPCOMING FIXTURES (next 7 days) — every hour
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 30_000L)
    public void pollUpcomingFixtures() {
        log.info("=== Upcoming fixtures poll starting ===");
        try {
            log.info("Upcoming poll [A]: fetching per-league fixtures via enum...");
            int top6Saved = 0, top6Skipped = 0;

            for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                List<Map<String, Object>> fixtures = liveScoreApiClient.getFixturesByLeague(league);
                log.debug("Upcoming poll [A]: {} fixture(s) for {}", fixtures.size(), league.displayName());
                teamLogoCache.ingest(fixtures);
                for (Map<String, Object> event : fixtures) {
                    try {
                        Match m = mapLiveScoreApiFixtureToMatch(event);
                        if (m != null && m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                            Match persisted = matchService.saveOrUpdate(m);
                            try { oddsPersistenceService.generateAndSaveAllOdds(persisted); }
                            catch (Exception oe) {
                                log.warn("Upcoming poll [A]: odds failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            top6Saved++;
                        } else {
                            top6Skipped++;
                        }
                    } catch (Exception e) {
                        top6Skipped++;
                        log.warn("Upcoming poll [A]: failed fixture id={} — {}",
                                LiveScoreApiClient.extractFixtureId(event), e.getMessage());
                    }
                }
            }

            for (CompetitionIds.CupCompetition cup : CompetitionIds.CupCompetition.top6Related()) {
                List<Map<String, Object>> fixtures = liveScoreApiClient.getFixturesByCup(cup);
                log.debug("Upcoming poll [A]: {} fixture(s) for {}", fixtures.size(), cup.displayName());
                teamLogoCache.ingest(fixtures);
                for (Map<String, Object> event : fixtures) {
                    try {
                        Match m = mapLiveScoreApiFixtureToMatch(event);
                        if (m != null && m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                            Match persisted = matchService.saveOrUpdate(m);
                            try { oddsPersistenceService.generateAndSaveAllOdds(persisted); }
                            catch (Exception oe) {
                                log.warn("Upcoming poll [A]: odds failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            top6Saved++;
                        } else {
                            top6Skipped++;
                        }
                    } catch (Exception e) {
                        top6Skipped++;
                        log.warn("Upcoming poll [A]: failed fixture id={} — {}",
                                LiveScoreApiClient.extractFixtureId(event), e.getMessage());
                    }
                }
            }

            log.info("Upcoming poll [A]: done — saved={}, skipped={}", top6Saved, top6Skipped);
            evictUpcomingCaches();

            log.info("Upcoming poll [B]: fetching general upcoming fixtures — logo cache size={}",
                    teamLogoCache.size());
            List<Map<String, Object>> fixtures = liveScoreApiClient.getUpcomingFixtures();
            if (fixtures == null || fixtures.isEmpty()) {
                log.info("Upcoming poll [B]: no fixtures returned from general endpoint.");
            } else {
                log.info("Upcoming poll [B]: {} fixtures to process.", fixtures.size());
                int saved = 0, skipped = 0, logoHits = 0;
                for (Map<String, Object> event : fixtures) {
                    try {
                        Match m = mapLiveScoreApiFixtureToMatch(event);
                        if (m != null) {
                            if (m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                                logoHits += enrichLogos(m);
                                Match persisted = matchService.saveOrUpdate(m);
                                try { oddsPersistenceService.generateAndSaveAllOdds(persisted); }
                                catch (Exception oe) {
                                    log.warn("Upcoming poll [B]: odds failed matchId={} — {}",
                                            persisted.getId(), oe.getMessage());
                                }
                                saved++;
                            } else {
                                log.debug("Upcoming poll [B]: skipping past/null kickoff externalId={}",
                                        m.getExternalId());
                                skipped++;
                            }
                        } else skipped++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Upcoming poll [B]: failed fixture id={} — {}",
                                LiveScoreApiClient.extractMatchId(event), e.getMessage());
                    }
                }
                log.info("Upcoming poll [B]: done — saved={}, skipped={}, logoHits={}.",
                        saved, skipped, logoHits);
            }

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
            if (liveMatches.isEmpty()) {
                log.debug("Live odds refresh: no live matches, skipping.");
                return;
            }
            log.info("Live odds refresh: {} live match(es).", liveMatches.size());
            matchService.refreshLiveOddsCache(liveMatches);

            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                try {
                    oddsPersistenceService.generateAndSaveLiveOdds(match);
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Live odds refresh: DB save failed matchId={} — {}",
                            match.getId(), e.getMessage());
                }
            }
            log.info("Live odds refresh: persisted={}/{}, failed={}",
                    persisted, liveMatches.size(), failed);
        } catch (Exception e) {
            log.warn("Live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== Live odds refresh complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LOGO ENRICHMENT
    // ═══════════════════════════════════════════════════════════════════════

    private int enrichLogos(Match match) {
        int hits = 0;
        if (isBlank(match.getHomeLogo())) {
            String logo = teamLogoCache.getTeamLogo(match.getHomeTeam());
            if (!logo.isBlank()) { match.setHomeLogo(logo); hits++; }
        }
        if (isBlank(match.getAwayLogo())) {
            String logo = teamLogoCache.getTeamLogo(match.getAwayTeam());
            if (!logo.isBlank()) { match.setAwayLogo(logo); hits++; }
        }
        if (isBlank(match.getLeagueLogo())) {
            String logo = teamLogoCache.getLeagueLogo(match.getLeague());
            if (!logo.isBlank()) { match.setLeagueLogo(logo); hits++; }
        }
        return hits;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

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
        log.info("evictUpcomingCaches: {}/{} caches cleared.", cleared, names.size());
    }

    private void evictMatchCaches() {
        List<String> names = List.of("matches", "todayMatches", "futureMatches", "featuredMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            log.debug("evictMatchCaches: cache='{}' found={}", name, cache != null);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("evictMatchCaches: {}/{} caches cleared.", cleared, names.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maps a raw LiveScore API event to a Match entity.
     *
     * ── Status resolution ──────────────────────────────────────────────────
     *
     * Full priority order:
     *
     *   0. isNotStarted()             → return null  (skip; do not overwrite fixture data)
     *   1. isFinished()               → FINISHED     (finished always wins)
     *   2. isLive() + null kickoff    → LIVE          (trust provider; stale sweep is safety net)
     *   3. isLive() + valid kickoff within 4h → LIVE
     *   4. isLive() + kickoff > 4h ago → FINISHED    (stale demotion, with warning)
     *   5. Otherwise                  → UPCOMING
     *
     * ── FIX #5: NOT STARTED skip ──────────────────────────────────────────
     *
     *   The live endpoint returns ALL today's matches, including those that
     *   have not yet kicked off (status "NOT STARTED").  Previously these
     *   fell through to the UPCOMING branch and were saved, potentially
     *   overwriting cleaner data from the fixtures poller.
     *
     *   Now: isNotStarted() returns true for "NOT STARTED" and related
     *   pre-match statuses.  The mapper returns null immediately, causing the
     *   event to be counted as skipped without any DB write.
     *
     * ── FIX #2: null-kickoff LIVE tolerance ───────────────────────────────
     *
     *   Previously: if (isGenuinelyLive(kickoff)) { LIVE } else { FINISHED }
     *   → kickoff==null caused isGenuinelyLive() to return false → FINISHED.
     *
     *   Now: if (kickoff == null || isGenuinelyLive(kickoff)) { LIVE }
     *   → null kickoff trusts the provider.  The 10-min stale sweep
     *   (sweepStaleLiveMatches) is the safety net for any truly stuck matches.
     *
     * ── FIX #1 / #4 note ─────────────────────────────────────────────────
     *
     *   isFinished() and isLive() are defined in LiveScoreApiClient.
     *   They now recognise the full range of provider status strings including
     *   multi-word variants: "IN PLAY", "HALF TIME BREAK", "ADDED TIME",
     *   "FULL TIME", "NOT STARTED", etc.
     *
     * The {@code forceStatusLive} parameter is kept for backward-compatibility
     * but is IGNORED — callers should always pass {@code false}.
     */
    private Match mapLiveScoreApiMatchToMatch(Map<String, Object> event,
                                              @SuppressWarnings("unused") boolean forceStatusLive) {
        if (event == null) return null;

        String externalId = LiveScoreApiClient.extractMatchId(event);
        if (externalId == null || externalId.isBlank()) return null;

        // ── STATUS RESOLUTION ──────────────────────────────────────────────

        // FIX #5: Skip NOT STARTED events from the live endpoint entirely.
        // They must not overwrite scheduled fixture data with a stale UPCOMING
        // record that lacks kickoff time, scores, or other enriched fields.
        if (LiveScoreApiClient.isNotStarted(event)) {
            log.debug("mapLiveScoreApiMatchToMatch: skipping NOT STARTED " +
                            "externalId=ls-{} home='{}' away='{}'",
                    externalId,
                    LiveScoreApiClient.extractHomeName(event),
                    LiveScoreApiClient.extractAwayName(event));
            return null;
        }

        Match match = new Match();
        match.setExternalId("ls-" + externalId);
        match.setSource(MatchSource.LIVESCORE);
        match.setSport("football");

        Instant kickoff = LiveScoreApiClient.buildKickoffInstant(event);
        match.setKickoffAt(kickoff);

        if (LiveScoreApiClient.isFinished(event)) {
            // Rule 1: finished always wins — regardless of kickoff parse result.
            match.setStatus("FINISHED");

        } else if (LiveScoreApiClient.isLive(event)) {
            // FIX #2: null kickoff no longer causes a live match to be demoted.
            // Previously this branch did: if (isGenuinelyLive(kickoff)) { LIVE } else { FINISHED }
            // which silently demoted every match whose kickoff could not be parsed.
            //
            // Now: trust the provider when kickoff is null.
            // The periodic stale sweep (sweepStaleLiveMatches, every 10 min) acts
            // as the safety net — it will force-finish matches stuck in LIVE after 4h.
            if (kickoff == null || isGenuinelyLive(kickoff)) {
                if (kickoff == null) {
                    log.warn("mapLiveScoreApiMatchToMatch: accepting LIVE with null kickoff " +
                                    "externalId=ls-{} home='{}' away='{}' status='{}' — stale sweep is safety net",
                            externalId,
                            LiveScoreApiClient.extractHomeName(event),
                            LiveScoreApiClient.extractAwayName(event),
                            LiveScoreApiClient.extractStatus(event));
                }
                match.setStatus("LIVE");
            } else {
                // Kickoff was parsed and is more than 4 hours ago — demote to FINISHED.
                log.warn("mapLiveScoreApiMatchToMatch: demoting stale LIVE to FINISHED " +
                                "externalId=ls-{} home='{}' away='{}' kickoff={}",
                        externalId,
                        LiveScoreApiClient.extractHomeName(event),
                        LiveScoreApiClient.extractAwayName(event),
                        kickoff);
                match.setStatus("FINISHED");
            }

        } else {
            match.setStatus("UPCOMING");
        }

        match.setHomeTeam(LiveScoreApiClient.extractHomeName(event));
        match.setAwayTeam(LiveScoreApiClient.extractAwayName(event));
        match.setHomeLogo(LiveScoreApiClient.extractHomeLogo(event));
        match.setAwayLogo(LiveScoreApiClient.extractAwayLogo(event));

        match.setLeague(resolveLeagueName(event));
        match.setLeagueLogo(LiveScoreApiClient.extractLeagueLogo(event));
        enrichLogos(match);

        // Score: extractScore() already strips spaces so "1 - 0" → "1-0"
        String scoreStr = LiveScoreApiClient.extractScore(event);
        if (scoreStr.contains("-")) {
            String[] parts = scoreStr.split("-");
            if (parts.length == 2) {
                try { match.setScoreHome(Integer.parseInt(parts[0].trim())); } catch (NumberFormatException ignored) {}
                try { match.setScoreAway(Integer.parseInt(parts[1].trim())); } catch (NumberFormatException ignored) {}
            }
        }

        String htScore = LiveScoreApiClient.extractHalfTimeScore(event);
        if (htScore.contains("-")) {
            String[] htParts = htScore.split("-");
            if (htParts.length == 2) {
                try {
                    int htHome = Integer.parseInt(htParts[0].trim());
                    int htAway = Integer.parseInt(htParts[1].trim());
                    Map<String, Object> meta = match.getMetadata() != null
                            ? new HashMap<>(match.getMetadata()) : new HashMap<>();
                    meta.put("score_home_ht", htHome);
                    meta.put("score_away_ht", htAway);
                    match.setMetadata(meta);
                } catch (NumberFormatException ignored) {}
            }
        }

        return match;
    }

    private Match mapLiveScoreApiFixtureToMatch(Map<String, Object> event) {
        if (event == null) return null;

        String externalId = LiveScoreApiClient.extractFixtureId(event);
        if (externalId == null || externalId.isBlank()) return null;

        String homeName = LiveScoreApiClient.extractHomeName(event);
        String awayName = LiveScoreApiClient.extractAwayName(event);

        if (homeName.isBlank() || awayName.isBlank()) {
            log.debug("mapFixtureToMatch: blank team names for id={}, skipping", externalId);
            return null;
        }

        Match match = new Match();
        match.setExternalId("ls-" + externalId);
        match.setSource(MatchSource.LIVESCORE);
        match.setSport("football");
        match.setStatus("UPCOMING");
        match.setHomeTeam(homeName);
        match.setAwayTeam(awayName);
        match.setHomeLogo(LiveScoreApiClient.extractHomeLogo(event));
        match.setAwayLogo(LiveScoreApiClient.extractAwayLogo(event));

        match.setLeague(resolveLeagueName(event));
        match.setLeagueLogo(LiveScoreApiClient.extractLeagueLogo(event));

        Instant kickoff = LiveScoreApiClient.buildKickoffInstant(event);
        match.setKickoffAt(kickoff);

        if (kickoff != null) {
            log.debug("mapFixtureToMatch: id={} {} vs {} kickoff={}",
                    externalId, homeName, awayName, kickoff);
        } else {
            log.warn("mapFixtureToMatch: id={} {} vs {} — could not parse kickoff",
                    externalId, homeName, awayName);
        }

        return match;
    }
}
package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.match.Sport;
import com.speedbet.api.match.TennisMatchService;
import com.speedbet.api.sportsdata.TennisDataService;
import com.speedbet.api.sportsdata.TennisDataService.Tour;
import com.speedbet.api.sportsdata.odds.TennisOddsPersistenceService;
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
 * Scheduled poller for tennis live scores, fixtures, and odds.
 *
 * ── Schedule overview ─────────────────────────────────────────────────────
 *
 *   pollLiveScores()       — every 30 s  — updates LIVE match scores + live odds cache
 *   pollTodaysFixtures()   — every 15 min — upserts today's ATP + WTA fixtures + pre-match odds
 *   pollUpcomingFixtures() — every 60 min — upserts next 7 days of fixtures + pre-match odds
 *   sweepStaleLiveMatches()— every 10 min — force-finishes LIVE matches older than 4 hours
 *   refreshLiveOdds()      — every 30 s  — persists live winner odds for all LIVE tennis matches
 *
 * ── Tour coverage ─────────────────────────────────────────────────────────
 *
 *   All polls run for both Tour.ATP and Tour.WTA unless otherwise noted.
 *
 * ── Status resolution ─────────────────────────────────────────────────────
 *
 *   ESPN state "pre"  → UPCOMING  (fixture path) or skip (live path)
 *   ESPN state "in"   → LIVE      (with 4-hour genuine-live guard)
 *   ESPN state "post" → FINISHED
 *
 * ── Confirmed-finished suppression ───────────────────────────────────────
 *
 *   External IDs that our DB has confirmed FINISHED but ESPN keeps reporting
 *   as IN PLAY are added to confirmedFinishedIds and skipped on subsequent
 *   polls. The set is cleared every 10 minutes by sweepStaleLiveMatches().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TennisLiveScorePoller {

    private final TennisDataService            tennisDataService;
    private final TennisMatchService           tennisMatchService;
    private final TennisOddsPersistenceService oddsPersistenceService;
    private final CacheManager                 cacheManager;

    // ── Constants ─────────────────────────────────────────────────────────
    private static final long FOUR_HOURS_MS = 4 * 60 * 60_000L;

    /**
     * External IDs confirmed FINISHED in our DB but still reported LIVE by ESPN.
     * Cleared every 10 minutes by sweepStaleLiveMatches().
     */
    private final Set<String> confirmedFinishedIds = ConcurrentHashMap.newKeySet();

    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long ms = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return ms >= 0 && ms <= FOUR_HOURS_MS;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. LIVE SCORES — every 30 seconds
    //
    // Fetches in-progress matches for both tours from TennisDataService
    // (always fresh — no cache). Skips "pre" (upcoming) events.
    // Confirmed-finished IDs are suppressed to avoid WARN spam.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        log.debug("=== Tennis live score poll starting ===");
        try {
            List<Map<String, Object>> allLive = new ArrayList<>();

            List<Map<String, Object>> atpLive = tennisDataService.getLiveMatches(Tour.ATP);
            List<Map<String, Object>> wtaLive = tennisDataService.getLiveMatches(Tour.WTA);
            allLive.addAll(atpLive);
            allLive.addAll(wtaLive);

            log.info("Tennis live poll: atp={}, wta={} in-progress match(es)",
                    atpLive.size(), wtaLive.size());

            if (allLive.isEmpty()) {
                log.info("Tennis live poll: no live matches found.");
                return;
            }

            allLive = deduplicateByMatchId(allLive);
            log.info("Tennis live poll: {} deduplicated event(s) to classify.", allLive.size());

            int updated = 0, skipped = 0, demoted = 0;
            for (Map<String, Object> event : allLive) {
                String rawMatchId = TennisDataService.extractMatchId(event);
                Tour   tour       = resolveTour(event, allLive, atpLive);
                String externalId = TennisMatchService.buildExternalId(rawMatchId, tour);

                if (confirmedFinishedIds.contains(externalId)) {
                    log.debug("Tennis live poll: skipping confirmed-finished externalId={}", externalId);
                    skipped++;
                    continue;
                }

                try {
                    Match m = mapLiveEventToMatch(event, tour);
                    if (m != null) {
                        Match persisted = tennisMatchService.saveOrUpdate(m);

                        if ("LIVE".equals(m.getStatus()) && "FINISHED".equals(persisted.getStatus())) {
                            confirmedFinishedIds.add(externalId);
                            log.info("Tennis live poll: confirmed-finished suppression added " +
                                            "externalId={} — ESPN feed stuck; skipping until next sweep.",
                                    externalId);
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
                    log.warn("Tennis live poll: failed matchId={} — {}", rawMatchId, e.getMessage());
                }
            }

            log.info("Tennis live poll: done — live={}, demoted={}, skipped={}.",
                    updated, demoted, skipped);

            if (demoted > 0) {
                evictMatchCaches();
                log.info("Tennis live poll: evicted match caches after {} demotion(s).", demoted);
            }

        } catch (Exception e) {
            log.error("Tennis live poll: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== Tennis live score poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. TODAY'S FIXTURES — every 15 minutes
    //
    // Fetches today's ATP and WTA matches from the scoreboard (cached) and
    // upserts UPCOMING/SCHEDULED rows. Pre-match odds generated and persisted.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 10_000L)
    public void pollTodaysFixtures() {
        log.info("=== Tennis today's fixtures poll starting for date={} ===", LocalDate.now());
        try {
            List<Map<String, Object>> allToday = new ArrayList<>();

            List<Map<String, Object>> atpToday = tennisDataService.getUpcomingMatches(Tour.ATP);
            List<Map<String, Object>> wtaToday = tennisDataService.getUpcomingMatches(Tour.WTA);
            allToday.addAll(labelWithTour(atpToday, Tour.ATP));
            allToday.addAll(labelWithTour(wtaToday, Tour.WTA));

            log.info("Tennis today poll: atp={}, wta={} upcoming match(es) before dedup",
                    atpToday.size(), wtaToday.size());

            allToday = deduplicateByMatchId(allToday);

            if (allToday.isEmpty()) {
                log.info("Tennis today poll: no upcoming matches returned.");
                return;
            }

            log.info("Tennis today poll: {} deduplicated event(s) to process.", allToday.size());

            int saved = 0, skipped = 0;
            for (Map<String, Object> event : allToday) {
                try {
                    Tour  tour = extractLabelledTour(event, Tour.ATP);
                    Match m    = mapFixtureToMatch(event, tour);
                    if (m != null) {
                        Match persisted = tennisMatchService.saveOrUpdate(m);
                        if ("UPCOMING".equals(persisted.getStatus()) ||
                                "SCHEDULED".equals(persisted.getStatus())) {
                            try {
                                oddsPersistenceService.generateAndSavePreMatchOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Tennis today poll: odds save failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                        }
                        saved++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("Tennis today poll: failed matchId={} — {}",
                            TennisDataService.extractMatchId(event), e.getMessage());
                }
            }

            log.info("Tennis today poll: done — saved={}, skipped={}.", saved, skipped);
            evictMatchCaches();

        } catch (Exception e) {
            log.error("Tennis today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Tennis today's fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. UPCOMING FIXTURES (next 7 days) — every 60 minutes
    //
    // Iterates all tournaments on the ATP and WTA scoreboards and upserts
    // every non-past match. Pre-match odds generated and persisted for
    // UPCOMING/SCHEDULED rows. Skips matches with null or past kickoffs.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 30_000L)
    public void pollUpcomingFixtures() {
        log.info("=== Tennis upcoming fixtures poll starting ===");
        try {
            int saved = 0, skipped = 0;

            for (Tour tour : Tour.values()) {
                List<Map<String, Object>> upcoming = tennisDataService.getUpcomingMatches(tour);
                log.info("Tennis upcoming poll: {} upcoming match(es) for {}", upcoming.size(), tour.displayName());

                for (Map<String, Object> event : upcoming) {
                    try {
                        Match m = mapFixtureToMatch(event, tour);
                        if (m != null && m.getKickoffAt() != null
                                && m.getKickoffAt().isAfter(Instant.now())) {
                            Match persisted = tennisMatchService.saveOrUpdate(m);
                            try {
                                oddsPersistenceService.generateAndSavePreMatchOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Tennis upcoming poll: odds failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            saved++;
                        } else {
                            log.debug("Tennis upcoming poll: skipping null/past kickoff matchId={}",
                                    TennisDataService.extractMatchId(event));
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Tennis upcoming poll: failed matchId={} — {}",
                                TennisDataService.extractMatchId(event), e.getMessage());
                    }
                }
            }

            log.info("Tennis upcoming poll: done — saved={}, skipped={}.", saved, skipped);
            evictUpcomingCaches();

        } catch (Exception e) {
            log.error("Tennis upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== Tennis upcoming fixtures poll complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. STALE LIVE SWEEP — every 10 minutes
    //
    // Force-finishes any LIVE tennis match whose kickoff was more than 4 hours
    // ago. Clears confirmedFinishedIds so ESPN-corrected fixtures are re-picked.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 5 * 60_000L)
    public void sweepStaleLiveMatches() {
        log.debug("=== Tennis stale LIVE sweep starting ===");
        try {
            Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
            int closed = tennisMatchService.finishStaleLiveMatches(cutoff);
            if (closed > 0) {
                log.info("Tennis stale sweep: force-finished {} LIVE match(es).", closed);
                evictMatchCaches();
            } else {
                log.debug("Tennis stale sweep: no stale LIVE matches found.");
            }

            if (!confirmedFinishedIds.isEmpty()) {
                log.info("Tennis stale sweep: clearing {} confirmed-finished suppression id(s).",
                        confirmedFinishedIds.size());
                confirmedFinishedIds.clear();
            }
        } catch (Exception e) {
            log.error("Tennis stale sweep: error — {}", e.getMessage(), e);
        }
        log.debug("=== Tennis stale LIVE sweep complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. LIVE ODDS REFRESH — every 30 seconds
    //
    // Refreshes the in-memory live winner odds cache for all LIVE tennis
    // matches and persists updated odds rows.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 15_000L)
    public void refreshLiveOdds() {
        log.debug("=== Tennis live odds refresh starting ===");
        try {
            List<Match> liveMatches = tennisMatchService.getLiveMatches();
            if (liveMatches.isEmpty()) {
                log.debug("Tennis live odds refresh: no live matches, skipping.");
                return;
            }
            log.info("Tennis live odds refresh: {} live match(es).", liveMatches.size());

            // Refresh in-memory cache
            tennisMatchService.refreshLiveOddsCache(liveMatches);

            // Persist live odds for each match by tour
            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                Tour tour = TennisMatchService.extractTour(match.getExternalId());
                if (tour == null) {
                    log.warn("Tennis live odds refresh: cannot resolve tour for externalId={} — skipping",
                            match.getExternalId());
                    failed++;
                    continue;
                }
                try {
                    oddsPersistenceService.generateAndSaveLiveOdds(match, tour);
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Tennis live odds refresh: DB save failed matchId={} — {}",
                            match.getId(), e.getMessage());
                }
            }
            log.info("Tennis live odds refresh: persisted={}/{}, failed={}",
                    persisted, liveMatches.size(), failed);

        } catch (Exception e) {
            log.warn("Tennis live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== Tennis live odds refresh complete ===");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CACHE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private void evictUpcomingCaches() {
        List<String> names = List.of("tennisMatches", "tennisFeaturedMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("Tennis evictUpcomingCaches: {}/{} caches cleared.", cleared, names.size());
    }

    private void evictMatchCaches() {
        List<String> names = List.of("tennisMatches", "tennisFeaturedMatches", "tennisMatchDetail");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("Tennis evictMatchCaches: {}/{} caches cleared.", cleared, names.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maps a raw ESPN tennis match competition to a Match entity for the live
     * poll path.
     *
     * ── Status resolution ───────────────────────────────────────────────────
     *
     *   ESPN state "pre"  → return null (skip; do not overwrite fixture data)
     *   ESPN state "post" → FINISHED (terminal)
     *   ESPN state "in"
     *       + null kickoff              → LIVE (trust ESPN; sweep is safety net)
     *       + kickoff within 4 hours   → LIVE
     *       + kickoff > 4 hours ago    → FINISHED (stale demotion + WARN)
     *   Otherwise                      → UPCOMING
     *
     * Set scores are extracted from competitor linescores (sets won).
     * Metadata captures currentSet and espnMatchId for live odds generation.
     */
    private Match mapLiveEventToMatch(Map<String, Object> event, Tour tour) {
        if (event == null) return null;

        String matchId = TennisDataService.extractMatchId(event);
        if (matchId == null || matchId.isBlank()) return null;

        // Skip NOT STARTED events from the live endpoint
        if (TennisDataService.isUpcoming(event)) {
            log.debug("Tennis mapLiveEvent: skipping PRE (not started) matchId={}", matchId);
            return null;
        }

        List<Map<String, Object>> players = TennisDataService.extractPlayers(event);
        if (players.size() < 2) {
            log.debug("Tennis mapLiveEvent: skipping matchId={} — fewer than 2 players", matchId);
            return null;
        }

        Map<String, Object> p1 = players.get(0);
        Map<String, Object> p2 = players.get(1);

        Match match = new Match();
        match.setExternalId(TennisMatchService.buildExternalId(matchId, tour));
        match.setSource(MatchSource.ESPN);
        match.setSport(TennisMatchService.SPORT);
        match.setSportEnum(Sport.TENNIS);
        match.setHomeTeam(TennisDataService.extractPlayerName(p1));
        match.setAwayTeam(TennisDataService.extractPlayerName(p2));

        // Kickoff
        Instant kickoff = parseKickoff(event);
        match.setKickoffAt(kickoff);

        // Status
        if (TennisDataService.isFinished(event)) {
            match.setStatus("FINISHED");

        } else if (TennisDataService.isLive(event)) {
            if (kickoff == null || isGenuinelyLive(kickoff)) {
                if (kickoff == null) {
                    log.warn("Tennis mapLiveEvent: accepting LIVE with null kickoff " +
                                    "matchId={} {} vs {} — stale sweep is safety net",
                            matchId, match.getHomeTeam(), match.getAwayTeam());
                }
                match.setStatus("LIVE");
            } else {
                log.warn("Tennis mapLiveEvent: demoting stale LIVE to FINISHED " +
                                "matchId={} {} vs {} kickoff={}",
                        matchId, match.getHomeTeam(), match.getAwayTeam(), kickoff);
                match.setStatus("FINISHED");
            }
        } else {
            match.setStatus("UPCOMING");
        }

        // Set scores (scoreHome/Away = sets won)
        match.setScoreHome(TennisDataService.countSetsWon(p1, p2));
        match.setScoreAway(TennisDataService.countSetsWon(p2, p1));

        // Metadata for live odds generation
        int currentSet = TennisDataService.extractCurrentSet(event);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("espnMatchId", matchId);
        metadata.put("currentSet",  currentSet > 0 ? String.valueOf(currentSet) : "1");
        metadata.put("statusDetail", TennisDataService.extractStatusDetail(event));
        metadata.put("shortDetail",  TennisDataService.extractShortDetail(event));
        match.setMetadata(metadata);

        return match;
    }

    /**
     * Maps a raw ESPN tennis match competition to a Match entity for the
     * fixture (upcoming) poll path.
     *
     * Status is always UPCOMING. Skips matches without both player names.
     * Kickoff is parsed from the competition's "date" field.
     */
    private Match mapFixtureToMatch(Map<String, Object> event, Tour tour) {
        if (event == null) return null;

        String matchId = TennisDataService.extractMatchId(event);
        if (matchId == null || matchId.isBlank()) return null;

        List<Map<String, Object>> players = TennisDataService.extractPlayers(event);
        if (players.size() < 2) {
            log.debug("Tennis mapFixture: skipping matchId={} — fewer than 2 players", matchId);
            return null;
        }

        String player1 = TennisDataService.extractPlayerName(players.get(0));
        String player2 = TennisDataService.extractPlayerName(players.get(1));

        if (player1.isBlank() || player2.isBlank()) {
            log.debug("Tennis mapFixture: skipping matchId={} — blank player name(s)", matchId);
            return null;
        }

        Match match = new Match();
        match.setExternalId(TennisMatchService.buildExternalId(matchId, tour));
        match.setSource(MatchSource.ESPN);
        match.setSport(TennisMatchService.SPORT);
        match.setSportEnum(Sport.TENNIS);
        match.setStatus("UPCOMING");
        match.setHomeTeam(player1);
        match.setAwayTeam(player2);

        Instant kickoff = parseKickoff(event);
        match.setKickoffAt(kickoff);

        if (kickoff == null) {
            log.warn("Tennis mapFixture: matchId={} {} vs {} — could not parse kickoff",
                    matchId, player1, player2);
        } else {
            log.debug("Tennis mapFixture: matchId={} {} vs {} kickoff={}",
                    matchId, player1, player2, kickoff);
        }

        // Embed ESPN match ID in metadata for downstream use
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("espnMatchId", matchId);
        match.setMetadata(metadata);

        return match;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Deduplicates a list of ESPN match events by their match ID.
     * Preserves insertion order; first occurrence wins.
     */
    private static List<Map<String, Object>> deduplicateByMatchId(List<Map<String, Object>> events) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String id = TennisDataService.extractMatchId(event);
            if (!id.isBlank() && seen.add(id)) result.add(event);
        }
        return result;
    }

    /**
     * Tags each event map with a "__tour__" label so the calling code can
     * recover the tour without carrying a parallel list.
     * Returns a new list of shallow-copied maps — never mutates ESPN data.
     */
    private static List<Map<String, Object>> labelWithTour(List<Map<String, Object>> events, Tour tour) {
        List<Map<String, Object>> labelled = new ArrayList<>(events.size());
        for (Map<String, Object> e : events) {
            Map<String, Object> copy = new LinkedHashMap<>(e);
            copy.put("__tour__", tour.name());
            labelled.add(copy);
        }
        return labelled;
    }

    /**
     * Recovers the tour from the {@code __tour__} label injected by
     * {@link #labelWithTour}. Falls back to {@code defaultTour} if absent.
     */
    private static Tour extractLabelledTour(Map<String, Object> event, Tour defaultTour) {
        Object label = event.get("__tour__");
        if (label == null) return defaultTour;
        try { return Tour.valueOf(label.toString()); }
        catch (IllegalArgumentException e) { return defaultTour; }
    }

    /**
     * Resolves the tour for a live event by checking whether its match ID
     * appears in the ATP live list. Falls back to WTA.
     */
    private static Tour resolveTour(Map<String, Object> event,
                                    List<Map<String, Object>> allLive,
                                    List<Map<String, Object>> atpLive) {
        String matchId = TennisDataService.extractMatchId(event);
        boolean isAtp = atpLive.stream()
                .anyMatch(e -> matchId.equals(TennisDataService.extractMatchId(e)));
        return isAtp ? Tour.ATP : Tour.WTA;
    }

    /**
     * Parses the kickoff Instant from the ESPN competition's "date" field.
     * ESPN returns ISO-8601 UTC strings e.g. "2025-05-14T11:00:00Z".
     * Returns null if the field is absent or unparseable.
     */
    private static Instant parseKickoff(Map<String, Object> event) {
        Object date = event.get("date");
        if (date == null) return null;
        try {
            return Instant.parse(date.toString());
        } catch (Exception e) {
            log.debug("Tennis parseKickoff: could not parse '{}' — {}", date, e.getMessage());
            return null;
        }
    }

    /**
     * Builds a list of the next 7 days (including today) formatted as
     * "yyyyMMdd" for date-scoped ESPN queries.
     */
    @SuppressWarnings("unused")
    private static List<String> buildNext7DayStrings() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
        List<String> dates = new ArrayList<>(7);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) dates.add(today.plusDays(i).format(fmt));
        return dates;
    }
}
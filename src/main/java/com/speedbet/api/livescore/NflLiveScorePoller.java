package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.NflMatchService;
import com.speedbet.api.match.Sport;
import com.speedbet.api.sportsdata.AmericanFootballDataService;
import com.speedbet.api.sportsdata.odds.NflOddsPersistenceService;
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
 * Poller for live NFL scores, upcoming fixtures, and in-play odds.
 *
 * ── Responsibilities ─────────────────────────────────────────────────────
 *
 *   1. Live scores    — every 30 seconds   (pollLiveScores)
 *   2. Today fixtures — every 15 minutes   (pollTodaysFixtures)
 *   3. Upcoming (7d)  — every hour         (pollUpcomingFixtures)
 *   4. Stale sweep    — every 10 minutes   (sweepStaleLiveMatches)
 *   5. Live odds      — every 2 minutes    (refreshLiveOdds)
 *
 * ── Status guard ─────────────────────────────────────────────────────────
 *
 *   Mirrored from NflMatchService — FINISHED is terminal; LIVE can only
 *   transition to FINISHED.  Events ESPN mis-classifies as still live after
 *   4+ hours are demoted here before being handed to the service layer.
 *
 * ── Confirmed-finished suppression ───────────────────────────────────────
 *
 *   ESPN occasionally keeps a finished game in its live feed for several
 *   cycles.  Once our DB confirms a game is FINISHED, its externalId is added
 *   to confirmedFinishedIds and skipped on subsequent live polls.  The set is
 *   cleared every 10 minutes by the stale sweep so genuinely rescheduled
 *   fixtures are not suppressed indefinitely.
 *
 * ── NFL week vs. daily scoreboard ────────────────────────────────────────
 *
 *   Unlike soccer (daily per-league calls), the NFL scoreboard is week-based.
 *   Upcoming fixtures are fetched by date (YYYYMMDD) for the next 7 days via
 *   AmericanFootballDataService.getGamesByDate().  Current-week live games
 *   come from AmericanFootballDataService.getLiveGames().
 *
 * ── External ID convention ───────────────────────────────────────────────
 *
 *   All NFL external IDs are stored as "espn-nfl-{espnId}".
 *   NflMatchService.stripNflPrefix() is used to recover the raw ESPN ID.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NflLiveScorePoller {

    private final AmericanFootballDataService nflDataService;
    private final NflMatchService             nflMatchService;
    private final NflOddsPersistenceService   nflOddsPersistenceService;
    private final CacheManager                cacheManager;

    /** Maximum game duration guard — no NFL game runs longer than ~4 hours. */
    private static final long FOUR_HOURS_MS = 4 * 60 * 60_000L;

    /**
     * externalIds confirmed FINISHED in our DB but still appearing in ESPN live feed.
     * Cleared by the stale sweep every 10 minutes.
     */
    private final Set<String> confirmedFinishedIds = ConcurrentHashMap.newKeySet();

    // ═════════════════════════════════════════════════════════════════════
    //  HELPER — genuinely live guard
    // ═════════════════════════════════════════════════════════════════════

    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long msSinceKickoff = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return msSinceKickoff >= 0 && msSinceKickoff <= FOUR_HOURS_MS;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  1. LIVE SCORES — every 30 seconds
    //
    //   Fetches in-progress NFL games from AmericanFootballDataService.getLiveGames().
    //   Skips "pre" (not-started) events returned erroneously by ESPN.
    //   Demotes events live for more than 4 hours to FINISHED before persisting.
    //   Tracks confirmed-finished IDs to suppress stale ESPN feed noise.
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        log.debug("=== NFL live score poll starting ===");
        try {
            List<Map<String, Object>> liveGames = nflDataService.getLiveGames();

            if (liveGames.isEmpty()) {
                log.info("NFL live poll: no live games returned from ESPN.");
                log.debug("=== NFL live score poll complete ===");
                return;
            }

            log.info("NFL live poll: {} game(s) from ESPN live feed.", liveGames.size());

            // Diagnostic log — raw ESPN state for every event
            for (Map<String, Object> game : liveGames) {
                log.info("NFL LIVE EVENT raw: id='{}' home='{}' away='{}' score='{}-{}' Q{} clock='{}'",
                        AmericanFootballDataService.extractGameId(game),
                        safeTeamName(AmericanFootballDataService.extractHomeCompetitor(game)),
                        safeTeamName(AmericanFootballDataService.extractAwayCompetitor(game)),
                        safeScore(AmericanFootballDataService.extractHomeCompetitor(game)),
                        safeScore(AmericanFootballDataService.extractAwayCompetitor(game)),
                        AmericanFootballDataService.extractQuarter(game),
                        AmericanFootballDataService.extractClock(game));
            }

            int updated = 0, skipped = 0, demoted = 0;

            for (Map<String, Object> game : liveGames) {
                String espnGameId  = AmericanFootballDataService.extractGameId(game);
                String externalId  = "espn-nfl-" + espnGameId;

                // Skip IDs already confirmed FINISHED in our DB
                if (confirmedFinishedIds.contains(externalId)) {
                    log.debug("NFL live poll: skipping confirmed-finished externalId={}", externalId);
                    skipped++;
                    continue;
                }

                try {
                    Match m = mapNflLiveGameToMatch(game, espnGameId, externalId);
                    if (m == null) {
                        skipped++;
                        continue;
                    }

                    Match persisted = nflMatchService.saveOrUpdate(m);

                    // If our LIVE mapping was rejected (DB guard kept FINISHED), track it
                    if ("LIVE".equals(m.getStatus()) && "FINISHED".equals(persisted.getStatus())) {
                        confirmedFinishedIds.add(externalId);
                        log.info("NFL live poll: confirmed-finished suppression added for externalId={}" +
                                " — ESPN feed is stuck; will skip until next stale sweep.", externalId);
                        skipped++;
                    } else if ("LIVE".equals(persisted.getStatus())) {
                        updated++;
                    } else if ("FINISHED".equals(persisted.getStatus())) {
                        demoted++;
                    }

                } catch (Exception e) {
                    skipped++;
                    log.warn("NFL live poll: failed espnGameId={} — {}", espnGameId, e.getMessage());
                }
            }

            log.info("NFL live poll: done — live={}, demoted-to-finished={}, skipped={}.",
                    updated, demoted, skipped);

            if (demoted > 0) {
                evictMatchCaches();
                log.info("NFL live poll: evicted match caches after {} demotion(s).", demoted);
            }

        } catch (Exception e) {
            log.error("NFL live poll: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== NFL live score poll complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  2. TODAY'S FIXTURES — every 15 minutes
    //
    //   Fetches today's NFL games from the current-week scoreboard, persists
    //   any UPCOMING games, and generates pre-match odds for each.
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 10_000L)
    public void pollTodaysFixtures() {
        log.info("=== NFL today's fixtures poll starting for date={} ===", LocalDate.now());
        try {
            // Current-week scoreboard is cached in AmericanFootballDataService
            List<Map<String, Object>> todayGames = nflDataService.getCurrentWeekGames();

            if (todayGames.isEmpty()) {
                log.info("NFL today poll: no games returned for current week.");
                log.info("=== NFL today's fixtures poll complete ===");
                return;
            }

            log.info("NFL today poll: {} game(s) from current-week scoreboard.", todayGames.size());

            int saved = 0, skipped = 0;

            for (Map<String, Object> game : todayGames) {
                String espnGameId = AmericanFootballDataService.extractGameId(game);
                String externalId = "espn-nfl-" + espnGameId;
                try {
                    Match m = mapNflFixtureToMatch(game, espnGameId, externalId);
                    if (m == null) {
                        skipped++;
                        continue;
                    }

                    Match persisted = nflMatchService.saveOrUpdate(m);

                    if ("UPCOMING".equals(persisted.getStatus()) ||
                            "SCHEDULED".equals(persisted.getStatus())) {
                        try {
                            nflOddsPersistenceService.generateAndSavePreMatchOdds(persisted, espnGameId);
                        } catch (Exception oe) {
                            log.warn("NFL today poll: pre-match odds failed matchId={} — {}",
                                    persisted.getId(), oe.getMessage());
                        }
                    }

                    saved++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("NFL today poll: failed espnGameId={} — {}", espnGameId, e.getMessage());
                }
            }

            log.info("NFL today poll: done — saved={}, skipped={}.", saved, skipped);
            evictMatchCaches();

        } catch (Exception e) {
            log.error("NFL today poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== NFL today's fixtures poll complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  3. UPCOMING FIXTURES (next 7 days) — every hour
    //
    //   Iterates the next 7 calendar days and fetches the NFL scoreboard
    //   for each date via AmericanFootballDataService.getGamesByDate(YYYYMMDD).
    //   Only future kickoffs are persisted; pre-match odds generated immediately.
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 30_000L)
    public void pollUpcomingFixtures() {
        log.info("=== NFL upcoming fixtures poll starting ===");
        try {
            List<String> next7Days = buildNext7DayStrings();
            int saved = 0, skipped = 0;

            for (String yyyymmdd : next7Days) {
                List<Map<String, Object>> fixtures = nflDataService.getGamesByDate(yyyymmdd);

                log.debug("NFL upcoming poll: {} game(s) for date={}", fixtures.size(), yyyymmdd);

                for (Map<String, Object> game : fixtures) {
                    String espnGameId = AmericanFootballDataService.extractGameId(game);
                    String externalId = "espn-nfl-" + espnGameId;
                    try {
                        Match m = mapNflFixtureToMatch(game, espnGameId, externalId);
                        if (m == null || m.getKickoffAt() == null
                                || !m.getKickoffAt().isAfter(Instant.now())) {
                            skipped++;
                            continue;
                        }

                        Match persisted = nflMatchService.saveOrUpdate(m);

                        try {
                            nflOddsPersistenceService.generateAndSavePreMatchOdds(persisted, espnGameId);
                        } catch (Exception oe) {
                            log.warn("NFL upcoming poll: odds failed matchId={} — {}",
                                    persisted.getId(), oe.getMessage());
                        }

                        saved++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("NFL upcoming poll: failed espnGameId={} date={} — {}",
                                espnGameId, yyyymmdd, e.getMessage());
                    }
                }
            }

            log.info("NFL upcoming poll: done — saved={}, skipped={}.", saved, skipped);
            evictUpcomingCaches();

        } catch (Exception e) {
            log.error("NFL upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== NFL upcoming fixtures poll complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  4. STALE LIVE SWEEP — every 10 minutes
    //
    //   Force-finishes any LIVE NFL match whose kickoff was more than 4 hours
    //   ago (scoped to sport="americanfootball" inside NflMatchService).
    //   Clears confirmedFinishedIds so genuinely rescheduled fixtures are
    //   not suppressed indefinitely.
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 5 * 60_000L)
    public void sweepStaleLiveMatches() {
        log.debug("=== NFL stale LIVE sweep starting ===");
        try {
            Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
            int closed = nflMatchService.finishStaleLiveMatches(cutoff);

            if (closed > 0) {
                log.info("NFL stale sweep: force-finished {} stale LIVE match(es).", closed);
                evictMatchCaches();
            } else {
                log.debug("NFL stale sweep: no stale LIVE matches found.");
            }

            // Clear suppression set so corrected ESPN events are picked up again
            if (!confirmedFinishedIds.isEmpty()) {
                log.info("NFL stale sweep: clearing {} confirmed-finished suppression id(s).",
                        confirmedFinishedIds.size());
                confirmedFinishedIds.clear();
            }

        } catch (Exception e) {
            log.error("NFL stale sweep: error — {}", e.getMessage(), e);
        }
        log.debug("=== NFL stale LIVE sweep complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  5. LIVE ODDS REFRESH — every 2 minutes
    //
    //   Regenerates the in-memory moneyline odds cache for all LIVE NFL
    //   matches, then persists the fresh "nfl_live_moneyline" rows to the DB.
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 2 * 60_000L, initialDelay = 15_000L)
    public void refreshLiveOdds() {
        log.debug("=== NFL live odds refresh starting ===");
        try {
            List<Match> liveMatches = nflMatchService.getLiveMatches();

            if (liveMatches.isEmpty()) {
                log.debug("NFL live odds refresh: no live matches, skipping.");
                return;
            }

            log.info("NFL live odds refresh: {} live match(es).", liveMatches.size());

            // Refresh in-memory cache (NflMatchService.liveMoneylineCache)
            nflMatchService.refreshLiveOddsCache(liveMatches);

            // Persist live odds to DB
            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                try {
                    String espnGameId = NflMatchService.stripNflPrefix(match.getExternalId());
                    nflOddsPersistenceService.generateAndSaveLiveOdds(match, espnGameId);
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("NFL live odds refresh: DB save failed matchId={} — {}",
                            match.getId(), e.getMessage());
                }
            }

            log.info("NFL live odds refresh: persisted={}/{}, failed={}",
                    persisted, liveMatches.size(), failed);

        } catch (Exception e) {
            log.warn("NFL live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== NFL live odds refresh complete ===");
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MAPPERS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Maps a raw ESPN live game to a Match entity for the live score poller.
     *
     * ── Status resolution ─────────────────────────────────────────────────
     *
     *   Mirrors LiveScorePoller.mapEspnEventToMatch logic, adapted for NFL:
     *
     *   1. If home/away teams cannot be extracted → return null (skip)
     *   2. kickoff null OR within 4 hours         → LIVE
     *   3. kickoff > 4 hours ago                  → FINISHED (stale demotion)
     *
     * Score, quarter, clock, possession, and red-zone are recorded in
     * the Match metadata map for downstream use by the live odds generator.
     */
    private Match mapNflLiveGameToMatch(Map<String, Object> game,
                                        String espnGameId,
                                        String externalId) {
        if (game == null || espnGameId == null || espnGameId.isBlank()) return null;

        Optional<Map<String, Object>> homeOpt = AmericanFootballDataService.extractHomeCompetitor(game);
        Optional<Map<String, Object>> awayOpt = AmericanFootballDataService.extractAwayCompetitor(game);

        if (homeOpt.isEmpty() || awayOpt.isEmpty()) {
            log.debug("mapNflLiveGameToMatch: could not resolve competitors espnGameId={} — skipping", espnGameId);
            return null;
        }

        Map<String, Object> home = homeOpt.get();
        Map<String, Object> away = awayOpt.get();

        String homeTeam = AmericanFootballDataService.extractTeamName(home);
        String awayTeam = AmericanFootballDataService.extractTeamName(away);

        if (homeTeam == null || homeTeam.isBlank() || awayTeam == null || awayTeam.isBlank()) {
            log.debug("mapNflLiveGameToMatch: blank team names espnGameId={} — skipping", espnGameId);
            return null;
        }

        Instant kickoff = parseKickoff(game);

        // Stale live guard — demote if kickoff was more than 4 hours ago
        String status;
        if (kickoff == null || isGenuinelyLive(kickoff)) {
            if (kickoff == null) {
                log.warn("mapNflLiveGameToMatch: accepting LIVE with null kickoff espnGameId={}" +
                        " home='{}' away='{}' — stale sweep is safety net",
                        espnGameId, homeTeam, awayTeam);
            }
            status = "LIVE";
        } else {
            log.warn("mapNflLiveGameToMatch: demoting stale LIVE to FINISHED espnGameId={}" +
                    " home='{}' away='{}' kickoff={}", espnGameId, homeTeam, awayTeam, kickoff);
            status = "FINISHED";
        }

        Match match = new Match();
        match.setExternalId(externalId);
        match.setSport("american_football");
        match.setSportEnum(Sport.AMERICAN_FOOTBALL);
        match.setStatus(status);
        match.setKickoffAt(kickoff);
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLogo(AmericanFootballDataService.extractTeamLogo(home));
        match.setAwayLogo(AmericanFootballDataService.extractTeamLogo(away));

        // Scores
        try { match.setScoreHome(Integer.parseInt(AmericanFootballDataService.extractScore(home))); }
        catch (NumberFormatException ignored) {}
        try { match.setScoreAway(Integer.parseInt(AmericanFootballDataService.extractScore(away))); }
        catch (NumberFormatException ignored) {}

        // Rich NFL in-play metadata (consumed by NflLiveOddsGeneratorService)
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("quarter",       AmericanFootballDataService.extractQuarter(game));
        meta.put("clock",         AmericanFootballDataService.extractClock(game));
        meta.put("homePossession", AmericanFootballDataService.hasPossession(home));
        meta.put("homeRedZone",    AmericanFootballDataService.isInRedZone(home));
        meta.put("awayRedZone",    AmericanFootballDataService.isInRedZone(away));
        match.setMetadata(meta);

        log.debug("mapNflLiveGameToMatch: espnGameId={} {} vs {} status={} score={}-{} Q{} clock='{}'",
                espnGameId, homeTeam, awayTeam, status,
                match.getScoreHome(), match.getScoreAway(),
                meta.get("quarter"), meta.get("clock"));

        return match;
    }

    /**
     * Maps a raw ESPN game entry to a Match entity for the fixtures pollers.
     *
     * Status is always UPCOMING — this mapper is only called from the
     * today/upcoming fixture paths where events have ESPN state "pre".
     * Events with blank team names are skipped.
     */
    private Match mapNflFixtureToMatch(Map<String, Object> game,
                                       String espnGameId,
                                       String externalId) {
        if (game == null || espnGameId == null || espnGameId.isBlank()) return null;

        Optional<Map<String, Object>> homeOpt = AmericanFootballDataService.extractHomeCompetitor(game);
        Optional<Map<String, Object>> awayOpt = AmericanFootballDataService.extractAwayCompetitor(game);

        String homeTeam = homeOpt.map(AmericanFootballDataService::extractTeamName).orElse("");
        String awayTeam = awayOpt.map(AmericanFootballDataService::extractTeamName).orElse("");

        if (homeTeam.isBlank() || awayTeam.isBlank()) {
            log.debug("mapNflFixtureToMatch: blank team names espnGameId={} — skipping", espnGameId);
            return null;
        }

        Instant kickoff = parseKickoff(game);

        Match match = new Match();
        match.setExternalId(externalId);
        match.setSport("american_football");
        match.setSportEnum(Sport.AMERICAN_FOOTBALL);
        match.setStatus("UPCOMING");
        match.setKickoffAt(kickoff);
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLogo(homeOpt.map(AmericanFootballDataService::extractTeamLogo).orElse(null));
        match.setAwayLogo(awayOpt.map(AmericanFootballDataService::extractTeamLogo).orElse(null));
        match.setLeague("NFL");

        if (kickoff != null) {
            log.debug("mapNflFixtureToMatch: espnGameId={} {} vs {} kickoff={}",
                    espnGameId, homeTeam, awayTeam, kickoff);
        } else {
            log.warn("mapNflFixtureToMatch: espnGameId={} {} vs {} — could not parse kickoff",
                    espnGameId, homeTeam, awayTeam);
        }

        return match;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CACHE HELPERS
    // ═════════════════════════════════════════════════════════════════════

    private void evictMatchCaches() {
        List<String> names = List.of("nflTodayMatches", "nflUpcomingMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            log.debug("evictMatchCaches (NFL): cache='{}' found={}", name, cache != null);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("evictMatchCaches (NFL): {}/{} caches cleared.", cleared, names.size());
    }

    private void evictUpcomingCaches() {
        List<String> names = List.of("nflUpcomingMatches");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("evictUpcomingCaches (NFL): {}/{} caches cleared.", cleared, names.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UTILITY
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Builds a list of the next 7 days (including today) formatted as
     * "yyyyMMdd" for ESPN's scoreboard date query parameter.
     */
    private static List<String> buildNext7DayStrings() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
        List<String> dates = new ArrayList<>(7);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            dates.add(today.plusDays(i).format(fmt));
        }
        return dates;
    }

    /**
     * Parses the kickoff Instant from the ESPN game's "date" field.
     * ESPN returns ISO-8601 UTC strings e.g. "2026-09-10T20:20:00Z".
     * Returns null if the field is absent or unparseable.
     */
    private static Instant parseKickoff(Map<String, Object> game) {
        Object dateObj = game.get("date");
        if (dateObj == null) return null;
        try {
            return Instant.parse(dateObj.toString());
        } catch (Exception e) {
            log.debug("parseKickoff (NFL): could not parse '{}' — {}", dateObj, e.getMessage());
            return null;
        }
    }

    // ── Diagnostic helpers ────────────────────────────────────────────────

    private static String safeTeamName(Optional<Map<String, Object>> competitorOpt) {
        return competitorOpt
                .map(AmericanFootballDataService::extractTeamName)
                .orElse("?");
    }

    private static String safeScore(Optional<Map<String, Object>> competitorOpt) {
        return competitorOpt
                .map(AmericanFootballDataService::extractScore)
                .orElse("0");
    }
}
package com.speedbet.api.livescore;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveScorePoller {

    private final EspnFootballDataService espnService;
    private final MatchService            matchService;
    private final OddsPersistenceService  oddsPersistenceService;
    private final CacheManager            cacheManager;

    /**
     * A match claiming to be LIVE is only genuinely live if:
     *   1. It has a kickoff time
     *   2. The kickoff was less than 4 hours ago (no match runs longer than ~4h)
     */
    private static final long FOUR_HOURS_MS = 4 * 60 * 60_000L;

    /**
     * How far back (in hours) a FINISHED match's kickoff must be before we
     * consider it a "stale historical" result and skip it in the today poll.
     * Set to 24h so same-day finished matches still appear in results.
     */
    private static final long SKIP_FINISHED_OLDER_THAN_HOURS = 24;

    /**
     * External IDs that our DB has confirmed FINISHED but ESPN keeps reporting
     * as IN PLAY (stale feed). We skip these early in each poll cycle to avoid
     * repeated WARN log noise and unnecessary DB round-trips.
     *
     * The set is cleared by sweepStaleLiveMatches() every 10 minutes so that
     * any genuinely replayed / rescheduled fixture is picked up again.
     */
    private final Set<String> confirmedFinishedIds = ConcurrentHashMap.newKeySet();

    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long msSinceKickoff = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return msSinceKickoff >= 0 && msSinceKickoff <= FOUR_HOURS_MS;
    }

    /**
     * Returns true if a FINISHED match should be skipped because its kickoff
     * is either null or more than SKIP_FINISHED_OLDER_THAN_HOURS hours ago.
     * This prevents ESPN's "current matchweek" scoreboard from flooding the DB
     * with historical completed matches from previous weeks.
     */
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
            if (league.displayName().equalsIgnoreCase(rawName)) {
                log.debug("resolveLeagueName: matched EspnLeague '{}' → '{}'", rawName, league.displayName());
                return league.displayName();
            }
        }

        for (EspnCup cup : EspnCup.values()) {
            if (cup.displayName().equalsIgnoreCase(rawName)) {
                log.debug("resolveLeagueName: matched EspnCup '{}' → '{}'", rawName, cup.displayName());
                return cup.displayName();
            }
        }

        log.debug("resolveLeagueName: no registry match for '{}' — using raw name", rawName);
        return rawName;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. LIVE SCORES — every 30 seconds
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 5_000L)
    public void pollLiveScores() {
        log.debug("=== Live score poll starting ===");
        try {
            List<Map<String, Object>> allLive = new ArrayList<>();

            List<Map<String, Object>> top6Live     = espnService.getTop6LiveMatches();
            List<Map<String, Object>> top6CupsLive = espnService.getTop6CupsLiveMatches();
            List<Map<String, Object>> uefaLive     = espnService.getUefaLiveMatches();

            allLive.addAll(top6Live);
            allLive.addAll(top6CupsLive);
            allLive.addAll(uefaLive);

            log.info("Live poll: primary — top6={}, cups={}, uefa={} event(s)",
                    top6Live.size(), top6CupsLive.size(), uefaLive.size());

            if (allLive.isEmpty()) {
                log.debug("Live poll: primary empty — falling back to per-league/cup calls.");

                for (EspnLeague league : EspnLeague.top6()) {
                    List<Map<String, Object>> leagueLive = espnService.getLiveMatches(league);
                    if (!leagueLive.isEmpty()) {
                        log.debug("Live poll [fallback]: {} live event(s) for {}",
                                leagueLive.size(), league.displayName());
                        allLive.addAll(leagueLive);
                    }
                }

                for (EspnCup cup : EspnCup.top6Related()) {
                    List<Map<String, Object>> cupLive = espnService.getCupLiveMatches(cup);
                    if (!cupLive.isEmpty()) {
                        log.debug("Live poll [fallback]: {} live event(s) for {}",
                                cupLive.size(), cup.displayName());
                        allLive.addAll(cupLive);
                    }
                }
            }

            allLive = deduplicateByEventId(allLive);

            if (allLive.isEmpty()) {
                log.info("Live poll: no live matches found.");
            } else {
                log.info("Live poll: {} deduplicated event(s) to classify.", allLive.size());

                for (Map<String, Object> event : allLive) {
                    log.info("LIVE EVENT raw: id='{}' status='{}' home='{}' away='{}'",
                            EspnFootballDataService.extractEventId(event),
                            EspnFootballDataService.extractStatus(event),
                            EspnFootballDataService.extractHomeName(event),
                            EspnFootballDataService.extractAwayName(event));
                }

                int updated = 0, skipped = 0, demoted = 0;
                for (Map<String, Object> event : allLive) {
                    String rawId = "espn-" + EspnFootballDataService.extractEventId(event);

                    if (confirmedFinishedIds.contains(rawId)) {
                        log.debug("Live poll: skipping confirmed-finished externalId={}", rawId);
                        skipped++;
                        continue;
                    }

                    try {
                        Match m = mapEspnEventToMatch(event);
                        if (m != null) {
                            Match persisted = matchService.saveOrUpdate(m);

                            if ("LIVE".equals(m.getStatus()) && "FINISHED".equals(persisted.getStatus())) {
                                confirmedFinishedIds.add(m.getExternalId());
                                log.info("Live poll: confirmed-finished suppression added for externalId={} " +
                                                "— ESPN feed is stuck; will skip until next stale sweep.",
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
                        log.warn("Live poll: failed event id={} — {}",
                                EspnFootballDataService.extractEventId(event), e.getMessage());
                    }
                }

                log.info("Live poll: done — live={}, demoted-to-finished={}, skipped={}.",
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
    //
    // FIX: Skip FINISHED matches whose kickoff is older than 24 hours.
    // ESPN's soccer scoreboard returns the entire current matchweek/round,
    // which includes completed matches from previous days/weeks. Without this
    // guard those historical results flood the DB and never appear in the API
    // because they fall outside the today/upcoming query windows.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 10_000L)
    public void pollTodaysFixtures() {
        log.info("=== Today's fixtures poll starting for date={} ===", LocalDate.now());
        try {
            List<Map<String, Object>> allToday = new ArrayList<>();

            List<Map<String, Object>> top6Today     = espnService.getTop6TodayMatches();
            List<Map<String, Object>> top6CupsToday = espnService.getTop6CupsTodayMatches();
            List<Map<String, Object>> uefaToday     = espnService.getUefaCompetitionsTodayMatches();

            allToday.addAll(top6Today);
            allToday.addAll(top6CupsToday);
            allToday.addAll(uefaToday);

            log.info("Today poll: top6={}, cups={}, uefa={} event(s) before dedup",
                    top6Today.size(), top6CupsToday.size(), uefaToday.size());

            allToday = deduplicateByEventId(allToday);

            if (allToday.isEmpty()) {
                log.info("Today poll: no matches returned.");
            } else {
                log.info("Today poll: {} deduplicated event(s) to process.", allToday.size());

                int saved = 0, skipped = 0;
                for (Map<String, Object> event : allToday) {
                    try {
                        Match m = mapEspnEventToMatch(event);
                        if (m == null) {
                            skipped++;
                            continue;
                        }

                        // ── FIX: skip old finished matches from prior matchweeks ──
                        if (isStaleHistoricalFinished(m)) {
                            log.debug("Today poll: skipping stale historical FINISHED externalId={} kickoff={}",
                                    m.getExternalId(), m.getKickoffAt());
                            skipped++;
                            continue;
                        }

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

                    } catch (Exception e) {
                        skipped++;
                        log.warn("Today poll: failed event id={} — {}",
                                EspnFootballDataService.extractEventId(event), e.getMessage());
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

            log.info("Upcoming poll [A]: fetching per-league fixtures for next 7 days...");
            int top6Saved = 0, top6Skipped = 0;

            List<String> next7Days = buildNext7DayStrings();

            for (EspnLeague league : EspnLeague.top6()) {
                for (String yyyymmdd : next7Days) {
                    List<Map<String, Object>> fixtures =
                            espnService.getUpcomingFixturesByDate(league, yyyymmdd);
                    log.debug("Upcoming poll [A]: {} fixture(s) for {} on {}",
                            fixtures.size(), league.displayName(), yyyymmdd);

                    for (Map<String, Object> event : fixtures) {
                        try {
                            Match m = mapEspnFixtureToMatch(event);
                            if (m != null && m.getKickoffAt() != null
                                    && m.getKickoffAt().isAfter(Instant.now())) {
                                Match persisted = matchService.saveOrUpdate(m);
                                try {
                                    oddsPersistenceService.generateAndSaveAllOdds(persisted);
                                } catch (Exception oe) {
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
                                    EspnFootballDataService.extractEventId(event), e.getMessage());
                        }
                    }
                }
            }

            for (EspnCup cup : EspnCup.top6Related()) {
                for (String yyyymmdd : next7Days) {
                    List<Map<String, Object>> fixtures =
                            espnService.getCupUpcomingFixturesByDate(cup, yyyymmdd);
                    log.debug("Upcoming poll [A]: {} fixture(s) for {} on {}",
                            fixtures.size(), cup.displayName(), yyyymmdd);

                    for (Map<String, Object> event : fixtures) {
                        try {
                            Match m = mapEspnFixtureToMatch(event);
                            if (m != null && m.getKickoffAt() != null
                                    && m.getKickoffAt().isAfter(Instant.now())) {
                                Match persisted = matchService.saveOrUpdate(m);
                                try {
                                    oddsPersistenceService.generateAndSaveAllOdds(persisted);
                                } catch (Exception oe) {
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
                                    EspnFootballDataService.extractEventId(event), e.getMessage());
                        }
                    }
                }
            }

            log.info("Upcoming poll [A]: done — saved={}, skipped={}", top6Saved, top6Skipped);
            evictUpcomingCaches();

            log.info("Upcoming poll [B]: fetching general upcoming fixtures...");

            List<Map<String, Object>> generalUpcoming = new ArrayList<>();
            generalUpcoming.addAll(espnService.getTop6UpcomingMatches());
            generalUpcoming.addAll(espnService.getTop6CupsUpcomingMatches());
            generalUpcoming = deduplicateByEventId(generalUpcoming);

            if (generalUpcoming.isEmpty()) {
                log.info("Upcoming poll [B]: no upcoming fixtures from general endpoint.");
            } else {
                log.info("Upcoming poll [B]: {} deduplicated fixture(s) to process.",
                        generalUpcoming.size());

                int saved = 0, skipped = 0;
                for (Map<String, Object> event : generalUpcoming) {
                    try {
                        Match m = mapEspnFixtureToMatch(event);
                        if (m != null && m.getKickoffAt() != null
                                && m.getKickoffAt().isAfter(Instant.now())) {
                            Match persisted = matchService.saveOrUpdate(m);
                            try {
                                oddsPersistenceService.generateAndSaveAllOdds(persisted);
                            } catch (Exception oe) {
                                log.warn("Upcoming poll [B]: odds failed matchId={} — {}",
                                        persisted.getId(), oe.getMessage());
                            }
                            saved++;
                        } else {
                            log.debug("Upcoming poll [B]: skipping past/null kickoff externalId={}",
                                    m != null ? m.getExternalId() : "null");
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("Upcoming poll [B]: failed fixture id={} — {}",
                                EspnFootballDataService.extractEventId(event), e.getMessage());
                    }
                }
                log.info("Upcoming poll [B]: done — saved={}, skipped={}.", saved, skipped);
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

            if (!confirmedFinishedIds.isEmpty()) {
                log.info("Stale sweep: clearing {} confirmed-finished suppression id(s).",
                        confirmedFinishedIds.size());
                confirmedFinishedIds.clear();
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

    private static List<Map<String, Object>> deduplicateByEventId(List<Map<String, Object>> events) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String id = EspnFootballDataService.extractEventId(event);
            if (!id.isBlank() && seen.add(id)) {
                result.add(event);
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ═══════════════════════════════════════════════════════════════════════

    private Match mapEspnEventToMatch(Map<String, Object> event) {
        if (event == null) return null;

        String externalId = EspnFootballDataService.extractEventId(event);
        if (externalId == null || externalId.isBlank()) return null;

        if (EspnFootballDataService.isUpcoming(event)) {
            log.debug("mapEspnEventToMatch: skipping PRE (not started) espn-{} home='{}' away='{}'",
                    externalId,
                    EspnFootballDataService.extractHomeName(event),
                    EspnFootballDataService.extractAwayName(event));
            return null;
        }

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
                if (kickoff == null) {
                    log.warn("mapEspnEventToMatch: accepting LIVE with null kickoff " +
                                    "espn-{} home='{}' away='{}' status='{}' — stale sweep is safety net",
                            externalId,
                            EspnFootballDataService.extractHomeName(event),
                            EspnFootballDataService.extractAwayName(event),
                            EspnFootballDataService.extractStatus(event));
                }
                match.setStatus("LIVE");
            } else {
                log.warn("mapEspnEventToMatch: demoting stale LIVE to FINISHED " +
                                "espn-{} home='{}' away='{}' kickoff={}",
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
                try { match.setScoreHome(Integer.parseInt(parts[0].trim())); }
                catch (NumberFormatException ignored) {}
                try { match.setScoreAway(Integer.parseInt(parts[1].trim())); }
                catch (NumberFormatException ignored) {}
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

        if (homeName.isBlank() || awayName.isBlank()) {
            log.debug("mapEspnFixtureToMatch: blank team names for espn-{}, skipping", externalId);
            return null;
        }

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

        Instant kickoff = parseKickoff(event);
        match.setKickoffAt(kickoff);

        if (kickoff != null) {
            log.debug("mapEspnFixtureToMatch: espn-{} {} vs {} kickoff={}",
                    externalId, homeName, awayName, kickoff);
        } else {
            log.warn("mapEspnFixtureToMatch: espn-{} {} vs {} — could not parse kickoff",
                    externalId, homeName, awayName);
        }

        return match;
    }

    private static Instant parseKickoff(Map<String, Object> event) {
        String dateStr = EspnFootballDataService.extractKickoffTime(event);
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            log.debug("parseKickoff: could not parse '{}' — {}", dateStr, e.getMessage());
            return null;
        }
    }
}
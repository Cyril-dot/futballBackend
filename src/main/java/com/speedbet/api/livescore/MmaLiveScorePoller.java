package com.speedbet.api.livescore;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.match.MmaMatchService;
import com.speedbet.api.match.Sport;
import com.speedbet.api.sportsdata.MmaDataService;
import com.speedbet.api.sportsdata.odds.MmaOddsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduled poller for UFC / MMA live events, upcoming fight cards, and live odds.
 *
 * ── Poll schedule ─────────────────────────────────────────────────────────
 *
 *   pollLiveEvents()     — every 30 seconds  (initialDelay 8 s)
 *   pollUpcomingEvents() — every 60 minutes  (initialDelay 20 s)
 *   sweepStaleLive()     — every 10 minutes  (initialDelay 5 min)
 *   refreshLiveOdds()    — every 2 minutes   (initialDelay 18 s)
 *
 * ── Data flow ────────────────────────────────────────────────────────────
 *
 *   ESPN Scoreboard  ──► MmaDataService.getLiveEvents()
 *                    ──► per-bout Match rows (one row per competition[])
 *                    ──► MmaMatchService.saveOrUpdate()
 *
 *   The poller stores round progress in Match.scoreHome (roundsCompleted)
 *   and Match.scoreAway (totalRounds).  dominanceScore, weightClass, and
 *   fighter records are written to Match.metadata.
 *
 * ── External ID convention ────────────────────────────────────────────────
 *
 *   "espn-mma-<eventId>-bout<boutIndex>"
 *   e.g. "espn-mma-600033284-bout0" is the main event (boutIndex = 0).
 *
 *   Use {@link MmaMatchService#buildExternalId(String, int)} to construct IDs
 *   and {@link MmaMatchService#stripMmaEventId(String)} to recover the raw
 *   ESPN event ID.
 *
 * ── Status resolution ────────────────────────────────────────────────────
 *
 *   ESPN state "in"   → LIVE
 *   ESPN state "post" → FINISHED
 *   ESPN state "pre"  → UPCOMING
 *
 *   A fight claiming LIVE with a kickoff > 4 hours ago is demoted to
 *   FINISHED (stale feed guard, identical to LiveScorePoller).
 *
 * ── confirmedFinishedIds suppression ─────────────────────────────────────
 *
 *   When our DB rejects a LIVE update (the row is already FINISHED),
 *   the externalId is added to this set so subsequent 30-second ticks
 *   skip it immediately without a DB round-trip.  The set is cleared by
 *   sweepStaleLive() every 10 minutes, allowing genuinely rescheduled
 *   bouts to be picked up again.
 *
 * ── Bout-level granularity ───────────────────────────────────────────────
 *
 *   Each UFC event contains multiple bouts (competitions[]).  This poller
 *   iterates every bout on every live event and upserts one Match row per
 *   bout, so a single UFC card generates N Match rows (typically 5–12).
 *
 * ── Odds persistence ─────────────────────────────────────────────────────
 *
 *   Pre-match odds are generated and persisted for UPCOMING bouts during
 *   pollUpcomingEvents().
 *
 *   Live odds are written to the in-memory cache via
 *   MmaMatchService.refreshLiveOddsCache(), and also persisted to the DB
 *   via MmaOddsPersistenceService.generateAndSaveLiveOdds() in
 *   refreshLiveOdds().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MmaLiveScorePoller {

    private final MmaDataService            mmaDataService;
    private final MmaMatchService           mmaMatchService;
    private final MmaOddsPersistenceService oddsPersistenceService;
    private final CacheManager              cacheManager;

    /** Maximum fight duration — no single bout can run longer than ~4 hours. */
    private static final long FOUR_HOURS_MS = 4 * 60 * 60_000L;

    /**
     * External IDs our DB has confirmed FINISHED but ESPN still reports as LIVE.
     * Cleared every 10 minutes by sweepStaleLive() so replayed events are caught.
     */
    private final Set<String> confirmedFinishedIds = ConcurrentHashMap.newKeySet();

    // ── Stale-live guard (mirrors LiveScorePoller.isGenuinelyLive) ────────
    private static boolean isGenuinelyLive(Instant kickoffAt) {
        if (kickoffAt == null) return false;
        long msSinceKickoff = Instant.now().toEpochMilli() - kickoffAt.toEpochMilli();
        return msSinceKickoff >= 0 && msSinceKickoff <= FOUR_HOURS_MS;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. LIVE EVENTS — every 30 seconds
    //
    // Fetches all UFC events currently in progress from ESPN.
    // For each event, iterates all bouts (competitions[]) and upserts a
    // Match row per bout.
    //
    // Round state is encoded as:
    //   Match.scoreHome = roundsCompleted  (status.period - 1)
    //   Match.scoreAway = totalRounds      (3 or 5)
    //
    // Dominance score (derived from compuStrikes) and other bout metadata
    // are stored in Match.metadata for use by the odds engine.
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 30_000L, initialDelay = 8_000L)
    public void pollLiveEvents() {
        log.debug("=== MMA live event poll starting ===");
        try {
            List<Map<String, Object>> liveEvents = mmaDataService.getLiveEvents();

            if (liveEvents.isEmpty()) {
                log.info("MMA live poll: no UFC events in progress.");
                log.debug("=== MMA live event poll complete ===");
                return;
            }

            log.info("MMA live poll: {} event(s) in progress.", liveEvents.size());

            // Diagnostic log — raw status/fighter info for each live event
            for (Map<String, Object> event : liveEvents) {
                List<Map<String, Object>> bouts = MmaDataService.extractBouts(event);
                for (int i = 0; i < bouts.size(); i++) {
                    Map<String, Object> bout = bouts.get(i);
                    List<Map<String, Object>> fighters = MmaDataService.extractBoutFighters(bout);
                    String f1 = fighters.size() > 0 ? MmaDataService.extractFighterName(fighters.get(0)) : "?";
                    String f2 = fighters.size() > 1 ? MmaDataService.extractFighterName(fighters.get(1)) : "?";
                    log.info("MMA LIVE EVENT raw: eventId='{}' boutIndex={} detail='{}' fighter1='{}' fighter2='{}'",
                            MmaDataService.extractEventId(event),
                            i,
                            MmaDataService.extractStatusDetail(bout),
                            f1, f2);
                }
            }

            int updated = 0, skipped = 0, demoted = 0;

            for (Map<String, Object> event : liveEvents) {
                String espnEventId = MmaDataService.extractEventId(event);
                if (espnEventId.isBlank()) {
                    log.warn("MMA live poll: event with blank ID, skipping.");
                    skipped++;
                    continue;
                }

                List<Map<String, Object>> bouts = MmaDataService.extractBouts(event);
                if (bouts.isEmpty()) {
                    log.debug("MMA live poll: event {} has no bouts.", espnEventId);
                    skipped++;
                    continue;
                }

                log.info("MMA live poll: event='{}' eventId='{}' bouts={}",
                        MmaDataService.extractEventName(event), espnEventId, bouts.size());

                for (int boutIndex = 0; boutIndex < bouts.size(); boutIndex++) {
                    Map<String, Object> bout      = bouts.get(boutIndex);
                    String             externalId = MmaMatchService.buildExternalId(espnEventId, boutIndex);

                    // Skip if already confirmed finished in DB
                    if (confirmedFinishedIds.contains(externalId)) {
                        log.debug("MMA live poll: skipping confirmed-finished externalId={}", externalId);
                        skipped++;
                        continue;
                    }

                    try {
                        Match m = mapBoutToMatch(event, bout, boutIndex, espnEventId);
                        if (m == null) { skipped++; continue; }

                        Match persisted = mmaMatchService.saveOrUpdate(m);

                        // Detect DB rejection (LIVE → FINISHED guard)
                        if ("LIVE".equals(m.getStatus()) && "FINISHED".equals(persisted.getStatus())) {
                            confirmedFinishedIds.add(externalId);
                            log.info("MMA live poll: confirmed-finished suppression added externalId={} " +
                                    "— ESPN feed is stuck; skipping until next stale sweep.", externalId);
                            skipped++;
                        } else if ("LIVE".equals(persisted.getStatus())) {
                            updated++;
                        } else if ("FINISHED".equals(persisted.getStatus())) {
                            demoted++;
                        }

                    } catch (Exception e) {
                        skipped++;
                        log.warn("MMA live poll: failed boutIndex={} externalId={} — {}",
                                boutIndex, externalId, e.getMessage());
                    }
                }
            }

            log.info("MMA live poll: done — live={}, demoted-to-finished={}, skipped={}.",
                    updated, demoted, skipped);

            if (demoted > 0) {
                evictMmaMatchCaches();
                log.info("MMA live poll: evicted caches after {} demotion(s).", demoted);
            }

        } catch (Exception e) {
            log.error("MMA live poll: top-level error — {}", e.getMessage(), e);
        }
        log.debug("=== MMA live event poll complete ===");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. UPCOMING EVENTS — every 60 minutes
    //
    // Fetches all upcoming UFC events from the ESPN scoreboard.
    // For each event, upserts one Match row per bout with status UPCOMING
    // and generates pre-match moneyline odds.
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60 * 60_000L, initialDelay = 20_000L)
    public void pollUpcomingEvents() {
        log.info("=== MMA upcoming events poll starting ===");
        try {
            List<Map<String, Object>> upcomingEvents = mmaDataService.getUpcomingEvents();

            if (upcomingEvents.isEmpty()) {
                log.info("MMA upcoming poll: no upcoming UFC events found.");
                log.info("=== MMA upcoming events poll complete ===");
                return;
            }

            log.info("MMA upcoming poll: {} upcoming event(s).", upcomingEvents.size());

            int saved = 0, skipped = 0;

            for (Map<String, Object> event : upcomingEvents) {
                String espnEventId = MmaDataService.extractEventId(event);
                if (espnEventId.isBlank()) { skipped++; continue; }

                List<Map<String, Object>> bouts = MmaDataService.extractBouts(event);
                log.info("MMA upcoming poll: event='{}' eventId='{}' bouts={}",
                        MmaDataService.extractEventName(event), espnEventId, bouts.size());

                // Build boutIndex → matchId map for batch odds generation
                Map<Integer, UUID> boutMatchIds = new LinkedHashMap<>();

                for (int boutIndex = 0; boutIndex < bouts.size(); boutIndex++) {
                    Map<String, Object> bout = bouts.get(boutIndex);
                    try {
                        Match m = mapBoutToMatch(event, bout, boutIndex, espnEventId);
                        if (m == null) { skipped++; continue; }

                        // Only persist if kickoff is in the future
                        if (m.getKickoffAt() != null && m.getKickoffAt().isAfter(Instant.now())) {
                            Match persisted = mmaMatchService.saveOrUpdate(m);
                            boutMatchIds.put(boutIndex, persisted.getId());
                            saved++;
                        } else {
                            log.debug("MMA upcoming poll: skipping past/null kickoff externalId={}",
                                    m.getExternalId());
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                        log.warn("MMA upcoming poll: failed boutIndex={} eventId={} — {}",
                                boutIndex, espnEventId, e.getMessage());
                    }
                }

                // Generate and persist pre-match odds for all bouts on this card
                if (!boutMatchIds.isEmpty()) {
                    try {
                        oddsPersistenceService.generateAndSaveAllBoutsPreMatchOdds(espnEventId, boutMatchIds);
                    } catch (Exception oe) {
                        log.warn("MMA upcoming poll: pre-match odds failed eventId={} — {}",
                                espnEventId, oe.getMessage());
                    }
                }
            }

            log.info("MMA upcoming poll: done — saved={}, skipped={}.", saved, skipped);
            evictMmaMatchCaches();

        } catch (Exception e) {
            log.error("MMA upcoming poll: top-level error — {}", e.getMessage(), e);
        }
        log.info("=== MMA upcoming events poll complete ===");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. STALE LIVE SWEEP — every 10 minutes
    //
    // Force-finishes any LIVE MMA match whose kickoff was >4 hours ago.
    // Clears confirmedFinishedIds so that corrected ESPN feeds are re-picked-up.
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 10 * 60_000L, initialDelay = 5 * 60_000L)
    public void sweepStaleLive() {
        log.debug("=== MMA stale LIVE sweep starting ===");
        try {
            Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
            int closed = mmaMatchService.finishStaleLiveMatches(cutoff);
            if (closed > 0) {
                log.info("MMA stale sweep: force-finished {} LIVE match(es).", closed);
                evictMmaMatchCaches();
            } else {
                log.debug("MMA stale sweep: no stale LIVE matches found.");
            }

            // Clear suppression set every sweep so corrected ESPN feeds are honoured
            if (!confirmedFinishedIds.isEmpty()) {
                log.info("MMA stale sweep: clearing {} confirmed-finished suppression id(s).",
                        confirmedFinishedIds.size());
                confirmedFinishedIds.clear();
            }
        } catch (Exception e) {
            log.error("MMA stale sweep: error — {}", e.getMessage(), e);
        }
        log.debug("=== MMA stale LIVE sweep complete ===");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. LIVE ODDS REFRESH — every 2 minutes
    //
    // Refreshes the in-memory live moneyline cache for all LIVE MMA matches
    // and also persists live odds rows to the DB via MmaOddsPersistenceService.
    //
    // For each live match the persister fetches a fresh event summary from ESPN
    // (MmaOddsPersistenceService.generateAndSaveLiveOdds does this internally)
    // to read compuStrike dominance stats.
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 2 * 60_000L, initialDelay = 18_000L)
    public void refreshLiveOdds() {
        log.debug("=== MMA live odds refresh starting ===");
        try {
            List<Match> liveMatches = mmaMatchService.getLiveMatches();
            if (liveMatches.isEmpty()) {
                log.debug("MMA live odds refresh: no live matches, skipping.");
                log.debug("=== MMA live odds refresh complete ===");
                return;
            }
            log.info("MMA live odds refresh: {} live match(es).", liveMatches.size());

            // Refresh in-memory cache first
            mmaMatchService.refreshLiveOddsCache(liveMatches);

            // Then persist to DB
            int persisted = 0, failed = 0;
            for (Match match : liveMatches) {
                try {
                    String espnEventId = MmaMatchService.stripMmaEventId(match.getExternalId());
                    int    boutIndex   = MmaMatchService.extractBoutIndex(match.getExternalId());
                    oddsPersistenceService.generateAndSaveLiveOdds(espnEventId, boutIndex, match.getId());
                    persisted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("MMA live odds refresh: DB save failed matchId={} externalId={} — {}",
                            match.getId(), match.getExternalId(), e.getMessage());
                }
            }
            log.info("MMA live odds refresh: persisted={}/{}, failed={}",
                    persisted, liveMatches.size(), failed);
        } catch (Exception e) {
            log.warn("MMA live odds refresh: error — {}", e.getMessage());
        }
        log.debug("=== MMA live odds refresh complete ===");
    }

    // ══════════════════════════════════════════════════════════════════════
    // MAPPER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Maps a single ESPN bout (competition) inside a UFC event to a {@link Match}.
     *
     * ── Status resolution ───────────────────────────────────────────────
     *
     *   ESPN state "in"   → LIVE (subject to 4-hour stale guard)
     *   ESPN state "post" → FINISHED
     *   ESPN state "pre"  → UPCOMING
     *
     * ── Field mapping ───────────────────────────────────────────────────
     *
     *   homeTeam   = fighter1 display name  (competitors[0])
     *   awayTeam   = fighter2 display name  (competitors[1])
     *   homeLogo   = fighter1 headshot URL
     *   awayLogo   = fighter2 headshot URL
     *   league     = weight class / bout name (e.g. "Lightweight")
     *   scoreHome  = roundsCompleted  (status.period - 1, or 0 for pre/post)
     *   scoreAway  = totalRounds      (5 for title/main events, 3 otherwise)
     *   kickoffAt  = parsed from event.date (ISO-8601 UTC)
     *   sport      = "mma"  (string discriminator)
     *   sportEnum  = Sport.MMA
     *   metadata   = { weightClass, titleBout, boutIndex, fighter1Record,
     *                  fighter2Record, fighter1Nationality,
     *                  fighter2Nationality, currentRound, clock,
     *                  statusDetail, resultMethod, winner, resultRound,
     *                  resultClock (post only), dominanceScore }
     *
     * @param event      raw ESPN event map (top-level card)
     * @param bout       raw ESPN competition map (individual fight)
     * @param boutIndex  0-based index in competitions[] (0 = main event)
     * @param espnEventId ESPN event ID string
     * @return mapped Match, or null if fighters cannot be resolved
     */
    private Match mapBoutToMatch(Map<String, Object> event,
                                 Map<String, Object> bout,
                                 int boutIndex,
                                 String espnEventId) {

        List<Map<String, Object>> fighters = MmaDataService.extractBoutFighters(bout);
        if (fighters.size() < 2) {
            log.debug("mapBoutToMatch: boutIndex={} eventId={} — fewer than 2 fighters, skipping",
                    boutIndex, espnEventId);
            return null;
        }

        Map<String, Object> f1 = fighters.get(0);
        Map<String, Object> f2 = fighters.get(1);

        String fighter1 = MmaDataService.extractFighterName(f1);
        String fighter2 = MmaDataService.extractFighterName(f2);

        if (fighter1.isBlank() || fighter2.isBlank()) {
            log.debug("mapBoutToMatch: boutIndex={} eventId={} — blank fighter name(s), skipping",
                    boutIndex, espnEventId);
            return null;
        }

        Match match = new Match();
        match.setExternalId(MmaMatchService.buildExternalId(espnEventId, boutIndex));
        match.setSource(MatchSource.ESPN);
        match.setSport("mma");
        match.setSportEnum(Sport.MMA);  // always set both, mirrors Baseball poller

        // ── Fighters ──────────────────────────────────────────────────────
        match.setHomeTeam(fighter1);
        match.setAwayTeam(fighter2);
        match.setHomeLogo(MmaDataService.extractFighterHeadshot(f1));
        match.setAwayLogo(MmaDataService.extractFighterHeadshot(f2));

        // ── Weight class / league ─────────────────────────────────────────
        String weightClass = MmaDataService.extractWeightClass(bout);
        match.setLeague(weightClass.isBlank() ? MmaDataService.extractEventName(event) : weightClass);

        // ── Kickoff — use the event-level date ────────────────────────────
        Instant kickoff = parseKickoff(event);
        match.setKickoffAt(kickoff);

        // ── Status resolution ─────────────────────────────────────────────
        String state = MmaDataService.extractState(bout);
        switch (state) {
            case MmaDataService.STATE_POST -> match.setStatus("FINISHED");
            case MmaDataService.STATE_IN -> {
                if (kickoff == null || isGenuinelyLive(kickoff)) {
                    if (kickoff == null) {
                        log.warn("mapBoutToMatch: accepting LIVE with null kickoff " +
                                        "externalId={} {} vs {} — stale sweep is safety net",
                                match.getExternalId(), fighter1, fighter2);
                    }
                    match.setStatus("LIVE");
                } else {
                    log.warn("mapBoutToMatch: demoting stale LIVE to FINISHED " +
                                    "externalId={} {} vs {} kickoff={}",
                            match.getExternalId(), fighter1, fighter2, kickoff);
                    match.setStatus("FINISHED");
                }
            }
            default -> match.setStatus("UPCOMING");
        }

        // ── Round counters ────────────────────────────────────────────────
        // scoreHome = roundsCompleted, scoreAway = totalRounds
        int currentRound    = MmaDataService.extractCurrentRound(bout);
        int roundsCompleted = Math.max(0, currentRound - 1);
        boolean titleBout   = MmaDataService.isTitleBout(bout);
        int totalRounds     = (titleBout || boutIndex == 0) ? 5 : 3;

        match.setScoreHome(roundsCompleted);
        match.setScoreAway(totalRounds);

        // ── Metadata ──────────────────────────────────────────────────────
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("weightClass",          weightClass);
        meta.put("titleBout",            titleBout);
        meta.put("boutIndex",            boutIndex);
        meta.put("fighter1Record",       MmaDataService.extractFighterRecord(f1));
        meta.put("fighter2Record",       MmaDataService.extractFighterRecord(f2));
        meta.put("fighter1Nationality",  MmaDataService.extractFighterNationality(f1));
        meta.put("fighter2Nationality",  MmaDataService.extractFighterNationality(f2));
        meta.put("currentRound",         currentRound);
        meta.put("clock",                MmaDataService.extractClock(bout));
        meta.put("statusDetail",         MmaDataService.extractStatusDetail(bout));

        // Post-fight extras
        if (MmaDataService.STATE_POST.equals(state)) {
            meta.put("resultMethod", MmaDataService.extractResultMethod(bout));
            meta.put("winner",       MmaDataService.extractWinner(bout));
            meta.put("resultRound",  currentRound);
            meta.put("resultClock",  MmaDataService.extractClock(bout));
        }

        // dominanceScore defaults to 0.0 — refreshed by refreshLiveOdds()
        // via MmaOddsPersistenceService which reads compuStrikes from summary
        meta.put("dominanceScore", 0.0);

        match.setMetadata(meta);

        log.debug("mapBoutToMatch: externalId={} {} vs {} status='{}' rounds={}/{} kickoff='{}'",
                match.getExternalId(), fighter1, fighter2,
                match.getStatus(), roundsCompleted, totalRounds, kickoff);

        return match;
    }

    // ══════════════════════════════════════════════════════════════════════
    // CACHE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void evictMmaMatchCaches() {
        List<String> names = List.of("mmaMatches", "mmaFeaturedMatches", "mmaFightCards");
        int cleared = 0;
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) { cache.clear(); cleared++; }
        }
        log.info("MMA evictMmaMatchCaches: {}/{} caches cleared.", cleared, names.size());
    }

    private void evictUpcomingCaches() {
        Cache cache = cacheManager.getCache("mmaFeaturedMatches");
        if (cache != null) {
            cache.clear();
            log.info("MMA evictUpcomingCaches: mmaFeaturedMatches cleared.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parses the kickoff Instant from the ESPN event's "date" field.
     * ESPN returns ISO-8601 UTC strings, e.g. "2026-05-10T02:00Z".
     * Returns null if the field is absent or unparseable.
     */
    private static Instant parseKickoff(Map<String, Object> event) {
        String dateStr = MmaDataService.extractEventDate(event);
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            log.debug("MMA parseKickoff: could not parse '{}' — {}", dateStr, e.getMessage());
            return null;
        }
    }
}
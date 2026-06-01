package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.MmaDataService;
import com.speedbet.api.sportsdata.odds.MmaLiveOddsGeneratorService;
import com.speedbet.api.sportsdata.odds.MmaOddsGeneratorService;
import com.speedbet.api.sportsdata.odds.MmaOddsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MmaMatchService {

    private final MatchRepository              matchRepo;
    private final OddsRepository               oddsRepo;
    private final MmaDataService               mmaDataService;
    private final MmaOddsGeneratorService      preMatchGenerator;
    private final MmaLiveOddsGeneratorService  liveOddsGenerator;
    private final MmaOddsPersistenceService    oddsPersistenceService;

    // ── Sport discriminator ───────────────────────────────────────────────
    private static final String SPORT = "mma";

    // ── External-ID prefix ────────────────────────────────────────────────
    public static final String ID_PREFIX = "espn-mma-";

    // ── Live odds in-memory cache ─────────────────────────────────────────
    private static final long LIVE_ODDS_TTL_MS = 2 * 60_000L;

    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveMoneylineCache =
            new ConcurrentHashMap<>();

    private record OddsCacheEntry(List<Map<String, Object>> odds, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() <= expiresAt; }
    }

    // ── Status transition guard ───────────────────────────────────────────
    private static boolean isPermittedTransition(String existing, String incoming) {
        if (existing == null || existing.equals(incoming)) return true;
        return switch (existing) {
            case "FINISHED" -> false;
            case "LIVE"     -> "FINISHED".equals(incoming);
            default         -> true;
        };
    }

    // Warn-once set so repeated blocked demotions don't spam logs
    private final Set<String> warnedDemotions = ConcurrentHashMap.newKeySet();

    // ── Misc helpers ──────────────────────────────────────────────────────
    private static boolean isMissing(String val) {
        return val == null || val.isBlank();
    }

    private static boolean isRealKickoff(Instant t) {
        return t != null && t.getNano() == 0;
    }

    private static boolean hasLogos(Match m) {
        return !isMissing(m.getHomeLogo()) && !isMissing(m.getAwayLogo());
    }

    private static final Comparator<Match> LOGO_THEN_KICKOFF =
            Comparator.comparingInt((Match m) -> hasLogos(m) ? 0 : 1)
                    .thenComparing(m -> m.getKickoffAt() != null ? m.getKickoffAt() : Instant.MAX);

    private UUID toUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) {
            throw ApiException.notFound("MMA match not found: " + id);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE — public API (used by MmaLiveScorePoller)
    // ══════════════════════════════════════════════════════════════════════

    public boolean isMoneylineCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveMoneylineCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveMoneylineOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveMoneylineCache.put(matchId, new OddsCacheEntry(odds, expires));
    }

    public List<Map<String, Object>> getMoneylineFromCache(UUID matchId) {
        OddsCacheEntry entry = liveMoneylineCache.get(matchId);
        return (entry != null && entry.isValid()) ? entry.odds() : null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BASIC QUERIES — DB-backed, scoped to sport="mma"
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All LIVE MMA matches, ordered by kickoff ascending.
     */
    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "LIVE");
        log.info("MMA getLiveMatches: {} LIVE match(es)", matches.size());
        return matches;
    }

    /**
     * MMA matches with kickoff in the next 7 days, sorted logos-first then
     * earliest kickoff.
     */
    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport(SPORT, now, now.plus(7, ChronoUnit.DAYS))
                .stream()
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("MMA getUpcomingMatches: {} upcoming match(es)", matches.size());
        return matches;
    }

    /**
     * All FINISHED MMA matches whose kickoff was within the last 72 hours,
     * capped at {@code limit}.
     */
    public List<Match> getRecentResults(int limit) {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "FINISHED")
                .stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .limit(limit)
                .toList();
        log.info("MMA getRecentResults: {} FINISHED match(es) (72h, cap={})", matches.size(), limit);
        return matches;
    }

    public List<Match> getRecentResults() {
        return getRecentResults(20);
    }

    @Cacheable("mmaFeaturedMatches")
    public List<Match> getFeaturedMatches() {
        List<Match> matches = matchRepo.findByFeaturedTrueOrderByKickoffAt().stream()
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()))
                .toList();
        log.info("MMA getFeaturedMatches: {} featured match(es)", matches.size());
        return matches;
    }

    /**
     * Fetch a single MMA match by its internal UUID.
     */
    public Match getById(String id) {
        Match m = matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("MMA match not found: " + id));
        if (!SPORT.equalsIgnoreCase(m.getSport())) {
            throw ApiException.notFound("MMA match not found: " + id);
        }
        return m;
    }

    /**
     * Fetch a single MMA match by its ESPN external ID
     * (e.g. "espn-mma-600033284-bout0").
     */
    public Optional<Match> getByExternalId(String externalId) {
        return matchRepo.findByExternalId(externalId)
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — direct endpoints
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 1X2 moneyline odds for a single MMA match.
     *
     * <ul>
     *   <li>LIVE    → served from in-memory cache if valid; otherwise generated
     *                 live via {@link MmaLiveOddsGeneratorService}.</li>
     *   <li>UPCOMING → generated pre-match via {@link MmaOddsGeneratorService}.</li>
     *   <li>FINISHED → empty list.</li>
     * </ul>
     */
    public List<Map<String, Object>> getMatchOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();

        if ("LIVE".equals(status)) {
            List<Map<String, Object>> cached = getMoneylineFromCache(match.getId());
            if (cached != null) return cached;

            // Generate on-demand; caller can also call refreshLiveOddsCache()
            int    scoreHome    = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int    scoreAway    = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int    minute       = extractMinute(match);
            double dominance    = deriveDominanceFromMetadata(match);

            // Interpret scoreHome/Away as roundsCompleted/totalRounds for MMA
            int roundsCompleted = scoreHome;
            int totalRounds     = scoreAway > 0 ? scoreAway : 3;

            List<Map<String, Object>> generated = liveOddsGenerator.generateLiveOdds(
                    match.getHomeTeam(), match.getAwayTeam(),
                    roundsCompleted, totalRounds, dominance);
            cacheLiveMoneylineOdds(match.getId(), generated);
            return generated;
        }

        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            String fighter1Record = extractMetaString(match, "fighter1Record");
            String fighter2Record = extractMetaString(match, "fighter2Record");
            String weightClass    = extractMetaString(match, "weightClass");
            return preMatchGenerator.generatePreMatchOdds(
                    match.getHomeTeam(), match.getAwayTeam(),
                    fighter1Record, fighter2Record, weightClass);
        }

        return List.of();
    }

    /**
     * All persisted {@link Odds} rows for a given match (all markets).
     */
    public List<Odds> getOddsForMatch(String id) {
        return oddsRepo.findByMatchId(toUuid(id));
    }

    /**
     * Bundles a list of matches with their current moneyline odds.
     * Preserves logos-first ordering.
     */
    public List<Map<String, Object>> withOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        List<Match> sorted = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        List<Map<String, Object>> out = new ArrayList<>(sorted.size());

        for (Match match : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", match);
            String status = match.getStatus();

            if ("LIVE".equals(status)) {
                OddsCacheEntry cached = liveMoneylineCache.get(match.getId());
                if (cached != null && cached.isValid()) {
                    entry.put("odds", cached.odds());
                } else {
                    entry.put("odds", preMatchGenerator.generatePreMatchOdds(
                            match.getHomeTeam(), match.getAwayTeam(),
                            extractMetaString(match, "fighter1Record"),
                            extractMetaString(match, "fighter2Record"),
                            extractMetaString(match, "weightClass")));
                }
            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                entry.put("odds", preMatchGenerator.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam(),
                        extractMetaString(match, "fighter1Record"),
                        extractMetaString(match, "fighter2Record"),
                        extractMetaString(match, "weightClass")));
            } else {
                entry.put("odds", List.of());
            }
            out.add(entry);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE REFRESH — called by MmaLiveScorePoller every 2 min
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Refreshes the in-memory live moneyline cache for all currently LIVE
     * MMA matches.  Uses dominance score stored in match metadata where
     * available; falls back to 0.0 (even fight).
     */
    public void refreshLiveOddsCache(List<Match> liveMatches) {
        if (liveMatches.isEmpty()) return;
        int refreshed = 0;
        for (Match match : liveMatches) {
            try {
                int    roundsCompleted = match.getScoreHome() != null ? match.getScoreHome() : 0;
                int    totalRounds     = match.getScoreAway() != null && match.getScoreAway() > 0
                        ? match.getScoreAway() : 3;
                double dominance       = deriveDominanceFromMetadata(match);

                List<Map<String, Object>> liveOdds = liveOddsGenerator.generateLiveOdds(
                        match.getHomeTeam(), match.getAwayTeam(),
                        roundsCompleted, totalRounds, dominance);
                cacheLiveMoneylineOdds(match.getId(), liveOdds);
                refreshed++;
            } catch (Exception e) {
                log.warn("MMA refreshLiveOddsCache: matchId={} failed — {}", match.getId(), e.getMessage());
            }
        }
        log.info("MMA refreshLiveOddsCache: {}/{} match(es) refreshed", refreshed, liveMatches.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // MATCH DETAIL / FIGHT CARD / FIGHTER INFO — ESPN pass-throughs
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Full ESPN event summary for the event containing this match.
     * Includes all bouts, fighter records, result methods, and round details.
     */
    public Map<String, Object> getMatchDetail(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String eventId = stripMmaEventId(match.getExternalId());
        Map<String, Object> summary = mmaDataService.getEventSummary(eventId);
        if (summary.isEmpty()) return Map.of();
        return Map.of("source", "espn-mma", "data", summary);
    }

    /**
     * The compiled fight card for the UFC event containing this match.
     * Each entry includes boutOrder, fighter names, weight class, state,
     * result method, and round/clock (if applicable).
     *
     * @see MmaDataService#buildFightCard(Map)
     */
    @Cacheable(value = "mmaFightCards", key = "#id")
    public List<Map<String, Object>> getEventFightCard(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return List.of();
        String eventId = stripMmaEventId(match.getExternalId());

        // Try scoreboard cache first (avoids a summary fetch)
        for (Map<String, Object> event : mmaDataService.getEvents()) {
            if (eventId.equals(MmaDataService.extractEventId(event))) {
                List<Map<String, Object>> card = MmaDataService.buildFightCard(event);
                log.info("getEventFightCard: matchId={} eventId={} — {} bouts (from scoreboard)",
                        id, eventId, card.size());
                return card;
            }
        }

        // Fallback: full summary
        Map<String, Object> summary = mmaDataService.getEventSummary(eventId);
        if (summary.isEmpty()) return List.of();

        // Summary wraps the event data inside "header" → we build the card from there
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) summary.get("header");
        if (header == null) return List.of();

        List<Map<String, Object>> card = MmaDataService.buildFightCard(header);
        log.info("getEventFightCard: matchId={} eventId={} — {} bouts (from summary)", id, eventId, card.size());
        return card;
    }

    /**
     * Quick event snapshot: name, venue, main event fighters, state.
     *
     * @see MmaDataService#getEventScore(String)
     */
    public Map<String, Object> getEventScore(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String eventId = stripMmaEventId(match.getExternalId());
        return mmaDataService.getEventScore(eventId);
    }

    /**
     * Fighter profile by ESPN athlete ID (record, weight class, headshot, etc.).
     */
    public Map<String, Object> getFighterInfo(String athleteId) {
        return mmaDataService.getFighterInfo(athleteId);
    }

    /**
     * Combined event details: score snapshot + full fight card + live/pre odds.
     *
     * @see MmaDataService#getFullGameDetails(String)
     */
    public Map<String, Object> getFullEventDetails(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String eventId = stripMmaEventId(match.getExternalId());
        return mmaDataService.getFullGameDetails(eventId);
    }

    /**
     * Match events / metadata stored on the {@link Match} entity.
     * Falls back to the ESPN event summary if metadata is absent.
     */
    public Map<String, Object> getEvents(String id) {
        Match match = getById(id);
        if (match.getMetadata() != null && !match.getMetadata().isEmpty()) {
            return match.getMetadata();
        }
        String eventId = stripMmaEventId(match.getExternalId());
        Map<String, Object> summary = mmaDataService.getEventSummary(eventId);
        return summary.isEmpty() ? Map.of("events", List.of())
                : Map.of("source", "espn-mma", "data", summary);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ESPN PASS-THROUGH — raw scoreboard
    // ══════════════════════════════════════════════════════════════════════

    /** All UFC events visible on ESPN's scoreboard (cached). */
    public List<Map<String, Object>> getEspnMmaEvents() {
        return mmaDataService.getEvents();
    }

    /** UFC events currently in progress — fresh, no cache. */
    public List<Map<String, Object>> getEspnMmaLiveEvents() {
        return mmaDataService.getLiveEvents();
    }

    /** UFC events that have not yet started. */
    public List<Map<String, Object>> getEspnMmaUpcomingEvents() {
        return mmaDataService.getUpcomingEvents();
    }

    /** Recently completed UFC events. */
    public List<Map<String, Object>> getEspnMmaFinishedEvents() {
        return mmaDataService.getFinishedEvents();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Upserts an MMA {@link Match} row.
     *
     * <p>Enforces the status transition guard (FINISHED is terminal).
     * Sparse fields (logos, league/weight-class name, kickoff) are filled in
     * only when the existing row is missing them — matching MatchService behaviour.
     * Score fields (scoreHome = roundsCompleted, scoreAway = totalRounds) and
     * metadata (dominanceScore, weightClass, records) are always updated.
     */
    @Transactional
    @CacheEvict(value = {"mmaMatches", "mmaFeaturedMatches", "mmaFightCards"}, allEntries = true)
    public Match saveOrUpdate(Match match) {
        if (match.getExternalId() == null || match.getExternalId().isBlank()) {
            return matchRepo.save(match);
        }

        return matchRepo.findByExternalId(match.getExternalId())
                .map(existing -> {

                    // ── Status guard ───────────────────────────────────────
                    if (match.getStatus() != null) {
                        if (isPermittedTransition(existing.getStatus(), match.getStatus())) {
                            existing.setStatus(match.getStatus());
                        } else {
                            if (warnedDemotions.add(existing.getExternalId())) {
                                log.warn("MMA saveOrUpdate: blocked status demotion " +
                                                "externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            } else {
                                log.debug("MMA saveOrUpdate: repeated demotion blocked " +
                                                "externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            }
                            return matchRepo.save(existing);
                        }
                    }

                    // ── Round counters + metadata — always update ──────────
                    if (match.getScoreHome() != null) existing.setScoreHome(match.getScoreHome());
                    if (match.getScoreAway() != null) existing.setScoreAway(match.getScoreAway());
                    if (match.getMetadata()  != null) existing.setMetadata(match.getMetadata());

                    // ── League / weight class — always overwrite ───────────
                    if (!isMissing(match.getLeague())) existing.setLeague(match.getLeague());

                    // ── Sparse fields — fill in only when missing ──────────
                    if (isMissing(existing.getHomeTeam())   && !isMissing(match.getHomeTeam()))   existing.setHomeTeam(match.getHomeTeam());
                    if (isMissing(existing.getAwayTeam())   && !isMissing(match.getAwayTeam()))   existing.setAwayTeam(match.getAwayTeam());
                    if ((isMissing(existing.getSport()) || existing.getSportEnum() == null)
                            && (!isMissing(match.getSport()) || match.getSportEnum() != null)) {
                        Sport sportEnum = match.getSportEnum() != null
                                ? match.getSportEnum()
                                : Sport.fromKey(match.getSport());
                        existing.setSportEnum(sportEnum);
                        existing.setSport(sportEnum.key()); // ← ADD THIS LINE
                        log.debug("saveOrUpdate: updated sportEnum={} for externalId={}", sportEnum, existing.getExternalId());
                    }
                    if (isMissing(existing.getHomeLogo())   && !isMissing(match.getHomeLogo()))    existing.setHomeLogo(match.getHomeLogo());
                    if (isMissing(existing.getAwayLogo())   && !isMissing(match.getAwayLogo()))    existing.setAwayLogo(match.getAwayLogo());
                    if (existing.getSource() == null && match.getSource() != null)                 existing.setSource(match.getSource());

                    // ── Kickoff healing — only upgrade to a real timestamp ─
                    if (match.getKickoffAt() != null) {
                        boolean existingMissing = existing.getKickoffAt() == null
                                || !isRealKickoff(existing.getKickoffAt());
                        boolean incomingReal    = isRealKickoff(match.getKickoffAt());
                        if (existingMissing && incomingReal) {
                            log.debug("MMA saveOrUpdate: healing kickoffAt externalId={} old={} new={}",
                                    existing.getExternalId(), existing.getKickoffAt(), match.getKickoffAt());
                            existing.setKickoffAt(match.getKickoffAt());
                        } else if (existing.getKickoffAt() == null) {
                            existing.setKickoffAt(match.getKickoffAt());
                        }
                    }

                    log.debug("MMA saveOrUpdate: updated externalId={} status='{}' home='{}' away='{}' " +
                                    "league='{}' kickoff='{}'",
                            existing.getExternalId(), existing.getStatus(),
                            existing.getHomeTeam(), existing.getAwayTeam(),
                            existing.getLeague(), existing.getKickoffAt());
                    return matchRepo.save(existing);
                })
                .orElseGet(() -> {
                    log.debug("MMA saveOrUpdate: inserting new externalId={} home='{}' away='{}' " +
                                    "league='{}' kickoff='{}'",
                            match.getExternalId(), match.getHomeTeam(), match.getAwayTeam(),
                            match.getLeague(), match.getKickoffAt());
                    return matchRepo.save(match);
                });
    }

    /**
     * Force-finishes any LIVE MMA match whose kickoff predates {@code cutoff}.
     * Uses sport‑scoped query so football rows are never touched.
     *
     * @return number of matches force-finished
     */
    @Transactional
    @CacheEvict(value = {"mmaMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff) {
        List<Match> stale = matchRepo.findStaleLiveBySport(SPORT, cutoff, MatchSource.ADMIN_CREATED);
        if (stale.isEmpty()) return 0;
        log.info("MMA finishStaleLiveMatches: force-finishing {} stale match(es)", stale.size());
        for (Match m : stale) {
            m.setStatus("FINISHED");
            matchRepo.save(m);
        }
        return stale.size();
    }


    /**
     * Returns all FINISHED MMA matches that have not yet been settled
     * (settledAt == null).  Used by MmaSettlementEngine.
     */
    public List<Match> getUnsettledFinished() {
        return matchRepo.findUnsettledFinishedBySport(SPORT);
    }

    /**
     * Stamps settledAt = now on the given match row, marking it as fully
     * processed by the settlement engine.  Mirrors MatchService.markSettled().
     */
    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
        });
    }
    // ══════════════════════════════════════════════════════════════════════
    // STATIC EXTERNAL-ID HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the canonical external ID for a specific bout.
     * e.g. buildExternalId("600033284", 0) → "espn-mma-600033284-bout0"
     */
    public static String buildExternalId(String espnEventId, int boutIndex) {
        return ID_PREFIX + espnEventId + "-bout" + boutIndex;
    }

    /**
     * Strips the "espn-mma-<eventId>-bout<N>" prefix to recover just the
     * ESPN event ID.  e.g. "espn-mma-600033284-bout0" → "600033284".
     */
    public static String stripMmaEventId(String externalId) {
        if (externalId == null) return "";
        // Strip leading prefix
        String s = externalId.startsWith(ID_PREFIX) ? externalId.substring(ID_PREFIX.length()) : externalId;
        // Remove trailing "-boutN"
        int boutIdx = s.lastIndexOf("-bout");
        return boutIdx >= 0 ? s.substring(0, boutIdx) : s;
    }

    /**
     * Extracts the bout index from an external ID.
     * e.g. "espn-mma-600033284-bout2" → 2.
     * Returns 0 if not parseable.
     */
    public static int extractBoutIndex(String externalId) {
        if (externalId == null) return 0;
        int boutIdx = externalId.lastIndexOf("-bout");
        if (boutIdx < 0) return 0;
        try {
            return Integer.parseInt(externalId.substring(boutIdx + 5));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Extracts the elapsed-minute equivalent from metadata for logging/display.
     * For MMA we use the current round number as a proxy (not a precise minute).
     */
    private int extractMinute(Match match) {
        if (match.getMetadata() != null) {
            Object round = match.getMetadata().get("currentRound");
            if (round != null) {
                try { return Integer.parseInt(round.toString()); }
                catch (NumberFormatException ignored) {}
            }
        }
        return 1;
    }

    /**
     * Reads dominanceScore from match metadata, falling back to 0.0.
     * The poller writes this after computing:
     *   (f1Strikes - f2Strikes) / max(total, 1)
     */
    private double deriveDominanceFromMetadata(Match match) {
        if (match.getMetadata() == null) return 0.0;
        Object ds = match.getMetadata().get("dominanceScore");
        if (ds == null) return 0.0;
        try {
            double val = Double.parseDouble(ds.toString());
            return Math.max(-1.0, Math.min(1.0, val));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Safe metadata string extractor — returns "" on null / missing key.
     */
    private String extractMetaString(Match match, String key) {
        if (match.getMetadata() == null) return "";
        Object val = match.getMetadata().get(key);
        return val != null ? val.toString() : "";
    }
}

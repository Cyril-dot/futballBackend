package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.TennisDataService;
import com.speedbet.api.sportsdata.TennisDataService.Tour;
import com.speedbet.api.sportsdata.odds.TennisLiveOddsService;
import com.speedbet.api.sportsdata.odds.TennisOddsPersistenceService;
import com.speedbet.api.sportsdata.odds.TennisPreMatchOddsService;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class TennisMatchService {

    private final MatchRepository              matchRepo;
    private final OddsRepository               oddsRepo;
    private final TennisDataService            tennisDataService;
    private final TennisPreMatchOddsService    preMatchGenerator;
    private final TennisLiveOddsService        liveOddsGenerator;
    private final TennisOddsPersistenceService oddsPersistenceService;

    // ── Sport discriminator ───────────────────────────────────────────────
    public static final String SPORT = "tennis";

    // ── External-ID prefix ────────────────────────────────────────────────
    public static final String ID_PREFIX_ATP = "espn-tennis-atp-";
    public static final String ID_PREFIX_WTA = "espn-tennis-wta-";

    // ── Live odds cache — 30 s TTL (tennis points change fast) ───────────
    private static final long LIVE_ODDS_TTL_MS = 30_000L;

    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveWinnerCache =
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
            throw ApiException.notFound("Tennis match not found: " + id);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE — public API (used by TennisLiveScorePoller)
    // ══════════════════════════════════════════════════════════════════════

    public boolean isWinnerCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveWinnerCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveWinnerOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveWinnerCache.put(matchId, new OddsCacheEntry(odds, expires));
    }

    public List<Map<String, Object>> getWinnerOddsFromCache(UUID matchId) {
        OddsCacheEntry entry = liveWinnerCache.get(matchId);
        return (entry != null && entry.isValid()) ? entry.odds() : null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BASIC QUERIES — DB-backed, scoped to sport="tennis"
    // ══════════════════════════════════════════════════════════════════════

    /** All LIVE tennis matches, ordered by kickoff ascending. */
    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "LIVE");
        log.info("Tennis getLiveMatches: {} LIVE match(es)", matches.size());
        return matches;
    }

    /**
     * LIVE matches for a specific tour only.
     *
     * @param tour Tour.ATP or Tour.WTA
     */
    public List<Match> getLiveMatches(Tour tour) {
        String prefix = tourPrefix(tour);
        List<Match> matches = getLiveMatches().stream()
                .filter(m -> m.getExternalId() != null && m.getExternalId().startsWith(prefix))
                .toList();
        log.info("Tennis getLiveMatches({}): {} LIVE match(es)", tour.displayName(), matches.size());
        return matches;
    }

    /** Upcoming tennis matches (next 7 days), logos-first then earliest kickoff. */
    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport(SPORT, now, now.plus(7, ChronoUnit.DAYS))
                .stream()
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("Tennis getUpcomingMatches: {} upcoming match(es)", matches.size());
        return matches;
    }

    /**
     * Upcoming matches for a specific tour.
     *
     * @param tour Tour.ATP or Tour.WTA
     */
    public List<Match> getUpcomingMatches(Tour tour) {
        String prefix = tourPrefix(tour);
        List<Match> matches = getUpcomingMatches().stream()
                .filter(m -> m.getExternalId() != null && m.getExternalId().startsWith(prefix))
                .toList();
        log.info("Tennis getUpcomingMatches({}): {} upcoming match(es)", tour.displayName(), matches.size());
        return matches;
    }

    /**
     * Recent FINISHED matches within 72 hours, capped at {@code limit}.
     */
    public List<Match> getRecentResults(int limit) {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "FINISHED")
                .stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .limit(limit)
                .toList();
        log.info("Tennis getRecentResults: {} FINISHED match(es) (72h, cap={})", matches.size(), limit);
        return matches;
    }

    public List<Match> getRecentResults() {
        return getRecentResults(20);
    }

    @Cacheable("tennisFeaturedMatches")
    public List<Match> getFeaturedMatches() {
        List<Match> matches = matchRepo.findByFeaturedTrueOrderByKickoffAt().stream()
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()))
                .toList();
        log.info("Tennis getFeaturedMatches: {} featured match(es)", matches.size());
        return matches;
    }

    /** Fetch a single tennis match by its internal UUID. */
    public Match getById(String id) {
        Match m = matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("Tennis match not found: " + id));
        if (!SPORT.equalsIgnoreCase(m.getSport())) {
            throw ApiException.notFound("Tennis match not found: " + id);
        }
        return m;
    }

    /** Fetch a single tennis match by its ESPN external ID. */
    public Optional<Match> getByExternalId(String externalId) {
        return matchRepo.findByExternalId(externalId)
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // SETTLEMENT SUPPORT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns all FINISHED tennis matches that have not yet been settled
     * (settledAt == null). Used by a TennisSettlementEngine.
     */
    public List<Match> getUnsettledFinished() {
        return matchRepo.findUnsettledFinishedBySport(SPORT);
    }

    /**
     * Stamps settledAt = now on the given match row.
     * Mirrors MatchService.markSettled().
     */
    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — direct endpoints
    // ══════════════════════════════════════════════════════════════════════

    /**
     * tennis_match_winner odds for a single match.
     *
     * <ul>
     *   <li>LIVE    → served from in-memory cache if valid; otherwise generated
     *                 on-demand via {@link TennisLiveOddsService} and persisted.</li>
     *   <li>UPCOMING → generated pre-match via {@link TennisPreMatchOddsService}
     *                 and persisted.</li>
     *   <li>FINISHED → empty list.</li>
     * </ul>
     */
    public List<Map<String, Object>> getMatchOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();

        if ("LIVE".equals(status)) {
            List<Map<String, Object>> cached = getWinnerOddsFromCache(match.getId());
            if (cached != null) return cached;

            int     p1Sets     = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int     p2Sets     = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int     currentSet = extractCurrentSetFromMetadata(match);
            boolean bestOfFive = extractBestOfFive(match);

            List<Map<String, Object>> generated = liveOddsGenerator.generateLiveOdds(
                    match.getHomeTeam(), match.getAwayTeam(),
                    p1Sets, p2Sets, currentSet, bestOfFive);
            cacheLiveWinnerOdds(match.getId(), generated);
            persistOddsAsync(match, generated, "tennis_match_winner_live");
            return generated;
        }

        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            List<Map<String, Object>> odds = preMatchGenerator.generatePreMatchOdds(
                    match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
            persistOddsAsync(match, odds, "tennis_match_winner");
            return odds;
        }

        return List.of();
    }

    /** All persisted {@link Odds} rows for a match (all markets). */
    public List<Odds> getOddsForMatch(String id) {
        return oddsRepo.findByMatchId(toUuid(id));
    }

    /**
     * Bundles a list of matches with their current winner odds.
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
                OddsCacheEntry cached = liveWinnerCache.get(match.getId());
                if (cached != null && cached.isValid()) {
                    entry.put("odds", cached.odds());
                } else {
                    List<Map<String, Object>> odds = preMatchGenerator.generatePreMatchOdds(
                            match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
                    persistOddsAsync(match, odds, "tennis_match_winner");
                    entry.put("odds", odds);
                }
            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                List<Map<String, Object>> odds = preMatchGenerator.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
                persistOddsAsync(match, odds, "tennis_match_winner");
                entry.put("odds", odds);
            } else {
                entry.put("odds", List.of());
            }
            out.add(entry);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE REFRESH — called by TennisLiveScorePoller every 30 s
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Refreshes the in-memory live winner odds cache for all currently LIVE
     * tennis matches and persists the updated odds rows.
     * Uses set scores and metadata stored on the Match entity.
     */
    public void refreshLiveOddsCache(List<Match> liveMatches) {
        if (liveMatches.isEmpty()) return;
        int refreshed = 0;
        for (Match match : liveMatches) {
            try {
                int     p1Sets     = match.getScoreHome() != null ? match.getScoreHome() : 0;
                int     p2Sets     = match.getScoreAway() != null ? match.getScoreAway() : 0;
                int     currentSet = extractCurrentSetFromMetadata(match);
                boolean bestOfFive = extractBestOfFive(match);

                List<Map<String, Object>> liveOdds = liveOddsGenerator.generateLiveOdds(
                        match.getHomeTeam(), match.getAwayTeam(),
                        p1Sets, p2Sets, currentSet, bestOfFive);
                cacheLiveWinnerOdds(match.getId(), liveOdds);
                persistOddsAsync(match, liveOdds, "tennis_match_winner_live");
                refreshed++;
            } catch (Exception e) {
                log.warn("Tennis refreshLiveOddsCache: matchId={} failed — {}",
                        match.getId(), e.getMessage());
            }
        }
        log.info("Tennis refreshLiveOddsCache: {}/{} match(es) refreshed", refreshed, liveMatches.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // MATCH DETAIL / SCORE / RANKINGS — ESPN pass-throughs
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Full ESPN match summary: set-by-set scores, aces, double faults,
     * first serve %, winners, unforced errors.  Always fresh.
     */
    @Cacheable(value = "tennisMatchDetail", key = "#id")
    public Map<String, Object> getMatchDetail(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String espnMatchId = extractMetaString(match, "espnMatchId");
        if (espnMatchId.isBlank()) espnMatchId = stripTennisMatchId(match.getExternalId());
        Tour tour = extractTour(match.getExternalId());
        if (tour == null) {
            log.warn("Tennis getMatchDetail: cannot resolve tour for externalId={}", match.getExternalId());
            return Map.of();
        }
        Map<String, Object> summary = tennisDataService.getMatchSummary(espnMatchId, tour);
        if (summary.isEmpty()) return Map.of();
        return Map.of("source", "espn-tennis", "tour", tour.displayName(), "data", summary);
    }

    /**
     * Quick score snapshot: players, set scores, current game score, surface, round.
     */
    public Map<String, Object> getMatchScore(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String espnMatchId = extractMetaString(match, "espnMatchId");
        if (espnMatchId.isBlank()) espnMatchId = stripTennisMatchId(match.getExternalId());
        Tour tour = extractTour(match.getExternalId());
        if (tour == null) return Map.of();
        return tennisDataService.getMatchScore(espnMatchId, tour);
    }

    /**
     * Match events / metadata stored on the {@link Match} entity.
     * Falls back to the ESPN match summary if metadata is absent.
     */
    public Map<String, Object> getEvents(String id) {
        Match match = getById(id);
        if (match.getMetadata() != null && !match.getMetadata().isEmpty()) {
            return match.getMetadata();
        }
        String espnMatchId = stripTennisMatchId(match.getExternalId());
        Tour   tour        = extractTour(match.getExternalId());
        if (tour == null || espnMatchId.isBlank()) return Map.of("events", List.of());
        Map<String, Object> summary = tennisDataService.getMatchSummary(espnMatchId, tour);
        return summary.isEmpty()
                ? Map.of("events", List.of())
                : Map.of("source", "espn-tennis", "data", summary);
    }

    /**
     * Combined match details: score snapshot + full summary + live/pre odds.
     */
    public Map<String, Object> getFullMatchDetails(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String espnMatchId = extractMetaString(match, "espnMatchId");
        if (espnMatchId.isBlank()) espnMatchId = stripTennisMatchId(match.getExternalId());
        Tour tour = extractTour(match.getExternalId());
        if (tour == null) return Map.of();
        return tennisDataService.getFullMatchDetails(espnMatchId, tour);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ESPN PASS-THROUGH — raw scoreboard
    // ══════════════════════════════════════════════════════════════════════

    /** All tournaments on the ATP scoreboard (cached). */
    public List<Map<String, Object>> getAtpTournaments() {
        return tennisDataService.getAtpTournaments();
    }

    /** All tournaments on the WTA scoreboard (cached). */
    public List<Map<String, Object>> getWtaTournaments() {
        return tennisDataService.getWtaTournaments();
    }

    /** All ATP matches (flattened across all tournaments, cached). */
    public List<Map<String, Object>> getAllAtpMatches() {
        return tennisDataService.getAllAtpMatches();
    }

    /** All WTA matches (flattened across all tournaments, cached). */
    public List<Map<String, Object>> getAllWtaMatches() {
        return tennisDataService.getAllWtaMatches();
    }

    /** ATP matches currently in progress — fresh, no cache. */
    public List<Map<String, Object>> getAtpLiveMatches() {
        return tennisDataService.getLiveMatches(Tour.ATP);
    }

    /** WTA matches currently in progress — fresh, no cache. */
    public List<Map<String, Object>> getWtaLiveMatches() {
        return tennisDataService.getLiveMatches(Tour.WTA);
    }

    /** ATP upcoming matches from ESPN scoreboard. */
    public List<Map<String, Object>> getAtpUpcomingMatches() {
        return tennisDataService.getUpcomingMatches(Tour.ATP);
    }

    /** WTA upcoming matches from ESPN scoreboard. */
    public List<Map<String, Object>> getWtaUpcomingMatches() {
        return tennisDataService.getUpcomingMatches(Tour.WTA);
    }

    /** Current ATP rankings. */
    public Map<String, Object> getAtpRankings() {
        return tennisDataService.getAtpRankings();
    }

    /** Current WTA rankings. */
    public Map<String, Object> getWtaRankings() {
        return tennisDataService.getWtaRankings();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Upserts a tennis {@link Match} row.
     *
     * <p>Enforces the status transition guard (FINISHED is terminal).
     * Score fields (sets won), metadata (currentSet, bestOfFive, etc.), and
     * league (surface) are always updated. Sparse fields (logos, kickoff) are
     * filled in only when the existing row is missing them.
     */
    @Transactional
    @CacheEvict(value = {"tennisMatches", "tennisFeaturedMatches", "tennisMatchDetail"}, allEntries = true)
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
                                log.warn("Tennis saveOrUpdate: blocked status demotion " +
                                                "externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            } else {
                                log.debug("Tennis saveOrUpdate: repeated demotion blocked " +
                                                "externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            }
                            return matchRepo.save(existing);
                        }
                    }

                    // ── Set scores + metadata — always update ──────────────
                    if (match.getScoreHome() != null) existing.setScoreHome(match.getScoreHome());
                    if (match.getScoreAway() != null) existing.setScoreAway(match.getScoreAway());
                    if (match.getMetadata()  != null) existing.setMetadata(match.getMetadata());

                    // ── Surface / league — always overwrite with resolved value
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
                            log.debug("Tennis saveOrUpdate: healing kickoffAt externalId={} old={} new={}",
                                    existing.getExternalId(), existing.getKickoffAt(), match.getKickoffAt());
                            existing.setKickoffAt(match.getKickoffAt());
                        } else if (existing.getKickoffAt() == null) {
                            existing.setKickoffAt(match.getKickoffAt());
                        }
                    }

                    log.debug("Tennis saveOrUpdate: updated externalId={} status='{}' home='{}' " +
                                    "away='{}' league='{}' sets={}-{} kickoff='{}'",
                            existing.getExternalId(), existing.getStatus(),
                            existing.getHomeTeam(), existing.getAwayTeam(),
                            existing.getLeague(),
                            existing.getScoreHome(), existing.getScoreAway(),
                            existing.getKickoffAt());
                    return matchRepo.save(existing);
                })
                .orElseGet(() -> {
                    log.debug("Tennis saveOrUpdate: inserting new externalId={} home='{}' " +
                                    "away='{}' league='{}' kickoff='{}'",
                            match.getExternalId(), match.getHomeTeam(), match.getAwayTeam(),
                            match.getLeague(), match.getKickoffAt());
                    return matchRepo.save(match);
                });
    }

    /**
     * Force-finishes any LIVE tennis match whose kickoff predates {@code cutoff}.
     * Uses sport‑scoped query so other sport rows are never touched.
     */
    @Transactional
    @CacheEvict(value = {"tennisMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff) {
        List<Match> stale = matchRepo.findStaleLiveBySport(SPORT, cutoff, MatchSource.ADMIN_CREATED);
        if (stale.isEmpty()) return 0;
        log.info("Tennis finishStaleLiveMatches: force-finishing {} stale match(es)", stale.size());
        for (Match m : stale) {
            m.setStatus("FINISHED");
            matchRepo.save(m);
        }
        return stale.size();
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATIC EXTERNAL-ID HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the canonical external ID for a tennis match.
     * e.g. buildExternalId("401234567", Tour.ATP) → "espn-tennis-atp-401234567"
     */
    public static String buildExternalId(String espnMatchId, Tour tour) {
        return tourPrefix(tour) + espnMatchId;
    }

    /**
     * Strips the tour prefix to recover the raw ESPN match ID.
     * e.g. "espn-tennis-atp-401234567" → "401234567"
     */
    public static String stripTennisMatchId(String externalId) {
        if (externalId == null) return "";
        if (externalId.startsWith(ID_PREFIX_ATP)) return externalId.substring(ID_PREFIX_ATP.length());
        if (externalId.startsWith(ID_PREFIX_WTA)) return externalId.substring(ID_PREFIX_WTA.length());
        return externalId;
    }

    /**
     * Resolves the {@link Tour} from an external ID prefix.
     * Returns null if the ID is not a recognized tennis prefix.
     */
    public static Tour extractTour(String externalId) {
        if (externalId == null) return null;
        if (externalId.startsWith(ID_PREFIX_ATP)) return Tour.ATP;
        if (externalId.startsWith(ID_PREFIX_WTA)) return Tour.WTA;
        return null;
    }

    /** Returns the correct ID prefix string for a tour. */
    public static String tourPrefix(Tour tour) {
        return tour == Tour.WTA ? ID_PREFIX_WTA : ID_PREFIX_ATP;
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Persists generated odds rows via {@link TennisOddsPersistenceService}.
     * Failures are caught and logged — odds persistence must never block the
     * API response.
     */
    private void persistOddsAsync(Match match, List<Map<String, Object>> odds, String market) {
        if (odds == null || odds.isEmpty()) return;
        try {
            oddsPersistenceService.saveOdds(match, odds, market);
            log.debug("Tennis persistOdds: saved {} odd(s) market='{}' matchId={}",
                    odds.size(), market, match.getId());
        } catch (Exception e) {
            log.warn("Tennis persistOdds: failed market='{}' matchId={} — {}",
                    market, match.getId(), e.getMessage());
        }
    }

    private int extractCurrentSetFromMetadata(Match match) {
        String val = extractMetaString(match, "currentSet");
        if (!val.isBlank()) {
            try { return Integer.parseInt(val); }
            catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    private boolean extractBestOfFive(Match match) {
        return "true".equalsIgnoreCase(extractMetaString(match, "bestOfFive"));
    }

    private String extractMetaString(Match match, String key) {
        if (match.getMetadata() == null) return "";
        Object val = match.getMetadata().get(key);
        return val != null ? val.toString() : "";
    }
}

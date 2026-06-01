package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.BaseballDataService;
import com.speedbet.api.sportsdata.odds.MlbLiveOddsGeneratorService;
import com.speedbet.api.sportsdata.odds.MlbOddsGeneratorService;
import com.speedbet.api.sportsdata.odds.MlbOddsPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Match service scoped to MLB baseball.
 *
 * ── Responsibilities ─────────────────────────────────────────────────────
 *
 *   - CRUD / query layer for Match rows where sport = "baseball"
 *   - Live odds cache management (two-way moneyline only — no draw in baseball)
 *   - Delegates ESPN data fetching to {@link BaseballDataService}
 *   - Delegates odds generation to {@link MlbOddsGeneratorService} (pre-match)
 *     and {@link MlbLiveOddsGeneratorService} (in-play)
 *   - Delegates odds persistence to {@link MlbOddsPersistenceService}
 *
 * ── Status transition guard ───────────────────────────────────────────────
 *
 *   UPCOMING  → LIVE      ✓
 *   UPCOMING  → FINISHED  ✓
 *   LIVE      → FINISHED  ✓
 *   LIVE      → UPCOMING  ✗  (blocked — poller mis-classification guard)
 *   FINISHED  → *         ✗  (terminal — never demoted)
 *
 * ── Live odds cache ───────────────────────────────────────────────────────
 *
 *   TTL: 2 minutes.  Market key: "mlb_live_moneyline".
 *   Cache is checked by the poller before generating fresh odds so that a
 *   brief ESPN outage does not blank out the betting surface.
 *
 * ── External ID convention ───────────────────────────────────────────────
 *
 *   All MLB matches are stored with externalId prefix "espn-mlb-".
 *   Raw ESPN game IDs are stripped via {@link #stripMlbPrefix(String)}.
 *
 * ── No draw ──────────────────────────────────────────────────────────────
 *
 *   Baseball has no draw.  Only HOME and AWAY selections are produced.
 *   The odds cache stores exactly two entries per game.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaseballMatchService {

    private static final String SPORT            = "baseball";
    private static final String EXT_ID_PREFIX    = "espn-mlb-";
    private static final long   LIVE_ODDS_TTL_MS = 2 * 60_000L;

    // ── Repositories & services ───────────────────────────────────────────
    private final MatchRepository              matchRepo;
    private final OddsRepository               oddsRepo;
    private final BaseballDataService          baseballDataService;
    private final MlbOddsGeneratorService      preMatchGenerator;
    private final MlbLiveOddsGeneratorService  liveGenerator;
    private final MlbOddsPersistenceService    mlbOddsPersistenceService;

    // ── Live odds cache ───────────────────────────────────────────────────
    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveOddsCache = new ConcurrentHashMap<>();

    private record OddsCacheEntry(List<Map<String, Object>> odds, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() <= expiresAt; }
    }

    // ── Demotion warn-once guard ──────────────────────────────────────────
    private final Set<String> warnedDemotions = ConcurrentHashMap.newKeySet();

    // ═════════════════════════════════════════════════════════════════════
    //  STATUS TRANSITION GUARD
    // ═════════════════════════════════════════════════════════════════════

    private static boolean isPermittedTransition(String existing, String incoming) {
        if (existing == null || existing.equals(incoming)) return true;
        return switch (existing) {
            case "FINISHED" -> false;
            case "LIVE"     -> "FINISHED".equals(incoming);
            default         -> true;
        };
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE ODDS CACHE HELPERS
    // ═════════════════════════════════════════════════════════════════════

    public boolean isOddsCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveOddsCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveOddsCache.put(matchId, new OddsCacheEntry(odds, expires));
        log.debug("cacheLiveOdds (MLB): matchId={} cached {} odd(s)", matchId, odds.size());
    }

    public List<Map<String, Object>> getLiveOddsFromCache(UUID matchId) {
        OddsCacheEntry entry = liveOddsCache.get(matchId);
        return (entry != null && entry.isValid()) ? entry.odds() : null;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  BASIC QUERIES — DB-backed, scoped to sport = "baseball"
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All currently LIVE baseball matches.
     * Used by the poller to drive live score and live odds updates.
     */
    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE").stream()
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()))
                .toList();
        log.info("getLiveMatches (MLB): {} LIVE match(es)", matches.size());
        return matches;
    }

    /**
     * All UPCOMING baseball matches scheduled within the next 7 days.
     */
    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()))
                .toList();
        log.info("getUpcomingMatches (MLB): {} upcoming match(es)", matches.size());
        return matches;
    }

    /**
     * All baseball matches kicking off today (UTC).
     */
    @Cacheable("mlbTodayMatches")
    public List<Match> getTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetween(startOfDay, endOfDay).stream()
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()))
                .toList();
        log.info("getTodayMatches (MLB): {} match(es) today UTC", matches.size());
        return matches;
    }

    /**
     * FINISHED baseball matches from the past 72 hours.
     * 72-hour window ensures games completed late the previous evening
     * remain visible on the following morning.
     */
    public List<Match> getRecentResults() {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("FINISHED").stream()
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()))
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .toList();
        log.info("getRecentResults (MLB): {} finished match(es) in 72h window", matches.size());
        return matches;
    }

    /**
     * FINISHED baseball matches that have not yet been settled.
     * Used by MlbSettlementService to find bets ready for payout.
     * Scoped to sport="baseball" so no other sport's rows are touched.
     */
    public List<Match> getUnsettledFinished() {
        List<Match> matches = matchRepo.findUnsettledFinished().stream()
                .filter(m -> SPORT.equalsIgnoreCase(m.getSport()))
                .toList();
        log.info("getUnsettledFinished (MLB): {} unsettled finished match(es)", matches.size());
        return matches;
    }

    /**
     * Marks a match as settled by setting settledAt to now.
     * Called by MlbSettlementService after all bets for a match have been resolved.
     *
     * @param id internal Match UUID string
     */
    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
            log.info("markSettled (MLB): matchId={} settledAt={}", id, m.getSettledAt());
        });
    }

    /**
     * Single baseball match by internal UUID.
     *
     * @throws ApiException 404 if not found or ID is not a valid UUID
     */
    public Match getById(String id) {
        return matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("Baseball match not found: " + id));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ESPN PASS-THROUGH HELPERS
    // ═════════════════════════════════════════════════════════════════════

    /** Live MLB games currently in progress — fresh from ESPN, no cache. */
    public List<Map<String, Object>> getEspnMlbLive() {
        return baseballDataService.getLiveGames();
    }

    /** All MLB games for today from ESPN. */
    public List<Map<String, Object>> getEspnMlbToday() {
        return baseballDataService.getTodayGames();
    }

    /** Upcoming (pre-game) MLB games for today from ESPN. */
    public List<Map<String, Object>> getEspnMlbUpcoming() {
        return baseballDataService.getUpcomingGames();
    }

    /** MLB standings (AL + NL, by division) from ESPN. */
    public Map<String, Object> getEspnMlbStandings() {
        return baseballDataService.getStandings();
    }

    /**
     * Full game details for a single MLB game: score + ESPN box score + odds.
     *
     * @param espnGameId raw ESPN event ID (without "espn-mlb-" prefix)
     */
    public Map<String, Object> getEspnMlbGameDetail(String espnGameId) {
        return baseballDataService.getFullGameDetails(espnGameId);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ODDS — direct endpoints
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Moneyline odds for a single baseball match.
     *
     * <p>For LIVE matches: returns cached live odds if still valid, otherwise
     * generates and caches fresh in-play odds from the ESPN live feed.
     * <p>For UPCOMING matches: generates pre-match odds (deterministic seed).
     * <p>For FINISHED matches: returns an empty list.
     *
     * @param id internal Match UUID string
     */
    public List<Map<String, Object>> getMatchOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();

        if ("LIVE".equals(status)) {
            List<Map<String, Object>> cached = getLiveOddsFromCache(match.getId());
            if (cached != null) return cached;

            // Fall back to generating from live state
            return generateAndCacheLiveOddsForMatch(match);
        }

        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return generatePreMatchOddsForMatch(match);
        }

        return List.of();
    }

    /**
     * All persisted odds rows for a match from the database.
     * Includes both pre-match and live moneyline entries where available.
     */
    public List<Odds> getPersistedOdds(String id) {
        return oddsRepo.findByMatchId(toUuid(id));
    }

    /**
     * Full match detail from ESPN (box score, pitching stats, scoring plays).
     * Routes via the espn-mlb- externalId to get the raw game ID.
     */
    public Map<String, Object> getMatchDetail(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripMlbPrefix(match.getExternalId());
        Map<String, Object> summary = baseballDataService.getGameSummary(gameId);
        if (!summary.isEmpty()) return Map.of("source", "espn-mlb", "data", summary);
        return Map.of();
    }

    /**
     * Current score snapshot for a match (teams, runs, inning, half, outs).
     */
    public Map<String, Object> getMatchScore(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripMlbPrefix(match.getExternalId());
        return baseballDataService.getGameScore(gameId);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE ODDS CACHE REFRESH  (called by the poller)
    // ═════════════════════════════════════════════════════════════════════

    public void refreshLiveOddsCache(List<Match> liveMatches) {
        if (liveMatches.isEmpty()) return;

        List<Map<String, Object>> espnLiveGames = baseballDataService.getLiveGames();
        int refreshed = 0, failed = 0;

        for (Match match : liveMatches) {
            try {
                String gameId = stripMlbPrefix(match.getExternalId());
                Map<String, Object> espnGame = espnLiveGames.stream()
                        .filter(g -> gameId.equals(BaseballDataService.extractGameId(g)))
                        .findFirst()
                        .orElse(null);

                if (espnGame == null) {
                    log.warn("refreshLiveOddsCache (MLB): game not found in ESPN live feed — matchId={} gameId={}",
                            match.getId(), gameId);
                    failed++;
                    continue;
                }

                Optional<Map<String, Object>> homeOpt = BaseballDataService.extractHomeCompetitor(espnGame);
                Optional<Map<String, Object>> awayOpt = BaseballDataService.extractAwayCompetitor(espnGame);

                if (homeOpt.isEmpty() || awayOpt.isEmpty()) {
                    log.warn("refreshLiveOddsCache (MLB): could not resolve competitors — matchId={}", match.getId());
                    failed++;
                    continue;
                }

                String homeTeam  = BaseballDataService.extractTeamName(homeOpt.get());
                String awayTeam  = BaseballDataService.extractTeamName(awayOpt.get());
                int    homeScore = parseScore(BaseballDataService.extractScore(homeOpt.get()));
                int    awayScore = parseScore(BaseballDataService.extractScore(awayOpt.get()));
                int    inning    = BaseballDataService.extractInning(espnGame);
                String half      = BaseballDataService.extractInningHalf(espnGame);
                int    outs      = BaseballDataService.extractOuts(espnGame);

                List<Map<String, Object>> liveOdds = liveGenerator.generateLiveOdds(
                        homeTeam, awayTeam, homeScore, awayScore, inning, half, outs);

                cacheLiveOdds(match.getId(), liveOdds);
                refreshed++;

            } catch (Exception e) {
                failed++;
                log.warn("refreshLiveOddsCache (MLB): failed matchId={} — {}", match.getId(), e.getMessage());
            }
        }

        log.info("refreshLiveOddsCache (MLB): refreshed={}/{}, failed={}",
                refreshed, liveMatches.size(), failed);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  PERSISTENCE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Saves a new match or merges into an existing one matched by externalId.
     *
     * <p>Status demotion guard (same rules as {@code MatchService}):
     * FINISHED is terminal; LIVE can only transition to FINISHED.
     * Blocked demotions log a WARN on first occurrence and are silently
     * dropped on subsequent calls.
     *
     * <p>Sparse fields (logos, sport, teams) are filled in only when the
     * existing row is missing them.  Score and metadata are always updated
     * when non-null in the incoming match.
     *
     * @param match the Match entity to save or merge
     * @return the persisted (and possibly merged) Match entity
     */
    @Transactional
    @CacheEvict(value = {"mlbTodayMatches", "mlbUpcomingMatches"}, allEntries = true)
    public Match saveOrUpdate(Match match) {
        if (match.getExternalId() == null || match.getExternalId().isBlank()) {
            return matchRepo.save(match);
        }

        return matchRepo.findByExternalId(match.getExternalId())
                .map(existing -> {

                    // ── Status guard ─────────────────────────────────────
                    if (match.getStatus() != null) {
                        if (isPermittedTransition(existing.getStatus(), match.getStatus())) {
                            existing.setStatus(match.getStatus());
                        } else {
                            if (warnedDemotions.add(existing.getExternalId())) {
                                log.warn("saveOrUpdate (MLB): blocked status demotion externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            } else {
                                log.debug("saveOrUpdate (MLB): repeated demotion blocked externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            }
                            return matchRepo.save(existing);
                        }
                    }

                    // ── Score / metadata — always update when present ─────
                    if (match.getScoreHome() != null) existing.setScoreHome(match.getScoreHome());
                    if (match.getScoreAway() != null) existing.setScoreAway(match.getScoreAway());
                    if (match.getMetadata()  != null) existing.setMetadata(match.getMetadata());

                    // ── League ────────────────────────────────────────────
                    if (!isMissing(match.getLeague())) existing.setLeague(match.getLeague());

                    // ── Sparse fields — fill in only when missing ─────────
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
                    if (isMissing(existing.getHomeLogo())   && !isMissing(match.getHomeLogo()))   existing.setHomeLogo(match.getHomeLogo());
                    if (isMissing(existing.getAwayLogo())   && !isMissing(match.getAwayLogo()))   existing.setAwayLogo(match.getAwayLogo());
                    if (isMissing(existing.getLeagueLogo()) && !isMissing(match.getLeagueLogo())) existing.setLeagueLogo(match.getLeagueLogo());
                    if (existing.getSource() == null && match.getSource() != null)                existing.setSource(match.getSource());

                    // ── Kickoff healing — only upgrade to a real timestamp ─
                    // ── Kickoff healing — always update when incoming has a value ─
                    if (match.getKickoffAt() != null) {
                        existing.setKickoffAt(match.getKickoffAt());
                    }

                    log.debug("saveOrUpdate (MLB): updated externalId={} status='{}' home='{}' away='{}' league='{}'",
                            existing.getExternalId(), existing.getStatus(),
                            existing.getHomeTeam(), existing.getAwayTeam(), existing.getLeague());
                    return matchRepo.save(existing);
                })
                .orElseGet(() -> {
                    log.debug("saveOrUpdate (MLB): inserting new externalId={} home='{}' away='{}' kickoff='{}'",
                            match.getExternalId(), match.getHomeTeam(),
                            match.getAwayTeam(), match.getKickoffAt());
                    return matchRepo.save(match);
                });
    }

    /**
     * Force-finishes LIVE baseball matches whose kickoff predates {@code cutoff}.
     * Scoped to sport = "baseball" so this poller never touches football or
     * basketball rows.
     *
     * @param cutoff matches kicked off before this instant are force-finished
     * @return number of matches force-finished
     */
    @Transactional
    @CacheEvict(value = {"mlbTodayMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff) {
        List<Match> stale = matchRepo.findStaleLiveBySport(SPORT, cutoff, MatchSource.ADMIN_CREATED);
        if (stale.isEmpty()) return 0;
        log.info("finishStaleLiveMatches (MLB): force-finishing {} stale match(es)", stale.size());
        for (Match m : stale) {
            m.setStatus("FINISHED");
            matchRepo.save(m);
        }
        return stale.size();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generates live moneyline odds for a LIVE match by looking up its current
     * state in the ESPN live feed, then caches and returns the result.
     * Returns an empty list if the game cannot be resolved from the live feed.
     */
    private List<Map<String, Object>> generateAndCacheLiveOddsForMatch(Match match) {
        if (match.getExternalId() == null) return List.of();
        String gameId = stripMlbPrefix(match.getExternalId());

        Map<String, Object> espnGame = baseballDataService.getLiveGames().stream()
                .filter(g -> gameId.equals(BaseballDataService.extractGameId(g)))
                .findFirst()
                .orElse(null);

        if (espnGame == null) {
            log.warn("generateAndCacheLiveOddsForMatch (MLB): game not in live feed — gameId={}", gameId);
            return List.of();
        }

        Optional<Map<String, Object>> homeOpt = BaseballDataService.extractHomeCompetitor(espnGame);
        Optional<Map<String, Object>> awayOpt = BaseballDataService.extractAwayCompetitor(espnGame);
        if (homeOpt.isEmpty() || awayOpt.isEmpty()) return List.of();

        String homeTeam  = BaseballDataService.extractTeamName(homeOpt.get());
        String awayTeam  = BaseballDataService.extractTeamName(awayOpt.get());
        int    homeScore = parseScore(BaseballDataService.extractScore(homeOpt.get()));
        int    awayScore = parseScore(BaseballDataService.extractScore(awayOpt.get()));
        int    inning    = BaseballDataService.extractInning(espnGame);
        String half      = BaseballDataService.extractInningHalf(espnGame);
        int    outs      = BaseballDataService.extractOuts(espnGame);

        List<Map<String, Object>> odds = liveGenerator.generateLiveOdds(
                homeTeam, awayTeam, homeScore, awayScore, inning, half, outs);
        cacheLiveOdds(match.getId(), odds);
        return odds;
    }

    /**
     * Generates pre-match moneyline odds for a match using team names, records,
     * and starting pitcher ERAs resolved from the ESPN scoreboard.
     */
    private List<Map<String, Object>> generatePreMatchOddsForMatch(Match match) {
        String homeTeam = match.getHomeTeam();
        String awayTeam = match.getAwayTeam();

        // Attempt to enrich with record + pitcher ERA from today's ESPN scoreboard
        String homeRecord = "", awayRecord = "", homeEra = null, awayEra = null;
        if (match.getExternalId() != null) {
            String gameId = stripMlbPrefix(match.getExternalId());
            for (Map<String, Object> game : baseballDataService.getTodayGames()) {
                if (!gameId.equals(BaseballDataService.extractGameId(game))) continue;
                Optional<Map<String, Object>> homeOpt = BaseballDataService.extractHomeCompetitor(game);
                Optional<Map<String, Object>> awayOpt = BaseballDataService.extractAwayCompetitor(game);
                if (homeOpt.isPresent()) homeRecord = BaseballDataService.extractRecord(homeOpt.get());
                if (awayOpt.isPresent()) awayRecord = BaseballDataService.extractRecord(awayOpt.get());
                Map<String, Object> hp = BaseballDataService.extractStartingPitcher(game, "home");
                Map<String, Object> ap = BaseballDataService.extractStartingPitcher(game, "away");
                if (!hp.isEmpty()) homeEra = nullIfBlank(hp.getOrDefault("era", "").toString());
                if (!ap.isEmpty()) awayEra = nullIfBlank(ap.getOrDefault("era", "").toString());
                break;
            }
        }

        return preMatchGenerator.generatePreMatchOdds(
                homeTeam, awayTeam,
                homeRecord, awayRecord,
                homeEra, awayEra,
                match.getLeague());
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean isMissing(String val) {
        return val == null || val.isBlank();
    }

    private static int parseScore(String score) {
        if (score == null || score.isBlank()) return 0;
        try { return Integer.parseInt(score.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * Strips the "espn-mlb-" prefix from a stored externalId to yield the
     * raw ESPN game ID used by {@link BaseballDataService}.
     */
    public static String stripMlbPrefix(String externalId) {
        if (externalId == null) return "";
        if (externalId.startsWith(EXT_ID_PREFIX)) return externalId.substring(EXT_ID_PREFIX.length());
        return externalId;
    }

    private UUID toUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw ApiException.notFound("Baseball match not found: " + id); }
    }
}
package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.AmericanFootballDataService;
import com.speedbet.api.sportsdata.odds.NflLiveOddsGeneratorService;
import com.speedbet.api.sportsdata.odds.NflOddsGeneratorService;
import com.speedbet.api.sportsdata.odds.NflOddsPersistenceService;
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
 * Match service scoped to NFL American Football.
 *
 * ── Responsibilities ─────────────────────────────────────────────────────
 *
 *   - CRUD / query layer for Match rows where sport = "americanfootball"
 *   - Live odds cache management (moneyline only — HOME, DRAW, AWAY)
 *   - Delegates ESPN data fetching to {@link AmericanFootballDataService}
 *   - Delegates odds generation to {@link NflOddsGeneratorService} (pre-match)
 *     and {@link NflLiveOddsGeneratorService} (in-play)
 *   - Delegates odds persistence to {@link NflOddsPersistenceService}
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
 *   TTL: 2 minutes.  Markets: "nfl_moneyline" and "nfl_live_moneyline".
 *   Cache is checked by the poller before generating fresh odds so that a
 *   brief ESPN outage does not blank out the betting surface.
 *
 * ── External ID convention ───────────────────────────────────────────────
 *
 *   All NFL matches are stored with externalId prefix "espn-nfl-".
 *   Raw ESPN game IDs are stripped via {@link #stripNflPrefix(String)}.
 *
 * ── Draw ─────────────────────────────────────────────────────────────────
 *
 *   Unlike baseball, NFL CAN end in a tie in the regular season (OT rules).
 *   A DRAW selection is therefore included in the moneyline market at a
 *   very high odd (~1.8% base probability).
 *   In the playoffs, OT continues until a team scores — no ties possible.
 *
 * ── NFL week-based scoreboard ─────────────────────────────────────────────
 *
 *   Unlike the NBA (daily games), the NFL scoreboard is week-based.
 *   {@link AmericanFootballDataService#getCurrentWeekGames()} returns the
 *   current week. Individual date queries use getGamesByDate(YYYYMMDD).
 *
 * ── Settlement support ────────────────────────────────────────────────────
 *
 *   {@link #getUnsettledFinished()} — used by NflSettlementEngine.
 *   {@link #markSettled(String)}    — called after all bets on a match settle.
 *   Both are scoped to sport="americanfootball" so no other sport's rows
 *   are ever touched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NflMatchService {

    private static final String SPORT            = "americanfootball";
    private static final String EXT_ID_PREFIX    = "espn-nfl-";
    private static final long   LIVE_ODDS_TTL_MS = 2 * 60_000L;

    // ── Repositories & services ───────────────────────────────────────────
    private final MatchRepository              matchRepo;
    private final OddsRepository               oddsRepo;
    private final AmericanFootballDataService  nflDataService;
    private final NflOddsGeneratorService      preMatchGenerator;
    private final NflLiveOddsGeneratorService  liveGenerator;
    private final NflOddsPersistenceService    nflOddsPersistenceService;

    // ── Live odds cache (moneyline + live moneyline) ──────────────────────
    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveMoneylineCache = new ConcurrentHashMap<>();

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

    public boolean isMoneylineCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveMoneylineCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveMoneylineOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveMoneylineCache.put(matchId, new OddsCacheEntry(odds, expires));
        log.debug("cacheLiveMoneylineOdds (NFL): matchId={} cached {} odd(s)", matchId, odds.size());
    }

    public List<Map<String, Object>> getLiveMoneylineFromCache(UUID matchId) {
        OddsCacheEntry entry = liveMoneylineCache.get(matchId);
        return (entry != null && entry.isValid()) ? entry.odds() : null;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  BASIC QUERIES — DB-backed, scoped to sport = "americanfootball"
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All currently LIVE NFL matches.
     * Used by the poller to drive live score and live odds updates.
     */
    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "LIVE");
        log.info("getLiveMatches (NFL): {} LIVE match(es)", matches.size());
        return matches;
    }

    /**
     * All UPCOMING NFL matches scheduled within the next 7 days.
     */
    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport(SPORT, now, now.plus(7, ChronoUnit.DAYS));
        log.info("getUpcomingMatches (NFL): {} upcoming match(es)", matches.size());
        return matches;
    }

    /**
     * All NFL matches kicking off today (UTC).
     */
    @Cacheable("nflTodayMatches")
    public List<Match> getTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetweenAndSport(SPORT, startOfDay, endOfDay);
        log.info("getTodayMatches (NFL): {} match(es) today UTC", matches.size());
        return matches;
    }

    /**
     * FINISHED NFL matches from the past 72 hours.
     * 72-hour window ensures games completed late Sunday night
     * remain visible on Monday morning.
     */
    public List<Match> getRecentResults() {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "FINISHED")
                .stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .toList();
        log.info("getRecentResults (NFL): {} finished match(es) in 72h window", matches.size());
        return matches;
    }

    /**
     * FINISHED NFL matches that have not yet been settled.
     * Used by NflSettlementEngine to find bets ready for payout.
     * Scoped to sport="americanfootball" so no other sport's rows are touched.
     */
    public List<Match> getUnsettledFinished() {
        List<Match> matches = matchRepo.findUnsettledFinishedBySport(SPORT);
        log.info("getUnsettledFinished (NFL): {} unsettled finished match(es)", matches.size());
        return matches;
    }

    /**
     * Marks a match as settled by setting settledAt to now.
     * Called by NflSettlementEngine after all bets for a match have been resolved.
     *
     * @param id internal Match UUID string
     */
    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
            log.info("markSettled (NFL): matchId={} settledAt={}", id, m.getSettledAt());
        });
    }

    /**
     * Single NFL match by internal UUID.
     *
     * @throws ApiException 404 if not found or ID is not a valid UUID
     */
    public Match getById(String id) {
        return matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("NFL match not found: " + id));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ESPN PASS-THROUGH HELPERS
    // ═════════════════════════════════════════════════════════════════════

    /** All games for the current NFL week — cached by AmericanFootballDataService. */
    public List<Map<String, Object>> getEspnCurrentWeek() {
        return nflDataService.getCurrentWeekGames();
    }

    /** Live NFL games currently in progress — fresh from ESPN, no cache. */
    public List<Map<String, Object>> getEspnLive() {
        return nflDataService.getLiveGames();
    }

    /** Upcoming (pre-game) NFL games for the current week. */
    public List<Map<String, Object>> getEspnUpcoming() {
        return nflDataService.getUpcomingGames();
    }

    /** Finished NFL games from the current week. */
    public List<Map<String, Object>> getEspnFinished() {
        return nflDataService.getFinishedGames();
    }

    /**
     * NFL games for a specific week and season type.
     *
     * @param week       NFL week number
     * @param seasonType use AmericanFootballDataService.SEASON_* constants
     */
    public List<Map<String, Object>> getEspnByWeek(int week, int seasonType) {
        return nflDataService.getGamesByWeek(week, seasonType);
    }

    /**
     * NFL games filtered to a specific calendar date.
     *
     * @param date date in YYYYMMDD format, e.g. "20260910"
     */
    public List<Map<String, Object>> getEspnByDate(String date) {
        return nflDataService.getGamesByDate(date);
    }

    /** NFL standings (AFC + NFC, by division) from ESPN. */
    public Map<String, Object> getEspnStandings() {
        return nflDataService.getStandings();
    }

    /**
     * Full game details for a single NFL game: score + ESPN box score + odds.
     *
     * @param espnGameId raw ESPN event ID (without "espn-nfl-" prefix)
     */
    public Map<String, Object> getEspnGameDetail(String espnGameId) {
        return nflDataService.getFullGameDetails(espnGameId);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ODDS — direct endpoints
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Moneyline odds for a single NFL match (HOME / DRAW / AWAY).
     *
     * <p>For LIVE matches: returns cached live odds if still valid, otherwise
     * generates and caches fresh in-play odds from the ESPN live feed.
     * <p>For UPCOMING matches: generates pre-match odds (deterministic seed).
     * <p>For FINISHED matches: returns an empty list.
     *
     * @param id internal Match UUID string
     */
    public List<Map<String, Object>> getMoneylineOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();

        if ("LIVE".equals(status)) {
            List<Map<String, Object>> cached = getLiveMoneylineFromCache(match.getId());
            if (cached != null) return cached;
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
     * Full match detail from ESPN (box score, player stats, scoring plays, drives).
     * Routes via the espn-nfl- externalId to get the raw game ID.
     */
    public Map<String, Object> getMatchDetail(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripNflPrefix(match.getExternalId());
        Map<String, Object> summary = nflDataService.getGameSummary(gameId);
        if (!summary.isEmpty()) return Map.of("source", "espn-nfl", "data", summary);
        return Map.of();
    }

    /**
     * Current score snapshot for a match (teams, scores, quarter, clock, possession).
     */
    public Map<String, Object> getMatchScore(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripNflPrefix(match.getExternalId());
        return nflDataService.getGameScore(gameId);
    }

    /**
     * All NFL odds for a single match — moneyline only (other markets
     * are persisted via NflOddsPersistenceService but not generated on-demand here).
     *
     * <pre>
     * {
     *   "moneyline" : [ ... ]
     * }
     * </pre>
     */
    public Map<String, Object> getAllOddsForMatch(String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moneyline", getMoneylineOdds(id));
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE ODDS CACHE REFRESH  (called by the poller)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Regenerate and cache live moneyline odds for all live NFL matches.
     * Called by the NFL live score poller every 2 minutes.
     */
    public void refreshLiveOddsCache(List<Match> liveMatches) {
        if (liveMatches.isEmpty()) return;

        List<Map<String, Object>> espnLiveGames = nflDataService.getLiveGames();
        int refreshed = 0, failed = 0;

        for (Match match : liveMatches) {
            try {
                String gameId = stripNflPrefix(match.getExternalId());
                Map<String, Object> espnGame = espnLiveGames.stream()
                        .filter(g -> gameId.equals(AmericanFootballDataService.extractGameId(g)))
                        .findFirst()
                        .orElse(null);

                if (espnGame == null) {
                    log.warn("refreshLiveOddsCache (NFL): game not found in ESPN live feed — matchId={} gameId={}",
                            match.getId(), gameId);
                    failed++;
                    continue;
                }

                Optional<Map<String, Object>> homeOpt = AmericanFootballDataService.extractHomeCompetitor(espnGame);
                Optional<Map<String, Object>> awayOpt = AmericanFootballDataService.extractAwayCompetitor(espnGame);

                if (homeOpt.isEmpty() || awayOpt.isEmpty()) {
                    log.warn("refreshLiveOddsCache (NFL): could not resolve competitors — matchId={}", match.getId());
                    failed++;
                    continue;
                }

                Map<String, Object> home = homeOpt.get();
                Map<String, Object> away = awayOpt.get();

                String homeTeam      = AmericanFootballDataService.extractTeamName(home);
                String awayTeam      = AmericanFootballDataService.extractTeamName(away);
                int    homeScore     = parseScore(AmericanFootballDataService.extractScore(home));
                int    awayScore     = parseScore(AmericanFootballDataService.extractScore(away));
                int    quarter       = AmericanFootballDataService.extractQuarter(espnGame);
                int    clockSeconds  = parseClockSeconds(AmericanFootballDataService.extractClock(espnGame));
                boolean homePossession = AmericanFootballDataService.hasPossession(home);
                boolean homeRedZone    = AmericanFootballDataService.isInRedZone(home);
                boolean awayRedZone    = AmericanFootballDataService.isInRedZone(away);

                List<Map<String, Object>> liveOdds = liveGenerator.generateLiveOdds(
                        homeTeam, awayTeam,
                        homeScore, awayScore,
                        quarter, clockSeconds,
                        homePossession, homeRedZone, awayRedZone);

                cacheLiveMoneylineOdds(match.getId(), liveOdds);
                refreshed++;

            } catch (Exception e) {
                failed++;
                log.warn("refreshLiveOddsCache (NFL): failed matchId={} — {}", match.getId(), e.getMessage());
            }
        }

        log.info("refreshLiveOddsCache (NFL): refreshed={}/{}, failed={}",
                refreshed, liveMatches.size(), failed);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ODDS — LIST BUNDLES
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Bundles each match with its moneyline odds (primary market).
     * Live matches use the cache; pre-match uses the deterministic generator.
     */
    public List<Map<String, Object>> withOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        log.debug("NFL withOdds: bundling moneyline odds for {} match(es)", matches.size());
        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (Match match : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", match);
            String status = match.getStatus();
            if ("LIVE".equals(status)) {
                OddsCacheEntry cached = liveMoneylineCache.get(match.getId());
                if (cached != null && cached.isValid()) {
                    entry.put("odds", cached.odds());
                } else {
                    entry.put("odds", generatePreMatchOddsForMatch(match));
                }
            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                entry.put("odds", generatePreMatchOddsForMatch(match));
            } else {
                entry.put("odds", List.of());
            }
            out.add(entry);
        }
        log.debug("NFL withOdds: bundled {} entries", out.size());
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  PERSISTENCE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Saves a new match or merges into an existing one matched by externalId.
     *
     * <p>Status demotion guard: FINISHED is terminal; LIVE can only transition
     * to FINISHED. Blocked demotions log a WARN on first occurrence and are
     * silently dropped on subsequent calls.
     *
     * <p>Sparse fields (logos, sport, teams) are filled in only when the
     * existing row is missing them. Score and metadata are always updated
     * when non-null in the incoming match.
     *
     * @param match the Match entity to save or merge
     * @return the persisted (and possibly merged) Match entity
     */
    @Transactional
    @CacheEvict(value = {"nflTodayMatches", "nflUpcomingMatches"}, allEntries = true)
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
                                log.warn("saveOrUpdate (NFL): blocked status demotion externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            } else {
                                log.debug("saveOrUpdate (NFL): repeated demotion blocked externalId={} {} → {} (keeping {})",
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
                    if (match.getKickoffAt() != null && existing.getKickoffAt() == null) {
                        existing.setKickoffAt(match.getKickoffAt());
                    }

                    log.debug("saveOrUpdate (NFL): updated externalId={} status='{}' home='{}' away='{}' league='{}'",
                            existing.getExternalId(), existing.getStatus(),
                            existing.getHomeTeam(), existing.getAwayTeam(), existing.getLeague());
                    return matchRepo.save(existing);
                })
                .orElseGet(() -> {
                    log.debug("saveOrUpdate (NFL): inserting new externalId={} home='{}' away='{}' kickoff='{}'",
                            match.getExternalId(), match.getHomeTeam(),
                            match.getAwayTeam(), match.getKickoffAt());
                    return matchRepo.save(match);
                });
    }

    /**
     * Generate and persist the full suite of pre-match NFL odds for a fixture.
     * Called by the NFL poller after persisting UPCOMING matches.
     */
    @Transactional
    public void generateAndSavePreMatchOdds(Match match) {
        if (match.getExternalId() == null) {
            log.warn("generateAndSavePreMatchOdds (NFL): matchId={} has no externalId — skipping", match.getId());
            return;
        }
        try {
            String espnGameId = stripNflPrefix(match.getExternalId());
            nflOddsPersistenceService.generateAndSavePreMatchOdds(match, espnGameId);
            log.info("generateAndSavePreMatchOdds (NFL): matchId={} {} vs {}",
                    match.getId(), match.getHomeTeam(), match.getAwayTeam());
        } catch (Exception e) {
            log.warn("generateAndSavePreMatchOdds (NFL): failed matchId={} — {}",
                    match.getId(), e.getMessage());
        }
    }

    /**
     * Generate and persist live NFL odds for a single in-progress match.
     */
    @Transactional
    public void generateAndSaveLiveOdds(Match match) {
        if (match.getExternalId() == null) {
            log.warn("generateAndSaveLiveOdds (NFL): matchId={} has no externalId — skipping", match.getId());
            return;
        }
        try {
            String espnGameId = stripNflPrefix(match.getExternalId());
            nflOddsPersistenceService.generateAndSaveLiveOdds(match, espnGameId);
            log.info("generateAndSaveLiveOdds (NFL): matchId={} {} vs {}",
                    match.getId(), match.getHomeTeam(), match.getAwayTeam());
        } catch (Exception e) {
            log.warn("generateAndSaveLiveOdds (NFL): failed matchId={} — {}",
                    match.getId(), e.getMessage());
        }
    }

    /**
     * Force-finishes LIVE NFL matches whose kickoff predates {@code cutoff}.
     * Uses sport‑scoped query so other sport rows are never touched.
     *
     * @param cutoff matches kicked off before this instant are force-finished
     * @return number of matches force-finished
     */
    @Transactional
    @CacheEvict(value = {"nflTodayMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff) {
        List<Match> stale = matchRepo.findStaleLiveBySport(SPORT, cutoff);
        if (stale.isEmpty()) return 0;
        log.info("finishStaleLiveMatches (NFL): force-finishing {} stale match(es)", stale.size());
        for (Match m : stale) {
            m.setStatus("FINISHED");
            matchRepo.save(m);
        }
        return stale.size();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STANDINGS / TEAMS — ESPN pass-throughs
    // ═════════════════════════════════════════════════════════════════════

    /** AFC + NFC standings from ESPN. */
    public Map<String, Object> getStandings() {
        return nflDataService.getStandings();
    }

    /** All 32 NFL teams. */
    public Map<String, Object> getAllTeams() {
        return nflDataService.getAllTeams();
    }

    /** Single NFL team by ESPN team ID. */
    public Map<String, Object> getTeamInfo(String teamId) {
        return nflDataService.getTeamInfo(teamId);
    }

    /** Full season schedule for a team. */
    public Map<String, Object> getTeamSchedule(String teamId) {
        return nflDataService.getTeamSchedule(teamId);
    }

    /** Current roster for a team. */
    public Map<String, Object> getTeamRoster(String teamId) {
        return nflDataService.getTeamRoster(teamId);
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
        String gameId = stripNflPrefix(match.getExternalId());

        Map<String, Object> espnGame = nflDataService.getLiveGames().stream()
                .filter(g -> gameId.equals(AmericanFootballDataService.extractGameId(g)))
                .findFirst()
                .orElse(null);

        if (espnGame == null) {
            log.warn("generateAndCacheLiveOddsForMatch (NFL): game not in live feed — gameId={}", gameId);
            return List.of();
        }

        Optional<Map<String, Object>> homeOpt = AmericanFootballDataService.extractHomeCompetitor(espnGame);
        Optional<Map<String, Object>> awayOpt = AmericanFootballDataService.extractAwayCompetitor(espnGame);
        if (homeOpt.isEmpty() || awayOpt.isEmpty()) return List.of();

        Map<String, Object> home = homeOpt.get();
        Map<String, Object> away = awayOpt.get();

        String  homeTeam       = AmericanFootballDataService.extractTeamName(home);
        String  awayTeam       = AmericanFootballDataService.extractTeamName(away);
        int     homeScore      = parseScore(AmericanFootballDataService.extractScore(home));
        int     awayScore      = parseScore(AmericanFootballDataService.extractScore(away));
        int     quarter        = AmericanFootballDataService.extractQuarter(espnGame);
        int     clockSeconds   = parseClockSeconds(AmericanFootballDataService.extractClock(espnGame));
        boolean homePossession = AmericanFootballDataService.hasPossession(home);
        boolean homeRedZone    = AmericanFootballDataService.isInRedZone(home);
        boolean awayRedZone    = AmericanFootballDataService.isInRedZone(away);

        List<Map<String, Object>> odds = liveGenerator.generateLiveOdds(
                homeTeam, awayTeam,
                homeScore, awayScore,
                quarter, clockSeconds,
                homePossession, homeRedZone, awayRedZone);

        cacheLiveMoneylineOdds(match.getId(), odds);
        return odds;
    }

    /**
     * Generates pre-match moneyline odds using team names and records resolved
     * from the ESPN current-week scoreboard.
     */
    private List<Map<String, Object>> generatePreMatchOddsForMatch(Match match) {
        String homeTeam = match.getHomeTeam();
        String awayTeam = match.getAwayTeam();
        String homeRecord = "", awayRecord = "";

        if (match.getExternalId() != null) {
            String gameId = stripNflPrefix(match.getExternalId());
            for (Map<String, Object> game : nflDataService.getCurrentWeekGames()) {
                if (!gameId.equals(AmericanFootballDataService.extractGameId(game))) continue;
                Optional<Map<String, Object>> homeOpt = AmericanFootballDataService.extractHomeCompetitor(game);
                Optional<Map<String, Object>> awayOpt = AmericanFootballDataService.extractAwayCompetitor(game);
                if (homeOpt.isPresent()) homeRecord = AmericanFootballDataService.extractRecord(homeOpt.get());
                if (awayOpt.isPresent()) awayRecord = AmericanFootballDataService.extractRecord(awayOpt.get());
                break;
            }
        }

        return preMatchGenerator.generatePreMatchOdds(
                homeTeam, awayTeam,
                homeRecord, awayRecord,
                match.getLeague());
    }

    /**
     * Parses ESPN display clock string "M:SS" into total seconds remaining.
     * e.g. "8:42" → 522 seconds. Falls back to 450 (mid-quarter) on failure.
     */
    private static int parseClockSeconds(String clock) {
        if (clock == null || clock.isBlank()) return 450;
        try {
            String[] parts = clock.trim().split(":");
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
        } catch (NumberFormatException ignored) {}
        return 450;
    }

    private static int parseScore(String score) {
        if (score == null || score.isBlank()) return 0;
        try { return Integer.parseInt(score.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static boolean isMissing(String val) {
        return val == null || val.isBlank();
    }

    /**
     * Strips the "espn-nfl-" prefix from a stored externalId to yield the
     * raw ESPN game ID used by {@link AmericanFootballDataService}.
     */
    public static String stripNflPrefix(String externalId) {
        if (externalId == null) return "";
        if (externalId.startsWith(EXT_ID_PREFIX)) return externalId.substring(EXT_ID_PREFIX.length());
        return externalId;
    }

    private UUID toUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw ApiException.notFound("NFL match not found: " + id); }
    }
}

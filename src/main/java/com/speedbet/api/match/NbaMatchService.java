package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.BasketballDataService;
import com.speedbet.api.sportsdata.odds.*;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class NbaMatchService {

    // ── Core dependencies ─────────────────────────────────────────────────
    private final MatchRepository                  matchRepo;
    private final OddsRepository                   oddsRepo;
    private final BasketballDataService            basketballDataService;
    private final BasketballOddsPersistenceService oddsPersistenceService;

    // ── NBA odds generators ───────────────────────────────────────────────
    private final BasketballMoneylineOddsService   moneylineService;
    private final BasketballPointSpreadService     spreadService;
    private final BasketballTotalsService          totalsService;
    private final BasketballWinningMarginService   marginService;
    private final BasketballQuarterService         quarterService;

    // ── Constants ─────────────────────────────────────────────────────────
    private static final String SPORT              = "basketball";
    private static final String EXTERNAL_ID_PREFIX = "espn-nba-";
    private static final long   LIVE_ODDS_TTL_MS   = 2 * 60_000L;

    // ── Live odds caches ──────────────────────────────────────────────────
    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveMoneylineCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveSpreadCache    = new ConcurrentHashMap<>();

    private record OddsCacheEntry(List<Map<String, Object>> odds, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() <= expiresAt; }
    }

    // ── Sorting — logos-first then kickoff ascending ───────────────────────
    private static boolean hasLogos(Match m) {
        return !isMissing(m.getHomeLogo()) && !isMissing(m.getAwayLogo());
    }

    private static final Comparator<Match> LOGO_THEN_KICKOFF =
            Comparator.comparingInt((Match m) -> hasLogos(m) ? 0 : 1)
                    .thenComparing(m -> m.getKickoffAt() != null ? m.getKickoffAt() : Instant.MAX);

    // ── Small helpers ─────────────────────────────────────────────────────
    private static boolean isMissing(String v) {
        return v == null || v.isBlank();
    }

    private UUID toUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw ApiException.notFound("NBA match not found: " + id); }
    }

    /** Strip "espn-nba-" prefix to get the raw ESPN game ID. */
    private static String stripNbaPrefix(String externalId) {
        return (externalId != null && externalId.startsWith(EXTERNAL_ID_PREFIX))
                ? externalId.substring(EXTERNAL_ID_PREFIX.length())
                : externalId;
    }

    /** True for any match row belonging to the NBA basketball feed. */
    private static boolean isNbaMatch(Match m) {
        return SPORT.equalsIgnoreCase(m.getSport())
                || (m.getExternalId() != null && m.getExternalId().startsWith(EXTERNAL_ID_PREFIX));
    }

    /** Derive game-minute from metadata or elapsed wall-clock time. Clamped to [0, 53]. */
    private int extractMinute(Match match) {
        if (match.getMetadata() != null) {
            Object min = match.getMetadata().get("minute");
            if (min != null) {
                try { return Integer.parseInt(min.toString()); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (match.getKickoffAt() != null) {
            long elapsed = ChronoUnit.MINUTES.between(match.getKickoffAt(), Instant.now());
            return (int) Math.min(Math.max(elapsed, 0), 53);
        }
        return 24; // default: halftime equivalent
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE — public API
    // ══════════════════════════════════════════════════════════════════════

    public boolean isMoneylineCacheValid(UUID matchId) {
        OddsCacheEntry e = liveMoneylineCache.get(matchId);
        return e != null && e.isValid();
    }

    public void cacheLiveMoneylineOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveMoneylineCache.put(matchId, new OddsCacheEntry(odds, expires));
    }

    public boolean isSpreadCacheValid(UUID matchId) {
        OddsCacheEntry e = liveSpreadCache.get(matchId);
        return e != null && e.isValid();
    }

    public void cacheLiveSpreadOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveSpreadCache.put(matchId, new OddsCacheEntry(odds, expires));
    }

    /**
     * Retrieve cached live odds by market name.
     *
     * @param matchId internal match UUID
     * @param market  "moneyline" or "point_spread"
     * @return cached odds or null if absent / expired
     */
    public List<Map<String, Object>> getOddsFromCache(UUID matchId, String market) {
        return switch (market) {
            case "moneyline" -> {
                OddsCacheEntry e = liveMoneylineCache.get(matchId);
                yield (e != null && e.isValid()) ? e.odds() : null;
            }
            case "point_spread" -> {
                OddsCacheEntry e = liveSpreadCache.get(matchId);
                yield (e != null && e.isValid()) ? e.odds() : null;
            }
            default -> {
                log.debug("getOddsFromCache [NBA]: no cache for market='{}' matchId={}", market, matchId);
                yield null;
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // BASIC DB QUERIES — sport-scoped (basketball only)
    // ══════════════════════════════════════════════════════════════════════

    /** All LIVE NBA matches. */
    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "LIVE");
        log.info("NBA getLiveMatches: {} LIVE match(es)", matches.size());
        return matches;
    }

    /** All UPCOMING NBA matches in the next 7 days, logos-first. */
    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport(SPORT, now, now.plus(7, ChronoUnit.DAYS))
                .stream()
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        int withLogos = (int) matches.stream().filter(NbaMatchService::hasLogos).count();
        log.info("NBA getUpcomingMatches: {} upcoming — {} with logos, {} without",
                matches.size(), withLogos, matches.size() - withLogos);
        return matches;
    }

    /** All NBA matches today (UTC). */
    @Cacheable("nbaTodayMatches")
    public List<Match> getTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetweenAndSport(SPORT, startOfDay, endOfDay);
        log.info("NBA getTodayMatches: {} match(es) today UTC", matches.size());
        return matches;
    }

    /** NBA matches in the next 7 days, logos-first. Alias of getUpcomingMatches(). */
    public List<Match> getFutureMatches() {
        return getUpcomingMatches();
    }

    /**
     * Recent FINISHED NBA results within a 72-hour window.
     *
     * @param limit max number of results to return
     */
    public List<Match> getRecentResults(int limit) {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(SPORT, "FINISHED")
                .stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .limit(limit)
                .toList();
        log.info("NBA getRecentResults: {} FINISHED match(es) (72h window, cap={})",
                matches.size(), limit);
        return matches;
    }

    public List<Match> getRecentResults() {
        return getRecentResults(20);
    }

    /**
     * Retrieve a single NBA match by internal UUID string.
     * Throws ApiException.notFound if the match does not exist or is not NBA.
     */
    public Match getById(String id) {
        Match match = matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("NBA match not found: " + id));
        if (!isNbaMatch(match)) {
            throw ApiException.notFound("Match " + id + " is not an NBA match");
        }
        return match;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BY-TEAM QUERIES
    // ══════════════════════════════════════════════════════════════════════

    /** All LIVE NBA matches involving the given team. */
    public List<Match> getLiveMatchesByTeam(String teamName) {
        List<Match> matches = getLiveMatches().stream()
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("NBA getLiveMatchesByTeam: {} LIVE match(es) for team='{}'", matches.size(), teamName);
        return matches;
    }

    /** All UPCOMING NBA matches involving the given team (next 7 days). */
    public List<Match> getUpcomingMatchesByTeam(String teamName) {
        List<Match> matches = getUpcomingMatches().stream()
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("NBA getUpcomingMatchesByTeam: {} upcoming match(es) for team='{}'",
                matches.size(), teamName);
        return matches;
    }

    /** Recent FINISHED NBA results (72h window) for a specific team. */
    public List<Match> getRecentResultsByTeam(String teamName) {
        List<Match> matches = getRecentResults().stream()
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("NBA getRecentResultsByTeam: {} result(s) for team='{}'", matches.size(), teamName);
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — LIVE CACHE REFRESH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Regenerate and cache live moneyline + spread odds for all live NBA matches.
     * Called by BasketballLiveScorePoller every 2 minutes.
     */
    public void refreshLiveOddsCache(List<Match> liveMatches) {
        if (liveMatches.isEmpty()) return;
        int refreshedMoneyline = 0, refreshedSpread = 0;
        for (Match match : liveMatches) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            try {
                List<Map<String, Object>> liveMoneyline = moneylineService.generateLiveOdds(
                        match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
                cacheLiveMoneylineOdds(match.getId(), liveMoneyline);
                refreshedMoneyline++;
            } catch (Exception e) {
                log.warn("NBA refreshLiveOddsCache [moneyline]: matchId={} failed — {}",
                        match.getId(), e.getMessage());
            }
            try {
                List<Map<String, Object>> liveSpread = spreadService.generateLiveSpreadOdds(
                        match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
                cacheLiveSpreadOdds(match.getId(), liveSpread);
                refreshedSpread++;
            } catch (Exception e) {
                log.warn("NBA refreshLiveOddsCache [spread]: matchId={} failed — {}",
                        match.getId(), e.getMessage());
            }
        }
        log.info("NBA refreshLiveOddsCache: moneyline={}/{} spread={}/{} match(es) refreshed",
                refreshedMoneyline, liveMatches.size(), refreshedSpread, liveMatches.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — DIRECT ENDPOINTS
    // ══════════════════════════════════════════════════════════════════════

    /** Moneyline odds (home/away 2-way). Returns cached live odds when available. */
    public List<Map<String, Object>> getMoneylineOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();
        if ("LIVE".equals(status)) {
            OddsCacheEntry cached = liveMoneylineCache.get(match.getId());
            if (cached != null && cached.isValid()) return cached.odds();
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> generated = moneylineService.generateLiveOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            cacheLiveMoneylineOdds(match.getId(), generated);
            return generated;
        }
        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return moneylineService.generatePreMatchOdds(match.getHomeTeam(), match.getAwayTeam());
        }
        return List.of();
    }

    /** Point spread odds. Returns cached live odds when available. */
    public List<Map<String, Object>> getPointSpreadOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();
        if ("LIVE".equals(status)) {
            OddsCacheEntry cached = liveSpreadCache.get(match.getId());
            if (cached != null && cached.isValid()) return cached.odds();
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> generated = spreadService.generateLiveSpreadOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            cacheLiveSpreadOdds(match.getId(), generated);
            return generated;
        }
        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return spreadService.generateSpreadOdds(match.getHomeTeam(), match.getAwayTeam());
        }
        return List.of();
    }

    /** Game total (Over/Under) odds. Live totals react to current combined score + game clock. */
    public List<Map<String, Object>> getGameTotalOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();
        if ("LIVE".equals(status)) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            return totalsService.generateLiveTotalOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
        }
        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return totalsService.generateTotalOdds(match.getHomeTeam(), match.getAwayTeam());
        }
        return List.of();
    }

    /** Winning margin + overtime odds. */
    public List<Map<String, Object>> getWinningMarginOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();
        if ("LIVE".equals(status)) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            return marginService.generateLiveMarginOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
        }
        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return marginService.generateMarginOdds(match.getHomeTeam(), match.getAwayTeam());
        }
        return List.of();
    }

    /** Quarter / half period markets. Settled periods are omitted live. */
    public List<Map<String, Object>> getQuarterOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();
        if ("LIVE".equals(status)) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            return quarterService.generateLiveQuarterOdds(
                    match.getHomeTeam(), match.getAwayTeam(),
                    scoreHome, scoreAway,
                    0, 0, 0, 0, 0, 0,
                    minute);
        }
        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return quarterService.generateQuarterOdds(match.getHomeTeam(), match.getAwayTeam());
        }
        return List.of();
    }

    /**
     * All NBA odds for a single match — returns a keyed map of every market.
     *
     * <pre>
     * {
     *   "moneyline"      : [ ... ],
     *   "point_spread"   : [ ... ],
     *   "game_total"     : [ ... ],
     *   "winning_margin" : [ ... ],
     *   "quarters"       : [ ... ]
     * }
     * </pre>
     */
    public Map<String, Object> getAllOddsForMatch(String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moneyline",      getMoneylineOdds(id));
        result.put("point_spread",   getPointSpreadOdds(id));
        result.put("game_total",     getGameTotalOdds(id));
        result.put("winning_margin", getWinningMarginOdds(id));
        result.put("quarters",       getQuarterOdds(id));
        return result;
    }

    /** Raw Odds entities stored in the DB for a match (all markets). */
    public List<Odds> getOddsForMatch(String id) {
        return oddsRepo.findByMatchId(toUuid(id));
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — LIST + ODDS BUNDLES
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Bundles each match with its moneyline odds (primary market).
     * Live matches use the cache; pre-match uses the deterministic generator.
     */
    public List<Map<String, Object>> withOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        List<Match> sorted = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        log.debug("NBA withOdds: bundling moneyline odds for {} match(es)", sorted.size());
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
                    entry.put("odds", moneylineService.generatePreMatchOdds(
                            match.getHomeTeam(), match.getAwayTeam()));
                }
            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                entry.put("odds", moneylineService.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam()));
            } else {
                entry.put("odds", List.of());
            }
            out.add(entry);
        }
        log.debug("NBA withOdds: bundled {} entries", out.size());
        return out;
    }

    /**
     * Bundles each match with moneyline + point spread odds.
     */
    public List<Map<String, Object>> withAllOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        List<Match> sorted = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        log.debug("NBA withAllOdds: bundling all markets for {} match(es)", sorted.size());
        List<Map<String, Object>> out = new ArrayList<>(sorted.size());
        for (Match match : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", match);
            String status = match.getStatus();
            if ("LIVE".equals(status)) {
                OddsCacheEntry moneylineEntry = liveMoneylineCache.get(match.getId());
                OddsCacheEntry spreadEntry    = liveSpreadCache.get(match.getId());
                List<Map<String, Object>> moneyline = (moneylineEntry != null && moneylineEntry.isValid())
                        ? moneylineEntry.odds()
                        : moneylineService.generatePreMatchOdds(match.getHomeTeam(), match.getAwayTeam());
                List<Map<String, Object>> spread = (spreadEntry != null && spreadEntry.isValid())
                        ? spreadEntry.odds()
                        : spreadService.generateSpreadOdds(match.getHomeTeam(), match.getAwayTeam());
                entry.put("moneyline",    moneyline);
                entry.put("point_spread", spread);
            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                entry.put("moneyline",    moneylineService.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam()));
                entry.put("point_spread", spreadService.generateSpreadOdds(
                        match.getHomeTeam(), match.getAwayTeam()));
            } else {
                entry.put("moneyline",    List.of());
                entry.put("point_spread", List.of());
            }
            out.add(entry);
        }
        log.debug("NBA withAllOdds: bundled {} entries", out.size());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // MATCH DETAIL / EVENTS / STATS / LINEUPS
    // ══════════════════════════════════════════════════════════════════════

    /** Full game detail — box score, play-by-play, leaders from ESPN. */
    public Map<String, Object> getMatchDetail(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripNbaPrefix(match.getExternalId());
        Map<String, Object> summary = basketballDataService.getGameSummary(gameId);
        if (!summary.isEmpty()) return Map.of("source", "espn-nba", "data", summary);
        log.debug("NBA getMatchDetail: empty summary for matchId={} gameId={}", id, gameId);
        return Map.of();
    }

    /** Match events / play-by-play. Returns stored metadata if available; falls back to ESPN. */
    public Map<String, Object> getEvents(String id) {
        Match match = getById(id);
        if (match.getMetadata() != null && !match.getMetadata().isEmpty()) {
            return match.getMetadata();
        }
        if (match.getExternalId() == null) return Map.of("events", List.of());
        String gameId = stripNbaPrefix(match.getExternalId());
        Map<String, Object> summary = basketballDataService.getGameSummary(gameId);
        Object plays = summary.get("plays");
        if (plays != null) return Map.of("source", "espn-nba", "plays", plays);
        return Map.of("events", List.of());
    }

    /** Match statistics — box score (player stats, quarter scores, leaders). */
    public Map<String, Object> getStats(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripNbaPrefix(match.getExternalId());
        Map<String, Object> summary = basketballDataService.getGameSummary(gameId);
        if (!summary.isEmpty()) return Map.of("source", "espn-nba", "type", "box_score", "data", summary);
        return Map.of();
    }

    /**
     * Lineups / roster context.
     * ESPN NBA API does not expose per-game lineups; returns the full game summary
     * which contains player stats and leaders.
     */
    @Cacheable(value = "nbaLineups", key = "#id")
    public Map<String, Object> getLineups(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripNbaPrefix(match.getExternalId());
        Map<String, Object> summary = basketballDataService.getGameSummary(gameId);
        if (!summary.isEmpty()) return Map.of("source", "espn-nba", "data", summary);
        return Map.of();
    }

    /** Quick score snapshot — lightweight alternative to getMatchDetail(). */
    public Map<String, Object> getScoreSnapshot(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripNbaPrefix(match.getExternalId());
        return basketballDataService.getGameScore(gameId);
    }

    /** Combined detail: score + ESPN stats + LiveScore odds. */
    public Map<String, Object> getFullGameDetails(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        String gameId = stripNbaPrefix(match.getExternalId());
        return basketballDataService.getFullGameDetails(gameId);
    }

    /** H2H is not available from the ESPN NBA API — always returns empty. */
    @Cacheable(value = "nbaH2h", key = "#id")
    public Map<String, Object> getH2H(String id) {
        getById(id); // validate existence
        log.debug("NBA getH2H: H2H not available for basketball matchId={}", id);
        return Map.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // STANDINGS / TEAMS — ESPN pass-throughs
    // ══════════════════════════════════════════════════════════════════════

    /** NBA East + West conference standings. */
    public Map<String, Object> getStandings() {
        return basketballDataService.getStandings();
    }

    /** All 30 NBA teams. */
    public Map<String, Object> getAllTeams() {
        return basketballDataService.getAllTeams();
    }

    /** Single NBA team by ESPN team ID. */
    public Map<String, Object> getTeamInfo(String teamId) {
        return basketballDataService.getTeamInfo(teamId);
    }

    /** Full season schedule for a team. */
    public Map<String, Object> getTeamSchedule(String teamId) {
        return basketballDataService.getTeamSchedule(teamId);
    }

    /** Current roster for a team. */
    public Map<String, Object> getTeamRoster(String teamId) {
        return basketballDataService.getTeamRoster(teamId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ESPN PASS-THROUGHS — raw scoreboard views
    // ══════════════════════════════════════════════════════════════════════

    /** Live NBA games from the ESPN scoreboard (raw ESPN event maps). */
    public List<Map<String, Object>> getEspnLive() {
        return basketballDataService.getLiveGames();
    }

    /** Today's NBA games from the ESPN scoreboard (raw ESPN event maps). */
    public List<Map<String, Object>> getEspnToday() {
        return basketballDataService.getTodayGames();
    }

    /** Upcoming NBA games from ESPN (raw ESPN event maps). */
    public List<Map<String, Object>> getEspnUpcoming() {
        return basketballDataService.getUpcomingGames();
    }

    /** ESPN game detail by raw ESPN game ID (bypasses Match entity lookup). */
    public Map<String, Object> getEspnGameDetail(String espnGameId) {
        return basketballDataService.getFullGameDetails(espnGameId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generate and persist the full suite of pre-match NBA odds for a fixture.
     * Called by BasketballLiveScorePoller after persisting UPCOMING matches.
     */
    @Transactional
    public void generateAndSavePreMatchOdds(Match match) {
        try {
            oddsPersistenceService.generateAndSaveAllOdds(match);
            log.info("NBA generateAndSavePreMatchOdds: matchId={} {} vs {}",
                    match.getId(), match.getHomeTeam(), match.getAwayTeam());
        } catch (Exception e) {
            log.warn("NBA generateAndSavePreMatchOdds: failed matchId={} — {}",
                    match.getId(), e.getMessage());
        }
    }

    /**
     * Generate and persist live NBA odds for a single match.
     */
    @Transactional
    public void generateAndSaveLiveOdds(Match match) {
        try {
            oddsPersistenceService.generateAndSaveLiveOdds(match);
            log.info("NBA generateAndSaveLiveOdds: matchId={} {} vs {}",
                    match.getId(), match.getHomeTeam(), match.getAwayTeam());
        } catch (Exception e) {
            log.warn("NBA generateAndSaveLiveOdds: failed matchId={} — {}",
                    match.getId(), e.getMessage());
        }
    }

    /**
     * Force-finishes LIVE NBA matches whose kickoff predates {@code cutoff}.
     * Uses sport‑scoped repository query so football rows are never touched.
     *
     * @param cutoff matches kicked off before this instant are force-finished
     * @return number of matches force-finished
     */
    @Transactional
    @CacheEvict(value = {"nbaTodayMatches", "matches", "todayMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff) {
        List<Match> stale = matchRepo.findStaleLiveBySport(SPORT, cutoff, MatchSource.ADMIN_CREATED);
        if (stale.isEmpty()) return 0;
        log.info("NBA finishStaleLiveMatches: force-finishing {} stale match(es)", stale.size());
        for (Match m : stale) {
            m.setStatus("FINISHED");
            matchRepo.save(m);
        }
        return stale.size();
    }

    /** Returns FINISHED NBA matches that have not yet been settled. */
    public List<Match> getUnsettledFinished() {
        return matchRepo.findUnsettledFinishedBySport(SPORT);
    }

    /** Mark a match as settled (sets settledAt = now). */
    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
        });
    }
}

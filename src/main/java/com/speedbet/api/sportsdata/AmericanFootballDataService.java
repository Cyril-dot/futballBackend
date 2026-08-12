package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Service for NFL American Football data via the ESPN unofficial API.
 *
 * ── Data source ──────────────────────────────────────────────────────────
 *
 *   Base URL : https://site.api.espn.com/apis/site/v2/sports/football/nfl
 *   Auth     : None required — plain HTTP GET
 *
 * ── NFL scoreboard vs NBA scoreboard ────────────────────────────────────
 *
 *   Unlike the NBA (daily games), the NFL scoreboard is week-based.
 *   Calling the endpoint with no params returns the current week's games.
 *   Use getGamesByWeek(week, seasonType) to fetch a specific week.
 *   Use getGamesByDate(date) to filter to a single calendar date.
 *
 *   Season type codes:
 *     1 = Preseason
 *     2 = Regular season  (default)
 *     3 = Postseason / playoffs
 *
 * ── Game status values (status.type.state) ──────────────────────────────
 *
 *   "pre"  — upcoming / not yet started
 *   "in"   — live / in progress
 *   "post" — finished
 *
 * ── Key methods ──────────────────────────────────────────────────────────
 *
 *   getCurrentWeekGames()        — all games for the current NFL week
 *   getGamesByWeek(int, int)     — games for a specific week + season type
 *   getGamesByDate(String)       — games filtered to a calendar date (YYYYMMDD)
 *   getLiveGames()               — only in-progress games (fresh, no cache)
 *   getUpcomingGames()           — pre-game entries for the current week
 *   getFinishedGames()           — completed games from the current week
 *   getGameSummary(String)       — full box score + player stats for a game ID
 *   getGameScore(String)         — quick score snapshot (teams, scores, quarter, clock)
 *   getGameOdds(String)          — pre/live 1X2 odds from LiveScoreApiClient
 *   getFullGameDetails(String)   — combined: score + ESPN stats + odds (all states)
 *   getStandings()               — AFC + NFC standings by division
 *   getAllTeams()                 — all 32 NFL teams
 *   getTeamInfo(String)          — single team by ESPN team ID
 *   getTeamSchedule(String)      — full season schedule for a team
 *   getTeamRoster(String)        — current roster for a team
 *
 * ── NFL-specific stats (in getGameSummary / getFullGameDetails) ──────────
 *
 *   Box score includes:
 *     - Passing: yards, completions, TDs, INTs
 *     - Rushing: yards, carries, TDs
 *     - Receiving: yards, receptions, TDs
 *     - Defense: tackles, sacks, INTs
 *     - Drive chart + scoring summary (all TDs, FGs, safeties)
 *     - Injuries and game notes
 *     - Play-by-play availability flag
 *
 * ── Odds source ──────────────────────────────────────────────────────────
 *
 *   Odds are sourced from LiveScoreApiClient (the project's primary sports-data
 *   client). The match is located by scanning the live feed for a fixture whose
 *   ESPN game ID or team names match. Pre/live 1X2 odds are then extracted via
 *   LiveScoreApiClient's existing odds helpers.
 *
 *   If no matching fixture is found in the live feed the odds block is returned
 *   empty — callers should always check the "matchedBy" field ("id", "teamName",
 *   or "none") before relying on odds values.
 *
 * ── Caching ──────────────────────────────────────────────────────────────
 *
 *   Scoreboard / standings / team data are cached for CACHE_TTL_MIN minutes.
 *   getLiveGames(), getGameSummary(), getGameOdds(), and getFullGameDetails()
 *   intentionally bypass the cache so callers always get fresh data.
 *
 * ── Polling guidance ─────────────────────────────────────────────────────
 *
 *   Poll getLiveGames() every 30–60 seconds during game windows.
 *   Polling faster risks ESPN rate-limiting the IP.
 */
@Slf4j
@Component
public class AmericanFootballDataService {

    // ── ESPN NFL base URL ──────────────────────────────────────────────────
    private static final String BASE_URL = "https://site.web.api.espn.com";
    private static final String NFL_PATH            = "/apis/site/v2/sports/football/nfl";
    private static final long   CACHE_TTL_MIN       = 5;
    private static final long   REQUEST_TIMEOUT_SEC = 12;

    // ── Season type constants ──────────────────────────────────────────────
    public static final int SEASON_PRESEASON  = 1;
    public static final int SEASON_REGULAR    = 2;
    public static final int SEASON_POSTSEASON = 3;

    // ── Game state constants (ESPN status.type.state) ─────────────────────
    public static final String STATE_PRE  = "pre";   // upcoming
    public static final String STATE_IN   = "in";    // live
    public static final String STATE_POST = "post";  // finished

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final WebClient          client;
    private final ObjectMapper       mapper             = new ObjectMapper();
    private final LiveScoreApiClient liveScoreApiClient;

    // Simple TTL cache: cacheKey → CacheEntry
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ── Constructor ────────────────────────────────────────────────────────
    @Autowired
    public AmericanFootballDataService(WebClient.Builder builder,
                                       LiveScoreApiClient liveScoreApiClient) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.liveScoreApiClient = liveScoreApiClient;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SCOREBOARD — CURRENT WEEK, BY WEEK, BY DATE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All NFL games for the current week (upcoming, live, and finished).
     * Results are cached for CACHE_TTL_MIN; use getLiveGames() for a fresh
     * uncached snapshot of in-progress games.
     *
     * @return list of raw ESPN event maps for the current NFL week
     */
    public List<Map<String, Object>> getCurrentWeekGames() {
        return cached("scoreboard:nfl:currentweek", () -> {
            Map<String, Object> response = fetch(NFL_PATH + "/scoreboard");
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> games = extractEvents(response);
            log.info("getCurrentWeekGames: {} game(s) found", games.size());
            return games;
        });
    }

    /**
     * NFL games for a specific week and season type.
     *
     * @param week       NFL week number (1–18 regular season, 1–4 postseason)
     * @param seasonType use the SEASON_* constants: SEASON_REGULAR (2),
     *                   SEASON_PRESEASON (1), or SEASON_POSTSEASON (3)
     * @return list of raw ESPN event maps
     */
    public List<Map<String, Object>> getGamesByWeek(int week, int seasonType) {
        String cacheKey = "scoreboard:nfl:week:" + seasonType + ":" + week;
        return cached(cacheKey, () -> {
            Map<String, Object> response = fetch(
                    NFL_PATH + "/scoreboard?week=" + week + "&seasontype=" + seasonType);
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> games = extractEvents(response);
            log.info("getGamesByWeek(week={}, seasonType={}): {} game(s)", week, seasonType, games.size());
            return games;
        });
    }

    /**
     * NFL games filtered to a specific calendar date.
     * Fetches the current week's scoreboard and filters by the date field
     * on each event, since ESPN's NFL endpoint does not support a direct
     * date filter the way the NBA scoreboard does.
     *
     * @param date date string in YYYYMMDD format, e.g. "20260910"
     * @return list of raw ESPN event maps whose date matches the given date
     */
    public List<Map<String, Object>> getGamesByDate(String date) {
        return cached("scoreboard:nfl:date:" + date, () -> {
            // ESPN NFL scoreboard accepts a dates param — try it first
            Map<String, Object> response = fetch(NFL_PATH + "/scoreboard?dates=" + date);
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> games = extractEvents(response);
            // If the dates param was ignored (some NFL scoreboard versions don't honour it),
            // fall back to filtering the current-week results manually
            if (games.isEmpty()) {
                games = getCurrentWeekGames().stream()
                        .filter(g -> {
                            String gameDate = extractGameDate(g);
                            return gameDate.startsWith(date.substring(0, 4) + "-"
                                    + date.substring(4, 6) + "-"
                                    + date.substring(6, 8));
                        })
                        .toList();
            }
            log.info("getGamesByDate({}): {} game(s)", date, games.size());
            return games;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FILTERED VIEWS — LIVE / UPCOMING / FINISHED
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Live NFL games currently in progress — FRESH, no cache.
     * Intended for polling every 30–60 seconds during game windows.
     *
     * @return list of in-progress game event maps
     */
    public List<Map<String, Object>> getLiveGames() {
        Map<String, Object> response = fetch(NFL_PATH + "/scoreboard");
        if (response == null) {
            log.warn("getLiveGames: null response from ESPN");
            return Collections.emptyList();
        }
        List<Map<String, Object>> live = extractEvents(response)
                .stream()
                .filter(AmericanFootballDataService::isLive)
                .toList();
        log.info("getLiveGames: {} game(s) in progress", live.size());
        return live;
    }

    /**
     * Upcoming games for the current NFL week that have not yet started.
     *
     * @return list of pre-game event maps
     */
    public List<Map<String, Object>> getUpcomingGames() {
        return getCurrentWeekGames().stream()
                .filter(AmericanFootballDataService::isUpcoming)
                .toList();
    }

    /**
     * Finished games from the current NFL week.
     *
     * @return list of completed game event maps
     */
    public List<Map<String, Object>> getFinishedGames() {
        return getCurrentWeekGames().stream()
                .filter(AmericanFootballDataService::isFinished)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME SUMMARY — BOX SCORE + STATS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Full NFL game summary: offensive/defensive stats, player stats,
     * drive chart, play-by-play, scoring summary, injuries, venue.
     * Always fetches fresh — never cached.
     *
     * <p>Notable keys in the returned map:
     * <ul>
     *   <li>{@code boxscore}        — team and player stats</li>
     *   <li>{@code drives}          — full drive chart</li>
     *   <li>{@code scoringPlays}    — all TDs, FGs, safeties</li>
     *   <li>{@code leaders}         — passing, rushing, receiving leaders per team</li>
     *   <li>{@code broadcasts}      — TV/streaming info</li>
     *   <li>{@code injuries}        — injury report</li>
     *   <li>{@code header}          — high-level game info and status</li>
     * </ul>
     *
     * @param gameId ESPN event ID (the "id" field from scoreboard events)
     * @return raw ESPN summary map, or {@code Map.of()} on failure
     */
    public Map<String, Object> getGameSummary(String gameId) {
        Map<String, Object> result = fetch(NFL_PATH + "/summary?event=" + gameId);
        if (result == null) {
            log.warn("getGameSummary({}): null response", gameId);
            return Map.of();
        }
        log.info("getGameSummary({}): fetched", gameId);
        return result;
    }

    /**
     * Quick score snapshot for a single NFL game.
     * Extracts home/away teams, current scores, quarter, clock, and game state.
     *
     * <p>Checks the current week's cached scoreboard first for speed; falls
     * back to a full summary call if the game is not in this week's board.
     *
     * @param gameId ESPN event ID
     * @return map with keys: gameId, shortName, state, detail, quarter, clock,
     *         home, homeAbbr, homeLogo, homeScore, homeRecord,
     *         away, awayAbbr, awayLogo, awayScore, awayRecord,
     *         possession (if present), redzone (if present)
     */
    public Map<String, Object> getGameScore(String gameId) {
        for (Map<String, Object> game : getCurrentWeekGames()) {
            if (gameId.equals(extractGameId(game))) {
                return buildScoreSnapshot(game);
            }
        }
        // Not in the current week — fetch directly via summary
        Map<String, Object> summary = getGameSummary(gameId);
        return summary.isEmpty() ? Map.of() : buildScoreSnapshotFromSummary(gameId, summary);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME ODDS  (sourced from LiveScoreApiClient)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Odds for a single NFL game (pre-match and live 1X2) sourced from
     * LiveScoreApiClient's live feed. Always fresh — never cached.
     *
     * <p>Matching strategy:
     * <ol>
     *   <li>ID match — livescore fixture "id" == ESPN gameId.</li>
     *   <li>Team name fallback — loose case-insensitive contains match on
     *       home/away team display names resolved from the current week's
     *       scoreboard.</li>
     * </ol>
     *
     * @param gameId ESPN event ID
     * @return map with keys:
     *         <ul>
     *           <li>{@code gameId}       — the ESPN event ID passed in</li>
     *           <li>{@code matchedBy}    — "id" | "teamName" | "none"</li>
     *           <li>{@code preOddsHome}  — home win pre-match odd (or "")</li>
     *           <li>{@code preOddsDraw}  — draw pre-match odd (or "")</li>
     *           <li>{@code preOddsAway}  — away win pre-match odd (or "")</li>
     *           <li>{@code liveOddsHome} — home win live odd (or "")</li>
     *           <li>{@code liveOddsDraw} — draw live odd (or "")</li>
     *           <li>{@code liveOddsAway} — away win live odd (or "")</li>
     *         </ul>
     */
    public Map<String, Object> getGameOdds(String gameId) {
        // Resolve ESPN team names for the fallback name-match
        String espnHome = "";
        String espnAway = "";
        for (Map<String, Object> game : getCurrentWeekGames()) {
            if (gameId.equals(extractGameId(game))) {
                espnHome = extractHomeCompetitor(game)
                        .map(AmericanFootballDataService::extractTeamName).orElse("");
                espnAway = extractAwayCompetitor(game)
                        .map(AmericanFootballDataService::extractTeamName).orElse("");
                break;
            }
        }

        // Search LiveScoreApiClient live feed
        List<Map<String, Object>> liveMatches = liveScoreApiClient.getLiveScores();
        Map<String, Object> matched   = null;
        String              matchedBy = "none";

        for (Map<String, Object> lsMatch : liveMatches) {
            // Try ID match first
            String lsId = LiveScoreApiClient.extractMatchId(lsMatch);
            if (!lsId.isBlank() && lsId.equals(gameId)) {
                matched   = lsMatch;
                matchedBy = "id";
                break;
            }
            // Fallback: loose team-name match (handles naming differences)
            if (!espnHome.isBlank() && !espnAway.isBlank()) {
                String lsHome = LiveScoreApiClient.extractHomeName(lsMatch).toLowerCase();
                String lsAway = LiveScoreApiClient.extractAwayName(lsMatch).toLowerCase();
                if (lsHome.contains(espnHome.toLowerCase()) || espnHome.toLowerCase().contains(lsHome)
                        || lsAway.contains(espnAway.toLowerCase()) || espnAway.toLowerCase().contains(lsAway)) {
                    matched   = lsMatch;
                    matchedBy = "teamName";
                }
            }
        }

        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("gameId",    gameId);
        odds.put("matchedBy", matchedBy);

        if (matched != null) {
            odds.put("preOddsHome",  liveScoreApiClient.extractPreOddsHome(matched));
            odds.put("preOddsDraw",  liveScoreApiClient.extractPreOddsDraw(matched));
            odds.put("preOddsAway",  liveScoreApiClient.extractPreOddsAway(matched));
            odds.put("liveOddsHome", liveScoreApiClient.extractLiveOddsHome(matched));
            odds.put("liveOddsDraw", liveScoreApiClient.extractLiveOddsDraw(matched));
            odds.put("liveOddsAway", liveScoreApiClient.extractLiveOddsAway(matched));
            log.info("getGameOdds({}): odds found via matchedBy={}", gameId, matchedBy);
        } else {
            odds.put("preOddsHome",  "");
            odds.put("preOddsDraw",  "");
            odds.put("preOddsAway",  "");
            odds.put("liveOddsHome", "");
            odds.put("liveOddsDraw", "");
            odds.put("liveOddsAway", "");
            log.warn("getGameOdds({}): no matching fixture in LiveScoreApiClient feed", gameId);
        }

        return Collections.unmodifiableMap(odds);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FULL GAME DETAILS  (score + ESPN stats + LiveScore odds — combined)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Combined game details for any game state (pre, live, post).
     *
     * <p>Merges three data sources into a single map so callers never need to
     * make multiple service calls:
     * <ol>
     *   <li><b>score</b> — from {@link #getGameScore(String)}
     *       (quarter, clock, home/away teams + scores)</li>
     *   <li><b>stats</b> — from {@link #getGameSummary(String)}
     *       (full ESPN box-score: passing, rushing, receiving, defense, drives)</li>
     *   <li><b>odds</b>  — from {@link #getGameOdds(String)}
     *       (LiveScoreApiClient pre/live 1X2 odds)</li>
     * </ol>
     *
     * <p>Each section is always present in the returned map (never null), but
     * may be empty ({@code Map.of()}) if its upstream source returned no data.
     *
     * <p>Intentionally NOT cached — combines a live stats feed with live odds;
     * callers that need reduced API load should call the individual methods and
     * manage their own caching strategy.
     *
     * <p><b>Returned map structure:</b>
     * <pre>
     * {
     *   "gameId" : "401671704",
     *   "score"  : { gameId, shortName, state, detail, quarter, clock,
     *                home, homeAbbr, homeLogo, homeScore, homeRecord,
     *                away, awayAbbr, awayLogo, awayScore, awayRecord,
     *                possession?, redzone? },
     *   "stats"  : { boxscore, drives, scoringPlays, leaders,
     *                broadcasts, injuries, header, ... },
     *   "odds"   : { matchedBy, preOddsHome, preOddsDraw, preOddsAway,
     *                liveOddsHome, liveOddsDraw, liveOddsAway }
     * }
     * </pre>
     *
     * @param gameId ESPN event ID
     * @return combined details map (never null; sections may be empty)
     */
    public Map<String, Object> getFullGameDetails(String gameId) {
        log.info("getFullGameDetails({}): fetching score + stats + odds", gameId);

        Map<String, Object> score = getGameScore(gameId);
        Map<String, Object> stats = getGameSummary(gameId);
        Map<String, Object> odds  = getGameOdds(gameId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gameId", gameId);
        details.put("score",  score);
        details.put("stats",  stats);
        details.put("odds",   odds);

        log.info("getFullGameDetails({}): assembled — scoreEmpty={} statsEmpty={} oddsMatchedBy={}",
                gameId,
                score.isEmpty(),
                stats.isEmpty(),
                odds.getOrDefault("matchedBy", "n/a"));

        return Collections.unmodifiableMap(details);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STANDINGS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Current NFL standings (AFC + NFC, broken down by division).
     *
     * @return raw ESPN standings map
     */
    public Map<String, Object> getStandings() {
        return cached("standings:nfl", () -> {
            Map<String, Object> result = fetch(NFL_PATH + "/standings");
            if (result == null) return Map.of();
            log.info("getStandings: fetched NFL standings");
            return result;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  TEAMS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All 32 NFL teams with IDs, colours, logos, and links.
     *
     * @return raw ESPN teams map
     */
    public Map<String, Object> getAllTeams() {
        return cached("teams:nfl:all", () -> {
            Map<String, Object> result = fetch(NFL_PATH + "/teams?limit=50");
            if (result == null) return Map.of();
            log.info("getAllTeams: fetched");
            return result;
        });
    }

    /**
     * Single NFL team by ESPN team ID.
     *
     * @param teamId ESPN team ID (stable across seasons)
     * @return raw ESPN team map
     */
    public Map<String, Object> getTeamInfo(String teamId) {
        return cached("teams:nfl:" + teamId, () -> {
            Map<String, Object> result = fetch(NFL_PATH + "/teams/" + teamId);
            if (result == null) return Map.of();
            log.info("getTeamInfo({}): fetched", teamId);
            return result;
        });
    }

    /**
     * Full season schedule for an NFL team.
     *
     * @param teamId ESPN team ID
     * @return raw ESPN schedule map
     */
    public Map<String, Object> getTeamSchedule(String teamId) {
        return cached("schedule:nfl:" + teamId, () -> {
            Map<String, Object> result = fetch(NFL_PATH + "/teams/" + teamId + "/schedule");
            if (result == null) return Map.of();
            log.info("getTeamSchedule({}): fetched", teamId);
            return result;
        });
    }

    /**
     * Current roster for an NFL team.
     *
     * @param teamId ESPN team ID
     * @return raw ESPN roster map
     */
    public Map<String, Object> getTeamRoster(String teamId) {
        return cached("roster:nfl:" + teamId, () -> {
            Map<String, Object> result = fetch(NFL_PATH + "/teams/" + teamId + "/roster");
            if (result == null) return Map.of();
            log.info("getTeamRoster({}): fetched", teamId);
            return result;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STATUS DETECTION HELPERS (static — usable without an instance)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Returns true if the game is currently in progress.
     * Checks status.type.state == "in".
     */
    public static boolean isLive(Map<String, Object> game) {
        return STATE_IN.equals(extractState(game));
    }

    /**
     * Returns true if the game has not yet started.
     * Checks status.type.state == "pre".
     */
    public static boolean isUpcoming(Map<String, Object> game) {
        return STATE_PRE.equals(extractState(game));
    }

    /**
     * Returns true if the game is over.
     * Checks status.type.state == "post".
     */
    public static boolean isFinished(Map<String, Object> game) {
        return STATE_POST.equals(extractState(game));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FIELD EXTRACTORS
    // ═════════════════════════════════════════════════════════════════════

    /** ESPN event ID ("id" field on the event root). */
    public static String extractGameId(Map<String, Object> game) {
        Object id = game.get("id");
        return id != null ? id.toString() : "";
    }

    /** Human-readable game name, e.g. "Kansas City Chiefs at Philadelphia Eagles". */
    public static String extractGameName(Map<String, Object> game) {
        Object name = game.get("name");
        return name != null ? name.toString() : "";
    }

    /** Short name, e.g. "KC @ PHI". */
    public static String extractShortName(Map<String, Object> game) {
        Object name = game.get("shortName");
        return name != null ? name.toString() : "";
    }

    /**
     * ISO-8601 date string for the game (e.g. "2026-09-10T17:00Z").
     * Useful for filtering games by date when ESPN's date param is not honoured.
     */
    public static String extractGameDate(Map<String, Object> game) {
        Object date = game.get("date");
        return date != null ? date.toString() : "";
    }

    /**
     * Raw status.type.state string: "pre", "in", or "post".
     */
    @SuppressWarnings("unchecked")
    public static String extractState(Map<String, Object> game) {
        try {
            Map<String, Object> status = (Map<String, Object>) game.get("status");
            if (status == null) return "";
            Map<String, Object> type = (Map<String, Object>) status.get("type");
            if (type == null) return "";
            Object state = type.get("state");
            return state != null ? state.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /**
     * Human-readable status detail, e.g. "3rd Quarter, 8:42" or "Final".
     */
    @SuppressWarnings("unchecked")
    public static String extractStatusDetail(Map<String, Object> game) {
        try {
            Map<String, Object> status = (Map<String, Object>) game.get("status");
            if (status == null) return "";
            Map<String, Object> type = (Map<String, Object>) status.get("type");
            if (type == null) return "";
            Object detail = type.get("detail");
            return detail != null ? detail.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /**
     * Current quarter / period number (0 if not started).
     * Overtime is represented as period 5.
     */
    @SuppressWarnings("unchecked")
    public static int extractQuarter(Map<String, Object> game) {
        try {
            Map<String, Object> status = (Map<String, Object>) game.get("status");
            if (status == null) return 0;
            Object period = status.get("period");
            return period != null ? Integer.parseInt(period.toString()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Display clock string, e.g. "8:42". Empty string when not in progress.
     */
    @SuppressWarnings("unchecked")
    public static String extractClock(Map<String, Object> game) {
        try {
            Map<String, Object> status = (Map<String, Object>) game.get("status");
            if (status == null) return "";
            Object clock = status.get("displayClock");
            return clock != null ? clock.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /**
     * The home-team competitor map from competitions[0].
     */
    public static Optional<Map<String, Object>> extractHomeCompetitor(Map<String, Object> game) {
        return extractCompetitors(game).stream()
                .filter(c -> "home".equals(c.get("homeAway")))
                .findFirst();
    }

    /**
     * The away-team competitor map from competitions[0].
     */
    public static Optional<Map<String, Object>> extractAwayCompetitor(Map<String, Object> game) {
        return extractCompetitors(game).stream()
                .filter(c -> "away".equals(c.get("homeAway")))
                .findFirst();
    }

    /** Team display name from a competitor map, e.g. "Kansas City Chiefs". */
    @SuppressWarnings("unchecked")
    public static String extractTeamName(Map<String, Object> competitor) {
        try {
            Map<String, Object> team = (Map<String, Object>) competitor.get("team");
            if (team == null) return "";
            Object name = team.get("displayName");
            return name != null ? name.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /** Team abbreviation, e.g. "KC", from a competitor map. */
    @SuppressWarnings("unchecked")
    public static String extractTeamAbbrev(Map<String, Object> competitor) {
        try {
            Map<String, Object> team = (Map<String, Object>) competitor.get("team");
            if (team == null) return "";
            Object abbrev = team.get("abbreviation");
            return abbrev != null ? abbrev.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /** Team logo URL from a competitor map. */
    @SuppressWarnings("unchecked")
    public static String extractTeamLogo(Map<String, Object> competitor) {
        try {
            Map<String, Object> team = (Map<String, Object>) competitor.get("team");
            if (team == null) return "";
            Object logo = team.get("logo");
            return logo != null ? logo.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /** Current score string for a competitor (e.g. "24"). Empty pre-game. */
    public static String extractScore(Map<String, Object> competitor) {
        Object score = competitor.get("score");
        return score != null ? score.toString() : "";
    }

    /** Win/loss record string (e.g. "12-5"). */
    public static String extractRecord(Map<String, Object> competitor) {
        Object record = competitor.get("record");
        return record != null ? record.toString() : "";
    }

    /**
     * Quarter-by-quarter scores from a competitor's linescores list.
     *
     * @return list of maps with keys: period, displayValue
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractLinescores(Map<String, Object> competitor) {
        Object ls = competitor.get("linescores");
        if (ls instanceof List<?> list) return (List<Map<String, Object>>) list;
        return Collections.emptyList();
    }

    /**
     * Whether the team currently has possession of the ball.
     * ESPN sets a "possession" flag on the competitor during live games.
     *
     * @param competitor competitor map from competitions[0].competitors
     * @return true if this team currently has the ball
     */
    public static boolean hasPossession(Map<String, Object> competitor) {
        Object possession = competitor.get("possession");
        return Boolean.TRUE.equals(possession) || "true".equalsIgnoreCase(
                possession != null ? possession.toString() : "");
    }

    /**
     * Whether the team is currently in the red zone (inside the opponent's 20).
     *
     * @param competitor competitor map from competitions[0].competitors
     * @return true if this team is in the red zone
     */
    public static boolean isInRedZone(Map<String, Object> competitor) {
        Object redzone = competitor.get("redzone");
        return Boolean.TRUE.equals(redzone) || "true".equalsIgnoreCase(
                redzone != null ? redzone.toString() : "");
    }

    /**
     * Passing yards leader for a competitor.
     *
     * @param competitor competitor map
     * @return map with keys: playerName, displayValue (passing yards), headshotUrl
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractPassingLeader(Map<String, Object> competitor) {
        return extractLeaderByName(competitor, "passingYards");
    }

    /**
     * Rushing yards leader for a competitor.
     *
     * @param competitor competitor map
     * @return map with keys: playerName, displayValue (rushing yards), headshotUrl
     */
    public static Map<String, Object> extractRushingLeader(Map<String, Object> competitor) {
        return extractLeaderByName(competitor, "rushingYards");
    }

    /**
     * Receiving yards leader for a competitor.
     *
     * @param competitor competitor map
     * @return map with keys: playerName, displayValue (receiving yards), headshotUrl
     */
    public static Map<String, Object> extractReceivingLeader(Map<String, Object> competitor) {
        return extractLeaderByName(competitor, "receivingYards");
    }

    /**
     * Venue name and city for the game, extracted from competitions[0].
     *
     * @return map with keys: name, city, state — or empty map if not present
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractVenue(Map<String, Object> game) {
        try {
            List<?> competitions = (List<?>) game.get("competitions");
            if (competitions == null || competitions.isEmpty()) return Map.of();
            Map<String, Object> comp  = (Map<String, Object>) competitions.get(0);
            Map<String, Object> venue = (Map<String, Object>) comp.get("venue");
            if (venue == null) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name",  venue.getOrDefault("fullName", "").toString());
            Map<String, Object> address = (Map<String, Object>) venue.get("address");
            if (address != null) {
                result.put("city",  address.getOrDefault("city",  "").toString());
                result.put("state", address.getOrDefault("state", "").toString());
            }
            return Collections.unmodifiableMap(result);
        } catch (ClassCastException ignored) {
            return Map.of();
        }
    }

    /**
     * Broadcast network(s) for the game, e.g. ["NBC", "Peacock"].
     *
     * @return list of broadcast name strings (may be empty)
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractBroadcasts(Map<String, Object> game) {
        try {
            List<?> competitions = (List<?>) game.get("competitions");
            if (competitions == null || competitions.isEmpty()) return Collections.emptyList();
            Map<String, Object> comp       = (Map<String, Object>) competitions.get(0);
            List<?>             broadcasts = (List<?>) comp.get("broadcasts");
            if (broadcasts == null) return Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (Object bObj : broadcasts) {
                Map<String, Object> broadcast = (Map<String, Object>) bObj;
                Object namesObj = broadcast.get("names");
                if (namesObj instanceof List<?> namesList) {
                    for (Object n : namesList) names.add(n.toString());
                }
            }
            return Collections.unmodifiableList(names);
        } catch (ClassCastException ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * Playoff series info, if present during the postseason.
     *
     * @return series map with keys: summary, completed — or empty map in regular season
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractPlayoffSeries(Map<String, Object> game) {
        try {
            List<?> competitions = (List<?>) game.get("competitions");
            if (competitions == null || competitions.isEmpty()) return Map.of();
            Map<String, Object> comp = (Map<String, Object>) competitions.get(0);
            Object series = comp.get("series");
            if (series instanceof Map<?, ?> seriesMap) return (Map<String, Object>) seriesMap;
        } catch (ClassCastException ignored) {}
        return Map.of();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CACHE MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════

    public void invalidateCache(String key) { cache.remove(key); }
    public void clearCache()                { cache.clear(); }

    // ═════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════

    /** TTL-based cache wrapper. */
    @SuppressWarnings("unchecked")
    private <T> T cached(String cacheKey, Supplier<T> loader) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            log.debug("AmericanFootballDataService cache HIT: '{}'", cacheKey);
            return (T) entry.data();
        }
        T result = loader.get();
        if (result != null) {
            long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CACHE_TTL_MIN);
            cache.put(cacheKey, new CacheEntry(result, expiresAt));
        }
        return result;
    }

    /** Single GET call — returns parsed JSON map or null on any failure. */
    private Map<String, Object> fetch(String path) {
        try {
            String raw = client.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(e -> {
                        log.warn("AmericanFootballDataService [{}] network error: {}", path, e.getMessage());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();

            if (raw == null || raw.isBlank()) {
                log.debug("AmericanFootballDataService [{}] blank response", path);
                return null;
            }

            Map<String, Object> result = mapper.readValue(raw, MAP_TYPE);
            log.info("AmericanFootballDataService [{}] OK ({} bytes)", path, raw.length());
            return result;

        } catch (Exception e) {
            log.error("AmericanFootballDataService [{}] error: {}", path, e.getMessage());
            return null;
        }
    }

    /** Extract the events list from a scoreboard response. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractEvents(Map<String, Object> response) {
        Object events = response.get("events");
        if (events instanceof List<?> list) return (List<Map<String, Object>>) list;
        return Collections.emptyList();
    }

    /** Extract the competitors list from competitions[0]. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractCompetitors(Map<String, Object> game) {
        try {
            List<?> competitions = (List<?>) game.get("competitions");
            if (competitions == null || competitions.isEmpty()) return Collections.emptyList();
            Map<String, Object> comp = (Map<String, Object>) competitions.get(0);
            Object competitors = comp.get("competitors");
            if (competitors instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (ClassCastException ignored) {}
        return Collections.emptyList();
    }

    /** Generic leader extractor by stat name (passingYards, rushingYards, etc.). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractLeaderByName(Map<String, Object> competitor,
                                                            String statName) {
        try {
            List<?> leaders = (List<?>) competitor.get("leaders");
            if (leaders == null) return Map.of();
            for (Object leaderObj : leaders) {
                Map<String, Object> leaderGroup = (Map<String, Object>) leaderObj;
                if (statName.equals(leaderGroup.get("name"))) {
                    List<?> leaderList = (List<?>) leaderGroup.get("leaders");
                    if (leaderList != null && !leaderList.isEmpty()) {
                        Map<String, Object> top     = (Map<String, Object>) leaderList.get(0);
                        Map<String, Object> athlete = (Map<String, Object>) top.get("athlete");
                        String displayValue = top.get("displayValue") != null
                                ? top.get("displayValue").toString() : "";
                        String playerName   = athlete != null && athlete.get("fullName") != null
                                ? athlete.get("fullName").toString() : "";
                        String headshot     = athlete != null && athlete.get("headshot") != null
                                ? athlete.get("headshot").toString() : "";
                        return Map.of(
                                "playerName",   playerName,
                                "displayValue", displayValue,
                                "headshotUrl",  headshot
                        );
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return Map.of();
    }

    /** Build a lightweight score snapshot from a current-week scoreboard event map. */
    private static Map<String, Object> buildScoreSnapshot(Map<String, Object> game) {
        Optional<Map<String, Object>> homeOpt = extractHomeCompetitor(game);
        Optional<Map<String, Object>> awayOpt = extractAwayCompetitor(game);

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("gameId",    extractGameId(game));
        snap.put("shortName", extractShortName(game));
        snap.put("state",     extractState(game));
        snap.put("detail",    extractStatusDetail(game));
        snap.put("quarter",   extractQuarter(game));
        snap.put("clock",     extractClock(game));
        snap.put("date",      extractGameDate(game));

        homeOpt.ifPresent(home -> {
            snap.put("home",       extractTeamName(home));
            snap.put("homeAbbr",   extractTeamAbbrev(home));
            snap.put("homeLogo",   extractTeamLogo(home));
            snap.put("homeScore",  extractScore(home));
            snap.put("homeRecord", extractRecord(home));
            if (hasPossession(home))  snap.put("possession", "home");
            if (isInRedZone(home))    snap.put("redzone",    "home");
        });
        awayOpt.ifPresent(away -> {
            snap.put("away",       extractTeamName(away));
            snap.put("awayAbbr",   extractTeamAbbrev(away));
            snap.put("awayLogo",   extractTeamLogo(away));
            snap.put("awayScore",  extractScore(away));
            snap.put("awayRecord", extractRecord(away));
            if (hasPossession(away) && !snap.containsKey("possession")) snap.put("possession", "away");
            if (isInRedZone(away)   && !snap.containsKey("redzone"))    snap.put("redzone",    "away");
        });

        Map<String, Object> venue = extractVenue(game);
        if (!venue.isEmpty()) snap.put("venue", venue);

        List<String> broadcasts = extractBroadcasts(game);
        if (!broadcasts.isEmpty()) snap.put("broadcasts", broadcasts);

        Map<String, Object> series = extractPlayoffSeries(game);
        if (!series.isEmpty()) snap.put("series", series);

        return Collections.unmodifiableMap(snap);
    }

    /** Minimal score snapshot built from a summary response (game not in current week board). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildScoreSnapshotFromSummary(String gameId,
                                                                      Map<String, Object> summary) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("gameId", gameId);
        try {
            Map<String, Object> header = (Map<String, Object>) summary.get("header");
            if (header != null) {
                Object competitions = header.get("competitions");
                if (competitions instanceof List<?> compList && !compList.isEmpty()) {
                    Map<String, Object> comp   = (Map<String, Object>) compList.get(0);
                    Map<String, Object> status = (Map<String, Object>) comp.get("status");
                    if (status != null) {
                        Map<String, Object> type = (Map<String, Object>) status.get("type");
                        if (type != null) {
                            snap.put("state",   type.getOrDefault("state",   "").toString());
                            snap.put("detail",  type.getOrDefault("detail",  "").toString());
                        }
                        Object period = status.get("period");
                        if (period != null) snap.put("quarter", Integer.parseInt(period.toString()));
                        Object clock = status.get("displayClock");
                        if (clock != null) snap.put("clock", clock.toString());
                    }
                    List<?> competitors = (List<?>) comp.get("competitors");
                    if (competitors != null) {
                        for (Object cObj : competitors) {
                            Map<String, Object> c    = (Map<String, Object>) cObj;
                            Map<String, Object> team = (Map<String, Object>) c.get("team");
                            String side  = c.getOrDefault("homeAway", "").toString();
                            String name  = team != null ? team.getOrDefault("displayName", "").toString() : "";
                            String score = c.getOrDefault("score", "").toString();
                            snap.put(side + "Team",  name);
                            snap.put(side + "Score", score);
                        }
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return Collections.unmodifiableMap(snap);
    }
}
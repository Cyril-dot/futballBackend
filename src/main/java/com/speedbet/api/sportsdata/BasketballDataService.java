package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Service for NBA basketball data via the ESPN unofficial API.
 *
 * ── Data source ──────────────────────────────────────────────────────────
 *
 *   Base URL : https://site.api.espn.com/apis/site/v2/sports/basketball/nba
 *   Auth     : None required — plain HTTP GET
 *
 * ── Game status values (status.type.state) ──────────────────────────────
 *
 *   "pre"  — upcoming / not yet started
 *   "in"   — live / in progress
 *   "post" — finished
 *
 * ── Key methods ──────────────────────────────────────────────────────────
 *
 *   getTodayGames()              — all games for the current day
 *   getGamesByDate(String)       — games for a specific date (YYYYMMDD)
 *   getLiveGames()               — only in-progress games (filtered from scoreboard)
 *   getUpcomingGames()           — only pre-game entries for today
 *   getFinishedGames()           — only completed games from today
 *   getGameSummary(String)       — full box score + player stats for a game ID
 *   getGameScore(String)         — quick score extract (home/away + current score)
 *   getGameOdds(String)          — pre/live/post-match odds (1X2) from LiveScoreApiClient
 *   getFullGameDetails(String)   — combined: score + ESPN box-score stats + odds
 *   getStandings()               — East + West conference standings
 *   getAllTeams()                 — all 30 NBA teams
 *   getTeamInfo(String)          — single team by ID
 *   getTeamSchedule(String)      — team's full season schedule
 *   getTeamRoster(String)        — team's current roster
 *
 * ── Odds source ──────────────────────────────────────────────────────────
 *
 *   Odds are sourced from LiveScoreApiClient (the project's primary sports-data
 *   client).  The match is located by scanning today's live feed for a fixture
 *   whose ESPN game ID or team names match; the odds fields (pre/live 1X2) are
 *   then extracted using LiveScoreApiClient's existing odds helpers.
 *
 *   If no matching fixture is found in the live feed (e.g. the game is not in a
 *   competition covered by livescore-api.com) the odds block is returned empty
 *   rather than throwing — callers should always null-check odds fields.
 *
 * ── Caching ──────────────────────────────────────────────────────────────
 *
 *   Scoreboard / standings / team data are cached for CACHE_TTL_MINUTES.
 *   getLiveGames() and getGameSummary() intentionally bypass the cache so
 *   callers always get the freshest score / box-score data.
 *
 * ── Polling guidance ─────────────────────────────────────────────────────
 *
 *   Poll getLiveGames() every 30–60 seconds during active game windows.
 *   Polling faster risks ESPN rate-limiting the IP.
 */
@Slf4j
@Component
public class BasketballDataService {

    // ── ESPN NBA base URL ──────────────────────────────────────────────────
    private static final String BASE_URL       = "https://site.api.espn.com";
    private static final String NBA_PATH       = "/apis/site/v2/sports/basketball/nba";
    private static final long   CACHE_TTL_MIN  = 5;
    private static final long   REQUEST_TIMEOUT_SEC = 12;

    // ── Game state constants (ESPN status.type.state) ─────────────────────
    public static final String STATE_PRE  = "pre";   // upcoming
    public static final String STATE_IN   = "in";    // live
    public static final String STATE_POST = "post";  // finished

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final WebClient           client;
    private final ObjectMapper        mapper            = new ObjectMapper();
    private final LiveScoreApiClient  liveScoreApiClient;

    // Simple TTL cache: cacheKey → CacheEntry
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ── Constructor ────────────────────────────────────────────────────────
    @Autowired
    public BasketballDataService(WebClient.Builder builder, LiveScoreApiClient liveScoreApiClient) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.liveScoreApiClient = liveScoreApiClient;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SCOREBOARD — TODAY & BY DATE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All NBA games scheduled for today (upcoming, live, and finished).
     * Results are cached for CACHE_TTL_MINUTES; use getLiveGames() for
     * a fresh uncached snapshot of in-progress games.
     *
     * @return list of raw ESPN event maps
     */
    public List<Map<String, Object>> getTodayGames() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return cached("scoreboard:today:" + today, () -> {
            Map<String, Object> response = fetch(NBA_PATH + "/scoreboard");
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> games = extractEvents(response);
            log.info("getTodayGames: {} game(s) found for {}", games.size(), today);
            return games;
        });
    }

    /**
     * NBA games for a specific calendar date.
     *
     * @param date date string in YYYYMMDD format, e.g. "20260510"
     * @return list of raw ESPN event maps
     */
    public List<Map<String, Object>> getGamesByDate(String date) {
        return cached("scoreboard:date:" + date, () -> {
            Map<String, Object> response = fetch(NBA_PATH + "/scoreboard?dates=" + date);
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> games = extractEvents(response);
            log.info("getGamesByDate({}): {} game(s)", date, games.size());
            return games;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FILTERED VIEWS — LIVE / UPCOMING / FINISHED
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Live games currently in progress — FRESH, no cache.
     * Intended for polling every 30–60 seconds.
     *
     * @return list of in-progress game event maps
     */
    public List<Map<String, Object>> getLiveGames() {
        Map<String, Object> response = fetch(NBA_PATH + "/scoreboard");
        if (response == null) {
            log.warn("getLiveGames: null response from ESPN");
            return Collections.emptyList();
        }
        List<Map<String, Object>> live = extractEvents(response)
                .stream()
                .filter(BasketballDataService::isLive)
                .toList();
        log.info("getLiveGames: {} game(s) in progress", live.size());
        return live;
    }

    /**
     * Upcoming games for today that have not yet started.
     *
     * @return list of pre-game event maps
     */
    public List<Map<String, Object>> getUpcomingGames() {
        return getTodayGames().stream()
                .filter(BasketballDataService::isUpcoming)
                .toList();
    }

    /**
     * Finished games from today's scoreboard.
     *
     * @return list of completed game event maps
     */
    public List<Map<String, Object>> getFinishedGames() {
        return getTodayGames().stream()
                .filter(BasketballDataService::isFinished)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME SUMMARY — BOX SCORE + STATS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Full game summary: box score, player stats, quarter scores, leaders.
     * Always fetches fresh — never cached — so it reflects the latest data.
     *
     * @param gameId ESPN event ID (the "id" field on an event from the scoreboard)
     * @return raw summary map (keys: boxscore, plays, leaders, broadcasts, etc.)
     */
    public Map<String, Object> getGameSummary(String gameId) {
        Map<String, Object> result = fetch(NBA_PATH + "/summary?event=" + gameId);
        if (result == null) {
            log.warn("getGameSummary({}): null response", gameId);
            return Map.of();
        }
        log.info("getGameSummary({}): fetched", gameId);
        return result;
    }

    /**
     * Quick score snapshot for a single game.
     * Extracts home team, away team, their scores, and current game state.
     *
     * @param gameId ESPN event ID
     * @return map with keys: gameId, home, away, homeScore, awayScore, state, detail
     */
    public Map<String, Object> getGameScore(String gameId) {
        // Prefer the live scoreboard (today) — fall back to full summary
        List<Map<String, Object>> todayGames = getTodayGames();
        for (Map<String, Object> game : todayGames) {
            if (gameId.equals(extractGameId(game))) {
                return buildScoreSnapshot(game);
            }
        }
        // Not in today's scoreboard — fetch directly via summary
        Map<String, Object> summary = getGameSummary(gameId);
        return summary.isEmpty() ? Map.of() : buildScoreSnapshotFromSummary(gameId, summary);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME ODDS  (sourced from LiveScoreApiClient)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Odds for a single NBA game (pre-match and live 1X2) sourced from
     * LiveScoreApiClient's live feed.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Pull today's live feed from LiveScoreApiClient (all live matches,
     *       including NOT STARTED entries which the feed includes).</li>
     *   <li>Match the fixture against the ESPN game ID (stored in the "id"
     *       field if livescore-api.com carries it) or, as a fallback, by
     *       comparing home/away team names from the ESPN scoreboard.</li>
     *   <li>Extract pre/live 1X2 odds using LiveScoreApiClient's existing
     *       helpers and return a structured odds map.</li>
     * </ol>
     *
     * <p>Caching: odds are intentionally NOT cached — they change frequently
     * and callers (e.g. a betting slip view) always need the latest value.
     *
     * @param gameId ESPN event ID (from scoreboard or summary)
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
        for (Map<String, Object> game : getTodayGames()) {
            if (gameId.equals(extractGameId(game))) {
                espnHome = extractHomeCompetitor(game).map(BasketballDataService::extractTeamName).orElse("");
                espnAway = extractAwayCompetitor(game).map(BasketballDataService::extractTeamName).orElse("");
                break;
            }
        }

        // Search LiveScoreApiClient live feed
        List<Map<String, Object>> liveMatches = liveScoreApiClient.getLiveScores();
        Map<String, Object> matched    = null;
        String              matchedBy  = "none";

        for (Map<String, Object> lsMatch : liveMatches) {
            // Try ID match first
            String lsId = LiveScoreApiClient.extractMatchId(lsMatch);
            if (!lsId.isBlank() && lsId.equals(gameId)) {
                matched   = lsMatch;
                matchedBy = "id";
                break;
            }
            // Fallback: loose team-name match (case-insensitive contains)
            if (!espnHome.isBlank() && !espnAway.isBlank()) {
                String lsHome = LiveScoreApiClient.extractHomeName(lsMatch).toLowerCase();
                String lsAway = LiveScoreApiClient.extractAwayName(lsMatch).toLowerCase();
                if (lsHome.contains(espnHome.toLowerCase()) || espnHome.toLowerCase().contains(lsHome)
                        || lsAway.contains(espnAway.toLowerCase()) || espnAway.toLowerCase().contains(lsAway)) {
                    matched   = lsMatch;
                    matchedBy = "teamName";
                    // No break — keep scanning in case an exact ID match exists later
                    if ("id".equals(matchedBy)) break;
                }
            }
        }

        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("gameId",       gameId);
        odds.put("matchedBy",    matchedBy);

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
            log.warn("getGameOdds({}): no matching fixture found in LiveScoreApiClient feed", gameId);
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
     *   <li><b>score</b>     — from {@link #getGameScore(String)} (lightweight snapshot)</li>
     *   <li><b>stats</b>     — from {@link #getGameSummary(String)} (full ESPN box-score)</li>
     *   <li><b>odds</b>      — from {@link #getGameOdds(String)} (LiveScoreApiClient 1X2)</li>
     * </ol>
     *
     * <p>Each section is always present in the returned map (never null), but may
     * be empty ({@code Map.of()}) if the upstream source returned no data.
     *
     * <p>Caching: none — this method is intentionally fresh on every call because
     * it combines a live stats feed with live odds. Callers that need reduced
     * API load should call the individual methods and manage their own caching.
     *
     * <p><b>Returned map keys:</b>
     * <pre>
     * {
     *   "gameId"  : "401871335",
     *   "score"   : { gameId, shortName, state, detail, period, clock,
     *                 home, homeAbbr, homeLogo, homeScore, homeRecord,
     *                 away, awayAbbr, awayLogo, awayScore, awayRecord,
     *                 series? },
     *   "stats"   : { boxscore, plays, leaders, broadcasts, ... },   // raw ESPN summary
     *   "odds"    : { matchedBy, preOddsHome, preOddsDraw, preOddsAway,
     *                 liveOddsHome, liveOddsDraw, liveOddsAway }
     * }
     * </pre>
     *
     * @param gameId ESPN event ID
     * @return combined details map
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



    /**
     * Current NBA standings (East + West conferences).
     *
     * @return raw ESPN standings map
     */
    public Map<String, Object> getStandings() {
        return cached("standings:nba", () -> {
            Map<String, Object> result = fetch(NBA_PATH + "/standings");
            if (result == null) return Map.of();
            log.info("getStandings: fetched NBA standings");
            return result;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  TEAMS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All 30 NBA teams with IDs, colours, logos, and links.
     *
     * @return raw ESPN teams map
     */
    public Map<String, Object> getAllTeams() {
        return cached("teams:nba:all", () -> {
            Map<String, Object> result = fetch(NBA_PATH + "/teams?limit=50");
            if (result == null) return Map.of();
            log.info("getAllTeams: fetched");
            return result;
        });
    }

    /**
     * Single NBA team by ESPN team ID.
     *
     * @param teamId ESPN team ID, e.g. "5" for Cleveland Cavaliers
     * @return raw ESPN team map
     */
    public Map<String, Object> getTeamInfo(String teamId) {
        return cached("teams:nba:" + teamId, () -> {
            Map<String, Object> result = fetch(NBA_PATH + "/teams/" + teamId);
            if (result == null) return Map.of();
            log.info("getTeamInfo({}): fetched", teamId);
            return result;
        });
    }

    /**
     * Full season schedule for a team.
     *
     * @param teamId ESPN team ID
     * @return raw ESPN schedule map
     */
    public Map<String, Object> getTeamSchedule(String teamId) {
        return cached("schedule:nba:" + teamId, () -> {
            Map<String, Object> result = fetch(NBA_PATH + "/teams/" + teamId + "/schedule");
            if (result == null) return Map.of();
            log.info("getTeamSchedule({}): fetched", teamId);
            return result;
        });
    }

    /**
     * Current roster for a team.
     *
     * @param teamId ESPN team ID
     * @return raw ESPN roster map
     */
    public Map<String, Object> getTeamRoster(String teamId) {
        return cached("roster:nba:" + teamId, () -> {
            Map<String, Object> result = fetch(NBA_PATH + "/teams/" + teamId + "/roster");
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

    /** Human-readable game name, e.g. "Cleveland Cavaliers at Boston Celtics". */
    public static String extractGameName(Map<String, Object> game) {
        Object name = game.get("name");
        return name != null ? name.toString() : "";
    }

    /** Short name, e.g. "CLE @ BOS". */
    public static String extractShortName(Map<String, Object> game) {
        Object name = game.get("shortName");
        return name != null ? name.toString() : "";
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
     * Human-readable status detail, e.g. "3rd Quarter, 3:00" or "Final".
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
     */
    @SuppressWarnings("unchecked")
    public static int extractPeriod(Map<String, Object> game) {
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
     * Display clock string, e.g. "3:00". Empty string when not in progress.
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
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> extractHomeCompetitor(Map<String, Object> game) {
        return extractCompetitors(game).stream()
                .filter(c -> "home".equals(c.get("homeAway")))
                .findFirst();
    }

    /**
     * The away-team competitor map from competitions[0].
     */
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> extractAwayCompetitor(Map<String, Object> game) {
        return extractCompetitors(game).stream()
                .filter(c -> "away".equals(c.get("homeAway")))
                .findFirst();
    }

    /** Team display name from a competitor map. */
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

    /** Team abbreviation (e.g. "CLE") from a competitor map. */
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

    /** Current score string for a competitor (e.g. "116"). Empty pre-game. */
    public static String extractScore(Map<String, Object> competitor) {
        Object score = competitor.get("score");
        return score != null ? score.toString() : "";
    }

    /** Win/loss record string (e.g. "42-20"). */
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
     * Points leader for a competitor.
     *
     * @return map with keys: playerName, displayValue (points), headshotUrl
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractPointsLeader(Map<String, Object> competitor) {
        try {
            List<?> leaders = (List<?>) competitor.get("leaders");
            if (leaders == null) return Map.of();
            for (Object leaderObj : leaders) {
                Map<String, Object> leaderGroup = (Map<String, Object>) leaderObj;
                if ("points".equals(leaderGroup.get("name"))) {
                    List<?> leaderList = (List<?>) leaderGroup.get("leaders");
                    if (leaderList != null && !leaderList.isEmpty()) {
                        Map<String, Object> top = (Map<String, Object>) leaderList.get(0);
                        Map<String, Object> athlete = (Map<String, Object>) top.get("athlete");
                        String displayValue = top.get("displayValue") != null ? top.get("displayValue").toString() : "";
                        String playerName   = athlete != null && athlete.get("fullName") != null
                                ? athlete.get("fullName").toString() : "";
                        String headshot     = athlete != null && athlete.get("headshot") != null
                                ? athlete.get("headshot").toString() : "";
                        return Map.of(
                                "playerName",    playerName,
                                "displayValue",  displayValue,
                                "headshotUrl",   headshot
                        );
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return Map.of();
    }

    /**
     * Playoff series summary, if present (during postseason).
     *
     * @return map with keys: summary, completed — or empty map in regular season
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
    public void clearCache() { cache.clear(); }

    // ═════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════

    /** TTL-based cache wrapper. */
    @SuppressWarnings("unchecked")
    private <T> T cached(String cacheKey, Supplier<T> loader) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            log.debug("BasketballDataService cache HIT: '{}'", cacheKey);
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
                        log.warn("BasketballDataService [{}] network error: {}", path, e.getMessage());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();

            if (raw == null || raw.isBlank()) {
                log.debug("BasketballDataService [{}] blank response", path);
                return null;
            }

            Map<String, Object> result = mapper.readValue(raw, MAP_TYPE);
            log.info("BasketballDataService [{}] OK ({} bytes)", path, raw.length());
            return result;

        } catch (Exception e) {
            log.error("BasketballDataService [{}] error: {}", path, e.getMessage());
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

    /** Extract competitors list from competitions[0]. */
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

    /** Build a lightweight score snapshot from a scoreboard event map. */
    private static Map<String, Object> buildScoreSnapshot(Map<String, Object> game) {
        Optional<Map<String, Object>> homeOpt = extractHomeCompetitor(game);
        Optional<Map<String, Object>> awayOpt = extractAwayCompetitor(game);

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("gameId",     extractGameId(game));
        snap.put("shortName",  extractShortName(game));
        snap.put("state",      extractState(game));
        snap.put("detail",     extractStatusDetail(game));
        snap.put("period",     extractPeriod(game));
        snap.put("clock",      extractClock(game));

        homeOpt.ifPresent(home -> {
            snap.put("home",      extractTeamName(home));
            snap.put("homeAbbr",  extractTeamAbbrev(home));
            snap.put("homeLogo",  extractTeamLogo(home));
            snap.put("homeScore", extractScore(home));
            snap.put("homeRecord",extractRecord(home));
        });
        awayOpt.ifPresent(away -> {
            snap.put("away",      extractTeamName(away));
            snap.put("awayAbbr",  extractTeamAbbrev(away));
            snap.put("awayLogo",  extractTeamLogo(away));
            snap.put("awayScore", extractScore(away));
            snap.put("awayRecord",extractRecord(away));
        });

        Map<String, Object> series = extractPlayoffSeries(game);
        if (!series.isEmpty()) snap.put("series", series);

        return Collections.unmodifiableMap(snap);
    }

    /** Minimal score snapshot when only a summary response is available. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildScoreSnapshotFromSummary(String gameId, Map<String, Object> summary) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("gameId", gameId);
        try {
            Map<String, Object> header = (Map<String, Object>) summary.get("header");
            if (header != null) {
                Object competitions = header.get("competitions");
                if (competitions instanceof List<?> compList && !compList.isEmpty()) {
                    Map<String, Object> comp    = (Map<String, Object>) compList.get(0);
                    Map<String, Object> status  = (Map<String, Object>) comp.get("status");
                    if (status != null) {
                        Map<String, Object> type = (Map<String, Object>) status.get("type");
                        if (type != null) {
                            snap.put("state",  type.getOrDefault("state",  "").toString());
                            snap.put("detail", type.getOrDefault("detail", "").toString());
                        }
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
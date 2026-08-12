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
 * Service for MLB Baseball data via the ESPN unofficial API.
 *
 * ── Data source ──────────────────────────────────────────────────────────
 *
 *   Base URL : https://site.api.espn.com/apis/site/v2/sports/baseball/mlb
 *   Auth     : None required — plain HTTP GET
 *
 * ── Baseball-specific status notes ──────────────────────────────────────
 *
 *   ESPN's status.type.state values are the same as all other sports:
 *     "pre"  — upcoming / not yet started
 *     "in"   — live / in progress
 *     "post" — finished
 *
 *   For live games the status.type.detail gives the inning context, e.g.
 *   "Top 7th" or "Bot 4th, 2 Outs". The inning and half are also
 *   accessible via extractInning() and extractInningHalf().
 *
 * ── Key methods ──────────────────────────────────────────────────────────
 *
 *   getTodayGames()              — all MLB games for the current day
 *   getGamesByDate(String)       — games for a specific date (YYYYMMDD)
 *   getLiveGames()               — only in-progress games (fresh, no cache)
 *   getUpcomingGames()           — pre-game entries for today
 *   getFinishedGames()           — completed games from today
 *   getGameSummary(String)       — full box score + pitcher/batter stats
 *   getGameScore(String)         — quick score snapshot (teams, runs, inning)
 *   getGameOdds(String)          — pre/live 1X2 odds from LiveScoreApiClient
 *   getFullGameDetails(String)   — combined: score + ESPN stats + odds
 *   getStandings()               — AL + NL standings by division
 *   getAllTeams()                 — all 30 MLB teams
 *   getTeamInfo(String)          — single team by ESPN team ID
 *   getTeamSchedule(String)      — full season schedule for a team
 *
 * ── MLB-specific stats (in getGameSummary / getFullGameDetails) ──────────
 *
 *   Box score includes:
 *     - Full batting lineup (AB, H, HR, RBI, BB, SO, AVG)
 *     - Starting and relief pitcher stats (IP, H, R, ER, BB, SO, ERA)
 *     - Inning-by-inning run breakdown (linescores)
 *     - Scoring plays (which inning, who scored, how many runs)
 *     - Win / loss / save pitcher decisions
 *     - Current pitcher matchup (live games)
 *
 * ── Odds source ──────────────────────────────────────────────────────────
 *
 *   Odds are sourced from LiveScoreApiClient. The fixture is matched by
 *   ESPN game ID first, then by home/away team name fallback. If no
 *   match is found the odds block is returned empty — always check
 *   the "matchedBy" field ("id", "teamName", or "none").
 *
 * ── Caching ──────────────────────────────────────────────────────────────
 *
 *   Scoreboard / standings / team data cached for CACHE_TTL_MIN minutes.
 *   getLiveGames(), getGameSummary(), getGameOdds(), getFullGameDetails()
 *   are always fresh — intentionally never cached.
 *
 * ── Polling guidance ─────────────────────────────────────────────────────
 *
 *   Poll getLiveGames() every 30–60 seconds during game windows.
 *   Polling faster risks ESPN rate-limiting the IP.
 */
@Slf4j
@Component
public class BaseballDataService {

    // ── ESPN MLB base URL ──────────────────────────────────────────────────
    private static final String BASE_URL = "https://site.web.api.espn.com";
    private static final String MLB_PATH            = "/apis/site/v2/sports/baseball/mlb";
    private static final long   CACHE_TTL_MIN       = 5;
    private static final long   REQUEST_TIMEOUT_SEC = 12;

    // ── Game state constants (ESPN status.type.state) ─────────────────────
    public static final String STATE_PRE  = "pre";
    public static final String STATE_IN   = "in";
    public static final String STATE_POST = "post";

    // ── Inning half constants ──────────────────────────────────────────────
    public static final String INNING_TOP = "top";
    public static final String INNING_BOT = "bot";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final WebClient          client;
    private final ObjectMapper       mapper             = new ObjectMapper();
    private final LiveScoreApiClient liveScoreApiClient;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ── Constructor ────────────────────────────────────────────────────────
    @Autowired
    public BaseballDataService(WebClient.Builder builder,
                               LiveScoreApiClient liveScoreApiClient) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.liveScoreApiClient = liveScoreApiClient;
    }

    public static String extractGameDate(Map<String, Object> game) {
        Object dateObj = game.get("date");
        log.info("extractKickoffTime: root date={}", dateObj);
        // Fallback: competitions[0].date
        if (dateObj == null) {
            try {
                List<?> competitions = (List<?>) game.get("competitions");
                if (competitions != null && !competitions.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> comp = (Map<String, Object>) competitions.get(0);
                    dateObj = comp.get("date");
                }
            } catch (ClassCastException ignored) {}
        }

        return dateObj != null ? dateObj.toString() : null;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SCOREBOARD — TODAY & BY DATE
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All MLB games scheduled for today (upcoming, live, and finished).
     * Cached for CACHE_TTL_MIN; use getLiveGames() for a fresh uncached
     * snapshot of in-progress games.
     *
     * @return list of raw ESPN event maps
     */
    public List<Map<String, Object>> getTodayGames() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return cached("scoreboard:mlb:today:" + today, () -> {
            Map<String, Object> response = fetch(MLB_PATH + "/scoreboard?dates=" + today); // ← fix
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> games = extractEvents(response);
            log.info("getTodayGames: {} game(s) found for {}", games.size(), today);
            return games;
        });
    }

    /**
     * MLB games for a specific calendar date.
     *
     * @param date date string in YYYYMMDD format, e.g. "20260815"
     * @return list of raw ESPN event maps
     */
    public List<Map<String, Object>> getGamesByDate(String date) {
        return cached("scoreboard:mlb:date:" + date, () -> {
            Map<String, Object> response = fetch(MLB_PATH + "/scoreboard?dates=" + date);
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
     * Live MLB games currently in progress — FRESH, no cache.
     * Intended for polling every 30–60 seconds during game windows.
     *
     * @return list of in-progress game event maps
     */
    public List<Map<String, Object>> getLiveGames() {
        Map<String, Object> response = fetch(MLB_PATH + "/scoreboard");
        if (response == null) {
            log.warn("getLiveGames: null response from ESPN");
            return Collections.emptyList();
        }
        List<Map<String, Object>> live = extractEvents(response)
                .stream()
                .filter(BaseballDataService::isLive)
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
                .filter(BaseballDataService::isUpcoming)
                .toList();
    }

    /**
     * Finished games from today's scoreboard.
     *
     * @return list of completed game event maps
     */
    public List<Map<String, Object>> getFinishedGames() {
        return getTodayGames().stream()
                .filter(BaseballDataService::isFinished)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME SUMMARY — BOX SCORE + STATS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Full MLB game summary: batting lineups, pitcher stats, inning-by-inning
     * scores, scoring plays, win/loss/save decisions.
     * Always fetches fresh — never cached.
     *
     * <p>Notable keys in the returned map:
     * <ul>
     *   <li>{@code boxscore}      — team stats + per-player batting/pitching lines</li>
     *   <li>{@code scoringPlays}  — all scoring plays with inning, description, runs</li>
     *   <li>{@code leaders}       — hitting/pitching leaders per team</li>
     *   <li>{@code broadcasts}    — TV/streaming info</li>
     *   <li>{@code header}        — high-level game info, status, linescores</li>
     * </ul>
     *
     * @param gameId ESPN event ID (the "id" field on an event from the scoreboard)
     * @return raw ESPN summary map, or {@code Map.of()} on failure
     */
    public Map<String, Object> getGameSummary(String gameId) {
        Map<String, Object> result = fetch(MLB_PATH + "/summary?event=" + gameId);
        if (result == null) {
            log.warn("getGameSummary({}): null response", gameId);
            return Map.of();
        }
        log.info("getGameSummary({}): fetched", gameId);
        return result;
    }

    /**
     * Quick score snapshot for a single MLB game.
     * Extracts home/away teams, current runs, inning, half (top/bot), and outs.
     *
     * <p>Checks today's cached scoreboard first; falls back to a full summary
     * call if the game is not in today's board.
     *
     * @param gameId ESPN event ID
     * @return map with keys: gameId, shortName, state, detail, inning, inningHalf,
     *         outs, home, homeAbbr, homeLogo, homeScore, homeRecord,
     *         away, awayAbbr, awayLogo, awayScore, awayRecord,
     *         broadcasts? (if present)
     */
    public Map<String, Object> getGameScore(String gameId) {
        for (Map<String, Object> game : getTodayGames()) {
            if (gameId.equals(extractGameId(game))) {
                return buildScoreSnapshot(game);
            }
        }
        Map<String, Object> summary = getGameSummary(gameId);
        return summary.isEmpty() ? Map.of() : buildScoreSnapshotFromSummary(gameId, summary);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME ODDS  (sourced from LiveScoreApiClient)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Odds for a single MLB game (pre-match and live 1X2) sourced from
     * LiveScoreApiClient's live feed. Always fresh — never cached.
     *
     * <p>Matching strategy:
     * <ol>
     *   <li>ID match — livescore fixture "id" == ESPN gameId.</li>
     *   <li>Team name fallback — loose case-insensitive contains match on
     *       home/away team display names resolved from today's scoreboard.</li>
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
        String espnHome = "";
        String espnAway = "";
        for (Map<String, Object> game : getTodayGames()) {
            if (gameId.equals(extractGameId(game))) {
                espnHome = extractHomeCompetitor(game)
                        .map(BaseballDataService::extractTeamName).orElse("");
                espnAway = extractAwayCompetitor(game)
                        .map(BaseballDataService::extractTeamName).orElse("");
                break;
            }
        }

        List<Map<String, Object>> liveMatches = liveScoreApiClient.getLiveScores();
        Map<String, Object> matched   = null;
        String              matchedBy = "none";

        for (Map<String, Object> lsMatch : liveMatches) {
            String lsId = LiveScoreApiClient.extractMatchId(lsMatch);
            if (!lsId.isBlank() && lsId.equals(gameId)) {
                matched   = lsMatch;
                matchedBy = "id";
                break;
            }
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
     * <p>Merges three data sources into one map:
     * <ol>
     *   <li><b>score</b> — from {@link #getGameScore(String)}
     *       (inning, half, outs, runs, teams)</li>
     *   <li><b>stats</b> — from {@link #getGameSummary(String)}
     *       (full ESPN box score: batting, pitching, inning-by-inning)</li>
     *   <li><b>odds</b>  — from {@link #getGameOdds(String)}
     *       (LiveScoreApiClient pre/live 1X2 odds)</li>
     * </ol>
     *
     * <p>Each section is always present (never null); may be {@code Map.of()}
     * if its upstream source returned no data. Never cached — always fresh.
     *
     * <p><b>Returned map structure:</b>
     * <pre>
     * {
     *   "gameId" : "401472463",
     *   "score"  : { gameId, shortName, state, detail, inning, inningHalf,
     *                outs, home, homeAbbr, homeLogo, homeScore, homeRecord,
     *                away, awayAbbr, awayLogo, awayScore, awayRecord,
     *                broadcasts? },
     *   "stats"  : { boxscore, scoringPlays, leaders, broadcasts, header, ... },
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
                gameId, score.isEmpty(), stats.isEmpty(),
                odds.getOrDefault("matchedBy", "n/a"));

        return Collections.unmodifiableMap(details);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STANDINGS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Current MLB standings (AL + NL, broken down by division).
     *
     * @return raw ESPN standings map
     */
    public Map<String, Object> getStandings() {
        return cached("standings:mlb", () -> {
            Map<String, Object> result = fetch(MLB_PATH + "/standings");
            if (result == null) return Map.of();
            log.info("getStandings: fetched MLB standings");
            return result;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  TEAMS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All 30 MLB teams with IDs, colours, logos, and links.
     *
     * @return raw ESPN teams map
     */
    public Map<String, Object> getAllTeams() {
        return cached("teams:mlb:all", () -> {
            Map<String, Object> result = fetch(MLB_PATH + "/teams?limit=50");
            if (result == null) return Map.of();
            log.info("getAllTeams: fetched");
            return result;
        });
    }

    /**
     * Single MLB team by ESPN team ID.
     *
     * @param teamId ESPN team ID (stable across seasons)
     * @return raw ESPN team map
     */
    public Map<String, Object> getTeamInfo(String teamId) {
        return cached("teams:mlb:" + teamId, () -> {
            Map<String, Object> result = fetch(MLB_PATH + "/teams/" + teamId);
            if (result == null) return Map.of();
            log.info("getTeamInfo({}): fetched", teamId);
            return result;
        });
    }

    /**
     * Full season schedule for an MLB team.
     *
     * @param teamId ESPN team ID
     * @return raw ESPN schedule map
     */
    public Map<String, Object> getTeamSchedule(String teamId) {
        return cached("schedule:mlb:" + teamId, () -> {
            Map<String, Object> result = fetch(MLB_PATH + "/teams/" + teamId + "/schedule");
            if (result == null) return Map.of();
            log.info("getTeamSchedule({}): fetched", teamId);
            return result;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STATUS DETECTION HELPERS (static — usable without an instance)
    // ═════════════════════════════════════════════════════════════════════

    /** Returns true if the game is currently in progress. */
    public static boolean isLive(Map<String, Object> game) {
        return STATE_IN.equals(extractState(game));
    }

    /** Returns true if the game has not yet started. */
    public static boolean isUpcoming(Map<String, Object> game) {
        return STATE_PRE.equals(extractState(game));
    }

    /** Returns true if the game is over. */
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

    /** Human-readable game name, e.g. "New York Yankees at Boston Red Sox". */
    public static String extractGameName(Map<String, Object> game) {
        Object name = game.get("name");
        return name != null ? name.toString() : "";
    }

    /** Short name, e.g. "NYY @ BOS". */
    public static String extractShortName(Map<String, Object> game) {
        Object name = game.get("shortName");
        return name != null ? name.toString() : "";
    }


    /** Raw status.type.state: "pre", "in", or "post". */
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
     * Human-readable status detail, e.g. "Top 7th", "Bot 4th, 2 Outs", or "Final".
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
     * Current inning number (1-based; 0 if not started).
     * Overtime / extra innings are represented as inning > 9.
     */
    @SuppressWarnings("unchecked")
    public static int extractInning(Map<String, Object> game) {
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
     * Current inning half: "top" or "bot".
     * Derived from status.type.detail (e.g. "Top 7th" → "top").
     * Returns empty string if the game has not started or has ended.
     */
    public static String extractInningHalf(Map<String, Object> game) {
        String detail = extractStatusDetail(game).toLowerCase();
        if (detail.startsWith("top")) return INNING_TOP;
        if (detail.startsWith("bot")) return INNING_BOT;
        return "";
    }

    /**
     * Current number of outs (0–2).
     * Parsed from the status detail string (e.g. "Bot 4th, 2 Outs" → 2).
     * Returns -1 if outs cannot be determined.
     */
    public static int extractOuts(Map<String, Object> game) {
        String detail = extractStatusDetail(game).toLowerCase();
        // Pattern: "... N Out" or "... N Outs"
        int outsIdx = detail.indexOf(" out");
        if (outsIdx > 1) {
            String before = detail.substring(0, outsIdx).trim();
            int spaceIdx = before.lastIndexOf(' ');
            String numStr = spaceIdx >= 0 ? before.substring(spaceIdx + 1) : before;
            try { return Integer.parseInt(numStr); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** The home-team competitor map from competitions[0]. */
    public static Optional<Map<String, Object>> extractHomeCompetitor(Map<String, Object> game) {
        return extractCompetitors(game).stream()
                .filter(c -> "home".equals(c.get("homeAway")))
                .findFirst();
    }

    /** The away-team competitor map from competitions[0]. */
    public static Optional<Map<String, Object>> extractAwayCompetitor(Map<String, Object> game) {
        return extractCompetitors(game).stream()
                .filter(c -> "away".equals(c.get("homeAway")))
                .findFirst();
    }

    /** Team display name from a competitor map, e.g. "New York Yankees". */
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

    /** Team abbreviation, e.g. "NYY", from a competitor map. */
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

    /** Current runs scored for a competitor (e.g. "5"). Empty pre-game. */
    public static String extractScore(Map<String, Object> competitor) {
        Object score = competitor.get("score");
        return score != null ? score.toString() : "";
    }

    /** Win/loss record string (e.g. "72-45"). */
    public static String extractRecord(Map<String, Object> competitor) {
        Object record = competitor.get("record");
        return record != null ? record.toString() : "";
    }

    /**
     * Inning-by-inning run breakdown from a competitor's linescores.
     *
     * @return list of maps with keys: period (inning number), displayValue (runs)
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractLinescores(Map<String, Object> competitor) {
        Object ls = competitor.get("linescores");
        if (ls instanceof List<?> list) return (List<Map<String, Object>>) list;
        return Collections.emptyList();
    }

    /**
     * Starting pitcher info for a competitor, if available in the scoreboard.
     * ESPN sometimes surfaces the probable/starting pitcher in competitions[0].
     *
     * @param game       full event map
     * @param homeOrAway "home" or "away"
     * @return map with keys: name, era, record — or empty map if not available
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractStartingPitcher(Map<String, Object> game,
                                                              String homeOrAway) {
        try {
            List<?> competitions = (List<?>) game.get("competitions");
            if (competitions == null || competitions.isEmpty()) return Map.of();
            Map<String, Object> comp = (Map<String, Object>) competitions.get(0);
            // ESPN sometimes puts pitcher info under "situation" or "probables"
            Object probables = comp.get("probables");
            if (probables instanceof List<?> probList) {
                for (Object pObj : probList) {
                    Map<String, Object> p = (Map<String, Object>) pObj;
                    if (homeOrAway.equals(p.get("homeAway"))) {
                        Map<String, Object> athlete = (Map<String, Object>) p.get("athlete");
                        if (athlete == null) return Map.of();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("name",   athlete.getOrDefault("displayName", "").toString());
                        result.put("era",    p.getOrDefault("era",    "").toString());
                        result.put("record", p.getOrDefault("record", "").toString());
                        return Collections.unmodifiableMap(result);
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return Map.of();
    }

    /**
     * Current at-bat / situation text, e.g. "Runners on 1st and 3rd, 1 Out".
     * Only populated during live games.
     */
    @SuppressWarnings("unchecked")
    public static String extractSituation(Map<String, Object> game) {
        try {
            List<?> competitions = (List<?>) game.get("competitions");
            if (competitions == null || competitions.isEmpty()) return "";
            Map<String, Object> comp      = (Map<String, Object>) competitions.get(0);
            Map<String, Object> situation = (Map<String, Object>) comp.get("situation");
            if (situation == null) return "";
            Object text = situation.get("shortDownDistanceText");
            if (text != null && !text.toString().isBlank()) return text.toString();
            Object batter = situation.get("batter");
            if (batter instanceof Map<?, ?> batterMap) {
                Object name = ((Map<String, Object>) batterMap).get("displayName");
                return name != null ? "At bat: " + name : "";
            }
        } catch (ClassCastException ignored) {}
        return "";
    }

    /**
     * Broadcast network(s) for the game, e.g. ["ESPN", "Apple TV+"].
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
     * Venue name and city for the game.
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
            result.put("name", venue.getOrDefault("fullName", "").toString());
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
     * Playoff series info, if present during the postseason.
     *
     * @return series map (summary, completed) — or empty map in regular season
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

    @SuppressWarnings("unchecked")
    private <T> T cached(String cacheKey, Supplier<T> loader) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            log.debug("BaseballDataService cache HIT: '{}'", cacheKey);
            return (T) entry.data();
        }
        T result = loader.get();
        if (result != null) {
            long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CACHE_TTL_MIN);
            cache.put(cacheKey, new CacheEntry(result, expiresAt));
        }
        return result;
    }

    private Map<String, Object> fetch(String path) {
        try {
            String raw = client.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(e -> {
                        log.warn("BaseballDataService [{}] network error: {}", path, e.getMessage());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();

            if (raw == null || raw.isBlank()) {
                log.debug("BaseballDataService [{}] blank response", path);
                return null;
            }

            Map<String, Object> result = mapper.readValue(raw, MAP_TYPE);
            log.info("BaseballDataService [{}] OK ({} bytes)", path, raw.length());
            return result;

        } catch (Exception e) {
            log.error("BaseballDataService [{}] error: {}", path, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractEvents(Map<String, Object> response) {
        Object events = response.get("events");
        if (events instanceof List<?> list) return (List<Map<String, Object>>) list;
        return Collections.emptyList();
    }

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

    private static Map<String, Object> buildScoreSnapshot(Map<String, Object> game) {
        Optional<Map<String, Object>> homeOpt = extractHomeCompetitor(game);
        Optional<Map<String, Object>> awayOpt = extractAwayCompetitor(game);

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("gameId",      extractGameId(game));
        snap.put("shortName",   extractShortName(game));
        snap.put("state",       extractState(game));
        snap.put("detail",      extractStatusDetail(game));
        snap.put("inning",      extractInning(game));
        snap.put("inningHalf",  extractInningHalf(game));
        snap.put("outs",        extractOuts(game));
        snap.put("date",        extractGameDate(game));

        homeOpt.ifPresent(home -> {
            snap.put("home",       extractTeamName(home));
            snap.put("homeAbbr",   extractTeamAbbrev(home));
            snap.put("homeLogo",   extractTeamLogo(home));
            snap.put("homeScore",  extractScore(home));
            snap.put("homeRecord", extractRecord(home));
        });
        awayOpt.ifPresent(away -> {
            snap.put("away",       extractTeamName(away));
            snap.put("awayAbbr",   extractTeamAbbrev(away));
            snap.put("awayLogo",   extractTeamLogo(away));
            snap.put("awayScore",  extractScore(away));
            snap.put("awayRecord", extractRecord(away));
        });

        String situation = extractSituation(game);
        if (!situation.isBlank()) snap.put("situation", situation);

        Map<String, Object> homeStartingPitcher = extractStartingPitcher(game, "home");
        Map<String, Object> awayStartingPitcher = extractStartingPitcher(game, "away");
        if (!homeStartingPitcher.isEmpty()) snap.put("homeStartingPitcher", homeStartingPitcher);
        if (!awayStartingPitcher.isEmpty()) snap.put("awayStartingPitcher", awayStartingPitcher);

        Map<String, Object> venue = extractVenue(game);
        if (!venue.isEmpty()) snap.put("venue", venue);

        List<String> broadcasts = extractBroadcasts(game);
        if (!broadcasts.isEmpty()) snap.put("broadcasts", broadcasts);

        Map<String, Object> series = extractPlayoffSeries(game);
        if (!series.isEmpty()) snap.put("series", series);

        return Collections.unmodifiableMap(snap);
    }

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
                            snap.put("state",  type.getOrDefault("state",  "").toString());
                            snap.put("detail", type.getOrDefault("detail", "").toString());
                        }
                        Object period = status.get("period");
                        if (period != null) snap.put("inning", Integer.parseInt(period.toString()));
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
package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Service for ATP and WTA Tennis data via the ESPN unofficial API.
 *
 * ── Data source ──────────────────────────────────────────────────────────
 *
 *   Base URLs:
 *     ATP (Men's)  : https://site.api.espn.com/apis/site/v2/sports/tennis/atp
 *     WTA (Women's): https://site.api.espn.com/apis/site/v2/sports/tennis/wta
 *   Auth: None required — plain HTTP GET
 *
 * ── Tennis structural notes ──────────────────────────────────────────────
 *
 *   Each ESPN "event" is a tournament (e.g. "French Open").
 *   Within each tournament, individual matches are nested as competitions[].
 *   Each match has exactly two competitors (players).
 *
 *   Status fields follow the same ESPN convention:
 *     status.type.state  →  "pre" | "in" | "post"
 *     status.type.detail →  "Scheduled" | "In Progress" | "Final" |
 *                           "Retired" | "Walkover" | "Suspended" etc.
 *
 *   Set scores are exposed as linescores on each competitor.
 *   Current game/point score is in status.type.shortDetail during live matches.
 *
 * ── Tour constants ───────────────────────────────────────────────────────
 *
 *   Use Tour.ATP for men's matches and Tour.WTA for women's matches.
 *   All methods that are tour-aware accept a Tour parameter.
 *   Convenience wrapper methods exist for common single-tour use cases.
 *
 * ── Key methods ──────────────────────────────────────────────────────────
 *
 *   getTournaments(Tour)         — all tournaments on the scoreboard for a tour
 *   getLiveMatches(Tour)         — in-progress matches, fresh, no cache
 *   getUpcomingMatches(Tour)     — pre-game matches for the current scoreboard
 *   getFinishedMatches(Tour)     — completed matches from the current scoreboard
 *   getMatchSummary(String, Tour)— full match stats (sets, games, aces, etc.)
 *   getMatchScore(String, Tour)  — quick score snapshot (players, sets, status)
 *   getRankings(Tour)            — current ATP or WTA rankings
 *   getGameOdds(String, Tour)    — pre/live 1X2 odds from LiveScoreApiClient
 *   getFullMatchDetails(String, Tour) — combined: score + stats + odds
 *
 * ── Coverage note ────────────────────────────────────────────────────────
 *
 *   ESPN's tennis coverage is less consistent than NBA/NFL/MLB.
 *   Grand Slams (Australian Open, Roland Garros, Wimbledon, US Open) and
 *   Masters 1000 / WTA 1000 events have the best coverage.
 *   For full ATP/WTA data including all challenger/ITF events, CricAPI or
 *   RapidAPI tennis endpoints may be more reliable.
 *
 * ── Odds source ──────────────────────────────────────────────────────────
 *
 *   Odds are sourced from LiveScoreApiClient. The match is located by ESPN
 *   match ID first, then by player name fallback (last name matching).
 *   If no match is found the odds block is returned empty — always check
 *   the "matchedBy" field ("id", "playerName", or "none").
 *
 * ── Caching ──────────────────────────────────────────────────────────────
 *
 *   Tournament scoreboard and rankings cached for CACHE_TTL_MIN minutes.
 *   getLiveMatches(), getMatchSummary(), getGameOdds(), getFullMatchDetails()
 *   are always fresh — intentionally never cached.
 *
 * ── Polling guidance ─────────────────────────────────────────────────────
 *
 *   Poll getLiveMatches() every 30–60 seconds during tournament hours.
 */
@Slf4j
@Component
public class TennisDataService {

    // ── ESPN Tennis base paths ─────────────────────────────────────────────
    private static final String BASE_URL            = "https://site.api.espn.com";
    private static final String ATP_PATH            = "/apis/site/v2/sports/tennis/atp";
    private static final String WTA_PATH            = "/apis/site/v2/sports/tennis/wta";
    private static final long   CACHE_TTL_MIN       = 5;
    private static final long   REQUEST_TIMEOUT_SEC = 12;

    // ── Game state constants (ESPN status.type.state) ─────────────────────
    public static final String STATE_PRE  = "pre";
    public static final String STATE_IN   = "in";
    public static final String STATE_POST = "post";

    // ── Match result detail keywords ──────────────────────────────────────
    public static final String RESULT_RETIRED  = "retired";
    public static final String RESULT_WALKOVER = "walkover";
    public static final String RESULT_DEFAULT  = "default";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * Tour selector — passed to all tour-aware methods.
     * Use Tour.ATP for men's, Tour.WTA for women's.
     */
    public enum Tour {
        ATP("atp", "ATP Men's"),
        WTA("wta", "WTA Women's");

        private final String pathSegment;
        private final String displayName;

        Tour(String pathSegment, String displayName) {
            this.pathSegment = pathSegment;
            this.displayName = displayName;
        }

        public String path()        { return "/apis/site/v2/sports/tennis/" + pathSegment; }
        public String displayName() { return displayName; }
        public String cachePrefix() { return "tennis:" + pathSegment; }
    }

    private final WebClient          client;
    private final ObjectMapper       mapper             = new ObjectMapper();
    private final LiveScoreApiClient liveScoreApiClient;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ── Constructor ────────────────────────────────────────────────────────
    @Autowired
    public TennisDataService(WebClient.Builder builder,
                             LiveScoreApiClient liveScoreApiClient) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.liveScoreApiClient = liveScoreApiClient;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SCOREBOARD — TOURNAMENTS + MATCHES
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All tournaments currently on the ESPN scoreboard for a given tour.
     * Each entry represents one tournament; matches are nested inside as
     * competitions[]. Cached for CACHE_TTL_MIN minutes.
     *
     * @param tour Tour.ATP or Tour.WTA
     * @return list of raw ESPN tournament event maps
     */
    public List<Map<String, Object>> getTournaments(Tour tour) {
        return cached(tour.cachePrefix() + ":scoreboard", () -> {
            Map<String, Object> response = fetch(tour.path() + "/scoreboard");
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> tournaments = extractEvents(response);
            log.info("getTournaments({}): {} tournament(s)", tour.displayName(), tournaments.size());
            return tournaments;
        });
    }

    /**
     * All matches (across all tournaments) flattened into a single list.
     * Extracts competitions[] from every tournament on the scoreboard.
     * Cached for CACHE_TTL_MIN minutes.
     *
     * @param tour Tour.ATP or Tour.WTA
     * @return flat list of all match competition maps
     */
    public List<Map<String, Object>> getAllMatches(Tour tour) {
        return cached(tour.cachePrefix() + ":allmatches", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (Map<String, Object> tournament : getTournaments(tour)) {
                all.addAll(extractMatches(tournament));
            }
            log.info("getAllMatches({}): {} match(es) total", tour.displayName(), all.size());
            return all;
        });
    }

    // ─── ATP convenience wrappers ──────────────────────────────────────────

    /** Tournaments on the ATP scoreboard. */
    public List<Map<String, Object>> getAtpTournaments()  { return getTournaments(Tour.ATP); }

    /** Tournaments on the WTA scoreboard. */
    public List<Map<String, Object>> getWtaTournaments()  { return getTournaments(Tour.WTA); }

    /** All ATP matches (flattened). */
    public List<Map<String, Object>> getAllAtpMatches()    { return getAllMatches(Tour.ATP); }

    /** All WTA matches (flattened). */
    public List<Map<String, Object>> getAllWtaMatches()    { return getAllMatches(Tour.WTA); }

    // ═════════════════════════════════════════════════════════════════════
    //  FILTERED VIEWS — LIVE / UPCOMING / FINISHED
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Matches currently in progress for a tour — FRESH, no cache.
     * Intended for polling every 30–60 seconds during tournament hours.
     *
     * @param tour Tour.ATP or Tour.WTA
     * @return list of in-progress match competition maps
     */
    public List<Map<String, Object>> getLiveMatches(Tour tour) {
        Map<String, Object> response = fetch(tour.path() + "/scoreboard");
        if (response == null) {
            log.warn("getLiveMatches({}): null response", tour.displayName());
            return Collections.emptyList();
        }
        List<Map<String, Object>> live = extractEvents(response).stream()
                .flatMap(t -> extractMatches(t).stream())
                .filter(TennisDataService::isLive)
                .toList();
        log.info("getLiveMatches({}): {} match(es) in progress", tour.displayName(), live.size());
        return live;
    }

    /**
     * Matches not yet started for a tour.
     *
     * @param tour Tour.ATP or Tour.WTA
     * @return list of upcoming match competition maps
     */
    public List<Map<String, Object>> getUpcomingMatches(Tour tour) {
        return getAllMatches(tour).stream()
                .filter(TennisDataService::isUpcoming)
                .toList();
    }

    /**
     * Finished matches for a tour.
     *
     * @param tour Tour.ATP or Tour.WTA
     * @return list of completed match competition maps
     */
    public List<Map<String, Object>> getFinishedMatches(Tour tour) {
        return getAllMatches(tour).stream()
                .filter(TennisDataService::isFinished)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MATCH SUMMARY — FULL STATS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Full match summary: set-by-set scores, game counts, match statistics
     * (aces, double faults, first serve %, winners, unforced errors).
     * Always fetches fresh — never cached.
     *
     * @param matchId ESPN event/match ID
     * @param tour    Tour.ATP or Tour.WTA
     * @return raw ESPN summary map, or {@code Map.of()} on failure
     */
    public Map<String, Object> getMatchSummary(String matchId, Tour tour) {
        Map<String, Object> result = fetch(tour.path() + "/summary?event=" + matchId);
        if (result == null) {
            log.warn("getMatchSummary({}, {}): null response", matchId, tour.displayName());
            return Map.of();
        }
        log.info("getMatchSummary({}, {}): fetched", matchId, tour.displayName());
        return result;
    }

    /**
     * Quick score snapshot for a single match: players, set scores, current
     * game/point score (live), and match state.
     *
     * <p>Searches all tournaments on the scoreboard for the match ID; falls
     * back to a full summary call if not found.
     *
     * @param matchId ESPN match ID
     * @param tour    Tour.ATP or Tour.WTA
     * @return map with keys: matchId, state, detail, shortDetail,
     *         player1, player1Seed, player1Sets, player1Score (current game),
     *         player2, player2Seed, player2Sets, player2Score,
     *         surface, round, tournamentName
     */
    public Map<String, Object> getMatchScore(String matchId, Tour tour) {
        for (Map<String, Object> tournament : getTournaments(tour)) {
            for (Map<String, Object> match : extractMatches(tournament)) {
                if (matchId.equals(extractMatchId(match))) {
                    return buildMatchSnapshot(match, tournament);
                }
            }
        }
        Map<String, Object> summary = getMatchSummary(matchId, tour);
        return summary.isEmpty() ? Map.of() : buildMatchSnapshotFromSummary(matchId, summary);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  RANKINGS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Current ATP or WTA rankings.
     * Cached for CACHE_TTL_MIN minutes.
     *
     * @param tour Tour.ATP or Tour.WTA
     * @return raw ESPN rankings map
     */
    public Map<String, Object> getRankings(Tour tour) {
        return cached(tour.cachePrefix() + ":rankings", () -> {
            Map<String, Object> result = fetch(tour.path() + "/rankings");
            if (result == null) return Map.of();
            log.info("getRankings({}): fetched", tour.displayName());
            return result;
        });
    }

    /** Current ATP rankings. */
    public Map<String, Object> getAtpRankings() { return getRankings(Tour.ATP); }

    /** Current WTA rankings. */
    public Map<String, Object> getWtaRankings() { return getRankings(Tour.WTA); }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME ODDS  (sourced from LiveScoreApiClient)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Odds for a single tennis match (pre-match and live 1X2) sourced from
     * LiveScoreApiClient's live feed. Always fresh — never cached.
     *
     * <p>Matching strategy:
     * <ol>
     *   <li>ID match — livescore fixture "id" == ESPN matchId.</li>
     *   <li>Player last-name fallback — extracts last names of both players
     *       and checks for contains match against livescore home/away names.</li>
     * </ol>
     *
     * @param matchId ESPN match ID
     * @param tour    Tour.ATP or Tour.WTA
     * @return map with keys:
     *         <ul>
     *           <li>{@code matchId}      — the ESPN match ID passed in</li>
     *           <li>{@code matchedBy}    — "id" | "playerName" | "none"</li>
     *           <li>{@code preOddsHome}  — player1 pre-match odd (or "")</li>
     *           <li>{@code preOddsDraw}  — draw pre-match odd (or "")</li>
     *           <li>{@code preOddsAway}  — player2 pre-match odd (or "")</li>
     *           <li>{@code liveOddsHome} — player1 live odd (or "")</li>
     *           <li>{@code liveOddsDraw} — draw live odd (or "")</li>
     *           <li>{@code liveOddsAway} — player2 live odd (or "")</li>
     *         </ul>
     */
    public Map<String, Object> getGameOdds(String matchId, Tour tour) {
        // Resolve player names for the name-based fallback
        String player1 = "";
        String player2 = "";
        outer:
        for (Map<String, Object> tournament : getTournaments(tour)) {
            for (Map<String, Object> match : extractMatches(tournament)) {
                if (matchId.equals(extractMatchId(match))) {
                    List<Map<String, Object>> players = extractPlayers(match);
                    if (players.size() >= 1) player1 = extractPlayerName(players.get(0));
                    if (players.size() >= 2) player2 = extractPlayerName(players.get(1));
                    break outer;
                }
            }
        }

        List<Map<String, Object>> liveMatches = liveScoreApiClient.getLiveScores();
        Map<String, Object> matched   = null;
        String              matchedBy = "none";

        for (Map<String, Object> lsMatch : liveMatches) {
            String lsId = LiveScoreApiClient.extractMatchId(lsMatch);
            if (!lsId.isBlank() && lsId.equals(matchId)) {
                matched   = lsMatch;
                matchedBy = "id";
                break;
            }
            if (!player1.isBlank() && !player2.isBlank()) {
                String lsHome  = LiveScoreApiClient.extractHomeName(lsMatch).toLowerCase();
                String lsAway  = LiveScoreApiClient.extractAwayName(lsMatch).toLowerCase();
                // Match on last name (e.g. "Carlos Alcaraz" → "alcaraz")
                String p1Last  = lastName(player1);
                String p2Last  = lastName(player2);
                if (lsHome.contains(p1Last) || lsAway.contains(p2Last)
                        || lsHome.contains(p2Last) || lsAway.contains(p1Last)) {
                    matched   = lsMatch;
                    matchedBy = "playerName";
                }
            }
        }

        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("matchId",   matchId);
        odds.put("matchedBy", matchedBy);

        if (matched != null) {
            odds.put("preOddsHome",  liveScoreApiClient.extractPreOddsHome(matched));
            odds.put("preOddsDraw",  liveScoreApiClient.extractPreOddsDraw(matched));
            odds.put("preOddsAway",  liveScoreApiClient.extractPreOddsAway(matched));
            odds.put("liveOddsHome", liveScoreApiClient.extractLiveOddsHome(matched));
            odds.put("liveOddsDraw", liveScoreApiClient.extractLiveOddsDraw(matched));
            odds.put("liveOddsAway", liveScoreApiClient.extractLiveOddsAway(matched));
            log.info("getGameOdds({}, {}): odds found via matchedBy={}", matchId, tour.displayName(), matchedBy);
        } else {
            odds.put("preOddsHome",  "");
            odds.put("preOddsDraw",  "");
            odds.put("preOddsAway",  "");
            odds.put("liveOddsHome", "");
            odds.put("liveOddsDraw", "");
            odds.put("liveOddsAway", "");
            log.warn("getGameOdds({}, {}): no matching fixture in LiveScoreApiClient feed",
                    matchId, tour.displayName());
        }

        return Collections.unmodifiableMap(odds);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FULL MATCH DETAILS  (score + stats + odds — combined)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Combined match details for any match state (pre, live, post).
     *
     * <p>Merges three data sources into one map:
     * <ol>
     *   <li><b>score</b> — from {@link #getMatchScore(String, Tour)}
     *       (players, sets, current game score, surface, round)</li>
     *   <li><b>stats</b> — from {@link #getMatchSummary(String, Tour)}
     *       (full ESPN summary: set scores, aces, serve %, winners)</li>
     *   <li><b>odds</b>  — from {@link #getGameOdds(String, Tour)}
     *       (LiveScoreApiClient pre/live 1X2 odds)</li>
     * </ol>
     *
     * <p>Each section is always present (never null); may be {@code Map.of()}
     * if its upstream source returned no data. Never cached — always fresh.
     *
     * <p><b>Returned map structure:</b>
     * <pre>
     * {
     *   "matchId" : "401234567",
     *   "tour"    : "ATP Men's",
     *   "score"   : { matchId, state, detail, shortDetail, surface, round,
     *                 tournamentName, player1, player1Seed, player1Sets,
     *                 player1Score, player2, player2Seed, player2Sets,
     *                 player2Score },
     *   "stats"   : { header, boxscore, leaders, ... },
     *   "odds"    : { matchedBy, preOddsHome, preOddsDraw, preOddsAway,
     *                 liveOddsHome, liveOddsDraw, liveOddsAway }
     * }
     * </pre>
     *
     * @param matchId ESPN match ID
     * @param tour    Tour.ATP or Tour.WTA
     * @return combined details map (never null; sections may be empty)
     */
    public Map<String, Object> getFullMatchDetails(String matchId, Tour tour) {
        log.info("getFullMatchDetails({}, {}): fetching score + stats + odds",
                matchId, tour.displayName());

        Map<String, Object> score = getMatchScore(matchId, tour);
        Map<String, Object> stats = getMatchSummary(matchId, tour);
        Map<String, Object> odds  = getGameOdds(matchId, tour);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("matchId", matchId);
        details.put("tour",    tour.displayName());
        details.put("score",   score);
        details.put("stats",   stats);
        details.put("odds",    odds);

        log.info("getFullMatchDetails({}, {}): assembled — scoreEmpty={} statsEmpty={} oddsMatchedBy={}",
                matchId, tour.displayName(), score.isEmpty(), stats.isEmpty(),
                odds.getOrDefault("matchedBy", "n/a"));

        return Collections.unmodifiableMap(details);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STATUS DETECTION HELPERS (static — usable without an instance)
    // ═════════════════════════════════════════════════════════════════════

    /** Returns true if the match is currently in progress. */
    public static boolean isLive(Map<String, Object> match) {
        return STATE_IN.equals(extractState(match));
    }

    /** Returns true if the match has not yet started. */
    public static boolean isUpcoming(Map<String, Object> match) {
        return STATE_PRE.equals(extractState(match));
    }

    /** Returns true if the match is over. */
    public static boolean isFinished(Map<String, Object> match) {
        return STATE_POST.equals(extractState(match));
    }

    /**
     * Returns true if the match ended due to a retirement (injury withdrawal).
     * Checks status.type.detail for the keyword "retired".
     */
    public static boolean isRetired(Map<String, Object> match) {
        return extractStatusDetail(match).toLowerCase().contains(RESULT_RETIRED);
    }

    /**
     * Returns true if the match was decided by walkover (opponent withdrew
     * before play began).
     */
    public static boolean isWalkover(Map<String, Object> match) {
        return extractStatusDetail(match).toLowerCase().contains(RESULT_WALKOVER);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FIELD EXTRACTORS
    // ═════════════════════════════════════════════════════════════════════

    /** ESPN match/competition ID ("id" field on the competition). */
    public static String extractMatchId(Map<String, Object> match) {
        Object id = match.get("id");
        return id != null ? id.toString() : "";
    }

    /** Raw status.type.state: "pre", "in", or "post". */
    @SuppressWarnings("unchecked")
    public static String extractState(Map<String, Object> match) {
        try {
            Map<String, Object> status = (Map<String, Object>) match.get("status");
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
     * Human-readable status detail, e.g. "In Progress", "Final",
     * "Final/Retired", "Suspended".
     */
    @SuppressWarnings("unchecked")
    public static String extractStatusDetail(Map<String, Object> match) {
        try {
            Map<String, Object> status = (Map<String, Object>) match.get("status");
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
     * Short status detail, e.g. "Final" or current game score during live play
     * like "40-30, 3rd Set". Derived from status.type.shortDetail.
     */
    @SuppressWarnings("unchecked")
    public static String extractShortDetail(Map<String, Object> match) {
        try {
            Map<String, Object> status = (Map<String, Object>) match.get("status");
            if (status == null) return "";
            Map<String, Object> type = (Map<String, Object>) status.get("type");
            if (type == null) return "";
            Object shortDetail = type.get("shortDetail");
            return shortDetail != null ? shortDetail.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /**
     * Current set number (1-based) for a live match. 0 if not in progress.
     * Mapped from status.period.
     */
    @SuppressWarnings("unchecked")
    public static int extractCurrentSet(Map<String, Object> match) {
        try {
            Map<String, Object> status = (Map<String, Object>) match.get("status");
            if (status == null) return 0;
            Object period = status.get("period");
            return period != null ? Integer.parseInt(period.toString()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * All player competitors in a match (exactly two for a singles match).
     * Index 0 is the "home" side, index 1 is "away".
     *
     * @param match competition map
     * @return list of two competitor maps
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractPlayers(Map<String, Object> match) {
        try {
            Object competitors = match.get("competitors");
            if (competitors instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (ClassCastException ignored) {}
        return Collections.emptyList();
    }

    /** Player display name from a competitor map, e.g. "Carlos Alcaraz". */
    @SuppressWarnings("unchecked")
    public static String extractPlayerName(Map<String, Object> competitor) {
        try {
            Map<String, Object> athlete = (Map<String, Object>) competitor.get("athlete");
            if (athlete == null) return "";
            Object name = athlete.get("displayName");
            return name != null ? name.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /** Player seeding string, e.g. "1", "WC" (wild card), "Q" (qualifier). */
    @SuppressWarnings("unchecked")
    public static String extractPlayerSeed(Map<String, Object> competitor) {
        Object seeding = competitor.get("seeding");
        return seeding != null ? seeding.toString() : "";
    }

    /** Player nationality / flag alt text from the athlete object. */
    @SuppressWarnings("unchecked")
    public static String extractPlayerNationality(Map<String, Object> competitor) {
        try {
            Map<String, Object> athlete = (Map<String, Object>) competitor.get("athlete");
            if (athlete == null) return "";
            Object flag = athlete.get("flag");
            if (flag instanceof Map<?, ?> flagMap) {
                Object alt = ((Map<String, Object>) flagMap).get("alt");
                return alt != null ? alt.toString() : "";
            }
        } catch (ClassCastException ignored) {}
        return "";
    }

    /** Player headshot URL from the athlete object. */
    @SuppressWarnings("unchecked")
    public static String extractPlayerHeadshot(Map<String, Object> competitor) {
        try {
            Map<String, Object> athlete = (Map<String, Object>) competitor.get("athlete");
            if (athlete == null) return "";
            Object headshot = athlete.get("headshot");
            if (headshot instanceof Map<?, ?> map) {
                Object href = ((Map<String, Object>) map).get("href");
                return href != null ? href.toString() : "";
            }
            if (headshot != null) return headshot.toString();
        } catch (ClassCastException ignored) {}
        return "";
    }

    /**
     * Whether this player won the match.
     * Returns false if the match is not yet finished.
     *
     * @param competitor competitor map
     * @return true if marked as winner
     */
    public static boolean isWinner(Map<String, Object> competitor) {
        Object winner = competitor.get("winner");
        return Boolean.TRUE.equals(winner) || "true".equalsIgnoreCase(
                winner != null ? winner.toString() : "");
    }

    /**
     * Current score string for a player during a live match.
     * e.g. "40" (game points) or "" if not available.
     *
     * @param competitor competitor map
     * @return score string or ""
     */
    public static String extractCurrentGameScore(Map<String, Object> competitor) {
        Object score = competitor.get("score");
        return score != null ? score.toString() : "";
    }

    /**
     * Set-by-set scores (linescores) for a player.
     *
     * @param competitor competitor map
     * @return list of maps with keys: period (set number), displayValue (games won)
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractSetScores(Map<String, Object> competitor) {
        Object ls = competitor.get("linescores");
        if (ls instanceof List<?> list) return (List<Map<String, Object>>) list;
        return Collections.emptyList();
    }

    /**
     * Number of sets won by a player, derived from their linescores.
     * A set is won when a player has more games in that set's linescore entry.
     * This is a convenience counter — full set scores are in extractSetScores().
     *
     * <p>Note: For a match not yet finished this represents sets won so far.
     *
     * @param competitor      the player to count sets for
     * @param otherCompetitor the opponent (needed to compare games per set)
     * @return number of sets won (0 if not determinable)
     */
    public static int countSetsWon(Map<String, Object> competitor,
                                   Map<String, Object> otherCompetitor) {
        List<Map<String, Object>> mySets    = extractSetScores(competitor);
        List<Map<String, Object>> theirSets = extractSetScores(otherCompetitor);
        int won = 0;
        int len = Math.min(mySets.size(), theirSets.size());
        for (int i = 0; i < len; i++) {
            try {
                int mine  = Integer.parseInt(mySets.get(i).getOrDefault("displayValue", "0").toString());
                int their = Integer.parseInt(theirSets.get(i).getOrDefault("displayValue", "0").toString());
                if (mine > their) won++;
            } catch (NumberFormatException ignored) {}
        }
        return won;
    }

    /**
     * Tournament / event name for a match's parent tournament.
     *
     * @param tournament tournament event map from getTournaments()
     * @return tournament name string, or ""
     */
    public static String extractTournamentName(Map<String, Object> tournament) {
        Object name = tournament.get("name");
        return name != null ? name.toString() : "";
    }

    /**
     * Court surface for the match's tournament, e.g. "Hard", "Clay", "Grass".
     * Extracted from the tournament's note or slug field if available.
     *
     * @param tournament tournament event map
     * @return surface string, or ""
     */
    public static String extractSurface(Map<String, Object> tournament) {
        Object note = tournament.get("note");
        if (note != null && !note.toString().isBlank()) return note.toString();
        // Some ESPN responses embed it in the slug or description
        Object slug = tournament.get("slug");
        if (slug != null) {
            String s = slug.toString().toLowerCase();
            if (s.contains("clay"))  return "Clay";
            if (s.contains("grass")) return "Grass";
            if (s.contains("hard"))  return "Hard";
        }
        return "";
    }

    /**
     * Round name for a match, e.g. "Quarterfinals", "Semifinals", "Final".
     * Extracted from the competition's note field.
     *
     * @param match competition (match) map
     * @return round string, or ""
     */
    public static String extractRound(Map<String, Object> match) {
        Object note = match.get("note");
        return note != null ? note.toString() : "";
    }

    /**
     * Broadcast networks for the tournament/match, e.g. ["Tennis Channel", "ESPN2"].
     *
     * @param match competition (match) map
     * @return list of broadcast name strings (may be empty)
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractBroadcasts(Map<String, Object> match) {
        try {
            Object broadcasts = match.get("broadcasts");
            if (broadcasts instanceof List<?> bList) {
                List<String> names = new ArrayList<>();
                for (Object bObj : bList) {
                    Map<String, Object> b = (Map<String, Object>) bObj;
                    Object namesObj = b.get("names");
                    if (namesObj instanceof List<?> namesList) {
                        for (Object n : namesList) names.add(n.toString());
                    }
                }
                return Collections.unmodifiableList(names);
            }
        } catch (ClassCastException ignored) {}
        return Collections.emptyList();
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
            log.debug("TennisDataService cache HIT: '{}'", cacheKey);
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
                        log.warn("TennisDataService [{}] network error: {}", path, e.getMessage());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();

            if (raw == null || raw.isBlank()) {
                log.debug("TennisDataService [{}] blank response", path);
                return null;
            }

            Map<String, Object> result = mapper.readValue(raw, MAP_TYPE);
            log.info("TennisDataService [{}] OK ({} bytes)", path, raw.length());
            return result;

        } catch (Exception e) {
            log.error("TennisDataService [{}] error: {}", path, e.getMessage());
            return null;
        }
    }

    /** Extract top-level events list from a scoreboard response. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractEvents(Map<String, Object> response) {
        Object events = response.get("events");
        if (events instanceof List<?> list) return (List<Map<String, Object>>) list;
        return Collections.emptyList();
    }

    /** Extract individual matches (competitions) from a tournament event map. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractMatches(Map<String, Object> tournament) {
        try {
            Object competitions = tournament.get("competitions");
            if (competitions instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (ClassCastException ignored) {}
        return Collections.emptyList();
    }

    /** Extract last name from a full name string, e.g. "Carlos Alcaraz" → "alcaraz". */
    private static String lastName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        String trimmed = fullName.trim().toLowerCase();
        int lastSpace = trimmed.lastIndexOf(' ');
        return lastSpace >= 0 ? trimmed.substring(lastSpace + 1) : trimmed;
    }

    private static Map<String, Object> buildMatchSnapshot(Map<String, Object> match,
                                                           Map<String, Object> tournament) {
        List<Map<String, Object>> players = extractPlayers(match);
        Map<String, Object> p1 = players.size() > 0 ? players.get(0) : Map.of();
        Map<String, Object> p2 = players.size() > 1 ? players.get(1) : Map.of();

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("matchId",        extractMatchId(match));
        snap.put("state",          extractState(match));
        snap.put("detail",         extractStatusDetail(match));
        snap.put("shortDetail",    extractShortDetail(match));
        snap.put("currentSet",     extractCurrentSet(match));
        snap.put("round",          extractRound(match));
        snap.put("tournamentName", extractTournamentName(tournament));
        snap.put("surface",        extractSurface(tournament));

        if (!p1.isEmpty()) {
            snap.put("player1",           extractPlayerName(p1));
            snap.put("player1Seed",       extractPlayerSeed(p1));
            snap.put("player1Nationality",extractPlayerNationality(p1));
            snap.put("player1Score",      extractCurrentGameScore(p1));
            snap.put("player1Sets",       p2.isEmpty() ? 0 : countSetsWon(p1, p2));
            snap.put("player1Winner",     isWinner(p1));
        }
        if (!p2.isEmpty()) {
            snap.put("player2",           extractPlayerName(p2));
            snap.put("player2Seed",       extractPlayerSeed(p2));
            snap.put("player2Nationality",extractPlayerNationality(p2));
            snap.put("player2Score",      extractCurrentGameScore(p2));
            snap.put("player2Sets",       p1.isEmpty() ? 0 : countSetsWon(p2, p1));
            snap.put("player2Winner",     isWinner(p2));
        }

        if (isRetired(match))   snap.put("endReason", RESULT_RETIRED);
        if (isWalkover(match))  snap.put("endReason", RESULT_WALKOVER);

        List<String> broadcasts = extractBroadcasts(match);
        if (!broadcasts.isEmpty()) snap.put("broadcasts", broadcasts);

        return Collections.unmodifiableMap(snap);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildMatchSnapshotFromSummary(String matchId,
                                                                      Map<String, Object> summary) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("matchId", matchId);
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
                            snap.put("state",       type.getOrDefault("state",       "").toString());
                            snap.put("detail",      type.getOrDefault("detail",      "").toString());
                            snap.put("shortDetail", type.getOrDefault("shortDetail", "").toString());
                        }
                    }
                    List<?> competitors = (List<?>) comp.get("competitors");
                    if (competitors != null) {
                        for (int i = 0; i < Math.min(competitors.size(), 2); i++) {
                            Map<String, Object> c = (Map<String, Object>) competitors.get(i);
                            String prefix = i == 0 ? "player1" : "player2";
                            snap.put(prefix,        extractPlayerName(c));
                            snap.put(prefix + "Score", extractCurrentGameScore(c));
                            snap.put(prefix + "Winner", isWinner(c));
                        }
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return Collections.unmodifiableMap(snap);
    }
}
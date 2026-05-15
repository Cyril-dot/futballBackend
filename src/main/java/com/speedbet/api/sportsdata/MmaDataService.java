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
 * Service for UFC / MMA data via the ESPN unofficial API.
 *
 * ── Data source ──────────────────────────────────────────────────────────
 *
 *   Base URL : https://site.api.espn.com/apis/site/v2/sports/mma/ufc
 *   Auth     : None required — plain HTTP GET
 *
 * ── MMA structural differences from team sports ──────────────────────────
 *
 *   UFC data is EVENT-based, not game/date-based.  Each ESPN "event" is a
 *   full UFC event (e.g. "UFC 315") containing multiple bouts (fights).
 *   The scoreboard endpoint returns upcoming and recent events; the summary
 *   endpoint returns the full fight card for a specific event.
 *
 *   Individual fights are nested inside events as competitions[].
 *   Each competition has exactly two competitors (fighters).
 *
 *   Status fields follow the same ESPN convention:
 *     status.type.state  →  "pre" | "in" | "post"
 *     status.type.detail →  "Scheduled" | "In Progress" | "Final" |
 *                           "KO/TKO - Round 2" | "Submission - Round 1" etc.
 *
 * ── Key methods ──────────────────────────────────────────────────────────
 *
 *   getEvents()                  — upcoming + recent UFC events (scoreboard)
 *   getLiveEvents()              — only events currently in progress (fresh)
 *   getUpcomingEvents()          — events not yet started
 *   getFinishedEvents()          — recently completed events
 *   getEventSummary(String)      — full fight card: all bouts + results
 *   getEventScore(String)        — quick snapshot: event name, venue, main event
 *   getFighterInfo(String)       — fighter profile by ESPN athlete ID
 *   getGameOdds(String)          — pre/live 1X2 odds from LiveScoreApiClient
 *   getFullGameDetails(String)   — combined: event snapshot + fight card + odds
 *
 * ── MMA-specific fight result fields ────────────────────────────────────
 *
 *   Result method is in status.type.detail (post-fight):
 *     "KO/TKO - Round N"
 *     "Submission - Round N"
 *     "Decision - Unanimous"
 *     "Decision - Split"
 *     "Decision - Majority"
 *     "No Contest"
 *     "DQ - Round N"
 *
 * ── Odds source ──────────────────────────────────────────────────────────
 *
 *   Odds are sourced from LiveScoreApiClient. The event/bout is matched by
 *   ESPN event ID first, then by fighter name fallback. If no match is found
 *   the odds block is returned empty — always check the "matchedBy" field
 *   ("id", "fighterName", or "none").
 *
 * ── Caching ──────────────────────────────────────────────────────────────
 *
 *   Event scoreboard and fighter profiles cached for CACHE_TTL_MIN minutes.
 *   getLiveEvents(), getEventSummary(), getGameOdds(), getFullGameDetails()
 *   are always fresh — intentionally never cached.
 *
 * ── Polling guidance ─────────────────────────────────────────────────────
 *
 *   Poll getLiveEvents() every 30–60 seconds on UFC event nights.
 *   Polling faster risks ESPN rate-limiting the IP.
 */
@Slf4j
@Component
public class MmaDataService {

    // ── ESPN UFC base URL ──────────────────────────────────────────────────
    private static final String BASE_URL            = "https://site.api.espn.com";
    private static final String UFC_PATH            = "/apis/site/v2/sports/mma/ufc";
    private static final long   CACHE_TTL_MIN       = 5;
    private static final long   REQUEST_TIMEOUT_SEC = 12;

    // ── Event / fight state constants (ESPN status.type.state) ───────────
    public static final String STATE_PRE  = "pre";
    public static final String STATE_IN   = "in";
    public static final String STATE_POST = "post";

    // ── Result method keywords (checked against status.type.detail) ───────
    public static final String METHOD_KO          = "ko";
    public static final String METHOD_TKO         = "tko";
    public static final String METHOD_SUBMISSION  = "submission";
    public static final String METHOD_DECISION    = "decision";
    public static final String METHOD_NO_CONTEST  = "no contest";
    public static final String METHOD_DQ          = "dq";

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
    public MmaDataService(WebClient.Builder builder,
                          LiveScoreApiClient liveScoreApiClient) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.liveScoreApiClient = liveScoreApiClient;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  EVENT SCOREBOARD
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All UFC events visible on the ESPN scoreboard — typically includes
     * upcoming events and the most recent completed event.
     * Cached for CACHE_TTL_MIN minutes.
     *
     * @return list of raw ESPN event maps (each event = one UFC card)
     */
    public List<Map<String, Object>> getEvents() {
        return cached("scoreboard:ufc:events", () -> {
            Map<String, Object> response = fetch(UFC_PATH + "/scoreboard");
            if (response == null) return Collections.emptyList();
            List<Map<String, Object>> events = extractEvents(response);
            log.info("getEvents: {} UFC event(s) found", events.size());
            return events;
        });
    }

    /**
     * UFC events currently in progress — FRESH, no cache.
     * Intended for polling every 30–60 seconds on event nights.
     *
     * @return list of in-progress event maps
     */
    public List<Map<String, Object>> getLiveEvents() {
        Map<String, Object> response = fetch(UFC_PATH + "/scoreboard");
        if (response == null) {
            log.warn("getLiveEvents: null response from ESPN");
            return Collections.emptyList();
        }
        List<Map<String, Object>> live = extractEvents(response)
                .stream()
                .filter(MmaDataService::isLive)
                .toList();
        log.info("getLiveEvents: {} event(s) in progress", live.size());
        return live;
    }

    /**
     * UFC events that have not yet started.
     *
     * @return list of upcoming event maps
     */
    public List<Map<String, Object>> getUpcomingEvents() {
        return getEvents().stream()
                .filter(MmaDataService::isUpcoming)
                .toList();
    }

    /**
     * UFC events that have already finished.
     *
     * @return list of completed event maps
     */
    public List<Map<String, Object>> getFinishedEvents() {
        return getEvents().stream()
                .filter(MmaDataService::isFinished)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  EVENT SUMMARY — FULL FIGHT CARD + RESULTS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Full UFC event summary: complete fight card with all bouts, winners,
     * result methods (KO/TKO/submission/decision), round and time,
     * fighter records, and main/co-main event details.
     * Always fetches fresh — never cached.
     *
     * <p>Notable keys in the returned map:
     * <ul>
     *   <li>{@code header}       — event name, date, venue, status</li>
     *   <li>{@code boxscore}     — fight-by-fight breakdown</li>
     *   <li>{@code competitors}  — fighter details per bout</li>
     *   <li>{@code broadcasts}   — TV/streaming info (ESPN+, PPV)</li>
     * </ul>
     *
     * @param eventId ESPN event ID (the "id" field from getEvents())
     * @return raw ESPN summary map, or {@code Map.of()} on failure
     */
    public Map<String, Object> getEventSummary(String eventId) {
        Map<String, Object> result = fetch(UFC_PATH + "/summary?event=" + eventId);
        if (result == null) {
            log.warn("getEventSummary({}): null response", eventId);
            return Map.of();
        }
        log.info("getEventSummary({}): fetched", eventId);
        return result;
    }

    /**
     * Quick snapshot for a single UFC event: event name, venue, date,
     * status, and the main event fighter matchup.
     *
     * <p>Checks the scoreboard cache first; falls back to a full summary
     * call if the event is not in the current scoreboard.
     *
     * @param eventId ESPN event ID
     * @return map with keys: eventId, name, shortName, state, detail, date,
     *         venue, mainEventFighter1, mainEventFighter2, broadcasts?
     */
    public Map<String, Object> getEventScore(String eventId) {
        for (Map<String, Object> event : getEvents()) {
            if (eventId.equals(extractEventId(event))) {
                return buildEventSnapshot(event);
            }
        }
        Map<String, Object> summary = getEventSummary(eventId);
        return summary.isEmpty() ? Map.of() : buildEventSnapshotFromSummary(eventId, summary);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FIGHTER INFO
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Fighter profile by ESPN athlete ID.
     * Includes record, weight class, nationality, fighting style, and headshot.
     * Cached for CACHE_TTL_MIN minutes.
     *
     * @param athleteId ESPN athlete ID (the "id" on a competitor's athlete object)
     * @return raw ESPN athlete map, or {@code Map.of()} on failure
     */
    public Map<String, Object> getFighterInfo(String athleteId) {
        return cached("fighter:ufc:" + athleteId, () -> {
            Map<String, Object> result = fetch(UFC_PATH + "/athletes/" + athleteId);
            if (result == null) return Map.of();
            log.info("getFighterInfo({}): fetched", athleteId);
            return result;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GAME ODDS  (sourced from LiveScoreApiClient)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Odds for a UFC event or main-event bout (pre-match and live 1X2)
     * sourced from LiveScoreApiClient's live feed. Always fresh — never cached.
     *
     * <p>Matching strategy:
     * <ol>
     *   <li>ID match — livescore fixture "id" == ESPN eventId.</li>
     *   <li>Fighter name fallback — the main event fighters' names are resolved
     *       from the scoreboard and compared against livescore home/away names
     *       using a loose case-insensitive contains match.</li>
     * </ol>
     *
     * @param eventId ESPN event ID
     * @return map with keys:
     *         <ul>
     *           <li>{@code eventId}      — the ESPN event ID passed in</li>
     *           <li>{@code matchedBy}    — "id" | "fighterName" | "none"</li>
     *           <li>{@code preOddsHome}  — fighter1 pre-match odd (or "")</li>
     *           <li>{@code preOddsDraw}  — draw pre-match odd (or "")</li>
     *           <li>{@code preOddsAway}  — fighter2 pre-match odd (or "")</li>
     *           <li>{@code liveOddsHome} — fighter1 live odd (or "")</li>
     *           <li>{@code liveOddsDraw} — draw live odd (or "")</li>
     *           <li>{@code liveOddsAway} — fighter2 live odd (or "")</li>
     *         </ul>
     */
    public Map<String, Object> getGameOdds(String eventId) {
        // Resolve main event fighter names for the name-based fallback
        String fighter1 = "";
        String fighter2 = "";
        for (Map<String, Object> event : getEvents()) {
            if (eventId.equals(extractEventId(event))) {
                List<Map<String, Object>> mainEventFighters = extractMainEventFighters(event);
                if (mainEventFighters.size() >= 2) {
                    fighter1 = extractFighterName(mainEventFighters.get(0));
                    fighter2 = extractFighterName(mainEventFighters.get(1));
                }
                break;
            }
        }

        List<Map<String, Object>> liveMatches = liveScoreApiClient.getLiveScores();
        Map<String, Object> matched   = null;
        String              matchedBy = "none";

        for (Map<String, Object> lsMatch : liveMatches) {
            // ID match
            String lsId = LiveScoreApiClient.extractMatchId(lsMatch);
            if (!lsId.isBlank() && lsId.equals(eventId)) {
                matched   = lsMatch;
                matchedBy = "id";
                break;
            }
            // Fighter name fallback
            if (!fighter1.isBlank() && !fighter2.isBlank()) {
                String lsHome = LiveScoreApiClient.extractHomeName(lsMatch).toLowerCase();
                String lsAway = LiveScoreApiClient.extractAwayName(lsMatch).toLowerCase();
                String f1Low  = fighter1.toLowerCase();
                String f2Low  = fighter2.toLowerCase();
                // Match last name against livescore name (e.g. "Islam Makhachev" → "makhachev")
                String f1Last = f1Low.contains(" ") ? f1Low.substring(f1Low.lastIndexOf(' ') + 1) : f1Low;
                String f2Last = f2Low.contains(" ") ? f2Low.substring(f2Low.lastIndexOf(' ') + 1) : f2Low;
                if (lsHome.contains(f1Last) || lsAway.contains(f2Last)
                        || lsHome.contains(f2Last) || lsAway.contains(f1Last)) {
                    matched   = lsMatch;
                    matchedBy = "fighterName";
                }
            }
        }

        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("eventId",   eventId);
        odds.put("matchedBy", matchedBy);

        if (matched != null) {
            odds.put("preOddsHome",  liveScoreApiClient.extractPreOddsHome(matched));
            odds.put("preOddsDraw",  liveScoreApiClient.extractPreOddsDraw(matched));
            odds.put("preOddsAway",  liveScoreApiClient.extractPreOddsAway(matched));
            odds.put("liveOddsHome", liveScoreApiClient.extractLiveOddsHome(matched));
            odds.put("liveOddsDraw", liveScoreApiClient.extractLiveOddsDraw(matched));
            odds.put("liveOddsAway", liveScoreApiClient.extractLiveOddsAway(matched));
            log.info("getGameOdds({}): odds found via matchedBy={}", eventId, matchedBy);
        } else {
            odds.put("preOddsHome",  "");
            odds.put("preOddsDraw",  "");
            odds.put("preOddsAway",  "");
            odds.put("liveOddsHome", "");
            odds.put("liveOddsDraw", "");
            odds.put("liveOddsAway", "");
            log.warn("getGameOdds({}): no matching fixture in LiveScoreApiClient feed", eventId);
        }

        return Collections.unmodifiableMap(odds);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FULL EVENT DETAILS  (snapshot + fight card + odds — combined)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Combined event details for any event state (pre, live, post).
     *
     * <p>Merges three data sources into one map:
     * <ol>
     *   <li><b>score</b> — from {@link #getEventScore(String)}
     *       (event name, venue, main event matchup, state)</li>
     *   <li><b>stats</b> — from {@link #getEventSummary(String)}
     *       (full ESPN fight card: all bouts, results, fighter records)</li>
     *   <li><b>odds</b>  — from {@link #getGameOdds(String)}
     *       (LiveScoreApiClient pre/live 1X2 odds for the main event)</li>
     * </ol>
     *
     * <p>Each section is always present (never null); may be {@code Map.of()}
     * if its upstream source returned no data. Never cached — always fresh.
     *
     * <p><b>Returned map structure:</b>
     * <pre>
     * {
     *   "eventId" : "600033284",
     *   "score"   : { eventId, name, shortName, state, detail, date,
     *                 venue, mainEventFighter1, mainEventFighter2,
     *                 broadcasts? },
     *   "stats"   : { header, boxscore, competitors, broadcasts, ... },
     *   "odds"    : { matchedBy, preOddsHome, preOddsDraw, preOddsAway,
     *                 liveOddsHome, liveOddsDraw, liveOddsAway }
     * }
     * </pre>
     *
     * @param eventId ESPN event ID
     * @return combined details map (never null; sections may be empty)
     */
    public Map<String, Object> getFullGameDetails(String eventId) {
        log.info("getFullGameDetails({}): fetching score + stats + odds", eventId);

        Map<String, Object> score = getEventScore(eventId);
        Map<String, Object> stats = getEventSummary(eventId);
        Map<String, Object> odds  = getGameOdds(eventId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", eventId);
        details.put("score",   score);
        details.put("stats",   stats);
        details.put("odds",    odds);

        log.info("getFullGameDetails({}): assembled — scoreEmpty={} statsEmpty={} oddsMatchedBy={}",
                eventId, score.isEmpty(), stats.isEmpty(),
                odds.getOrDefault("matchedBy", "n/a"));

        return Collections.unmodifiableMap(details);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STATUS DETECTION HELPERS (static — usable without an instance)
    // ═════════════════════════════════════════════════════════════════════

    /** Returns true if the event is currently in progress. */
    public static boolean isLive(Map<String, Object> event) {
        return STATE_IN.equals(extractState(event));
    }

    /** Returns true if the event has not yet started. */
    public static boolean isUpcoming(Map<String, Object> event) {
        return STATE_PRE.equals(extractState(event));
    }

    /** Returns true if the event is over. */
    public static boolean isFinished(Map<String, Object> event) {
        return STATE_POST.equals(extractState(event));
    }

    /**
     * Extracts the result method from a finished fight's status detail.
     * e.g. "KO/TKO - Round 2" → "ko/tko", "Submission - Round 1" → "submission".
     *
     * @param fight a competition (bout) map from competitions[]
     * @return lowercase method keyword, or "" if not finished / not parseable
     */
    public static String extractResultMethod(Map<String, Object> fight) {
        String detail = extractStatusDetail(fight).toLowerCase();
        if (detail.contains(METHOD_SUBMISSION)) return METHOD_SUBMISSION;
        if (detail.contains(METHOD_TKO))        return METHOD_TKO;
        if (detail.contains(METHOD_KO))         return METHOD_KO;
        if (detail.contains(METHOD_DECISION))   return METHOD_DECISION;
        if (detail.contains(METHOD_NO_CONTEST)) return METHOD_NO_CONTEST;
        if (detail.contains(METHOD_DQ))         return METHOD_DQ;
        return "";
    }

    /**
     * Returns true if the fight ended by KO or TKO.
     *
     * @param fight a competition (bout) map from competitions[]
     */
    public static boolean isKoTko(Map<String, Object> fight) {
        String detail = extractStatusDetail(fight).toLowerCase();
        return detail.contains(METHOD_KO) || detail.contains(METHOD_TKO);
    }

    /**
     * Returns true if the fight ended by submission.
     *
     * @param fight a competition (bout) map from competitions[]
     */
    public static boolean isSubmission(Map<String, Object> fight) {
        return extractStatusDetail(fight).toLowerCase().contains(METHOD_SUBMISSION);
    }

    /**
     * Returns true if the fight went to a judges' decision.
     *
     * @param fight a competition (bout) map from competitions[]
     */
    public static boolean isDecision(Map<String, Object> fight) {
        return extractStatusDetail(fight).toLowerCase().contains(METHOD_DECISION);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FIELD EXTRACTORS
    // ═════════════════════════════════════════════════════════════════════

    /** ESPN event ID ("id" field on the event root). */
    public static String extractEventId(Map<String, Object> event) {
        Object id = event.get("id");
        return id != null ? id.toString() : "";
    }

    /** Full event name, e.g. "UFC 315: Makhachev vs. Poirier". */
    public static String extractEventName(Map<String, Object> event) {
        Object name = event.get("name");
        return name != null ? name.toString() : "";
    }

    /** Short event name, e.g. "UFC 315". */
    public static String extractShortName(Map<String, Object> event) {
        Object name = event.get("shortName");
        return name != null ? name.toString() : "";
    }

    /** ISO-8601 event date/time string, e.g. "2026-05-10T02:00Z". */
    public static String extractEventDate(Map<String, Object> event) {
        Object dateObj = event.get("date");
        log.info("extractKickoffTime: root date={}", dateObj);
        // Fallback: competitions[0].date
        if (dateObj == null) {
            try {
                List<?> competitions = (List<?>) event.get("competitions");
                if (competitions != null && !competitions.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> comp = (Map<String, Object>) competitions.get(0);
                    dateObj = comp.get("date");
                }
            } catch (ClassCastException ignored) {}
        }

        return dateObj != null ? dateObj.toString() : null;
    }

    /** Raw status.type.state: "pre", "in", or "post". */
    @SuppressWarnings("unchecked")
    public static String extractState(Map<String, Object> event) {
        try {
            Map<String, Object> status = (Map<String, Object>) event.get("status");
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
     * Human-readable status detail for an event or bout.
     * Pre-fight: "Scheduled". Live: "In Progress". Post-fight:
     * "Final", "KO/TKO - Round 2", "Submission - Round 1", etc.
     */
    @SuppressWarnings("unchecked")
    public static String extractStatusDetail(Map<String, Object> eventOrFight) {
        try {
            Map<String, Object> status = (Map<String, Object>) eventOrFight.get("status");
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
     * Current round number for a live fight (0 if not in progress).
     * Mapped from status.period.
     */
    @SuppressWarnings("unchecked")
    public static int extractCurrentRound(Map<String, Object> fight) {
        try {
            Map<String, Object> status = (Map<String, Object>) fight.get("status");
            if (status == null) return 0;
            Object period = status.get("period");
            return period != null ? Integer.parseInt(period.toString()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clock string for a live fight, e.g. "2:47" (time remaining in round).
     */
    @SuppressWarnings("unchecked")
    public static String extractClock(Map<String, Object> fight) {
        try {
            Map<String, Object> status = (Map<String, Object>) fight.get("status");
            if (status == null) return "";
            Object clock = status.get("displayClock");
            return clock != null ? clock.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /**
     * All individual bouts (fights) within an event, extracted from
     * the event's competitions[] array.
     * The first entry in the list is typically the main event.
     *
     * @param event raw ESPN event map
     * @return list of bout maps (each is one fight)
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractBouts(Map<String, Object> event) {
        try {
            Object competitions = event.get("competitions");
            if (competitions instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (ClassCastException ignored) {}
        return Collections.emptyList();
    }

    /**
     * The main event bout — competitions[0] by ESPN convention.
     *
     * @param event raw ESPN event map
     * @return the main event bout map, or {@code Map.of()} if not present
     */
    public static Map<String, Object> extractMainEvent(Map<String, Object> event) {
        List<Map<String, Object>> bouts = extractBouts(event);
        return bouts.isEmpty() ? Map.of() : bouts.get(0);
    }

    /**
     * The two fighters in a bout, extracted from competitions[0].competitors.
     * Index 0 = "home" side (fighter1), Index 1 = "away" side (fighter2).
     *
     * @param event raw ESPN event map (uses the main event / first bout)
     * @return list of two fighter competitor maps, or empty if not available
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractMainEventFighters(Map<String, Object> event) {
        Map<String, Object> mainEvent = extractMainEvent(event);
        if (mainEvent.isEmpty()) return Collections.emptyList();
        try {
            Object competitors = mainEvent.get("competitors");
            if (competitors instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (ClassCastException ignored) {}
        return Collections.emptyList();
    }

    /**
     * Fighters for a specific bout (competition map, not top-level event).
     *
     * @param bout a bout map from extractBouts()
     * @return list of competitor maps for this specific fight
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractBoutFighters(Map<String, Object> bout) {
        try {
            Object competitors = bout.get("competitors");
            if (competitors instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (ClassCastException ignored) {}
        return Collections.emptyList();
    }

    /**
     * Fighter display name from a competitor map, e.g. "Islam Makhachev".
     *
     * @param competitor competitor map from competitions[N].competitors[N]
     * @return display name string, or ""
     */
    @SuppressWarnings("unchecked")
    public static String extractFighterName(Map<String, Object> competitor) {
        try {
            Map<String, Object> athlete = (Map<String, Object>) competitor.get("athlete");
            if (athlete == null) return "";
            Object name = athlete.get("displayName");
            return name != null ? name.toString() : "";
        } catch (ClassCastException e) {
            return "";
        }
    }

    /**
     * Fighter record string, e.g. "26-1-0" (W-L-D), from a competitor map.
     *
     * @param competitor competitor map
     * @return record string, or ""
     */
    public static String extractFighterRecord(Map<String, Object> competitor) {
        Object record = competitor.get("record");
        return record != null ? record.toString() : "";
    }

    /**
     * Fighter headshot URL from a competitor map.
     *
     * @param competitor competitor map
     * @return headshot URL string, or ""
     */
    @SuppressWarnings("unchecked")
    public static String extractFighterHeadshot(Map<String, Object> competitor) {
        try {
            Map<String, Object> athlete = (Map<String, Object>) competitor.get("athlete");
            if (athlete == null) return "";
            Object headshot = athlete.get("headshot");
            if (headshot instanceof Map<?, ?> headshotMap) {
                Object href = ((Map<String, Object>) headshotMap).get("href");
                return href != null ? href.toString() : "";
            }
            if (headshot != null) return headshot.toString();
        } catch (ClassCastException ignored) {}
        return "";
    }

    /**
     * Fighter nationality from a competitor's athlete object.
     *
     * @param competitor competitor map
     * @return nationality string, or ""
     */
    @SuppressWarnings("unchecked")
    public static String extractFighterNationality(Map<String, Object> competitor) {
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

    /**
     * Weight class / division name for a bout, e.g. "Lightweight".
     * Extracted from the bout's name field or the event note.
     *
     * @param bout a bout map from extractBouts()
     * @return weight class string, or ""
     */
    public static String extractWeightClass(Map<String, Object> bout) {
        Object note = bout.get("note");
        if (note != null && !note.toString().isBlank()) return note.toString();
        Object name = bout.get("name");
        return name != null ? name.toString() : "";
    }

    /**
     * Whether this bout is the championship fight.
     * ESPN marks title fights with a "titleBout" boolean on the competition.
     *
     * @param bout a bout map from extractBouts()
     * @return true if this is a title fight
     */
    public static boolean isTitleBout(Map<String, Object> bout) {
        Object tb = bout.get("titleBout");
        return Boolean.TRUE.equals(tb) || "true".equalsIgnoreCase(tb != null ? tb.toString() : "");
    }

    /**
     * The winner of a finished fight, from the competitor marked as winner.
     *
     * @param bout a bout map from extractBouts()
     * @return winner's display name, or "" if the fight is not finished
     */
    @SuppressWarnings("unchecked")
    public static String extractWinner(Map<String, Object> bout) {
        if (!STATE_POST.equals(extractState(bout))) return "";
        try {
            Object competitors = bout.get("competitors");
            if (competitors instanceof List<?> compList) {
                for (Object cObj : compList) {
                    Map<String, Object> c = (Map<String, Object>) cObj;
                    Object winner = c.get("winner");
                    if (Boolean.TRUE.equals(winner) || "true".equalsIgnoreCase(
                            winner != null ? winner.toString() : "")) {
                        return extractFighterName(c);
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return "";
    }

    /**
     * Venue info for the event.
     *
     * @param event raw ESPN event map
     * @return map with keys: name, city, state, country — or empty map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractVenue(Map<String, Object> event) {
        try {
            List<Map<String, Object>> bouts = extractBouts(event);
            if (bouts.isEmpty()) return Map.of();
            Map<String, Object> venue = (Map<String, Object>) bouts.get(0).get("venue");
            if (venue == null) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", venue.getOrDefault("fullName", "").toString());
            Map<String, Object> address = (Map<String, Object>) venue.get("address");
            if (address != null) {
                result.put("city",    address.getOrDefault("city",    "").toString());
                result.put("state",   address.getOrDefault("state",   "").toString());
                result.put("country", address.getOrDefault("country", "").toString());
            }
            return Collections.unmodifiableMap(result);
        } catch (ClassCastException ignored) {
            return Map.of();
        }
    }

    /**
     * Broadcast networks for the event, e.g. ["ESPN+", "PPV"].
     *
     * @param event raw ESPN event map
     * @return list of broadcast name strings (may be empty)
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractBroadcasts(Map<String, Object> event) {
        try {
            List<Map<String, Object>> bouts = extractBouts(event);
            if (bouts.isEmpty()) return Collections.emptyList();
            List<?> broadcasts = (List<?>) bouts.get(0).get("broadcasts");
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
     * Builds a compact summary of all bouts on the card.
     * Useful for rendering a fight card list in a UI without needing the
     * full raw summary response.
     *
     * @param event raw ESPN event map (from scoreboard or summary header)
     * @return list of maps, each with keys:
     *         boutOrder, fighter1, fighter2, weightClass, titleBout,
     *         state, detail, winner (if finished), method (if finished),
     *         round (if finished), clock (if finished/live)
     */
    public static List<Map<String, Object>> buildFightCard(Map<String, Object> event) {
        List<Map<String, Object>> bouts    = extractBouts(event);
        List<Map<String, Object>> fightCard = new ArrayList<>();

        for (int i = 0; i < bouts.size(); i++) {
            Map<String, Object> bout    = bouts.get(i);
            List<Map<String, Object>> fighters = extractBoutFighters(bout);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("boutOrder",   i + 1);
            entry.put("fighter1",    fighters.size() > 0 ? extractFighterName(fighters.get(0)) : "");
            entry.put("fighter2",    fighters.size() > 1 ? extractFighterName(fighters.get(1)) : "");
            entry.put("fighter1Record", fighters.size() > 0 ? extractFighterRecord(fighters.get(0)) : "");
            entry.put("fighter2Record", fighters.size() > 1 ? extractFighterRecord(fighters.get(1)) : "");
            entry.put("weightClass", extractWeightClass(bout));
            entry.put("titleBout",   isTitleBout(bout));
            entry.put("state",       extractState(bout));
            entry.put("detail",      extractStatusDetail(bout));

            if (STATE_POST.equals(extractState(bout))) {
                entry.put("winner", extractWinner(bout));
                entry.put("method", extractResultMethod(bout));
                entry.put("round",  extractCurrentRound(bout));
                entry.put("clock",  extractClock(bout));
            } else if (STATE_IN.equals(extractState(bout))) {
                entry.put("round", extractCurrentRound(bout));
                entry.put("clock", extractClock(bout));
            }

            fightCard.add(Collections.unmodifiableMap(entry));
        }
        return Collections.unmodifiableList(fightCard);
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
            log.debug("MmaDataService cache HIT: '{}'", cacheKey);
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
                        log.warn("MmaDataService [{}] network error: {}", path, e.getMessage());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();

            if (raw == null || raw.isBlank()) {
                log.debug("MmaDataService [{}] blank response", path);
                return null;
            }

            Map<String, Object> result = mapper.readValue(raw, MAP_TYPE);
            log.info("MmaDataService [{}] OK ({} bytes)", path, raw.length());
            return result;

        } catch (Exception e) {
            log.error("MmaDataService [{}] error: {}", path, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractEvents(Map<String, Object> response) {
        Object events = response.get("events");
        if (events instanceof List<?> list) return (List<Map<String, Object>>) list;
        return Collections.emptyList();
    }

    private static Map<String, Object> buildEventSnapshot(Map<String, Object> event) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("eventId",   extractEventId(event));
        snap.put("name",      extractEventName(event));
        snap.put("shortName", extractShortName(event));
        snap.put("state",     extractState(event));
        snap.put("detail",    extractStatusDetail(event));
        snap.put("date",      extractEventDate(event));

        Map<String, Object> venue = extractVenue(event);
        if (!venue.isEmpty()) snap.put("venue", venue);

        List<Map<String, Object>> mainFighters = extractMainEventFighters(event);
        snap.put("mainEventFighter1", mainFighters.size() > 0 ? extractFighterName(mainFighters.get(0)) : "");
        snap.put("mainEventFighter2", mainFighters.size() > 1 ? extractFighterName(mainFighters.get(1)) : "");
        snap.put("mainEventFighter1Record", mainFighters.size() > 0 ? extractFighterRecord(mainFighters.get(0)) : "");
        snap.put("mainEventFighter2Record", mainFighters.size() > 1 ? extractFighterRecord(mainFighters.get(1)) : "");

        Map<String, Object> mainEvent = extractMainEvent(event);
        if (!mainEvent.isEmpty()) {
            snap.put("mainEventWeightClass", extractWeightClass(mainEvent));
            snap.put("mainEventTitleBout",   isTitleBout(mainEvent));
            if (STATE_POST.equals(extractState(event))) {
                snap.put("mainEventWinner", extractWinner(mainEvent));
                snap.put("mainEventMethod", extractResultMethod(mainEvent));
                snap.put("mainEventRound",  extractCurrentRound(mainEvent));
            } else if (STATE_IN.equals(extractState(event))) {
                snap.put("mainEventRound", extractCurrentRound(mainEvent));
                snap.put("mainEventClock", extractClock(mainEvent));
            }
        }

        List<String> broadcasts = extractBroadcasts(event);
        if (!broadcasts.isEmpty()) snap.put("broadcasts", broadcasts);

        snap.put("totalBouts", extractBouts(event).size());

        return Collections.unmodifiableMap(snap);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildEventSnapshotFromSummary(String eventId,
                                                                      Map<String, Object> summary) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("eventId", eventId);
        try {
            Map<String, Object> header = (Map<String, Object>) summary.get("header");
            if (header != null) {
                Object name = header.get("name");
                if (name != null) snap.put("name", name.toString());
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
                    }
                    List<?> competitors = (List<?>) comp.get("competitors");
                    if (competitors != null && competitors.size() >= 2) {
                        snap.put("mainEventFighter1",
                                extractFighterName((Map<String, Object>) competitors.get(0)));
                        snap.put("mainEventFighter2",
                                extractFighterName((Map<String, Object>) competitors.get(1)));
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return Collections.unmodifiableMap(snap);
    }
}
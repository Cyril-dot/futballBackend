package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Client for the API-Football v3 (api-sports.io) REST API.
 *
 * Base URL  : https://v3.football.api-sports.io/
 * Auth      : x-apisports-key header
 * Plan      : Free — 100 requests/day, resets at 00:00 UTC
 *
 * Key endpoints:
 *   /status                      – account & quota info
 *   /timezone                    – available timezones
 *   /countries                   – country list
 *   /leagues                     – leagues & cups
 *   /standings                   – league table
 *   /fixtures                    – fixtures / live / results
 *   /fixtures/rounds             – rounds for a league/season
 *   /fixtures/headtohead         – H2H between two teams
 *   /fixtures/statistics         – match stats
 *   /fixtures/events             – match events (goals, cards…)
 *   /fixtures/lineups            – match lineups
 *   /fixtures/players            – player stats per fixture
 *   /injuries                    – injury/suspension list
 *   /predictions                 – AI match prediction
 *   /standings                   – league table
 *   /teams                       – team info & stats
 *   /venues                      – venue info
 *   /players                     – player stats
 *   /players/squads              – current squad
 *   /players/topscorers          – top scorers
 *   /players/topassists          – top assists
 *   /players/topyellowcards      – most yellow cards
 *   /players/topredcards         – most red cards
 *   /coachs                      – coach info & career
 *   /transfers                   – player transfers
 *   /trophies                    – player/coach trophies
 *   /sidelined                   – player/coach sidelined list
 *   /odds                        – pre-match odds
 *   /odds/live                   – in-play odds
 *   /odds/live/bets              – in-play bet types
 *   /odds/bookmakers             – bookmaker list
 *   /odds/bets                   – pre-match bet types
 *   /odds/mapping                – fixture ids available for odds
 */
@Slf4j
@Component
public class ApiFootballClient {

    private static final String API_KEY = "66ced9706dfe970d8985f08703a37170";
    private static final String BASE_URL = "https://v3.football.api-sports.io";

    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public ApiFootballClient(ObjectMapper objectMapper, WebClient.Builder webClientBuilder) {
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
    }


    // ═══════════════════════════════════════════════════════════
    // HTTP CLIENT — .clone() prevents shared builder mutation
    // ═══════════════════════════════════════════════════════════

    private WebClient client() {
        return webClientBuilder.clone()
                .baseUrl(BASE_URL)
                .defaultHeader("x-apisports-key", API_KEY)
                .build();
    }

    private JsonNode execute(Function<WebClient, String> call) {
        try {
            String response = call.apply(client());
            if (response == null || response.isBlank()) return objectMapper.createObjectNode();
            return objectMapper.readTree(response);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429)                       log.warn("⚠️ [AF] Rate limit hit — free tier is 100 req/day");
            else if (status == 401 || status == 403) log.error("❌ [AF] Auth error — check api.apifootball.key");
            else                                     log.error("❌ [AF] HTTP {}: {}", status, e.getMessage());
            return objectMapper.createObjectNode();
        } catch (Exception e) {
            log.error("❌ [AF] Unexpected error: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode get(String path, Map<String, String> params) {
        return execute(c -> c.get()
                .uri(u -> {
                    var b = u.path(path);
                    if (params != null) params.forEach(b::queryParam);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .block());
    }

    private JsonNode get(String path) {
        return get(path, null);
    }


    // ═══════════════════════════════════════════════════════════
    // CORE MATCH FETCHING — normalised, merged, deduped
    // ═══════════════════════════════════════════════════════════

    /**
     * Fetches fixtures across a date range, day-by-day.
     * Returns a unified wrapper: { "matches": [ ...normalised fixtures... ] }
     */
    public JsonNode getMatchesByDateRange(String dateFrom, String dateTo) {
        log.info("📅 [AF] Fetching matches — from: {}, to: {}", dateFrom, dateTo);

        ArrayNode merged = objectMapper.createArrayNode();

        try {
            LocalDate from = LocalDate.parse(dateFrom);
            LocalDate to   = LocalDate.parse(dateTo);

            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                final String dateStr = date.toString();
                JsonNode response = get("/fixtures", Map.of("date", dateStr));

                if (response.has("response")) {
                    int count = 0;
                    for (JsonNode fixture : response.get("response")) {
                        merged.add(normaliseFixture(fixture));
                        count++;
                    }
                    log.info("✅ [AF] {} matches fetched for {}", count, dateStr);
                }

                Thread.sleep(200); // respect free-tier rate limit
            }
        } catch (Exception e) {
            log.error("❌ [AF] Failed to fetch matches by date range: {}", e.getMessage());
        }

        ArrayNode deduped = deduplicateMatches(merged);
        log.info("📦 [AF] Total after dedup: {}", deduped.size());

        var wrapper = objectMapper.createObjectNode();
        wrapper.set("matches", deduped);
        return wrapper;
    }

    public JsonNode getTodayMatches() {
        String today = LocalDate.now().toString();
        return getMatchesByDateRange(today, today);
    }

    public JsonNode getNextFixtures(int count) {
        return getMatchesByDateRange(
                LocalDate.now().toString(),
                LocalDate.now().plusDays(7).toString()
        );
    }

    /**
     * Fetches all currently live fixtures.
     * Returns a unified wrapper: { "matches": [ ...normalised fixtures... ] }
     */
    public JsonNode getLiveFixtures() {
        log.info("🔴 [AF] Fetching all live fixtures");

        ArrayNode merged = objectMapper.createArrayNode();

        try {
            JsonNode response = get("/fixtures", Map.of("live", "all"));
            if (response.has("response")) {
                response.get("response").forEach(f -> merged.add(normaliseFixture(f)));
            }
        } catch (Exception e) {
            log.error("❌ [AF] Live fetch failed: {}", e.getMessage());
        }

        ArrayNode deduped = deduplicateMatches(merged);
        var wrapper = objectMapper.createObjectNode();
        wrapper.set("matches", deduped);
        return wrapper;
    }

    /** Live fixtures filtered by league ids (comma-separated, e.g. "39,140"). */
    public JsonNode getLiveFixtures(String leagueIds) {
        return getFixtures(Map.of("live", leagueIds));
    }

    /**
     * Fetches a single fixture by its API-Football id.
     * Response shape: { "response": [ { "fixture":{}, "goals":{ "home": N, "away": N } } ] }
     * Used by ScheduledFetchService.fetchFinishedGameScores() which reads response[0].goals.home/away.
     */
    public JsonNode getFixtureById(int fixtureId) {
        log.info("🔍 [AF] Fetching fixture by ID: {}", fixtureId);
        return get("/fixtures", Map.of("id", String.valueOf(fixtureId)));
    }


    // ═══════════════════════════════════════════════════════════
    // ACCOUNT / STATUS
    // ═══════════════════════════════════════════════════════════

    /** Returns account info + daily quota. Does NOT count against the quota. */
    public JsonNode getStatus() {
        return get("/status");
    }

    public JsonNode getApiStatus() {
        return getStatus();
    }

    public JsonNode getTimezones() {
        return get("/timezone");
    }


    // ═══════════════════════════════════════════════════════════
    // COUNTRIES
    // ═══════════════════════════════════════════════════════════

    public JsonNode getCountries() {
        return getCountries(null, null, null);
    }

    public JsonNode getCountries(String name, String code, String search) {
        var p = new HashMap<String, String>();
        if (name   != null) p.put("name",   name);
        if (code   != null) p.put("code",   code);
        if (search != null) p.put("search", search);
        return get("/countries", p.isEmpty() ? null : p);
    }


    // ═══════════════════════════════════════════════════════════
    // LEAGUES
    // ═══════════════════════════════════════════════════════════

    /**
     * Full league query — all params optional.
     * @param id      league id
     * @param name    league name
     * @param country country name
     * @param code    country alpha code (FR, GB-ENG…)
     * @param season  YYYY
     * @param team    team id
     * @param type    "league" or "cup"
     * @param current "true" / "false"
     * @param search  name or country (≥3 chars)
     * @param last    X last leagues added
     */
    public JsonNode getLeagues(Integer id, String name, String country,
                               String code, Integer season, Integer team,
                               String type, String current,
                               String search, Integer last) {
        var p = new HashMap<String, String>();
        if (id      != null) p.put("id",      String.valueOf(id));
        if (name    != null) p.put("name",    name);
        if (country != null) p.put("country", country);
        if (code    != null) p.put("code",    code);
        if (season  != null) p.put("season",  String.valueOf(season));
        if (team    != null) p.put("team",    String.valueOf(team));
        if (type    != null) p.put("type",    type);
        if (current != null) p.put("current", current);
        if (search  != null) p.put("search",  search);
        if (last    != null) p.put("last",    String.valueOf(last));
        return get("/leagues", p.isEmpty() ? null : p);
    }

    public JsonNode getAllLeagues() {
        return getLeagues(null, null, null, null, null, null, null, null, null, null);
    }

    public JsonNode getCurrentLeagues() {
        return getLeagues(null, null, null, null, null, null, null, "true", null, null);
    }

    public JsonNode getLeagueSeason(int leagueId) {
        return getLeagues(leagueId, null, null, null, null, null, null, null, null, null);
    }

    public JsonNode getLeagueSeasons() {
        return get("/leagues/seasons");
    }

    public JsonNode getActiveLeagues() {
        return getAllLeagues();
    }


    // ═══════════════════════════════════════════════════════════
    // STANDINGS
    // ═══════════════════════════════════════════════════════════

    /**
     * @param season required
     * @param league optional league id
     * @param team   optional team id
     */
    public JsonNode getStandings(int season, Integer league, Integer team) {
        var p = new HashMap<String, String>();
        p.put("season", String.valueOf(season));
        if (league != null) p.put("league", String.valueOf(league));
        if (team   != null) p.put("team",   String.valueOf(team));
        return get("/standings", p);
    }

    public JsonNode getLeagueStandings(int leagueId, int season) {
        return getStandings(season, leagueId, null);
    }


    // ═══════════════════════════════════════════════════════════
    // FIXTURES
    // ═══════════════════════════════════════════════════════════

    /**
     * General fixture query.
     * Keys: id, ids, live, date, league, season, team, last, next,
     *       from, to, round, status, venue, timezone
     */
    public JsonNode getFixtures(Map<String, String> params) {
        return get("/fixtures", params);
    }

    /** Today's fixtures for a league + season. */
    public JsonNode getTodayFixtures(int leagueId, int season) {
        return getFixtures(Map.of(
                "date",   LocalDate.now().toString(),
                "league", String.valueOf(leagueId),
                "season", String.valueOf(season)
        ));
    }

    /** Next N fixtures globally or filtered by team/league. */
    public JsonNode getNextFixtures(int n, Integer teamId, Integer leagueId) {
        var p = new HashMap<String, String>();
        p.put("next", String.valueOf(n));
        if (teamId   != null) p.put("team",   String.valueOf(teamId));
        if (leagueId != null) p.put("league", String.valueOf(leagueId));
        return getFixtures(p);
    }

    /** Last N fixtures filtered by team/league. */
    public JsonNode getLastFixtures(int n, Integer teamId, Integer leagueId) {
        var p = new HashMap<String, String>();
        p.put("last", String.valueOf(n));
        if (teamId   != null) p.put("team",   String.valueOf(teamId));
        if (leagueId != null) p.put("league", String.valueOf(leagueId));
        return getFixtures(p);
    }

    /** Single fixture by id (includes events, lineups, stats, players). */
    public JsonNode getFixture(int fixtureId) {
        return getFixtureById(fixtureId);
    }

    public JsonNode getMatchEvents(int fixtureId)      { return getFixtureById(fixtureId); }
    public JsonNode getMatchLineups(int fixtureId)     { return getFixtureById(fixtureId); }
    public JsonNode getMatchStatistics(int fixtureId)  { return getFixtureById(fixtureId); }
    public JsonNode getMatchPlayerStats(int fixtureId) { return getFixtureById(fixtureId); }


    // ═══════════════════════════════════════════════════════════
    // ROUNDS
    // ═══════════════════════════════════════════════════════════

    public JsonNode getRounds(int leagueId, int season, boolean currentOnly) {
        var p = new HashMap<String, String>();
        p.put("league", String.valueOf(leagueId));
        p.put("season", String.valueOf(season));
        if (currentOnly) p.put("current", "true");
        return get("/fixtures/rounds", p);
    }


    // ═══════════════════════════════════════════════════════════
    // HEAD-TO-HEAD
    // ═══════════════════════════════════════════════════════════

    /**
     * @param team1Id  first team id
     * @param team2Id  second team id
     * @param last     optional — last N fixtures
     * @param leagueId optional filter
     * @param season   optional filter (YYYY)
     */
    public JsonNode getH2H(int team1Id, int team2Id, Integer last,
                           Integer leagueId, Integer season) {
        var p = new HashMap<String, String>();
        p.put("h2h", team1Id + "-" + team2Id);
        if (last     != null) p.put("last",   String.valueOf(last));
        if (leagueId != null) p.put("league", String.valueOf(leagueId));
        if (season   != null) p.put("season", String.valueOf(season));
        return get("/fixtures/headtohead", p);
    }

    public JsonNode getHeadToHead(int team1Id, int team2Id) {
        return getH2H(team1Id, team2Id, null, null, null);
    }


    // ═══════════════════════════════════════════════════════════
    // FIXTURE SUB-RESOURCES
    // ═══════════════════════════════════════════════════════════

    public JsonNode getFixtureStatistics(int fixtureId, Integer teamId, String type) {
        var p = new HashMap<String, String>();
        p.put("fixture", String.valueOf(fixtureId));
        if (teamId != null) p.put("team", String.valueOf(teamId));
        if (type   != null) p.put("type", type);
        return get("/fixtures/statistics", p);
    }

    public JsonNode getFixtureEvents(int fixtureId, Integer teamId,
                                     Integer playerId, String type) {
        var p = new HashMap<String, String>();
        p.put("fixture", String.valueOf(fixtureId));
        if (teamId   != null) p.put("team",   String.valueOf(teamId));
        if (playerId != null) p.put("player", String.valueOf(playerId));
        if (type     != null) p.put("type",   type);
        return get("/fixtures/events", p);
    }

    public JsonNode getFixtureLineups(int fixtureId, Integer teamId) {
        var p = new HashMap<String, String>();
        p.put("fixture", String.valueOf(fixtureId));
        if (teamId != null) p.put("team", String.valueOf(teamId));
        return get("/fixtures/lineups", p);
    }

    public JsonNode getFixturePlayers(int fixtureId, Integer teamId) {
        var p = new HashMap<String, String>();
        p.put("fixture", String.valueOf(fixtureId));
        if (teamId != null) p.put("team", String.valueOf(teamId));
        return get("/fixtures/players", p);
    }


    // ═══════════════════════════════════════════════════════════
    // INJURIES
    // ═══════════════════════════════════════════════════════════

    public JsonNode getInjuries(Map<String, String> params) {
        return get("/injuries", params);
    }

    public JsonNode getFixtureInjuries(int fixtureId) {
        return getInjuries(Map.of("fixture", String.valueOf(fixtureId)));
    }

    public JsonNode getTeamInjuries(int teamId, int season) {
        return getInjuries(Map.of(
                "team",   String.valueOf(teamId),
                "season", String.valueOf(season)
        ));
    }


    // ═══════════════════════════════════════════════════════════
    // PREDICTIONS
    // ═══════════════════════════════════════════════════════════

    public JsonNode getPredictions(int fixtureId) {
        return get("/predictions", Map.of("fixture", String.valueOf(fixtureId)));
    }


    // ═══════════════════════════════════════════════════════════
    // TEAMS
    // ═══════════════════════════════════════════════════════════

    public JsonNode getTeam(int teamId) {
        return get("/teams", Map.of("id", String.valueOf(teamId)));
    }

    public JsonNode searchTeam(String name) {
        return get("/teams", Map.of("search", name));
    }

    public JsonNode getTeamsByLeague(int leagueId, int season) {
        return get("/teams", Map.of(
                "league", String.valueOf(leagueId),
                "season", String.valueOf(season)
        ));
    }

    public JsonNode getTeamStatistics(int leagueId, int season, int teamId) {
        return get("/teams/statistics", Map.of(
                "league", String.valueOf(leagueId),
                "season", String.valueOf(season),
                "team",   String.valueOf(teamId)
        ));
    }

    public JsonNode getTeamSeasons(int teamId) {
        return get("/teams/seasons", Map.of("team", String.valueOf(teamId)));
    }

    public JsonNode getTeamLastMatches(int teamId, int count) {
        return getLastFixtures(count, teamId, null);
    }

    public JsonNode getTeamUpcomingMatches(int teamId) {
        return getNextFixtures(10, teamId, null);
    }


    // ═══════════════════════════════════════════════════════════
    // VENUES
    // ═══════════════════════════════════════════════════════════

    public JsonNode getVenue(int venueId) {
        return get("/venues", Map.of("id", String.valueOf(venueId)));
    }

    public JsonNode searchVenue(String name) {
        return get("/venues", Map.of("search", name));
    }


    // ═══════════════════════════════════════════════════════════
    // PLAYERS
    // ═══════════════════════════════════════════════════════════

    public JsonNode getPlayerStatistics(int playerId, int season) {
        return get("/players", Map.of(
                "id",     String.valueOf(playerId),
                "season", String.valueOf(season)
        ));
    }

    public JsonNode getTeamPlayers(int teamId, int season) {
        return get("/players", Map.of(
                "team",   String.valueOf(teamId),
                "season", String.valueOf(season)
        ));
    }

    public JsonNode getPlayerSeasons(int playerId) {
        return get("/players/seasons", Map.of("player", String.valueOf(playerId)));
    }

    public JsonNode getPlayerProfile(int playerId) {
        return get("/players/profiles", Map.of("player", String.valueOf(playerId)));
    }

    public JsonNode searchPlayerProfile(String lastName) {
        return get("/players/profiles", Map.of("search", lastName));
    }

    public JsonNode getSquad(int teamId) {
        return get("/players/squads", Map.of("team", String.valueOf(teamId)));
    }

    public JsonNode getPlayerTeams(int playerId) {
        return get("/players/teams", Map.of("player", String.valueOf(playerId)));
    }

    public JsonNode getTopScorers(int leagueId, int season) {
        return get("/players/topscorers", Map.of(
                "league", String.valueOf(leagueId),
                "season", String.valueOf(season)
        ));
    }

    public JsonNode getTopAssists(int leagueId, int season) {
        return get("/players/topassists", Map.of(
                "league", String.valueOf(leagueId),
                "season", String.valueOf(season)
        ));
    }

    public JsonNode getTopYellowCards(int leagueId, int season) {
        return get("/players/topyellowcards", Map.of(
                "league", String.valueOf(leagueId),
                "season", String.valueOf(season)
        ));
    }

    public JsonNode getTopRedCards(int leagueId, int season) {
        return get("/players/topredcards", Map.of(
                "league", String.valueOf(leagueId),
                "season", String.valueOf(season)
        ));
    }


    // ═══════════════════════════════════════════════════════════
    // COACHES
    // ═══════════════════════════════════════════════════════════

    public JsonNode getCoach(int coachId) {
        return get("/coachs", Map.of("id", String.valueOf(coachId)));
    }

    public JsonNode getTeamCoach(int teamId) {
        return get("/coachs", Map.of("team", String.valueOf(teamId)));
    }

    public JsonNode searchCoach(String name) {
        return get("/coachs", Map.of("search", name));
    }


    // ═══════════════════════════════════════════════════════════
    // TRANSFERS
    // ═══════════════════════════════════════════════════════════

    public JsonNode getPlayerTransfers(int playerId) {
        return get("/transfers", Map.of("player", String.valueOf(playerId)));
    }

    public JsonNode getTeamTransfers(int teamId) {
        return get("/transfers", Map.of("team", String.valueOf(teamId)));
    }


    // ═══════════════════════════════════════════════════════════
    // TROPHIES & SIDELINED
    // ═══════════════════════════════════════════════════════════

    public JsonNode getPlayerTrophies(int playerId) {
        return get("/trophies", Map.of("player", String.valueOf(playerId)));
    }

    public JsonNode getCoachTrophies(int coachId) {
        return get("/trophies", Map.of("coach", String.valueOf(coachId)));
    }

    public JsonNode getPlayerSidelined(int playerId) {
        return get("/sidelined", Map.of("player", String.valueOf(playerId)));
    }

    public JsonNode getCoachSidelined(int coachId) {
        return get("/sidelined", Map.of("coach", String.valueOf(coachId)));
    }


    // ═══════════════════════════════════════════════════════════
    // ODDS (Pre-Match)
    // Available 1–14 days before fixture; 7-day history retained.
    // Supported params: fixture, league, season, date, timezone,
    //                   page, bookmaker, bet
    // ═══════════════════════════════════════════════════════════

    public JsonNode getOdds(Map<String, String> params) {
        return get("/odds", params);
    }

    public JsonNode getFixtureOdds(int fixtureId) {
        return getOdds(Map.of("fixture", String.valueOf(fixtureId)));
    }

    public JsonNode getFixtureOdds(int fixtureId, int bookmakerId, int betId) {
        return getOdds(Map.of(
                "fixture",   String.valueOf(fixtureId),
                "bookmaker", String.valueOf(bookmakerId),
                "bet",       String.valueOf(betId)
        ));
    }

    public JsonNode getOddsMapping(int page) {
        return get("/odds/mapping", Map.of("page", String.valueOf(page)));
    }

    public JsonNode getBookmakers(String search) {
        return get("/odds/bookmakers", search != null ? Map.of("search", search) : null);
    }

    public JsonNode getBets(String search) {
        return get("/odds/bets", search != null ? Map.of("search", search) : null);
    }


    // ═══════════════════════════════════════════════════════════
    // ODDS (In-Play)
    // Fixtures added 15–5 min before kick-off;
    // removed 5–20 min after final whistle.
    // Supported params: fixture, league, bet
    // ═══════════════════════════════════════════════════════════

    public JsonNode getLiveOdds(Map<String, String> params) {
        return get("/odds/live", params);
    }

    public JsonNode getAllLiveOdds() {
        return getLiveOdds(null);
    }

    public JsonNode getFixtureLiveOdds(int fixtureId) {
        return getLiveOdds(Map.of("fixture", String.valueOf(fixtureId)));
    }

    public JsonNode getLeagueLiveOdds(int leagueId) {
        return getLiveOdds(Map.of("league", String.valueOf(leagueId)));
    }

    public JsonNode getLiveOddsBets(String search) {
        return get("/odds/live/bets", search != null ? Map.of("search", search) : null);
    }


    // ═══════════════════════════════════════════════════════════
    // NORMALISER — converts raw API-Football fixture shape into
    // the unified platform model used by ScheduledFetchService etc.
    // ═══════════════════════════════════════════════════════════

    public JsonNode normaliseFixture(JsonNode af) {
        var out = objectMapper.createObjectNode();

        // ID — prefix with "af-" to avoid collisions
        String fixtureId = af.path("fixture").path("id").asText();
        out.put("id", "af-" + fixtureId);

        // Date
        String date = af.path("fixture").path("date").asText();
        if (date.length() >= 19) {
            out.put("utcDate", date.substring(0, 19));
        }

        // Status
        String shortStatus = af.path("fixture").path("status").path("short").asText();
        out.put("status", mapAfStatus(shortStatus));
        out.put("minute", af.path("fixture").path("status").path("elapsed").asInt(0));

        // Home team
        var homeTeam = objectMapper.createObjectNode();
        homeTeam.put("id",    af.path("teams").path("home").path("id").asInt(0));
        homeTeam.put("name",  af.path("teams").path("home").path("name").asText());
        homeTeam.put("crest", af.path("teams").path("home").path("logo").asText());
        out.set("homeTeam", homeTeam);

        // Away team
        var awayTeam = objectMapper.createObjectNode();
        awayTeam.put("id",    af.path("teams").path("away").path("id").asInt(0));
        awayTeam.put("name",  af.path("teams").path("away").path("name").asText());
        awayTeam.put("crest", af.path("teams").path("away").path("logo").asText());
        out.set("awayTeam", awayTeam);

        // Competition
        var competition = objectMapper.createObjectNode();
        competition.put("id",     af.path("league").path("id").asInt(0));
        competition.put("name",   af.path("league").path("name").asText());
        competition.put("emblem", af.path("league").path("logo").asText());
        out.set("competition", competition);

        // Area/country
        var area = objectMapper.createObjectNode();
        area.put("name", af.path("league").path("country").asText());
        out.set("area", area);

        // Season
        var season = objectMapper.createObjectNode();
        String seasonYear = af.path("league").path("season").asText("");
        season.put("startDate", seasonYear.isBlank() ? "" : seasonYear + "-01-01");
        out.set("season", season);

        // Score
        var score    = objectMapper.createObjectNode();
        var fullTime = objectMapper.createObjectNode();
        JsonNode goals = af.path("goals");
        if (!goals.path("home").isNull() && !goals.path("home").isMissingNode()) {
            fullTime.put("home", goals.path("home").asInt());
            fullTime.put("away", goals.path("away").asInt());
        } else {
            fullTime.putNull("home");
            fullTime.putNull("away");
        }
        score.set("fullTime", fullTime);
        out.set("score", score);

        log.debug("🔍 [ID CHECK] af-{} homeId={} awayId={} leagueId={}",
                fixtureId,
                af.path("teams").path("home").path("id").asInt(0),
                af.path("teams").path("away").path("id").asInt(0),
                af.path("league").path("id").asInt(0));

        return out;
    }

    private String mapAfStatus(String shortStatus) {
        return switch (shortStatus) {
            case "NS", "TBD"           -> "SCHEDULED";
            case "1H", "2H", "ET", "P" -> "IN_PLAY";
            case "HT"                  -> "PAUSED";
            case "FT", "AET", "PEN"    -> "FINISHED";
            case "SUSP", "INT"         -> "SUSPENDED";
            case "PST"                 -> "POSTPONED";
            case "CANC", "ABD"         -> "CANCELLED";
            default                    -> "SCHEDULED";
        };
    }


    // ═══════════════════════════════════════════════════════════
    // DE-DUPLICATION
    // ═══════════════════════════════════════════════════════════

    private ArrayNode deduplicateMatches(ArrayNode matches) {
        ArrayNode result = objectMapper.createArrayNode();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        for (JsonNode match : matches) {
            String home = normaliseForDedup(match.path("homeTeam").path("name").asText());
            String away = normaliseForDedup(match.path("awayTeam").path("name").asText());
            String date = match.path("utcDate").asText();
            if (date.length() >= 10) date = date.substring(0, 10);

            String key = home + "|" + away + "|" + date;

            if (seen.add(key)) {
                result.add(match);
            } else {
                log.debug("🔁 Duplicate skipped: {} vs {} on {}", home, away, date);
            }
        }

        return result;
    }

    private String normaliseForDedup(String name) {
        return name.toLowerCase()
                .replace(" fc", "").replace("fc ", "")
                .replace(" afc", "").replace("afc ", "")
                .replace(" cf", "").replace(" sc", "")
                .replace(" united", "").replace(" city", "")
                .replace("manchester ", "man ")
                .replace("and hove albion", "")
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }


    // ═══════════════════════════════════════════════════════════
    // COMMON LEAGUE IDs (for reference)
    //
    //   39  – Premier League          61  – Ligue 1
    //   140 – La Liga                135  – Serie A
    //   78  – Bundesliga               2  – Champions League
    //    3  – Europa League           848 – Conference League
    //   253 – MLS                      71 – Brasileiro
    //   94  – Primeira Liga            88 – Eredivisie
    // ═══════════════════════════════════════════════════════════
}
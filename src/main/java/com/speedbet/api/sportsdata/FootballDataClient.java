package com.speedbet.api.sportsdata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client for the football-data.org v4 API.
 *
 * Base URL  : https://api.football-data.org/v4/
 * Auth      : X-Auth-Token header
 * Plan      : Free (12 competitions, 10 calls/minute, scores delayed)
 *
 * Key endpoints:
 *   /v4/competitions                  – list competitions
 *   /v4/competitions/{id}/standings   – league table
 *   /v4/competitions/{id}/matches     – matches for a competition
 *   /v4/competitions/{id}/teams       – teams in a competition
 *   /v4/competitions/{id}/scorers     – top scorers
 *   /v4/teams/{id}                    – team info
 *   /v4/teams/{id}/matches            – matches for a team
 *   /v4/matches                       – matches across competitions
 *   /v4/matches/{id}                  – single match
 *   /v4/matches/{id}/head2head        – H2H
 *   /v4/persons/{id}                  – person info
 *   /v4/persons/{id}/matches          – matches for a person
 *   /v4/areas                         – areas/countries
 */
@Slf4j
@Component
public class FootballDataClient {

    private static final String BASE_URL = "https://api.football-data.org/v4/";

    // Free-tier API token (football-data.org)
    private static final String API_TOKEN = "d1e143cc75ae42169ea0f77b0caa52f7";

    private final WebClient client;

    public FootballDataClient(WebClient.Builder builder) {
        this.client = builder
                .baseUrl(BASE_URL)
                .defaultHeader("X-Auth-Token", API_TOKEN)
                .build();
    }

    // ── Generic caller ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, Map<String, String> queryParams) {
        try {
            return client.get()
                    .uri(u -> {
                        var b = u.path(path);
                        if (queryParams != null) {
                            queryParams.forEach(b::queryParam);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(e -> {
                        log.warn("FootballData [{}] error: {}", path, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.error("FootballData [{}] threw: {}", path, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> get(String path) {
        return get(path, null);
    }

    // ── Areas ─────────────────────────────────────────────────────────────

    /** List all areas/countries. */
    public Map<String, Object> getAreas() {
        return get("areas");
    }

    /** Get a specific area by id. */
    public Map<String, Object> getArea(int areaId) {
        return get("areas/" + areaId);
    }

    // ── Competitions ──────────────────────────────────────────────────────

    /**
     * List all available competitions.
     * @param areas optional comma-separated area ids filter
     */
    public Map<String, Object> getCompetitions(String areas) {
        Map<String, String> params = areas != null ? Map.of("areas", areas) : null;
        return get("competitions", params);
    }

    public Map<String, Object> getCompetitions() {
        return getCompetitions(null);
    }

    /** Get a single competition by id or code (e.g. "PL", "CL", 2021). */
    public Map<String, Object> getCompetition(String idOrCode) {
        return get("competitions/" + idOrCode);
    }

    /**
     * Get standings for a competition.
     * @param idOrCode competition id or code
     * @param matchday optional matchday filter
     * @param season   optional season (YYYY)
     * @param date     optional date (YYYY-MM-DD)
     */
    public Map<String, Object> getStandings(String idOrCode, String matchday,
                                             String season, String date) {
        var params = new java.util.HashMap<String, String>();
        if (matchday != null) params.put("matchday", matchday);
        if (season   != null) params.put("season",   season);
        if (date     != null) params.put("date",      date);
        return get("competitions/" + idOrCode + "/standings", params.isEmpty() ? null : params);
    }

    public Map<String, Object> getStandings(String idOrCode) {
        return getStandings(idOrCode, null, null, null);
    }

    /**
     * Get matches for a competition.
     * Supported filters: dateFrom, dateTo, stage, status, matchday, group, season.
     */
    public Map<String, Object> getCompetitionMatches(String idOrCode,
                                                      Map<String, String> filters) {
        return get("competitions/" + idOrCode + "/matches", filters);
    }

    public Map<String, Object> getCompetitionMatches(String idOrCode) {
        return getCompetitionMatches(idOrCode, null);
    }

    /**
     * Get teams for a competition.
     * @param season optional season (YYYY)
     */
    public Map<String, Object> getCompetitionTeams(String idOrCode, String season) {
        Map<String, String> params = season != null ? Map.of("season", season) : null;
        return get("competitions/" + idOrCode + "/teams", params);
    }

    public Map<String, Object> getCompetitionTeams(String idOrCode) {
        return getCompetitionTeams(idOrCode, null);
    }

    /**
     * Get top scorers for a competition.
     * @param limit  max results (default 10)
     * @param season optional season (YYYY)
     */
    public Map<String, Object> getTopScorers(String idOrCode, Integer limit, String season) {
        var params = new java.util.HashMap<String, String>();
        if (limit  != null) params.put("limit",  String.valueOf(limit));
        if (season != null) params.put("season", season);
        return get("competitions/" + idOrCode + "/scorers", params.isEmpty() ? null : params);
    }

    public Map<String, Object> getTopScorers(String idOrCode) {
        return getTopScorers(idOrCode, null, null);
    }

    // ── Teams ─────────────────────────────────────────────────────────────

    /** Get team info by id. */
    public Map<String, Object> getTeam(int teamId) {
        return get("teams/" + teamId);
    }

    /**
     * List teams (paginated).
     * @param limit  results per page
     * @param offset skip N records
     */
    public Map<String, Object> getTeams(Integer limit, Integer offset) {
        var params = new java.util.HashMap<String, String>();
        if (limit  != null) params.put("limit",  String.valueOf(limit));
        if (offset != null) params.put("offset", String.valueOf(offset));
        return get("teams", params.isEmpty() ? null : params);
    }

    public Map<String, Object> getTeams() {
        return getTeams(null, null);
    }

    /**
     * Get matches for a team.
     * Supported filters: dateFrom, dateTo, season, competitions, status, venue, limit.
     */
    public Map<String, Object> getTeamMatches(int teamId, Map<String, String> filters) {
        return get("teams/" + teamId + "/matches/", filters);
    }

    public Map<String, Object> getTeamMatches(int teamId) {
        return getTeamMatches(teamId, null);
    }

    // ── Matches ───────────────────────────────────────────────────────────

    /** Get a single match by id. */
    public Map<String, Object> getMatch(int matchId) {
        return get("matches/" + matchId);
    }

    /**
     * List matches across competitions.
     * Supported filters: competitions, ids, dateFrom, dateTo, status.
     */
    public Map<String, Object> getMatches(Map<String, String> filters) {
        return get("matches", filters);
    }

    public Map<String, Object> getMatches() {
        return getMatches(null);
    }

    /** Get today's matches (convenience). */
    public Map<String, Object> getTodayMatches() {
        String today = java.time.LocalDate.now().toString();
        return getMatches(Map.of("dateFrom", today, "dateTo", today));
    }

    /**
     * Get head-to-head history for a match.
     * @param limit          optional result count
     * @param dateFrom       optional start date (YYYY-MM-DD)
     * @param dateTo         optional end date   (YYYY-MM-DD)
     * @param competitions   optional comma-separated competition ids
     */
    public Map<String, Object> getH2H(int matchId, Integer limit, String dateFrom,
                                       String dateTo, String competitions) {
        var params = new java.util.HashMap<String, String>();
        if (limit        != null) params.put("limit",        String.valueOf(limit));
        if (dateFrom     != null) params.put("dateFrom",     dateFrom);
        if (dateTo       != null) params.put("dateTo",       dateTo);
        if (competitions != null) params.put("competitions", competitions);
        return get("matches/" + matchId + "/head2head", params.isEmpty() ? null : params);
    }

    public Map<String, Object> getH2H(int matchId) {
        return getH2H(matchId, null, null, null, null);
    }

    // ── Persons ───────────────────────────────────────────────────────────

    /** Get person info by id. */
    public Map<String, Object> getPerson(int personId) {
        return get("persons/" + personId);
    }

    /**
     * Get matches for a person.
     * Supported filters: dateFrom, dateTo, status, competitions, limit, offset.
     */
    public Map<String, Object> getPersonMatches(int personId, Map<String, String> filters) {
        return get("persons/" + personId + "/matches", filters);
    }

    public Map<String, Object> getPersonMatches(int personId) {
        return getPersonMatches(personId, null);
    }

    // ── Convenience competition codes ─────────────────────────────────────

    // Free-tier available codes (12 competitions):
    //   PL  – Premier League        BSA – Brasileirao
    //   PD  – Primera Division      ELC – Championship
    //   SA  – Serie A               PPL – Primeira Liga
    //   BL1 – Bundesliga            DED – Eredivisie
    //   FL1 – Ligue 1               CLI – Copa Libertadores
    //   CL  – Champions League      EC  – European Championship

    public Map<String, Object> getPremierLeagueStandings(String season) {
        return getStandings("PL", null, season, null);
    }

    public Map<String, Object> getChampionsLeagueMatches(String season) {
        return getCompetitionMatches("CL", season != null ? Map.of("season", season) : null);
    }

    public Map<String, Object> getLaLigaStandings(String season) {
        return getStandings("PD", null, season, null);
    }

    public Map<String, Object> getBundesligaStandings(String season) {
        return getStandings("BL1", null, season, null);
    }

    public Map<String, Object> getSerieAStandings(String season) {
        return getStandings("SA", null, season, null);
    }

    public Map<String, Object> getLigue1Standings(String season) {
        return getStandings("FL1", null, season, null);
    }
}
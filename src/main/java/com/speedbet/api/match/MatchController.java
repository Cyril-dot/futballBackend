package com.speedbet.api.match;

import com.speedbet.api.ai.MistralClient;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.sportsdata.CompetitionIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MatchController {

    private final MatchService  matchService;
    private final MistralClient mistralClient;

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE ENUM RESOLVERS
    // ══════════════════════════════════════════════════════════════════════

    /** Resolves a path-variable string to a {@link CompetitionIds.Top6League} enum or 400. */
    private CompetitionIds.Top6League resolveTop6League(String league) {
        return Arrays.stream(CompetitionIds.Top6League.values())
                .filter(l -> l.displayName().equalsIgnoreCase(league))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown top-6 league: '" + league + "'. Valid values: " +
                                Arrays.stream(CompetitionIds.Top6League.values())
                                        .map(CompetitionIds.Top6League::displayName)
                                        .toList()));
    }

    /** Resolves a path-variable string to a {@link CompetitionIds.CupCompetition} enum or 400. */
    private CompetitionIds.CupCompetition resolveCup(String cup) {
        return Arrays.stream(CompetitionIds.CupCompetition.values())
                .filter(c -> c.displayName().equalsIgnoreCase(cup))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown cup competition: '" + cup + "'. Valid values: " +
                                Arrays.stream(CompetitionIds.CupCompetition.values())
                                        .map(CompetitionIds.CupCompetition::displayName)
                                        .toList()));
    }

    /** Resolves a path-variable string to a {@link CompetitionIds.LeagueCompetition} enum or 400. */
    private CompetitionIds.LeagueCompetition resolveLeagueComp(String league) {
        return Arrays.stream(CompetitionIds.LeagueCompetition.values())
                .filter(l -> l.displayName().equalsIgnoreCase(league))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown league: '" + league + "'. Valid values: " +
                                Arrays.stream(CompetitionIds.LeagueCompetition.values())
                                        .map(CompetitionIds.LeagueCompetition::displayName)
                                        .toList()));
    }

    /**
     * Resolves any competition name across all three enums using
     * {@link CompetitionIds#resolveId(String)}, falling back to the free-text
     * display name for DB-backed queries when no numeric ID is needed.
     */
    private String resolveDisplayName(String name) {
        // Try Top6League first
        for (CompetitionIds.Top6League e : CompetitionIds.Top6League.values())
            if (e.displayName().equalsIgnoreCase(name)) return e.displayName();
        // Then CupCompetition
        for (CompetitionIds.CupCompetition e : CompetitionIds.CupCompetition.values())
            if (e.displayName().equalsIgnoreCase(name)) return e.displayName();
        // Then LeagueCompetition
        for (CompetitionIds.LeagueCompetition e : CompetitionIds.LeagueCompetition.values())
            if (e.displayName().equalsIgnoreCase(name)) return e.displayName();
        // Unknown — pass through as-is; DB query will simply return empty
        return name;
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE PREDICTION HELPER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolves a prediction for a match — tries the service first, falls back
     * to a direct Mistral call if the service returns nothing.
     */
    private Map<String, Object> resolvePrediction(String id) {
        Map<String, Object> prediction = matchService.getPrediction(id);
        if (prediction != null && !prediction.isEmpty()) return prediction;

        Match match = matchService.getById(id);
        Map<String, Object> context = new HashMap<>();
        context.put("home_team", match.getHomeTeam());
        context.put("away_team", match.getAwayTeam());
        context.put("league",    match.getLeague());
        context.put("kickoff",   match.getKickoffAt());
        Map<String, Object> aiResult = mistralClient.predictMatch(context);
        log.info("resolvePrediction: matchId={} AI returned {} key(s)", id,
                aiResult != null ? aiResult.size() : 0);
        return aiResult != null ? aiResult : Map.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — LOBBY (all buckets in one response)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatches() {
        log.debug("GET /api/public/matches");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/matches — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     live);
        payload.put("today",    today);
        payload.put("upcoming", upcoming);
        payload.put("future",   future);
        payload.put("results",  results);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/matches/with-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchesWithOdds() {
        log.debug("GET /api/public/matches/with-odds");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/matches/with-odds — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     matchService.withOdds(live));
        payload.put("today",    matchService.withOdds(today));
        payload.put("upcoming", matchService.withOdds(upcoming));
        payload.put("future",   matchService.withOdds(future));
        payload.put("results",  matchService.withOdds(results));
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/matches/with-all-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchesWithAllOdds() {
        log.debug("GET /api/public/matches/with-all-odds");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/matches/with-all-odds — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     matchService.withAllOdds(live));
        payload.put("today",    matchService.withAllOdds(today));
        payload.put("upcoming", matchService.withAllOdds(upcoming));
        payload.put("future",   matchService.withAllOdds(future));
        payload.put("results",  matchService.withAllOdds(results));
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/matches/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicLive() {
        List<Match> live = matchService.getLiveMatches();
        log.info("GET /api/public/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(live)));
    }

    @GetMapping("/api/public/matches/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicToday() {
        List<Match> today = matchService.getTodayMatches();
        log.info("GET /api/public/matches/today — {} match(es) today", today.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(today)));
    }

    @GetMapping("/api/public/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicUpcoming() {
        List<Match> upcoming = matchService.getUpcomingMatches();
        log.info("GET /api/public/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(upcoming)));
    }

    @GetMapping("/api/public/matches/future")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFuture() {
        List<Match> future = matchService.getFutureMatches();
        log.info("GET /api/public/matches/future — {} match(es) next 7 days", future.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(future)));
    }

    @GetMapping("/api/public/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = matchService.getRecentResultsLimited(limit);
        log.info("GET /api/public/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @GetMapping("/api/public/matches/featured")
    public ResponseEntity<ApiResponse<List<Match>>> featuredMatches() {
        List<Match> featured = matchService.getFeaturedMatches();
        log.info("GET /api/public/matches/featured — {} featured match(es)", featured.size());
        return ResponseEntity.ok(ApiResponse.ok(featured));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — TOP-6 LEAGUES (DB-filtered)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/matches/top6/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTop6Live() {
        List<Match> matches = matchService.getTop6LiveMatches();
        log.info("GET /api/public/matches/top6/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/matches/top6/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTop6Today() {
        List<Match> matches = matchService.getTop6TodayMatches();
        log.info("GET /api/public/matches/top6/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/matches/top6/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTop6Upcoming() {
        List<Match> matches = matchService.getTop6UpcomingMatches();
        log.info("GET /api/public/matches/top6/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — TOP-6 CUPS (top6-related cups only)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/matches/cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicCupsLive() {
        List<Match> matches = matchService.getTop6CupsLiveMatches();
        log.info("GET /api/public/matches/cups/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/matches/cups/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicCupsToday() {
        List<Match> matches = matchService.getTop6CupsTodayMatches();
        log.info("GET /api/public/matches/cups/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/matches/cups/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicCupsUpcoming() {
        List<Match> matches = matchService.getTop6CupsUpcomingMatches();
        log.info("GET /api/public/matches/cups/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ALL CUPS (every CupCompetition enum value)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/matches/all-cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAllCupsLive() {
        List<Match> matches = matchService.getAllCupsLiveMatches();
        log.info("GET /api/public/matches/all-cups/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/matches/all-cups/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAllCupsToday() {
        List<Match> matches = matchService.getAllCupsTodayMatches();
        log.info("GET /api/public/matches/all-cups/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/matches/all-cups/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAllCupsUpcoming() {
        List<Match> matches = matchService.getAllCupsUpcomingMatches();
        log.info("GET /api/public/matches/all-cups/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY TOP-6 LEAGUE NAME (enum-validated)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/leagues/top6/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTop6LeagueLive(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Match> matches = matchService.getLiveMatchesByLeagueEnum(leagueEnum);
        log.info("GET /api/public/leagues/top6/{}/live — {} match(es)", league, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/leagues/top6/{league}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTop6LeagueToday(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Match> matches = matchService.getTodayMatchesByLeagueEnum(leagueEnum);
        log.info("GET /api/public/leagues/top6/{}/today — {} match(es)", league, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/leagues/top6/{league}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTop6LeagueUpcoming(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Match> matches = matchService.getUpcomingMatchesByLeagueEnum(leagueEnum);
        log.info("GET /api/public/leagues/top6/{}/upcoming — {} match(es)", league, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY CUP NAME (enum-validated)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/cups/{cup}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicCupLive(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Match> matches = matchService.getLiveMatchesByCupEnum(cupEnum);
        log.info("GET /api/public/cups/{}/live — {} match(es)", cup, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/cups/{cup}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicCupToday(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Match> matches = matchService.getTodayMatchesByCupEnum(cupEnum);
        log.info("GET /api/public/cups/{}/today — {} match(es)", cup, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/cups/{cup}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicCupUpcoming(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Match> matches = matchService.getUpcomingMatchesByCupEnum(cupEnum);
        log.info("GET /api/public/cups/{}/upcoming — {} match(es)", cup, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY ANY LEAGUE NAME (free-text, resolveDisplayName normalises it)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/leagues/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicLeagueLive(
            @PathVariable String league) {
        String name = resolveDisplayName(league);
        List<Match> matches = matchService.getLiveMatchesByLeague(name);
        log.info("GET /api/public/leagues/{}/live — {} match(es)", name, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/leagues/{league}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicLeagueToday(
            @PathVariable String league) {
        String name = resolveDisplayName(league);
        List<Match> matches = matchService.getTodayMatchesByLeague(name);
        log.info("GET /api/public/leagues/{}/today — {} match(es)", name, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/leagues/{league}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicLeagueUpcoming(
            @PathVariable String league) {
        String name = resolveDisplayName(league);
        List<Match> matches = matchService.getUpcomingMatchesByLeague(name);
        log.info("GET /api/public/leagues/{}/upcoming — {} match(es)", name, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY TEAM NAME
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/teams/{team}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTeamLive(
            @PathVariable String team) {
        List<Match> matches = matchService.getLiveMatchesByTeamName(team);
        log.info("GET /api/public/teams/{}/live — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/teams/{team}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTeamUpcoming(
            @PathVariable String team) {
        List<Match> matches = matchService.getUpcomingMatchesByTeamName(team);
        log.info("GET /api/public/teams/{}/upcoming — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/teams/{team}/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicTeamResults(@PathVariable String team) {
        List<Match> matches = matchService.getRecentResultsByTeamName(team);
        log.info("GET /api/public/teams/{}/results — {} result(s)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> publicMatchById(@PathVariable String id) {
        Match match = matchService.getById(id);
        log.info("GET /api/public/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/public/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFullDetail(@PathVariable String id) {
        Match match                    = matchService.getById(id);
        Map<String, Object> detail     = matchService.getMatchDetail(id);
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        Map<String, Object> prediction = resolvePrediction(id);
        log.info("GET /api/public/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("match",      match);
        bundle.put("detail",     detail);
        bundle.put("odds",       odds);
        bundle.put("prediction", prediction);
        return ResponseEntity.ok(ApiResponse.ok(bundle));
    }

    @GetMapping("/api/public/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchEvents(@PathVariable String id) {
        Map<String, Object> events = matchService.getEvents(id);
        log.info("GET /api/public/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping("/api/public/matches/{id}/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchStats(@PathVariable String id) {
        Map<String, Object> stats = matchService.getStats(id);
        log.info("GET /api/public/matches/{}/stats — source='{}'", id, stats.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping("/api/public/matches/{id}/lineups")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchLineups(@PathVariable String id) {
        Map<String, Object> lineups = matchService.getLineups(id);
        log.info("GET /api/public/matches/{}/lineups — source='{}'", id, lineups.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(lineups));
    }

    @GetMapping("/api/public/matches/{id}/h2h")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchH2H(@PathVariable String id) {
        Map<String, Object> h2h = matchService.getH2H(id);
        log.info("GET /api/public/matches/{}/h2h — source='{}'", id, h2h.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(h2h));
    }

    @GetMapping("/api/public/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicMatchOdds(@PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        log.info("GET /api/public/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchOddsAll(@PathVariable String id) {
        Map<String, Object> odds = matchService.getAllOddsForMatch(id);
        log.info("GET /api/public/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/matches/{id}/odds/correct-score")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicCorrectScoreOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getCorrectScoreOdds(id);
        log.info("GET /api/public/matches/{}/odds/correct-score — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/matches/{id}/odds/half-time")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicHalfTimeOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getHalfTimeOdds(id);
        log.info("GET /api/public/matches/{}/odds/half-time — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/matches/{id}/odds/handicap")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicHandicapOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getHandicapOdds(id);
        log.info("GET /api/public/matches/{}/odds/handicap — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/matches/{id}/prediction")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMatchPrediction(
            @PathVariable String id) {
        Map<String, Object> prediction = resolvePrediction(id);
        log.info("GET /api/public/matches/{}/prediction — {} key(s)", id, prediction.size());
        return ResponseEntity.ok(ApiResponse.ok(prediction));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDINGS / SCORERS / CONFIG
    // ══════════════════════════════════════════════════════════════════════

    /** All top-6 standings in one response. */
    @GetMapping("/api/public/standings/top6")
    public ResponseEntity<ApiResponse<Map<String, Map<String, Object>>>> publicTop6Standings() {
        log.info("GET /api/public/standings/top6");
        return ResponseEntity.ok(ApiResponse.ok(matchService.getAllTop6Standings()));
    }

    /** Standings for a named top-6 league (enum-validated). */
    @GetMapping("/api/public/standings/leagues/top6/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicStandingsByTop6League(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        log.info("GET /api/public/standings/leagues/top6/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByLeague(leagueEnum)));
    }

    /** Standings for a named cup competition (enum-validated). */
    @GetMapping("/api/public/standings/cups/{cup}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicStandingsByCup(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        log.info("GET /api/public/standings/cups/{}", cup);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByCup(cupEnum)));
    }

    /** Standings for a named domestic league beyond the top-6 (enum-validated). */
    @GetMapping("/api/public/standings/leagues/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicStandingsByLeagueComp(
            @PathVariable String league) {
        CompetitionIds.LeagueCompetition leagueEnum = resolveLeagueComp(league);
        log.info("GET /api/public/standings/leagues/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByLeagueComp(leagueEnum)));
    }

    /** Standings by raw LiveScore competition ID (numeric pass-through). */
    @GetMapping("/api/public/standings/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicStandings(
            @PathVariable int competitionId) {
        log.info("GET /api/public/standings/{}", competitionId);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getLiveScoreApiStandings(competitionId)));
    }

    /** Top scorers for a named top-6 league (enum-validated). */
    @GetMapping("/api/public/scorers/leagues/top6/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicScorersByTop6League(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        log.info("GET /api/public/scorers/leagues/top6/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getTopScorersByLeague(leagueEnum)));
    }

    /** Top scorers for a named domestic league (enum-validated). */
    @GetMapping("/api/public/scorers/leagues/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicScorersByLeagueComp(
            @PathVariable String league) {
        CompetitionIds.LeagueCompetition leagueEnum = resolveLeagueComp(league);
        log.info("GET /api/public/scorers/leagues/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getTopScorersByLeagueComp(leagueEnum)));
    }

    /** Top scorers by raw LiveScore competition ID (numeric pass-through). */
    @GetMapping("/api/public/scorers/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTopScorers(
            @PathVariable int competitionId) {
        log.info("GET /api/public/scorers/{}", competitionId);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getLiveScoreApiTopScorers(competitionId)));
    }

    @GetMapping("/api/public/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> config() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "minDepositAmount", 300,
                "currency",         "GHS",
                "platformName",     "SpeedBet",
                "slogan",           "HIT DIFFERENT. CASH OUT SMART."
        )));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/live")
    public ResponseEntity<ApiResponse<List<Match>>> liveMatches() {
        List<Match> live = matchService.getLiveMatches();
        log.info("GET /api/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/matches/today")
    public ResponseEntity<ApiResponse<List<Match>>> todayMatches() {
        List<Match> today = matchService.getTodayMatches();
        log.info("GET /api/matches/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> upcomingMatches() {
        List<Match> upcoming = matchService.getUpcomingMatches();
        log.info("GET /api/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/matches/future")
    public ResponseEntity<ApiResponse<List<Match>>> futureMatches() {
        List<Match> future = matchService.getFutureMatches();
        log.info("GET /api/matches/future — {} match(es) next 7 days", future.size());
        return ResponseEntity.ok(ApiResponse.ok(future));
    }

    @GetMapping("/api/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> recentResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = matchService.getRecentResultsLimited(limit);
        log.info("GET /api/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> matchDetail(@PathVariable String id) {
        Match match = matchService.getById(id);
        log.info("GET /api/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchFullDetail(@PathVariable String id) {
        Map<String, Object> detail = matchService.getMatchDetail(id);
        log.info("GET /api/matches/{}/detail — source='{}'", id, detail.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @GetMapping("/api/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchEvents(@PathVariable String id) {
        Map<String, Object> events = matchService.getEvents(id);
        log.info("GET /api/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping("/api/matches/{id}/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchStats(@PathVariable String id) {
        Map<String, Object> stats = matchService.getStats(id);
        log.info("GET /api/matches/{}/stats — source='{}'", id, stats.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping("/api/matches/{id}/lineups")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchLineups(@PathVariable String id) {
        Map<String, Object> lineups = matchService.getLineups(id);
        log.info("GET /api/matches/{}/lineups — source='{}'", id, lineups.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(lineups));
    }

    @GetMapping("/api/matches/{id}/h2h")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchH2H(@PathVariable String id) {
        Map<String, Object> h2h = matchService.getH2H(id);
        log.info("GET /api/matches/{}/h2h — source='{}'", id, h2h.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(h2h));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — TOP-6 LEAGUES
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/top6/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> top6Live() {
        List<Match> matches = matchService.getTop6LiveMatches();
        log.info("GET /api/matches/top6/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/matches/top6/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> top6Today() {
        List<Match> matches = matchService.getTop6TodayMatches();
        log.info("GET /api/matches/top6/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/matches/top6/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> top6Upcoming() {
        List<Match> matches = matchService.getTop6UpcomingMatches();
        log.info("GET /api/matches/top6/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — TOP-6 CUPS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cupsLive() {
        List<Match> matches = matchService.getTop6CupsLiveMatches();
        log.info("GET /api/matches/cups/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/matches/cups/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cupsToday() {
        List<Match> matches = matchService.getTop6CupsTodayMatches();
        log.info("GET /api/matches/cups/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/matches/cups/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cupsUpcoming() {
        List<Match> matches = matchService.getTop6CupsUpcomingMatches();
        log.info("GET /api/matches/cups/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ALL CUPS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/all-cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> allCupsLive() {
        List<Match> matches = matchService.getAllCupsLiveMatches();
        log.info("GET /api/matches/all-cups/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/matches/all-cups/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> allCupsToday() {
        List<Match> matches = matchService.getAllCupsTodayMatches();
        log.info("GET /api/matches/all-cups/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/matches/all-cups/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> allCupsUpcoming() {
        List<Match> matches = matchService.getAllCupsUpcomingMatches();
        log.info("GET /api/matches/all-cups/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY TOP-6 LEAGUE NAME (enum-validated)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/leagues/top6/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> top6LeagueLive(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Match> matches = matchService.getLiveMatchesByLeagueEnum(leagueEnum);
        log.info("GET /api/leagues/top6/{}/live — {} match(es)", league, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/leagues/top6/{league}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> top6LeagueToday(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Match> matches = matchService.getTodayMatchesByLeagueEnum(leagueEnum);
        log.info("GET /api/leagues/top6/{}/today — {} match(es)", league, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/leagues/top6/{league}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> top6LeagueUpcoming(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Match> matches = matchService.getUpcomingMatchesByLeagueEnum(leagueEnum);
        log.info("GET /api/leagues/top6/{}/upcoming — {} match(es)", league, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY CUP NAME (enum-validated)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/cups/{cup}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cupLive(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Match> matches = matchService.getLiveMatchesByCupEnum(cupEnum);
        log.info("GET /api/cups/{}/live — {} match(es)", cup, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/cups/{cup}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cupToday(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Match> matches = matchService.getTodayMatchesByCupEnum(cupEnum);
        log.info("GET /api/cups/{}/today — {} match(es)", cup, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/cups/{cup}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cupUpcoming(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Match> matches = matchService.getUpcomingMatchesByCupEnum(cupEnum);
        log.info("GET /api/cups/{}/upcoming — {} match(es)", cup, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY ANY LEAGUE NAME (resolveDisplayName normalises it)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/leagues/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> leagueLive(
            @PathVariable String league) {
        String name = resolveDisplayName(league);
        List<Match> matches = matchService.getLiveMatchesByLeague(name);
        log.info("GET /api/leagues/{}/live — {} match(es)", name, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/leagues/{league}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> leagueToday(
            @PathVariable String league) {
        String name = resolveDisplayName(league);
        List<Match> matches = matchService.getTodayMatchesByLeague(name);
        log.info("GET /api/leagues/{}/today — {} match(es)", name, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/leagues/{league}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> leagueUpcoming(
            @PathVariable String league) {
        String name = resolveDisplayName(league);
        List<Match> matches = matchService.getUpcomingMatchesByLeague(name);
        log.info("GET /api/leagues/{}/upcoming — {} match(es)", name, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY TEAM NAME (string, DB-filtered)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/teams/name/{team}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> teamLive(@PathVariable String team) {
        List<Match> matches = matchService.getLiveMatchesByTeamName(team);
        log.info("GET /api/teams/name/{}/live — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/teams/name/{team}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> teamUpcoming(@PathVariable String team) {
        List<Match> matches = matchService.getUpcomingMatchesByTeamName(team);
        log.info("GET /api/teams/name/{}/upcoming — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/teams/name/{team}/results")
    public ResponseEntity<ApiResponse<List<Match>>> teamResults(@PathVariable String team) {
        List<Match> matches = matchService.getRecentResultsByTeamName(team);
        log.info("GET /api/teams/name/{}/results — {} result(s)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY TEAM (external numeric LiveScore API team ID)
    // Note: uses /api/teams/id/{teamId} prefix to avoid clash with name routes
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/teams/id/{teamId}/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> teamMatches(@PathVariable int teamId) {
        log.info("GET /api/teams/id/{}/matches", teamId);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getLiveScoreApiTeamMatches(teamId)));
    }

    @GetMapping("/api/teams/id/{teamId}/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> teamFixtures(@PathVariable int teamId) {
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiFixturesByTeam(teamId);
        log.info("GET /api/teams/id/{}/fixtures — {} fixture(s)", teamId, fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/teams/id/{teamId}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> teamLiveExternal(@PathVariable int teamId) {
        List<Map<String, Object>> live = matchService.getLiveScoreApiLiveByTeam(teamId);
        log.info("GET /api/teams/id/{}/live — {} match(es)", teamId, live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ODDS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Odds>>> matchOddsDb(@PathVariable String id) {
        List<Odds> odds = matchService.getOddsForMatch(id);
        log.info("GET /api/matches/{}/odds — {} DB odds entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchOddsLive(@PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        log.info("GET /api/matches/{}/odds/live — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchOddsAll(@PathVariable String id) {
        Map<String, Object> odds = matchService.getAllOddsForMatch(id);
        log.info("GET /api/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/correct-score")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchCorrectScoreOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getCorrectScoreOdds(id);
        log.info("GET /api/matches/{}/odds/correct-score — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/half-time")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchHalfTimeOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getHalfTimeOdds(id);
        log.info("GET /api/matches/{}/odds/half-time — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/matches/{id}/odds/handicap")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matchHandicapOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getHandicapOdds(id);
        log.info("GET /api/matches/{}/odds/handicap — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — PREDICTIONS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/matches/{id}/prediction")
    public ResponseEntity<ApiResponse<Map<String, Object>>> matchPrediction(@PathVariable String id) {
        Map<String, Object> prediction = resolvePrediction(id);
        log.info("GET /api/matches/{}/prediction — {} key(s)", id, prediction.size());
        return ResponseEntity.ok(ApiResponse.ok(prediction));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDINGS / SCORERS (enum-driven)
    // ══════════════════════════════════════════════════════════════════════

    /** All top-6 standings in one response. */
    @GetMapping("/api/standings/top6")
    public ResponseEntity<ApiResponse<Map<String, Map<String, Object>>>> top6Standings() {
        log.info("GET /api/standings/top6");
        return ResponseEntity.ok(ApiResponse.ok(matchService.getAllTop6Standings()));
    }

    /** Standings for a named top-6 league (enum-validated). */
    @GetMapping("/api/standings/leagues/top6/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> standingsByTop6League(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        log.info("GET /api/standings/leagues/top6/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByLeague(leagueEnum)));
    }

    /** Standings for a named cup competition (enum-validated). */
    @GetMapping("/api/standings/cups/{cup}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> standingsByCup(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        log.info("GET /api/standings/cups/{}", cup);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByCup(cupEnum)));
    }

    /** Standings for a named domestic league (enum-validated). */
    @GetMapping("/api/standings/leagues/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> standingsByLeagueComp(
            @PathVariable String league) {
        CompetitionIds.LeagueCompetition leagueEnum = resolveLeagueComp(league);
        log.info("GET /api/standings/leagues/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByLeagueComp(leagueEnum)));
    }

    /** Standings by raw LiveScore competition ID (numeric pass-through). */
    @GetMapping("/api/standings/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> standings(
            @PathVariable int competitionId) {
        log.info("GET /api/standings/{}", competitionId);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getLiveScoreApiStandings(competitionId)));
    }

    /** Top scorers for a named top-6 league (enum-validated). */
    @GetMapping("/api/scorers/leagues/top6/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scorersByTop6League(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        log.info("GET /api/scorers/leagues/top6/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getTopScorersByLeague(leagueEnum)));
    }

    /** Top scorers for a named domestic league (enum-validated). */
    @GetMapping("/api/scorers/leagues/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scorersByLeagueComp(
            @PathVariable String league) {
        CompetitionIds.LeagueCompetition leagueEnum = resolveLeagueComp(league);
        log.info("GET /api/scorers/leagues/{}", league);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getTopScorersByLeagueComp(leagueEnum)));
    }

    /** Top scorers by raw LiveScore competition ID (numeric pass-through). */
    @GetMapping("/api/scorers/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> topScorers(
            @PathVariable int competitionId) {
        log.info("GET /api/scorers/{}", competitionId);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getLiveScoreApiTopScorers(competitionId)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — LIVESCORE API PASS-THROUGH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/livescore/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreLive() {
        List<Map<String, Object>> live = matchService.getLiveScoreApiLive();
        log.info("GET /api/livescore/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/livescore/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreToday() {
        List<Map<String, Object>> today = matchService.getLiveScoreApiToday();
        log.info("GET /api/livescore/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/livescore/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreFixtures() {
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiFixtures();
        log.info("GET /api/livescore/fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/livescore/top6/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreTop6Live() {
        List<Map<String, Object>> live = matchService.getLiveScoreApiTop6Live();
        log.info("GET /api/livescore/top6/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/livescore/top6/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreTop6Fixtures() {
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiTop6Fixtures();
        log.info("GET /api/livescore/top6/fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/livescore/top6/all-fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreTop6AndCupFixtures() {
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiTop6AndCupFixtures();
        log.info("GET /api/livescore/top6/all-fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/livescore/cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreCupsLive() {
        List<Map<String, Object>> live = matchService.getLiveScoreApiTop6CupsLive();
        log.info("GET /api/livescore/cups/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/livescore/cups/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreCupsFixtures() {
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiTop6CupFixtures();
        log.info("GET /api/livescore/cups/fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    /** Live scores for a specific top-6 league (enum-validated). */
    @GetMapping("/api/livescore/leagues/top6/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreTop6LeagueLive(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Map<String, Object>> live = matchService.getLiveScoreApiLiveByLeague(leagueEnum);
        log.info("GET /api/livescore/leagues/top6/{}/live — {} match(es)", league, live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    /** Fixtures for a specific top-6 league (enum-validated). */
    @GetMapping("/api/livescore/leagues/top6/{league}/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreTop6LeagueFixtures(
            @PathVariable String league) {
        CompetitionIds.Top6League leagueEnum = resolveTop6League(league);
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiFixturesByLeague(leagueEnum);
        log.info("GET /api/livescore/leagues/top6/{}/fixtures — {} fixture(s)", league, fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    /** Live scores for a specific cup competition (enum-validated). */
    @GetMapping("/api/livescore/cups/{cup}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreCupLive(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Map<String, Object>> live = matchService.getLiveScoreApiLiveByCup(cupEnum);
        log.info("GET /api/livescore/cups/{}/live — {} match(es)", cup, live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    /** Fixtures for a specific cup competition (enum-validated). */
    @GetMapping("/api/livescore/cups/{cup}/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> liveScoreCupFixtures(
            @PathVariable String cup) {
        CompetitionIds.CupCompetition cupEnum = resolveCup(cup);
        List<Map<String, Object>> fixtures = matchService.getLiveScoreApiFixturesByCup(cupEnum);
        log.info("GET /api/livescore/cups/{}/fixtures — {} fixture(s)", cup, fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }
}
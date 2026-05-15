package com.speedbet.api.match.controller;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.NbaMatchService;
import com.speedbet.api.odds.Odds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class NbaMatchController {

    private final NbaMatchService nbaMatchService;

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — LOBBY (all buckets in one response)
    // Routes: /api/public/nba/...  AND  /api/public/basketball/...
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/public/nba/matches", "/api/public/basketball/matches"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatches() {
        log.debug("GET /api/public/[nba|basketball]/matches");
        List<Match> live     = nbaMatchService.getLiveMatches();
        List<Match> today    = nbaMatchService.getTodayMatches();
        List<Match> upcoming = nbaMatchService.getUpcomingMatches();
        List<Match> future   = nbaMatchService.getFutureMatches();
        List<Match> results  = nbaMatchService.getRecentResults();
        log.info("GET /api/public/[nba|basketball]/matches — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     live);
        payload.put("today",    today);
        payload.put("upcoming", upcoming);
        payload.put("future",   future);
        payload.put("results",  results);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping({"/api/public/nba/matches/with-odds", "/api/public/basketball/matches/with-odds"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchesWithOdds() {
        log.debug("GET /api/public/[nba|basketball]/matches/with-odds");
        List<Match> live     = nbaMatchService.getLiveMatches();
        List<Match> today    = nbaMatchService.getTodayMatches();
        List<Match> upcoming = nbaMatchService.getUpcomingMatches();
        List<Match> future   = nbaMatchService.getFutureMatches();
        List<Match> results  = nbaMatchService.getRecentResults();
        log.info("GET /api/public/[nba|basketball]/matches/with-odds — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     nbaMatchService.withOdds(live));
        payload.put("today",    nbaMatchService.withOdds(today));
        payload.put("upcoming", nbaMatchService.withOdds(upcoming));
        payload.put("future",   nbaMatchService.withOdds(future));
        payload.put("results",  nbaMatchService.withOdds(results));
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping({"/api/public/nba/matches/with-all-odds", "/api/public/basketball/matches/with-all-odds"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchesWithAllOdds() {
        log.debug("GET /api/public/[nba|basketball]/matches/with-all-odds");
        List<Match> live     = nbaMatchService.getLiveMatches();
        List<Match> today    = nbaMatchService.getTodayMatches();
        List<Match> upcoming = nbaMatchService.getUpcomingMatches();
        List<Match> future   = nbaMatchService.getFutureMatches();
        List<Match> results  = nbaMatchService.getRecentResults();
        log.info("GET /api/public/[nba|basketball]/matches/with-all-odds — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     nbaMatchService.withAllOdds(live));
        payload.put("today",    nbaMatchService.withAllOdds(today));
        payload.put("upcoming", nbaMatchService.withAllOdds(upcoming));
        payload.put("future",   nbaMatchService.withAllOdds(future));
        payload.put("results",  nbaMatchService.withAllOdds(results));
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/public/nba/matches/live", "/api/public/basketball/matches/live"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaLive() {
        List<Match> live = nbaMatchService.getLiveMatches();
        log.info("GET /api/public/[nba|basketball]/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(live)));
    }

    @GetMapping({"/api/public/nba/matches/today", "/api/public/basketball/matches/today"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaToday() {
        List<Match> today = nbaMatchService.getTodayMatches();
        log.info("GET /api/public/[nba|basketball]/matches/today — {} match(es) today", today.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(today)));
    }

    @GetMapping({"/api/public/nba/matches/upcoming", "/api/public/basketball/matches/upcoming"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaUpcoming() {
        List<Match> upcoming = nbaMatchService.getUpcomingMatches();
        log.info("GET /api/public/[nba|basketball]/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(upcoming)));
    }

    @GetMapping({"/api/public/nba/matches/future", "/api/public/basketball/matches/future"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaFuture() {
        List<Match> future = nbaMatchService.getFutureMatches();
        log.info("GET /api/public/[nba|basketball]/matches/future — {} match(es) next 7 days", future.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(future)));
    }

    @GetMapping({"/api/public/nba/matches/results", "/api/public/basketball/matches/results"})
    public ResponseEntity<ApiResponse<List<Match>>> publicNbaResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = nbaMatchService.getRecentResults(limit);
        log.info("GET /api/public/[nba|basketball]/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY TEAM NAME
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/public/nba/teams/{team}/live", "/api/public/basketball/teams/{team}/live"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaTeamLive(
            @PathVariable String team) {
        List<Match> matches = nbaMatchService.getLiveMatchesByTeam(team);
        log.info("GET /api/public/[nba|basketball]/teams/{}/live — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(matches)));
    }

    @GetMapping({"/api/public/nba/teams/{team}/upcoming", "/api/public/basketball/teams/{team}/upcoming"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaTeamUpcoming(
            @PathVariable String team) {
        List<Match> matches = nbaMatchService.getUpcomingMatchesByTeam(team);
        log.info("GET /api/public/[nba|basketball]/teams/{}/upcoming — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(matches)));
    }

    @GetMapping({"/api/public/nba/teams/{team}/results", "/api/public/basketball/teams/{team}/results"})
    public ResponseEntity<ApiResponse<List<Match>>> publicNbaTeamResults(@PathVariable String team) {
        List<Match> matches = nbaMatchService.getRecentResultsByTeam(team);
        log.info("GET /api/public/[nba|basketball]/teams/{}/results — {} result(s)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/public/nba/matches/{id}", "/api/public/basketball/matches/{id}"})
    public ResponseEntity<ApiResponse<Match>> publicNbaMatchById(@PathVariable String id) {
        Match match = nbaMatchService.getById(id);
        log.info("GET /api/public/[nba|basketball]/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping({"/api/public/nba/matches/{id}/detail", "/api/public/basketball/matches/{id}/detail"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchDetail(
            @PathVariable String id) {
        Match match                = nbaMatchService.getById(id);
        Map<String, Object> detail = nbaMatchService.getMatchDetail(id);
        Map<String, Object> odds   = nbaMatchService.getAllOddsForMatch(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("match",  match);
        bundle.put("detail", detail);
        bundle.put("odds",   odds);
        return ResponseEntity.ok(ApiResponse.ok(bundle));
    }

    @GetMapping({"/api/public/nba/matches/{id}/events", "/api/public/basketball/matches/{id}/events"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchEvents(
            @PathVariable String id) {
        Map<String, Object> events = nbaMatchService.getEvents(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping({"/api/public/nba/matches/{id}/stats", "/api/public/basketball/matches/{id}/stats"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchStats(
            @PathVariable String id) {
        Map<String, Object> stats = nbaMatchService.getStats(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/stats — source='{}'", id, stats.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping({"/api/public/nba/matches/{id}/lineups", "/api/public/basketball/matches/{id}/lineups"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchLineups(
            @PathVariable String id) {
        Map<String, Object> lineups = nbaMatchService.getLineups(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/lineups — source='{}'", id, lineups.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(lineups));
    }

    @GetMapping({"/api/public/nba/matches/{id}/h2h", "/api/public/basketball/matches/{id}/h2h"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchH2H(
            @PathVariable String id) {
        Map<String, Object> h2h = nbaMatchService.getH2H(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/h2h", id);
        return ResponseEntity.ok(ApiResponse.ok(h2h));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ODDS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/public/nba/matches/{id}/odds", "/api/public/basketball/matches/{id}/odds"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaMatchOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getMoneylineOdds(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/public/nba/matches/{id}/odds/all", "/api/public/basketball/matches/{id}/odds/all"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaMatchOddsAll(
            @PathVariable String id) {
        Map<String, Object> odds = nbaMatchService.getAllOddsForMatch(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/public/nba/matches/{id}/odds/moneyline", "/api/public/basketball/matches/{id}/odds/moneyline"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaMoneylineOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getMoneylineOdds(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/odds/moneyline — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/public/nba/matches/{id}/odds/spread", "/api/public/basketball/matches/{id}/odds/spread"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaSpreadOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getPointSpreadOdds(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/odds/spread — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/public/nba/matches/{id}/odds/total", "/api/public/basketball/matches/{id}/odds/total"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaTotalOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getGameTotalOdds(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/odds/total — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/public/nba/matches/{id}/odds/margin", "/api/public/basketball/matches/{id}/odds/margin"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaMarginOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getWinningMarginOdds(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/odds/margin — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/public/nba/matches/{id}/odds/quarters", "/api/public/basketball/matches/{id}/odds/quarters"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaQuarterOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getQuarterOdds(id);
        log.info("GET /api/public/[nba|basketball]/matches/{}/odds/quarters — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDINGS / TEAMS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/public/nba/standings", "/api/public/basketball/standings"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaStandings() {
        log.info("GET /api/public/[nba|basketball]/standings");
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getStandings()));
    }

    @GetMapping({"/api/public/nba/teams", "/api/public/basketball/teams"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaTeams() {
        log.info("GET /api/public/[nba|basketball]/teams");
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getAllTeams()));
    }

    @GetMapping({"/api/public/nba/teams/{teamId}/info", "/api/public/basketball/teams/{teamId}/info"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaTeamInfo(
            @PathVariable String teamId) {
        log.info("GET /api/public/[nba|basketball]/teams/{}/info", teamId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getTeamInfo(teamId)));
    }

    @GetMapping({"/api/public/nba/teams/{teamId}/schedule", "/api/public/basketball/teams/{teamId}/schedule"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaTeamSchedule(
            @PathVariable String teamId) {
        log.info("GET /api/public/[nba|basketball]/teams/{}/schedule", teamId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getTeamSchedule(teamId)));
    }

    @GetMapping({"/api/public/nba/teams/{teamId}/roster", "/api/public/basketball/teams/{teamId}/roster"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaTeamRoster(
            @PathVariable String teamId) {
        log.info("GET /api/public/[nba|basketball]/teams/{}/roster", teamId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getTeamRoster(teamId)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ESPN PASS-THROUGH (raw scoreboard views)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/public/nba/espn/live", "/api/public/basketball/espn/live"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaEspnLive() {
        List<Map<String, Object>> live = nbaMatchService.getEspnLive();
        log.info("GET /api/public/[nba|basketball]/espn/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping({"/api/public/nba/espn/today", "/api/public/basketball/espn/today"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaEspnToday() {
        List<Map<String, Object>> today = nbaMatchService.getEspnToday();
        log.info("GET /api/public/[nba|basketball]/espn/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping({"/api/public/nba/espn/upcoming", "/api/public/basketball/espn/upcoming"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNbaEspnUpcoming() {
        List<Map<String, Object>> upcoming = nbaMatchService.getEspnUpcoming();
        log.info("GET /api/public/[nba|basketball]/espn/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping({"/api/public/nba/espn/game/{espnGameId}", "/api/public/basketball/espn/game/{espnGameId}"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNbaEspnGameDetail(
            @PathVariable String espnGameId) {
        log.info("GET /api/public/[nba|basketball]/espn/game/{}", espnGameId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getEspnGameDetail(espnGameId)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDARD MATCH BUCKETS
    // Routes: /api/nba/...  AND  /api/basketball/...
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/nba/matches/live", "/api/basketball/matches/live"})
    public ResponseEntity<ApiResponse<List<Match>>> nbaLiveMatches() {
        List<Match> live = nbaMatchService.getLiveMatches();
        log.info("GET /api/[nba|basketball]/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping({"/api/nba/matches/today", "/api/basketball/matches/today"})
    public ResponseEntity<ApiResponse<List<Match>>> nbaTodayMatches() {
        List<Match> today = nbaMatchService.getTodayMatches();
        log.info("GET /api/[nba|basketball]/matches/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping({"/api/nba/matches/upcoming", "/api/basketball/matches/upcoming"})
    public ResponseEntity<ApiResponse<List<Match>>> nbaUpcomingMatches() {
        List<Match> upcoming = nbaMatchService.getUpcomingMatches();
        log.info("GET /api/[nba|basketball]/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping({"/api/nba/matches/future", "/api/basketball/matches/future"})
    public ResponseEntity<ApiResponse<List<Match>>> nbaFutureMatches() {
        List<Match> future = nbaMatchService.getFutureMatches();
        log.info("GET /api/[nba|basketball]/matches/future — {} match(es) next 7 days", future.size());
        return ResponseEntity.ok(ApiResponse.ok(future));
    }

    @GetMapping({"/api/nba/matches/results", "/api/basketball/matches/results"})
    public ResponseEntity<ApiResponse<List<Match>>> nbaRecentResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = nbaMatchService.getRecentResults(limit);
        log.info("GET /api/[nba|basketball]/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/nba/matches/{id}", "/api/basketball/matches/{id}"})
    public ResponseEntity<ApiResponse<Match>> nbaMatchById(@PathVariable String id) {
        Match match = nbaMatchService.getById(id);
        log.info("GET /api/[nba|basketball]/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping({"/api/nba/matches/{id}/detail", "/api/basketball/matches/{id}/detail"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchDetail(@PathVariable String id) {
        Map<String, Object> detail = nbaMatchService.getMatchDetail(id);
        log.info("GET /api/[nba|basketball]/matches/{}/detail — source='{}'", id, detail.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @GetMapping({"/api/nba/matches/{id}/detail/full", "/api/basketball/matches/{id}/detail/full"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchFullDetail(@PathVariable String id) {
        Map<String, Object> detail = nbaMatchService.getFullGameDetails(id);
        log.info("GET /api/[nba|basketball]/matches/{}/detail/full", id);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @GetMapping({"/api/nba/matches/{id}/score", "/api/basketball/matches/{id}/score"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchScore(@PathVariable String id) {
        Map<String, Object> score = nbaMatchService.getScoreSnapshot(id);
        log.info("GET /api/[nba|basketball]/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    @GetMapping({"/api/nba/matches/{id}/events", "/api/basketball/matches/{id}/events"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchEvents(@PathVariable String id) {
        Map<String, Object> events = nbaMatchService.getEvents(id);
        log.info("GET /api/[nba|basketball]/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping({"/api/nba/matches/{id}/stats", "/api/basketball/matches/{id}/stats"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchStats(@PathVariable String id) {
        Map<String, Object> stats = nbaMatchService.getStats(id);
        log.info("GET /api/[nba|basketball]/matches/{}/stats — source='{}'", id, stats.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping({"/api/nba/matches/{id}/lineups", "/api/basketball/matches/{id}/lineups"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchLineups(@PathVariable String id) {
        Map<String, Object> lineups = nbaMatchService.getLineups(id);
        log.info("GET /api/[nba|basketball]/matches/{}/lineups — source='{}'", id, lineups.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(lineups));
    }

    @GetMapping({"/api/nba/matches/{id}/h2h", "/api/basketball/matches/{id}/h2h"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchH2H(@PathVariable String id) {
        Map<String, Object> h2h = nbaMatchService.getH2H(id);
        log.info("GET /api/[nba|basketball]/matches/{}/h2h", id);
        return ResponseEntity.ok(ApiResponse.ok(h2h));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY TEAM NAME
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/nba/teams/{team}/live", "/api/basketball/teams/{team}/live"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaTeamLive(
            @PathVariable String team) {
        List<Match> matches = nbaMatchService.getLiveMatchesByTeam(team);
        log.info("GET /api/[nba|basketball]/teams/{}/live — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(matches)));
    }

    @GetMapping({"/api/nba/teams/{team}/upcoming", "/api/basketball/teams/{team}/upcoming"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaTeamUpcoming(
            @PathVariable String team) {
        List<Match> matches = nbaMatchService.getUpcomingMatchesByTeam(team);
        log.info("GET /api/[nba|basketball]/teams/{}/upcoming — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.withOdds(matches)));
    }

    @GetMapping({"/api/nba/teams/{team}/results", "/api/basketball/teams/{team}/results"})
    public ResponseEntity<ApiResponse<List<Match>>> nbaTeamResults(@PathVariable String team) {
        List<Match> matches = nbaMatchService.getRecentResultsByTeam(team);
        log.info("GET /api/[nba|basketball]/teams/{}/results — {} result(s)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ODDS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/nba/matches/{id}/odds", "/api/basketball/matches/{id}/odds"})
    public ResponseEntity<ApiResponse<List<Odds>>> nbaMatchOddsDb(@PathVariable String id) {
        List<Odds> odds = nbaMatchService.getOddsForMatch(id);
        log.info("GET /api/[nba|basketball]/matches/{}/odds — {} DB odds entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/nba/matches/{id}/odds/all", "/api/basketball/matches/{id}/odds/all"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaMatchOddsAll(@PathVariable String id) {
        Map<String, Object> odds = nbaMatchService.getAllOddsForMatch(id);
        log.info("GET /api/[nba|basketball]/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/nba/matches/{id}/odds/moneyline", "/api/basketball/matches/{id}/odds/moneyline"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaMoneylineOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getMoneylineOdds(id);
        log.info("GET /api/[nba|basketball]/matches/{}/odds/moneyline — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/nba/matches/{id}/odds/spread", "/api/basketball/matches/{id}/odds/spread"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaSpreadOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getPointSpreadOdds(id);
        log.info("GET /api/[nba|basketball]/matches/{}/odds/spread — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/nba/matches/{id}/odds/total", "/api/basketball/matches/{id}/odds/total"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaTotalOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getGameTotalOdds(id);
        log.info("GET /api/[nba|basketball]/matches/{}/odds/total — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/nba/matches/{id}/odds/margin", "/api/basketball/matches/{id}/odds/margin"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaMarginOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getWinningMarginOdds(id);
        log.info("GET /api/[nba|basketball]/matches/{}/odds/margin — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping({"/api/nba/matches/{id}/odds/quarters", "/api/basketball/matches/{id}/odds/quarters"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaQuarterOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = nbaMatchService.getQuarterOdds(id);
        log.info("GET /api/[nba|basketball]/matches/{}/odds/quarters — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDINGS / TEAMS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/nba/standings", "/api/basketball/standings"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaStandings() {
        log.info("GET /api/[nba|basketball]/standings");
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getStandings()));
    }

    @GetMapping({"/api/nba/teams", "/api/basketball/teams"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaAllTeams() {
        log.info("GET /api/[nba|basketball]/teams");
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getAllTeams()));
    }

    @GetMapping({"/api/nba/teams/{teamId}/info", "/api/basketball/teams/{teamId}/info"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaTeamInfo(
            @PathVariable String teamId) {
        log.info("GET /api/[nba|basketball]/teams/{}/info", teamId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getTeamInfo(teamId)));
    }

    @GetMapping({"/api/nba/teams/{teamId}/schedule", "/api/basketball/teams/{teamId}/schedule"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaTeamSchedule(
            @PathVariable String teamId) {
        log.info("GET /api/[nba|basketball]/teams/{}/schedule", teamId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getTeamSchedule(teamId)));
    }

    @GetMapping({"/api/nba/teams/{teamId}/roster", "/api/basketball/teams/{teamId}/roster"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaTeamRoster(
            @PathVariable String teamId) {
        log.info("GET /api/[nba|basketball]/teams/{}/roster", teamId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getTeamRoster(teamId)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ESPN PASS-THROUGH (raw scoreboard views)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping({"/api/nba/espn/live", "/api/basketball/espn/live"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaEspnLive() {
        List<Map<String, Object>> live = nbaMatchService.getEspnLive();
        log.info("GET /api/[nba|basketball]/espn/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping({"/api/nba/espn/today", "/api/basketball/espn/today"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaEspnToday() {
        List<Map<String, Object>> today = nbaMatchService.getEspnToday();
        log.info("GET /api/[nba|basketball]/espn/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping({"/api/nba/espn/upcoming", "/api/basketball/espn/upcoming"})
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nbaEspnUpcoming() {
        List<Map<String, Object>> upcoming = nbaMatchService.getEspnUpcoming();
        log.info("GET /api/[nba|basketball]/espn/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping({"/api/nba/espn/game/{espnGameId}", "/api/basketball/espn/game/{espnGameId}"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> nbaEspnGameDetail(
            @PathVariable String espnGameId) {
        log.info("GET /api/[nba|basketball]/espn/game/{}", espnGameId);
        return ResponseEntity.ok(ApiResponse.ok(nbaMatchService.getEspnGameDetail(espnGameId)));
    }
}
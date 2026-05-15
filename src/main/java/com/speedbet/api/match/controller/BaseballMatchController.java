package com.speedbet.api.match.controller;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.match.BaseballMatchService;
import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BaseballMatchController {

    private final BaseballMatchService baseballMatchService;

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — LOBBY (all buckets in one response)
    // Route prefix: /api/public/baseball
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/baseball/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicBaseballMatches() {
        log.debug("GET /api/public/baseball/matches");
        List<Match> live     = baseballMatchService.getLiveMatches();
        List<Match> today    = baseballMatchService.getTodayMatches();
        List<Match> upcoming = baseballMatchService.getUpcomingMatches();
        List<Match> results  = baseballMatchService.getRecentResults();
        log.info("GET /api/public/baseball/matches — live={} today={} upcoming={} results={}",
                live.size(), today.size(), upcoming.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     live);
        payload.put("today",    today);
        payload.put("upcoming", upcoming);
        payload.put("results",  results);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/baseball/matches/live")
    public ResponseEntity<ApiResponse<List<Match>>> publicBaseballLive() {
        List<Match> live = baseballMatchService.getLiveMatches();
        log.info("GET /api/public/baseball/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/baseball/matches/today")
    public ResponseEntity<ApiResponse<List<Match>>> publicBaseballToday() {
        List<Match> today = baseballMatchService.getTodayMatches();
        log.info("GET /api/public/baseball/matches/today — {} match(es) today", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/public/baseball/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> publicBaseballUpcoming() {
        List<Match> upcoming = baseballMatchService.getUpcomingMatches();
        log.info("GET /api/public/baseball/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/public/baseball/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicBaseballResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = baseballMatchService.getRecentResults();
        List<Match> paged   = results.stream().limit(limit).toList();
        log.info("GET /api/public/baseball/matches/results?limit={} — {} result(s)", limit, paged.size());
        return ResponseEntity.ok(ApiResponse.ok(paged));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY TEAM NAME
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/baseball/teams/{team}/live")
    public ResponseEntity<ApiResponse<List<Match>>> publicBaseballTeamLive(
            @PathVariable String team) {
        List<Match> live = baseballMatchService.getLiveMatches().stream()
                .filter(m -> team.equalsIgnoreCase(m.getHomeTeam()) || team.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("GET /api/public/baseball/teams/{}/live — {} match(es)", team, live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/baseball/teams/{team}/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> publicBaseballTeamUpcoming(
            @PathVariable String team) {
        List<Match> upcoming = baseballMatchService.getUpcomingMatches().stream()
                .filter(m -> team.equalsIgnoreCase(m.getHomeTeam()) || team.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("GET /api/public/baseball/teams/{}/upcoming — {} match(es)", team, upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/public/baseball/teams/{team}/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicBaseballTeamResults(
            @PathVariable String team) {
        List<Match> results = baseballMatchService.getRecentResults().stream()
                .filter(m -> team.equalsIgnoreCase(m.getHomeTeam()) || team.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("GET /api/public/baseball/teams/{}/results — {} result(s)", team, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/baseball/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> publicBaseballMatchById(@PathVariable String id) {
        Match match = baseballMatchService.getById(id);
        log.info("GET /api/public/baseball/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/public/baseball/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicBaseballMatchDetail(
            @PathVariable String id) {
        Match match                    = baseballMatchService.getById(id);
        Map<String, Object> detail     = baseballMatchService.getMatchDetail(id);
        List<Map<String, Object>> odds = baseballMatchService.getMatchOdds(id);
        log.info("GET /api/public/baseball/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("match",  match);
        bundle.put("detail", detail);
        bundle.put("odds",   odds);
        return ResponseEntity.ok(ApiResponse.ok(bundle));
    }

    @GetMapping("/api/public/baseball/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicBaseballMatchScore(
            @PathVariable String id) {
        Map<String, Object> score = baseballMatchService.getMatchScore(id);
        log.info("GET /api/public/baseball/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ODDS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/baseball/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicBaseballMatchOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = baseballMatchService.getMatchOdds(id);
        log.info("GET /api/public/baseball/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/baseball/matches/{id}/odds/persisted")
    public ResponseEntity<ApiResponse<List<Odds>>> publicBaseballPersistedOdds(
            @PathVariable String id) {
        List<Odds> odds = baseballMatchService.getPersistedOdds(id);
        log.info("GET /api/public/baseball/matches/{}/odds/persisted — {} DB entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDINGS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/baseball/standings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicBaseballStandings() {
        Map<String, Object> standings = baseballMatchService.getEspnMlbStandings();
        log.info("GET /api/public/baseball/standings");
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ESPN PASS-THROUGH (raw scoreboard views)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/baseball/espn/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicBaseballEspnLive() {
        List<Map<String, Object>> live = baseballMatchService.getEspnMlbLive();
        log.info("GET /api/public/baseball/espn/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/baseball/espn/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicBaseballEspnToday() {
        List<Map<String, Object>> today = baseballMatchService.getEspnMlbToday();
        log.info("GET /api/public/baseball/espn/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/public/baseball/espn/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicBaseballEspnUpcoming() {
        List<Map<String, Object>> upcoming = baseballMatchService.getEspnMlbUpcoming();
        log.info("GET /api/public/baseball/espn/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/public/baseball/espn/game/{espnGameId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicBaseballEspnGameDetail(
            @PathVariable String espnGameId) {
        Map<String, Object> detail = baseballMatchService.getEspnMlbGameDetail(espnGameId);
        log.info("GET /api/public/baseball/espn/game/{}", espnGameId);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDARD MATCH BUCKETS
    // Route prefix: /api/baseball
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/baseball/matches/live")
    public ResponseEntity<ApiResponse<List<Match>>> baseballLiveMatches() {
        List<Match> live = baseballMatchService.getLiveMatches();
        log.info("GET /api/baseball/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/baseball/matches/today")
    public ResponseEntity<ApiResponse<List<Match>>> baseballTodayMatches() {
        List<Match> today = baseballMatchService.getTodayMatches();
        log.info("GET /api/baseball/matches/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/baseball/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> baseballUpcomingMatches() {
        List<Match> upcoming = baseballMatchService.getUpcomingMatches();
        log.info("GET /api/baseball/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/baseball/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> baseballRecentResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = baseballMatchService.getRecentResults();
        List<Match> paged   = results.stream().limit(limit).toList();
        log.info("GET /api/baseball/matches/results?limit={} — {} result(s)", limit, paged.size());
        return ResponseEntity.ok(ApiResponse.ok(paged));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/baseball/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> baseballMatchById(@PathVariable String id) {
        Match match = baseballMatchService.getById(id);
        log.info("GET /api/baseball/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/baseball/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> baseballMatchDetail(
            @PathVariable String id) {
        Map<String, Object> detail = baseballMatchService.getMatchDetail(id);
        log.info("GET /api/baseball/matches/{}/detail — source='{}'", id, detail.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @GetMapping("/api/baseball/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> baseballMatchScore(
            @PathVariable String id) {
        Map<String, Object> score = baseballMatchService.getMatchScore(id);
        log.info("GET /api/baseball/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY TEAM NAME
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/baseball/teams/{team}/live")
    public ResponseEntity<ApiResponse<List<Match>>> baseballTeamLive(@PathVariable String team) {
        List<Match> matches = baseballMatchService.getLiveMatches().stream()
                .filter(m -> team.equalsIgnoreCase(m.getHomeTeam()) || team.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("GET /api/baseball/teams/{}/live — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/api/baseball/teams/{team}/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> baseballTeamUpcoming(@PathVariable String team) {
        List<Match> matches = baseballMatchService.getUpcomingMatches().stream()
                .filter(m -> team.equalsIgnoreCase(m.getHomeTeam()) || team.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("GET /api/baseball/teams/{}/upcoming — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/api/baseball/teams/{team}/results")
    public ResponseEntity<ApiResponse<List<Match>>> baseballTeamResults(@PathVariable String team) {
        List<Match> matches = baseballMatchService.getRecentResults().stream()
                .filter(m -> team.equalsIgnoreCase(m.getHomeTeam()) || team.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("GET /api/baseball/teams/{}/results — {} result(s)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ODDS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/baseball/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> baseballMatchOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = baseballMatchService.getMatchOdds(id);
        log.info("GET /api/baseball/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/baseball/matches/{id}/odds/persisted")
    public ResponseEntity<ApiResponse<List<Odds>>> baseballMatchOddsDb(@PathVariable String id) {
        List<Odds> odds = baseballMatchService.getPersistedOdds(id);
        log.info("GET /api/baseball/matches/{}/odds/persisted — {} DB entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDINGS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/baseball/standings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> baseballStandings() {
        Map<String, Object> standings = baseballMatchService.getEspnMlbStandings();
        log.info("GET /api/baseball/standings");
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ESPN PASS-THROUGH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/baseball/espn/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> baseballEspnLive() {
        List<Map<String, Object>> live = baseballMatchService.getEspnMlbLive();
        log.info("GET /api/baseball/espn/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/baseball/espn/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> baseballEspnToday() {
        List<Map<String, Object>> today = baseballMatchService.getEspnMlbToday();
        log.info("GET /api/baseball/espn/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/baseball/espn/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> baseballEspnUpcoming() {
        List<Map<String, Object>> upcoming = baseballMatchService.getEspnMlbUpcoming();
        log.info("GET /api/baseball/espn/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/baseball/espn/game/{espnGameId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> baseballEspnGameDetail(
            @PathVariable String espnGameId) {
        Map<String, Object> detail = baseballMatchService.getEspnMlbGameDetail(espnGameId);
        log.info("GET /api/baseball/espn/game/{}", espnGameId);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }
}
package com.speedbet.api.match.controller;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.NflMatchService;
import com.speedbet.api.odds.Odds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for NFL American Football (sport = "americanfootball") match data.
 *
 * ── Route conventions ─────────────────────────────────────────────────────
 *
 *   Public (no auth):     /api/public/nfl/...
 *   Authenticated:        /api/nfl/...
 *
 * ── Buckets ───────────────────────────────────────────────────────────────
 *
 *   live      – LIVE matches
 *   today     – matches kicking off today (UTC)
 *   upcoming  – SCHEDULED / UPCOMING within 7 days
 *   results   – FINISHED within 72 hours
 *
 * ── Odds ──────────────────────────────────────────────────────────────────
 *
 *   LIVE matches  → live moneyline cache (refreshed every 2 min by poller)
 *   UPCOMING/TODAY→ pre-match deterministic odds (HOME / DRAW / AWAY)
 *   FINISHED      → empty list
 *
 * ── Draw ─────────────────────────────────────────────────────────────────
 *
 *   NFL regular-season games CAN end in a tie.  A DRAW selection is therefore
 *   included in the moneyline market at a very low implied probability.
 *   Playoff games cannot tie — OT continues until a team scores.
 *
 * ── ESPN pass-throughs ────────────────────────────────────────────────────
 *
 *   Raw ESPN game data (week scoreboard, standings, rosters, etc.) are
 *   forwarded directly from {@link NflMatchService} with no additional
 *   transformation.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NflMatchController {

    private final NflMatchService nflMatchService;

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — LOBBY (all buckets in one response)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All NFL buckets in a single response — live, today, upcoming, results.
     * Mirrors {@code /api/public/matches}.
     */
    @GetMapping("/api/public/nfl/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNflMatches() {
        log.debug("GET /api/public/nfl/matches");
        List<Match> live     = nflMatchService.getLiveMatches();
        List<Match> today    = nflMatchService.getTodayMatches();
        List<Match> upcoming = nflMatchService.getUpcomingMatches();
        List<Match> results  = nflMatchService.getRecentResults();
        log.info("GET /api/public/nfl/matches — live={} today={} upcoming={} results={}",
                live.size(), today.size(), upcoming.size(), results.size());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "live",     live,
                "today",    today,
                "upcoming", upcoming,
                "results",  results
        )));
    }

    /**
     * All NFL buckets with moneyline odds attached.
     * Mirrors {@code /api/public/matches/with-odds}.
     */
    @GetMapping("/api/public/nfl/matches/with-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNflMatchesWithOdds() {
        log.debug("GET /api/public/nfl/matches/with-odds");
        List<Match> live     = nflMatchService.getLiveMatches();
        List<Match> today    = nflMatchService.getTodayMatches();
        List<Match> upcoming = nflMatchService.getUpcomingMatches();
        List<Match> results  = nflMatchService.getRecentResults();
        log.info("GET /api/public/nfl/matches/with-odds — live={} today={} upcoming={} results={}",
                live.size(), today.size(), upcoming.size(), results.size());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "live",     nflMatchService.withOdds(live),
                "today",    nflMatchService.withOdds(today),
                "upcoming", nflMatchService.withOdds(upcoming),
                "results",  results
        )));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All currently LIVE NFL matches with moneyline odds.
     */
    @GetMapping("/api/public/nfl/matches/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNflLive() {
        List<Match> live = nflMatchService.getLiveMatches();
        log.info("GET /api/public/nfl/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(nflMatchService.withOdds(live)));
    }

    /**
     * NFL matches kicking off today (UTC) with pre-match or live odds.
     */
    @GetMapping("/api/public/nfl/matches/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNflToday() {
        List<Match> today = nflMatchService.getTodayMatches();
        log.info("GET /api/public/nfl/matches/today — {} match(es) today", today.size());
        return ResponseEntity.ok(ApiResponse.ok(nflMatchService.withOdds(today)));
    }

    /**
     * NFL matches scheduled within the next 7 days with pre-match odds.
     */
    @GetMapping("/api/public/nfl/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNflUpcoming() {
        List<Match> upcoming = nflMatchService.getUpcomingMatches();
        log.info("GET /api/public/nfl/matches/upcoming — {} upcoming match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(nflMatchService.withOdds(upcoming)));
    }

    /**
     * FINISHED NFL matches from the past 72 hours.
     */
    @GetMapping("/api/public/nfl/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicNflResults() {
        List<Match> results = nflMatchService.getRecentResults();
        log.info("GET /api/public/nfl/matches/results — {} result(s)", results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A single NFL match by its internal UUID.
     */
    @GetMapping("/api/public/nfl/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> publicNflMatchById(@PathVariable String id) {
        Match match = nflMatchService.getById(id);
        log.info("GET /api/public/nfl/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    /**
     * Full detail bundle: match row + ESPN box score + moneyline odds.
     */
    @GetMapping("/api/public/nfl/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNflMatchDetail(@PathVariable String id) {
        Match match                    = nflMatchService.getById(id);
        Map<String, Object> detail     = nflMatchService.getMatchDetail(id);
        List<Map<String, Object>> odds = nflMatchService.getMoneylineOdds(id);
        log.info("GET /api/public/nfl/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "match",  match,
                "detail", detail,
                "odds",   odds
        )));
    }

    /**
     * Moneyline odds for a single NFL match (live cache or pre-match generated).
     */
    @GetMapping("/api/public/nfl/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicNflMatchOdds(@PathVariable String id) {
        List<Map<String, Object>> odds = nflMatchService.getMoneylineOdds(id);
        log.info("GET /api/public/nfl/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    /**
     * All odds markets for a match (currently moneyline only).
     */
    @GetMapping("/api/public/nfl/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNflMatchOddsAll(@PathVariable String id) {
        Map<String, Object> odds = nflMatchService.getAllOddsForMatch(id);
        log.info("GET /api/public/nfl/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    /**
     * Current score snapshot: teams, scores, quarter, clock, possession.
     */
    @GetMapping("/api/public/nfl/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNflMatchScore(@PathVariable String id) {
        Map<String, Object> score = nflMatchService.getMatchScore(id);
        log.info("GET /api/public/nfl/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDINGS / TEAMS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * AFC + NFC standings from ESPN.
     */
    @GetMapping("/api/public/nfl/standings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicNflStandings() {
        Map<String, Object> standings = nflMatchService.getEspnStandings();
        log.info("GET /api/public/nfl/standings");
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ESPN SCOREBOARD PASS-THROUGHS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All games for the current NFL week — cached by the data service.
     */
    @GetMapping("/api/public/nfl/espn/week")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnNflCurrentWeek() {
        List<Map<String, Object>> games = nflMatchService.getEspnCurrentWeek();
        log.info("GET /api/public/nfl/espn/week — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * NFL games currently in progress — fresh from ESPN, no cache.
     */
    @GetMapping("/api/public/nfl/espn/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnNflLive() {
        List<Map<String, Object>> games = nflMatchService.getEspnLive();
        log.info("GET /api/public/nfl/espn/live — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * Upcoming (pre-game) NFL games for the current week.
     */
    @GetMapping("/api/public/nfl/espn/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnNflUpcoming() {
        List<Map<String, Object>> games = nflMatchService.getEspnUpcoming();
        log.info("GET /api/public/nfl/espn/upcoming — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * Finished NFL games from the current week.
     */
    @GetMapping("/api/public/nfl/espn/finished")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnNflFinished() {
        List<Map<String, Object>> games = nflMatchService.getEspnFinished();
        log.info("GET /api/public/nfl/espn/finished — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * NFL games for a specific week and season type.
     *
     * @param week       NFL week number
     * @param seasonType season type (e.g. 2 = regular, 3 = playoff)
     */
    @GetMapping("/api/public/nfl/espn/week/{week}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnNflByWeek(
            @PathVariable int week,
            @RequestParam(defaultValue = "2") int seasonType) {
        List<Map<String, Object>> games = nflMatchService.getEspnByWeek(week, seasonType);
        log.info("GET /api/public/nfl/espn/week/{} seasonType={} — {} game(s)", week, seasonType, games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * NFL games for a specific calendar date.
     *
     * @param date date in YYYYMMDD format, e.g. "20260910"
     */
    @GetMapping("/api/public/nfl/espn/date/{date}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnNflByDate(
            @PathVariable String date) {
        List<Map<String, Object>> games = nflMatchService.getEspnByDate(date);
        log.info("GET /api/public/nfl/espn/date/{} — {} game(s)", date, games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All currently LIVE NFL matches (raw, no odds).
     */
    @GetMapping("/api/nfl/matches/live")
    public ResponseEntity<ApiResponse<List<Match>>> nflLiveMatches() {
        List<Match> live = nflMatchService.getLiveMatches();
        log.info("GET /api/nfl/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    /**
     * NFL matches kicking off today (UTC).
     */
    @GetMapping("/api/nfl/matches/today")
    public ResponseEntity<ApiResponse<List<Match>>> nflTodayMatches() {
        List<Match> today = nflMatchService.getTodayMatches();
        log.info("GET /api/nfl/matches/today — {} match(es) today", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    /**
     * NFL matches scheduled within the next 7 days.
     */
    @GetMapping("/api/nfl/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> nflUpcomingMatches() {
        List<Match> upcoming = nflMatchService.getUpcomingMatches();
        log.info("GET /api/nfl/matches/upcoming — {} upcoming match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    /**
     * FINISHED NFL matches from the past 72 hours.
     */
    @GetMapping("/api/nfl/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> nflRecentResults() {
        List<Match> results = nflMatchService.getRecentResults();
        log.info("GET /api/nfl/matches/results — {} result(s)", results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Single NFL match by internal UUID.
     */
    @GetMapping("/api/nfl/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> nflMatchById(@PathVariable String id) {
        Match match = nflMatchService.getById(id);
        log.info("GET /api/nfl/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    /**
     * Full detail bundle: match + ESPN box score + moneyline odds.
     */
    @GetMapping("/api/nfl/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflMatchDetail(@PathVariable String id) {
        Match match                    = nflMatchService.getById(id);
        Map<String, Object> detail     = nflMatchService.getMatchDetail(id);
        List<Map<String, Object>> odds = nflMatchService.getMoneylineOdds(id);
        log.info("GET /api/nfl/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "match",  match,
                "detail", detail,
                "odds",   odds
        )));
    }

    /**
     * Full ESPN game details: score + box score + player stats + drives.
     *
     * @param espnGameId raw ESPN event ID (without "espn-nfl-" prefix)
     */
    @GetMapping("/api/nfl/matches/espn/{espnGameId}/full")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflFullGameDetail(
            @PathVariable String espnGameId) {
        Map<String, Object> detail = nflMatchService.getEspnGameDetail(espnGameId);
        log.info("GET /api/nfl/matches/espn/{}/full", espnGameId);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    /**
     * Current score snapshot: teams, scores, quarter, clock, possession.
     */
    @GetMapping("/api/nfl/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflMatchScore(@PathVariable String id) {
        Map<String, Object> score = nflMatchService.getMatchScore(id);
        log.info("GET /api/nfl/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ODDS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Live or pre-match moneyline odds (HOME / DRAW / AWAY) for a match.
     */
    @GetMapping("/api/nfl/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> nflMatchOdds(@PathVariable String id) {
        List<Map<String, Object>> odds = nflMatchService.getMoneylineOdds(id);
        log.info("GET /api/nfl/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    /**
     * All odds markets for a match (currently moneyline only).
     */
    @GetMapping("/api/nfl/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflMatchOddsAll(@PathVariable String id) {
        Map<String, Object> odds = nflMatchService.getAllOddsForMatch(id);
        log.info("GET /api/nfl/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    /**
     * All persisted DB odds rows for a match.
     */
    @GetMapping("/api/nfl/matches/{id}/odds/db")
    public ResponseEntity<ApiResponse<List<Odds>>> nflMatchOddsDb(@PathVariable String id) {
        List<Odds> odds = nflMatchService.getPersistedOdds(id);
        log.info("GET /api/nfl/matches/{}/odds/db — {} DB entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDINGS / TEAMS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * AFC + NFC standings from ESPN.
     */
    @GetMapping("/api/nfl/standings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflStandings() {
        Map<String, Object> standings = nflMatchService.getStandings();
        log.info("GET /api/nfl/standings");
        return ResponseEntity.ok(ApiResponse.ok(standings));
    }

    /**
     * All 32 NFL teams.
     */
    @GetMapping("/api/nfl/teams")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflAllTeams() {
        Map<String, Object> teams = nflMatchService.getAllTeams();
        log.info("GET /api/nfl/teams");
        return ResponseEntity.ok(ApiResponse.ok(teams));
    }

    /**
     * Single NFL team by ESPN team ID.
     */
    @GetMapping("/api/nfl/teams/{teamId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflTeamInfo(@PathVariable String teamId) {
        Map<String, Object> team = nflMatchService.getTeamInfo(teamId);
        log.info("GET /api/nfl/teams/{}", teamId);
        return ResponseEntity.ok(ApiResponse.ok(team));
    }

    /**
     * Full season schedule for a team.
     */
    @GetMapping("/api/nfl/teams/{teamId}/schedule")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflTeamSchedule(@PathVariable String teamId) {
        Map<String, Object> schedule = nflMatchService.getTeamSchedule(teamId);
        log.info("GET /api/nfl/teams/{}/schedule", teamId);
        return ResponseEntity.ok(ApiResponse.ok(schedule));
    }

    /**
     * Current roster for a team.
     */
    @GetMapping("/api/nfl/teams/{teamId}/roster")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nflTeamRoster(@PathVariable String teamId) {
        Map<String, Object> roster = nflMatchService.getTeamRoster(teamId);
        log.info("GET /api/nfl/teams/{}/roster", teamId);
        return ResponseEntity.ok(ApiResponse.ok(roster));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ESPN SCOREBOARD PASS-THROUGHS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All games for the current NFL week.
     */
    @GetMapping("/api/nfl/espn/week")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnNflCurrentWeek() {
        List<Map<String, Object>> games = nflMatchService.getEspnCurrentWeek();
        log.info("GET /api/nfl/espn/week — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * NFL games currently in progress — fresh from ESPN, no cache.
     */
    @GetMapping("/api/nfl/espn/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnNflLive() {
        List<Map<String, Object>> games = nflMatchService.getEspnLive();
        log.info("GET /api/nfl/espn/live — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * Upcoming (pre-game) NFL games for the current week.
     */
    @GetMapping("/api/nfl/espn/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnNflUpcoming() {
        List<Map<String, Object>> games = nflMatchService.getEspnUpcoming();
        log.info("GET /api/nfl/espn/upcoming — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * Finished NFL games from the current week.
     */
    @GetMapping("/api/nfl/espn/finished")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnNflFinished() {
        List<Map<String, Object>> games = nflMatchService.getEspnFinished();
        log.info("GET /api/nfl/espn/finished — {} game(s)", games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * NFL games for a specific week and season type.
     *
     * @param week       NFL week number
     * @param seasonType season type (e.g. 2 = regular, 3 = playoff)
     */
    @GetMapping("/api/nfl/espn/week/{week}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnNflByWeek(
            @PathVariable int week,
            @RequestParam(defaultValue = "2") int seasonType) {
        List<Map<String, Object>> games = nflMatchService.getEspnByWeek(week, seasonType);
        log.info("GET /api/nfl/espn/week/{} seasonType={} — {} game(s)", week, seasonType, games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }

    /**
     * NFL games for a specific calendar date.
     *
     * @param date date in YYYYMMDD format, e.g. "20260910"
     */
    @GetMapping("/api/nfl/espn/date/{date}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnNflByDate(
            @PathVariable String date) {
        List<Map<String, Object>> games = nflMatchService.getEspnByDate(date);
        log.info("GET /api/nfl/espn/date/{} — {} game(s)", date, games.size());
        return ResponseEntity.ok(ApiResponse.ok(games));
    }
}
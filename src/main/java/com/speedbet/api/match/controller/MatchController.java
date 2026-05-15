package com.speedbet.api.match.controller;

import com.speedbet.api.ai.MistralClient;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.sportsdata.EspnFootballDataService;
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

    private final MatchService matchService;
    private final MistralClient mistralClient;

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE PREDICTION HELPER
    // ══════════════════════════════════════════════════════════════════════

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

    @GetMapping("/api/public/football/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatches() {
        log.debug("GET /api/public/football/matches");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/football/matches — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     live);
        payload.put("today",    today);
        payload.put("upcoming", upcoming);
        payload.put("future",   future);
        payload.put("results",  results);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/football/matches/with-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchesWithOdds() {
        log.debug("GET /api/public/football/matches/with-odds");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/football/matches/with-odds — live={} today={} upcoming={} future={} results={}",
                live.size(), today.size(), upcoming.size(), future.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     matchService.withOdds(live));
        payload.put("today",    matchService.withOdds(today));
        payload.put("upcoming", matchService.withOdds(upcoming));
        payload.put("future",   matchService.withOdds(future));
        payload.put("results",  matchService.withOdds(results));
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/football/matches/with-all-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchesWithAllOdds() {
        log.debug("GET /api/public/football/matches/with-all-odds");
        List<Match> live     = matchService.getLiveMatches();
        List<Match> today    = matchService.getTodayMatches();
        List<Match> upcoming = matchService.getUpcomingMatches();
        List<Match> future   = matchService.getFutureMatches();
        List<Match> results  = matchService.getRecentResults();
        log.info("GET /api/public/football/matches/with-all-odds — live={} today={} upcoming={} future={} results={}",
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

    @GetMapping("/api/public/football/matches/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLive() {
        List<Match> live = matchService.getLiveMatches();
        log.info("GET /api/public/football/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(live)));
    }

    @GetMapping("/api/public/football/matches/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballToday() {
        List<Match> today = matchService.getTodayMatches();
        log.info("GET /api/public/football/matches/today — {} match(es) today", today.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(today)));
    }

    @GetMapping("/api/public/football/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballUpcoming() {
        List<Match> upcoming = matchService.getUpcomingMatches();
        log.info("GET /api/public/football/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(upcoming)));
    }

    @GetMapping("/api/public/football/matches/future")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballFuture() {
        List<Match> future = matchService.getFutureMatches();
        log.info("GET /api/public/football/matches/future — {} match(es) next 7 days", future.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(future)));
    }

    @GetMapping("/api/public/football/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicFootballResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = matchService.getRecentResultsLimited(limit);
        log.info("GET /api/public/football/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @GetMapping("/api/public/football/matches/featured")
    public ResponseEntity<ApiResponse<List<Match>>> publicFootballFeatured() {
        List<Match> featured = matchService.getFeaturedMatches();
        log.info("GET /api/public/football/matches/featured — {} featured match(es)", featured.size());
        return ResponseEntity.ok(ApiResponse.ok(featured));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> publicFootballMatchById(@PathVariable String id) {
        Match match = matchService.getById(id);
        log.info("GET /api/public/football/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/public/football/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballFullDetail(@PathVariable String id) {
        Match match                    = matchService.getById(id);
        Map<String, Object> detail     = matchService.getMatchDetail(id);
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        Map<String, Object> prediction = resolvePrediction(id);
        log.info("GET /api/public/football/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("match",      match);
        bundle.put("detail",     detail);
        bundle.put("odds",       odds);
        bundle.put("prediction", prediction);
        return ResponseEntity.ok(ApiResponse.ok(bundle));
    }

    @GetMapping("/api/public/football/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchEvents(@PathVariable String id) {
        Map<String, Object> events = matchService.getEvents(id);
        log.info("GET /api/public/football/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping("/api/public/football/matches/{id}/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchStats(@PathVariable String id) {
        Map<String, Object> stats = matchService.getStats(id);
        log.info("GET /api/public/football/matches/{}/stats — source='{}'", id, stats.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping("/api/public/football/matches/{id}/lineups")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchLineups(@PathVariable String id) {
        Map<String, Object> lineups = matchService.getLineups(id);
        log.info("GET /api/public/football/matches/{}/lineups — source='{}'", id, lineups.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(lineups));
    }

    @GetMapping("/api/public/football/matches/{id}/h2h")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchH2H(@PathVariable String id) {
        Map<String, Object> h2h = matchService.getH2H(id);
        log.info("GET /api/public/football/matches/{}/h2h — source='{}'", id, h2h.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(h2h));
    }

    @GetMapping("/api/public/football/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballMatchOdds(@PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getMatchOdds(id);
        log.info("GET /api/public/football/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/raw")
    public ResponseEntity<ApiResponse<List<Odds>>> publicFootballMatchOddsRaw(@PathVariable String id) {
        List<Odds> odds = matchService.getOddsForMatch(id);
        log.info("GET /api/public/football/matches/{}/odds/raw — {} DB record(s)", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchOddsAll(@PathVariable String id) {
        Map<String, Object> odds = matchService.getAllOddsForMatch(id);
        log.info("GET /api/public/football/matches/{}/odds/all — markets={}", id, odds.keySet());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/correct-score")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCorrectScoreOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getCorrectScoreOdds(id);
        log.info("GET /api/public/football/matches/{}/odds/correct-score — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/half-time")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballHalfTimeOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getHalfTimeOdds(id);
        log.info("GET /api/public/football/matches/{}/odds/half-time — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/handicap")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballHandicapOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = matchService.getHandicapOdds(id);
        log.info("GET /api/public/football/matches/{}/odds/handicap — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/espn")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballEspnOdds(@PathVariable String id) {
        Map<String, Object> odds = matchService.getEspnMatchOdds(id);
        log.info("GET /api/public/football/matches/{}/odds/espn — {} provider(s)", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/goalscorer")
    public ResponseEntity<ApiResponse<Map<String, List<Map<String, Object>>>>> publicFootballGoalscorerOdds(
            @PathVariable String id) {
        Map<String, List<Map<String, Object>>> markets = matchService.getEspnGoalscorerOdds(id);
        log.info("GET /api/public/football/matches/{}/odds/goalscorer — {} market(s)", id, markets.size());
        return ResponseEntity.ok(ApiResponse.ok(markets));
    }

    @GetMapping("/api/public/football/matches/{id}/prediction")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballMatchPrediction(@PathVariable String id) {
        Map<String, Object> prediction = resolvePrediction(id);
        log.info("GET /api/public/football/matches/{}/prediction — {} key(s)", id, prediction.size());
        return ResponseEntity.ok(ApiResponse.ok(prediction));
    }

    @GetMapping("/api/public/football/matches/{id}/form")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballRecentForm(@PathVariable String id) {
        List<Map<String, Object>> form = matchService.getEspnRecentForm(id);
        log.info("GET /api/public/football/matches/{}/form — {} team block(s)", id, form.size());
        return ResponseEntity.ok(ApiResponse.ok(form));
    }

    @GetMapping("/api/public/football/matches/{id}/news")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballMatchNews(@PathVariable String id) {
        List<Map<String, Object>> articles = matchService.getEspnMatchNews(id);
        log.info("GET /api/public/football/matches/{}/news — {} article(s)", id, articles.size());
        return ResponseEntity.ok(ApiResponse.ok(articles));
    }

    @GetMapping("/api/public/football/matches/{id}/videos")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballMatchVideos(@PathVariable String id) {
        List<Map<String, Object>> videos = matchService.getEspnMatchVideos(id);
        log.info("GET /api/public/football/matches/{}/videos — {} video(s)", id, videos.size());
        return ResponseEntity.ok(ApiResponse.ok(videos));
    }

    @GetMapping("/api/public/football/matches/{id}/venue")
    public ResponseEntity<ApiResponse<String>> publicFootballMatchVenue(@PathVariable String id) {
        String venue = matchService.getEspnMatchVenue(id);
        log.info("GET /api/public/football/matches/{}/venue — '{}'", id, venue);
        return ResponseEntity.ok(ApiResponse.ok(venue));
    }

    @GetMapping("/api/public/football/matches/{id}/odds/cache-status")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> publicFootballOddsCacheStatus(
            @PathVariable String id) {
        try {
            UUID uuid = UUID.fromString(id);
            Map<String, Boolean> status = new LinkedHashMap<>();
            status.put("odds_valid",     matchService.isOddsCacheValid(uuid));
            status.put("handicap_valid", matchService.isHandicapCacheValid(uuid));
            log.info("GET /api/public/football/matches/{}/odds/cache-status — {}", id, status);
            return ResponseEntity.ok(ApiResponse.ok(status));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid match UUID: " + id);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — TOP-6 LEAGUES (DB-filtered)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/matches/top6/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6Live() {
        List<Match> matches = matchService.getTop6LiveMatches();
        log.info("GET /api/public/football/matches/top6/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/matches/top6/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6Today() {
        List<Match> matches = matchService.getTop6TodayMatches();
        log.info("GET /api/public/football/matches/top6/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/matches/top6/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6Upcoming() {
        List<Match> matches = matchService.getTop6UpcomingMatches();
        log.info("GET /api/public/football/matches/top6/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — TOP-6 CUPS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/matches/cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupsLive() {
        List<Match> matches = matchService.getTop6CupsLiveMatches();
        log.info("GET /api/public/football/matches/cups/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/matches/cups/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupsToday() {
        List<Match> matches = matchService.getTop6CupsTodayMatches();
        log.info("GET /api/public/football/matches/cups/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/matches/cups/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupsUpcoming() {
        List<Match> matches = matchService.getTop6CupsUpcomingMatches();
        log.info("GET /api/public/football/matches/cups/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ALL CUPS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/matches/all-cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballAllCupsLive() {
        List<Match> matches = matchService.getAllCupsLiveMatches();
        log.info("GET /api/public/football/matches/all-cups/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/matches/all-cups/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballAllCupsToday() {
        List<Match> matches = matchService.getAllCupsTodayMatches();
        log.info("GET /api/public/football/matches/all-cups/today — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/matches/all-cups/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballAllCupsUpcoming() {
        List<Match> matches = matchService.getAllCupsUpcomingMatches();
        log.info("GET /api/public/football/matches/all-cups/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY TOP-6 LEAGUE NAME (enum-validated)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/leagues/top6/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6LeagueLive(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Match> matches = matchService.getLiveMatchesByLeagueEnum(league);
        log.info("GET /api/public/football/leagues/top6/{}/live — {} match(es)", league.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/leagues/top6/{league}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6LeagueToday(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Match> matches = matchService.getTodayMatchesByLeagueEnum(league);
        log.info("GET /api/public/football/leagues/top6/{}/today — {} match(es)", league.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/leagues/top6/{league}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6LeagueUpcoming(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Match> matches = matchService.getUpcomingMatchesByLeagueEnum(league);
        log.info("GET /api/public/football/leagues/top6/{}/upcoming — {} match(es)", league.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/leagues/top6/{league}/results/finished")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6LeagueFinished(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Map<String, Object>> finished = matchService.getEspnFinishedMatchesByLeague(league);
        log.info("GET /api/public/football/leagues/top6/{}/results/finished — {} finished match(es)", league.displayName(), finished.size());
        return ResponseEntity.ok(ApiResponse.ok(finished));
    }

    @GetMapping("/api/public/football/leagues/top6/{league}/teams")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballTop6LeagueTeams(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        Map<String, Object> teams = matchService.getEspnTeamsByLeague(league);
        log.info("GET /api/public/football/leagues/top6/{}/teams", league.displayName());
        return ResponseEntity.ok(ApiResponse.ok(teams));
    }

    @GetMapping("/api/public/football/leagues/top6/{league}/teams/{teamId}/schedule")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballTop6TeamSchedule(
            @PathVariable EspnFootballDataService.EspnLeague league,
            @PathVariable String teamId) {
        Map<String, Object> schedule = matchService.getEspnTeamSchedule(league, teamId);
        log.info("GET /api/public/football/leagues/top6/{}/teams/{}/schedule", league.displayName(), teamId);
        return ResponseEntity.ok(ApiResponse.ok(schedule));
    }

    @GetMapping("/api/public/football/leagues/top6/{league}/fixtures/date/{date}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTop6LeagueFixturesByDate(
            @PathVariable EspnFootballDataService.EspnLeague league,
            @PathVariable String date) {
        List<Map<String, Object>> fixtures = matchService.getEspnTop6FixturesByDate(date);
        log.info("GET /api/public/football/leagues/top6/{}/fixtures/date/{} — {} fixture(s)", league.displayName(), date, fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY CUP NAME (enum-validated)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/cups/{cup}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupLive(
            @PathVariable EspnFootballDataService.EspnCup cup) {
        List<Match> matches = matchService.getLiveMatchesByCupEnum(cup);
        log.info("GET /api/public/football/cups/{}/live — {} match(es)", cup.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/cups/{cup}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupToday(
            @PathVariable EspnFootballDataService.EspnCup cup) {
        List<Match> matches = matchService.getTodayMatchesByCupEnum(cup);
        log.info("GET /api/public/football/cups/{}/today — {} match(es)", cup.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/cups/{cup}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupUpcoming(
            @PathVariable EspnFootballDataService.EspnCup cup) {
        List<Match> matches = matchService.getUpcomingMatchesByCupEnum(cup);
        log.info("GET /api/public/football/cups/{}/upcoming — {} match(es)", cup.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/cups/{cup}/results/finished")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupFinished(
            @PathVariable EspnFootballDataService.EspnCup cup) {
        List<Map<String, Object>> finished = matchService.getEspnFinishedMatchesByCup(cup);
        log.info("GET /api/public/football/cups/{}/results/finished — {} finished match(es)", cup.displayName(), finished.size());
        return ResponseEntity.ok(ApiResponse.ok(finished));
    }

    @GetMapping("/api/public/football/cups/{cup}/matches/{eventId}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballCupMatchDetail(
            @PathVariable EspnFootballDataService.EspnCup cup,
            @PathVariable String eventId) {
        Map<String, Object> detail = matchService.getCupMatchDetail(cup, eventId);
        log.info("GET /api/public/football/cups/{}/matches/{}/detail", cup.displayName(), eventId);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @GetMapping("/api/public/football/cups/fixtures/date/{date}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballCupsFixturesByDate(
            @PathVariable String date) {
        List<Map<String, Object>> fixtures = matchService.getEspnTop6CupsFixturesByDate(date);
        log.info("GET /api/public/football/cups/fixtures/date/{} — {} fixture(s)", date, fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY ANY LEAGUE NAME (EspnLeague enum, all leagues)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/leagues/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLeagueLive(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Match> matches = matchService.getLiveMatchesByLeague(league.displayName());
        log.info("GET /api/public/football/leagues/{}/live — {} match(es)", league.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/leagues/{league}/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLeagueToday(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Match> matches = matchService.getTodayMatchesByLeague(league.displayName());
        log.info("GET /api/public/football/leagues/{}/today — {} match(es)", league.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/leagues/{league}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLeagueUpcoming(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Match> matches = matchService.getUpcomingMatchesByLeague(league.displayName());
        log.info("GET /api/public/football/leagues/{}/upcoming — {} match(es)", league.displayName(), matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY TEAM NAME
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/teams/{team}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTeamLive(
            @PathVariable String team) {
        List<Match> matches = matchService.getLiveMatchesByTeamName(team);
        log.info("GET /api/public/football/teams/{}/live — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/teams/{team}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballTeamUpcoming(
            @PathVariable String team) {
        List<Match> matches = matchService.getUpcomingMatchesByTeamName(team);
        log.info("GET /api/public/football/teams/{}/upcoming — {} match(es)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matchService.withOdds(matches)));
    }

    @GetMapping("/api/public/football/teams/{team}/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicFootballTeamResults(@PathVariable String team) {
        List<Match> matches = matchService.getRecentResultsByTeamName(team);
        log.info("GET /api/public/football/teams/{}/results — {} result(s)", team, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDINGS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/standings/top6")
    public ResponseEntity<ApiResponse<Map<String, Map<String, Object>>>> publicFootballTop6Standings() {
        log.info("GET /api/public/football/standings/top6");
        return ResponseEntity.ok(ApiResponse.ok(matchService.getAllTop6Standings()));
    }

    @GetMapping("/api/public/football/standings/leagues/top6/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballStandingsByTop6League(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        log.info("GET /api/public/football/standings/leagues/top6/{}", league.displayName());
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByLeague(league)));
    }

    @GetMapping("/api/public/football/standings/cups/{cup}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballStandingsByCup(
            @PathVariable EspnFootballDataService.EspnCup cup) {
        log.info("GET /api/public/football/standings/cups/{}", cup.displayName());
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByCup(cup)));
    }

    @GetMapping("/api/public/football/standings/leagues/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballStandingsByLeagueComp(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        log.info("GET /api/public/football/standings/leagues/{}", league.displayName());
        return ResponseEntity.ok(ApiResponse.ok(matchService.getStandingsByLeague(league)));
    }

    @GetMapping("/api/public/football/standings/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballStandings(
            @PathVariable int competitionId) {
        log.info("GET /api/public/football/standings/{}", competitionId);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getAllTop6Standings()
                .getOrDefault(String.valueOf(competitionId), Map.of())));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SCORERS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/scorers/leagues/top6/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballScorersByTop6League(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        log.info("GET /api/public/football/scorers/leagues/top6/{}", league.displayName());
        return ResponseEntity.ok(ApiResponse.ok(matchService.getTopScorersByLeague(league)));
    }

    @GetMapping("/api/public/football/scorers/leagues/{league}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballScorersByLeagueComp(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        log.info("GET /api/public/football/scorers/leagues/{}", league.displayName());
        return ResponseEntity.ok(ApiResponse.ok(matchService.getTopScorersByLeague(league)));
    }

    @GetMapping("/api/public/football/scorers/{competitionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicFootballTopScorers(
            @PathVariable int competitionId) {
        log.info("GET /api/public/football/scorers/{}", competitionId);
        return ResponseEntity.ok(ApiResponse.ok(matchService.getTopScorersByLeague(
                EspnFootballDataService.EspnLeague.PREMIER_LEAGUE)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — LIVESCORE (ESPN pass-through)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/football/livescore/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreLive() {
        List<Map<String, Object>> live = matchService.getEspnFootballLive();
        log.info("GET /api/public/football/livescore/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/football/livescore/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreToday() {
        List<Map<String, Object>> today = matchService.getEspnFootballToday();
        log.info("GET /api/public/football/livescore/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/public/football/livescore/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreFixtures() {
        List<Map<String, Object>> fixtures = matchService.getEspnFootballFixtures();
        log.info("GET /api/public/football/livescore/fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/public/football/livescore/top6/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreTop6Live() {
        List<Map<String, Object>> live = matchService.getEspnFootballTop6Live();
        log.info("GET /api/public/football/livescore/top6/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/football/livescore/top6/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreTop6Fixtures() {
        List<Map<String, Object>> fixtures = matchService.getEspnFootballTop6Fixtures();
        log.info("GET /api/public/football/livescore/top6/fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/public/football/livescore/top6/all-fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreTop6AndCupFixtures() {
        List<Map<String, Object>> fixtures = matchService.getEspnFootballTop6AndCupFixtures();
        log.info("GET /api/public/football/livescore/top6/all-fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/public/football/livescore/all-leagues/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballAllLeaguesToday() {
        List<Map<String, Object>> today = matchService.getEspnAllLeaguesTodayMatches();
        log.info("GET /api/public/football/livescore/all-leagues/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/public/football/livescore/all-cups/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballAllCupsTodayLivescore() {
        List<Map<String, Object>> today = matchService.getEspnAllCupsTodayMatches();
        log.info("GET /api/public/football/livescore/all-cups/today — {} match(es)", today.size());
        return ResponseEntity.ok(ApiResponse.ok(today));
    }

    @GetMapping("/api/public/football/livescore/cups/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreCupsLive() {
        List<Map<String, Object>> live = matchService.getEspnFootballTop6CupsLive();
        log.info("GET /api/public/football/livescore/cups/live — {} match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/football/livescore/cups/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreCupsFixtures() {
        List<Map<String, Object>> fixtures = matchService.getEspnFootballTop6CupFixtures();
        log.info("GET /api/public/football/livescore/cups/fixtures — {} fixture(s)", fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/public/football/livescore/leagues/top6/{league}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreTop6LeagueLive(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Map<String, Object>> live = matchService.getEspnFootballLiveByLeague(league);
        log.info("GET /api/public/football/livescore/leagues/top6/{}/live — {} match(es)", league.displayName(), live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/football/livescore/leagues/top6/{league}/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreTop6LeagueFixtures(
            @PathVariable EspnFootballDataService.EspnLeague league) {
        List<Map<String, Object>> fixtures = matchService.getEspnFootballFixturesByLeague(league);
        log.info("GET /api/public/football/livescore/leagues/top6/{}/fixtures — {} fixture(s)", league.displayName(), fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }

    @GetMapping("/api/public/football/livescore/cups/{cup}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreCupLive(
            @PathVariable EspnFootballDataService.EspnCup cup) {
        List<Map<String, Object>> live = matchService.getEspnFootballLiveByCup(cup);
        log.info("GET /api/public/football/livescore/cups/{}/live — {} match(es)", cup.displayName(), live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/football/livescore/cups/{cup}/fixtures")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicFootballLiveScoreCupFixtures(
            @PathVariable EspnFootballDataService.EspnCup cup) {
        List<Map<String, Object>> fixtures = matchService.getEspnFootballFixturesByCup(cup);
        log.info("GET /api/public/football/livescore/cups/{}/fixtures — {} fixture(s)", cup.displayName(), fixtures.size());
        return ResponseEntity.ok(ApiResponse.ok(fixtures));
    }
}
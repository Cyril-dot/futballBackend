package com.speedbet.api.match.controller;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.TennisMatchService;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.sportsdata.TennisDataService;
import com.speedbet.api.sportsdata.TennisDataService.Tour;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TennisMatchController {

    private final TennisMatchService tennisMatchService;

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE ENUM RESOLVER
    // ══════════════════════════════════════════════════════════════════════

    /** Resolves a path-variable string to a {@link Tour} enum or 400. */
    private Tour resolveTour(String tour) {
        return Arrays.stream(Tour.values())
                .filter(t -> t.displayName().equalsIgnoreCase(tour))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Unknown tennis tour: '" + tour + "'. Valid values: " +
                                Arrays.stream(Tour.values())
                                        .map(Tour::displayName)
                                        .toList()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — LOBBY (all buckets in one response)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/tennis/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTennisMatches() {
        log.debug("GET /api/public/tennis/matches");
        List<Match> live     = tennisMatchService.getLiveMatches();
        List<Match> upcoming = tennisMatchService.getUpcomingMatches();
        List<Match> results  = tennisMatchService.getRecentResults();
        log.info("GET /api/public/tennis/matches — live={} upcoming={} results={}",
                live.size(), upcoming.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     live);
        payload.put("upcoming", upcoming);
        payload.put("results",  results);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/api/public/tennis/matches/with-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTennisMatchesWithOdds() {
        log.debug("GET /api/public/tennis/matches/with-odds");
        List<Match> live     = tennisMatchService.getLiveMatches();
        List<Match> upcoming = tennisMatchService.getUpcomingMatches();
        List<Match> results  = tennisMatchService.getRecentResults();
        log.info("GET /api/public/tennis/matches/with-odds — live={} upcoming={} results={}",
                live.size(), upcoming.size(), results.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("live",     tennisMatchService.withOdds(live));
        payload.put("upcoming", tennisMatchService.withOdds(upcoming));
        payload.put("results",  results);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/tennis/matches/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTennisLive() {
        List<Match> live = tennisMatchService.getLiveMatches();
        log.info("GET /api/public/tennis/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(live)));
    }

    @GetMapping("/api/public/tennis/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTennisUpcoming() {
        List<Match> upcoming = tennisMatchService.getUpcomingMatches();
        log.info("GET /api/public/tennis/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(upcoming)));
    }

    @GetMapping("/api/public/tennis/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicTennisResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = tennisMatchService.getRecentResults(limit);
        log.info("GET /api/public/tennis/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @GetMapping("/api/public/tennis/matches/featured")
    public ResponseEntity<ApiResponse<List<Match>>> publicTennisFeatured() {
        List<Match> featured = tennisMatchService.getFeaturedMatches();
        log.info("GET /api/public/tennis/matches/featured — {} featured match(es)", featured.size());
        return ResponseEntity.ok(ApiResponse.ok(featured));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — BY TOUR (ATP / WTA)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/tennis/tours/{tour}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTourLive(
            @PathVariable String tour) {
        Tour tourEnum = resolveTour(tour);
        List<Match> matches = tennisMatchService.getLiveMatches(tourEnum);
        log.info("GET /api/public/tennis/tours/{}/live — {} match(es)", tour, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(matches)));
    }

    @GetMapping("/api/public/tennis/tours/{tour}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTourUpcoming(
            @PathVariable String tour) {
        Tour tourEnum = resolveTour(tour);
        List<Match> matches = tennisMatchService.getUpcomingMatches(tourEnum);
        log.info("GET /api/public/tennis/tours/{}/upcoming — {} match(es)", tour, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/tennis/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> publicTennisMatchById(@PathVariable String id) {
        Match match = tennisMatchService.getById(id);
        log.info("GET /api/public/tennis/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/public/tennis/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTennisMatchDetail(
            @PathVariable String id) {
        Match match                    = tennisMatchService.getById(id);
        Map<String, Object> detail     = tennisMatchService.getMatchDetail(id);
        List<Map<String, Object>> odds = tennisMatchService.getMatchOdds(id);
        log.info("GET /api/public/tennis/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("match",  match);
        bundle.put("detail", detail);
        bundle.put("odds",   odds);
        return ResponseEntity.ok(ApiResponse.ok(bundle));
    }

    @GetMapping("/api/public/tennis/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTennisMatchScore(
            @PathVariable String id) {
        Map<String, Object> score = tennisMatchService.getMatchScore(id);
        log.info("GET /api/public/tennis/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    @GetMapping("/api/public/tennis/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTennisMatchEvents(
            @PathVariable String id) {
        Map<String, Object> events = tennisMatchService.getEvents(id);
        log.info("GET /api/public/tennis/matches/{}/events", id);
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping("/api/public/tennis/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicTennisMatchOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = tennisMatchService.getMatchOdds(id);
        log.info("GET /api/public/tennis/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ESPN SCOREBOARD PASS-THROUGHS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/public/tennis/atp/tournaments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAtpTournaments() {
        List<Map<String, Object>> tournaments = tennisMatchService.getAtpTournaments();
        log.info("GET /api/public/tennis/atp/tournaments — {} tournament(s)", tournaments.size());
        return ResponseEntity.ok(ApiResponse.ok(tournaments));
    }

    @GetMapping("/api/public/tennis/wta/tournaments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicWtaTournaments() {
        List<Map<String, Object>> tournaments = tennisMatchService.getWtaTournaments();
        log.info("GET /api/public/tennis/wta/tournaments — {} tournament(s)", tournaments.size());
        return ResponseEntity.ok(ApiResponse.ok(tournaments));
    }

    @GetMapping("/api/public/tennis/atp/matches")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAllAtpMatches() {
        List<Map<String, Object>> matches = tennisMatchService.getAllAtpMatches();
        log.info("GET /api/public/tennis/atp/matches — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/api/public/tennis/wta/matches")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAllWtaMatches() {
        List<Map<String, Object>> matches = tennisMatchService.getAllWtaMatches();
        log.info("GET /api/public/tennis/wta/matches — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/api/public/tennis/atp/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAtpLive() {
        List<Map<String, Object>> live = tennisMatchService.getAtpLiveMatches();
        log.info("GET /api/public/tennis/atp/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/tennis/wta/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicWtaLive() {
        List<Map<String, Object>> live = tennisMatchService.getWtaLiveMatches();
        log.info("GET /api/public/tennis/wta/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/public/tennis/atp/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicAtpUpcoming() {
        List<Map<String, Object>> upcoming = tennisMatchService.getAtpUpcomingMatches();
        log.info("GET /api/public/tennis/atp/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/public/tennis/wta/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicWtaUpcoming() {
        List<Map<String, Object>> upcoming = tennisMatchService.getWtaUpcomingMatches();
        log.info("GET /api/public/tennis/wta/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/public/tennis/atp/rankings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicAtpRankings() {
        Map<String, Object> rankings = tennisMatchService.getAtpRankings();
        log.info("GET /api/public/tennis/atp/rankings");
        return ResponseEntity.ok(ApiResponse.ok(rankings));
    }

    @GetMapping("/api/public/tennis/wta/rankings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicWtaRankings() {
        Map<String, Object> rankings = tennisMatchService.getWtaRankings();
        log.info("GET /api/public/tennis/wta/rankings");
        return ResponseEntity.ok(ApiResponse.ok(rankings));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/tennis/matches/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> tennisLiveMatches() {
        List<Match> live = tennisMatchService.getLiveMatches();
        log.info("GET /api/tennis/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(live)));
    }

    @GetMapping("/api/tennis/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> tennisUpcomingMatches() {
        List<Match> upcoming = tennisMatchService.getUpcomingMatches();
        log.info("GET /api/tennis/matches/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(upcoming)));
    }

    @GetMapping("/api/tennis/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> tennisRecentResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = tennisMatchService.getRecentResults(limit);
        log.info("GET /api/tennis/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @GetMapping("/api/tennis/matches/featured")
    public ResponseEntity<ApiResponse<List<Match>>> tennisFeaturedMatches() {
        List<Match> featured = tennisMatchService.getFeaturedMatches();
        log.info("GET /api/tennis/matches/featured — {} featured match(es)", featured.size());
        return ResponseEntity.ok(ApiResponse.ok(featured));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — BY TOUR (ATP / WTA)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/tennis/tours/{tour}/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> tourLive(
            @PathVariable String tour) {
        Tour tourEnum = resolveTour(tour);
        List<Match> matches = tennisMatchService.getLiveMatches(tourEnum);
        log.info("GET /api/tennis/tours/{}/live — {} match(es)", tour, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(matches)));
    }

    @GetMapping("/api/tennis/tours/{tour}/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> tourUpcoming(
            @PathVariable String tour) {
        Tour tourEnum = resolveTour(tour);
        List<Match> matches = tennisMatchService.getUpcomingMatches(tourEnum);
        log.info("GET /api/tennis/tours/{}/upcoming — {} match(es)", tour, matches.size());
        return ResponseEntity.ok(ApiResponse.ok(tennisMatchService.withOdds(matches)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/tennis/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> tennisMatchById(@PathVariable String id) {
        Match match = tennisMatchService.getById(id);
        log.info("GET /api/tennis/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/api/tennis/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tennisMatchDetail(
            @PathVariable String id) {
        Match match                    = tennisMatchService.getById(id);
        Map<String, Object> detail     = tennisMatchService.getMatchDetail(id);
        List<Map<String, Object>> odds = tennisMatchService.getMatchOdds(id);
        log.info("GET /api/tennis/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("match",  match);
        bundle.put("detail", detail);
        bundle.put("odds",   odds);
        return ResponseEntity.ok(ApiResponse.ok(bundle));
    }

    @GetMapping("/api/tennis/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tennisMatchScore(
            @PathVariable String id) {
        Map<String, Object> score = tennisMatchService.getMatchScore(id);
        log.info("GET /api/tennis/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    @GetMapping("/api/tennis/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tennisMatchEvents(
            @PathVariable String id) {
        Map<String, Object> events = tennisMatchService.getEvents(id);
        log.info("GET /api/tennis/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    @GetMapping("/api/tennis/matches/{id}/full-detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tennisMatchFullDetail(
            @PathVariable String id) {
        Map<String, Object> detail = tennisMatchService.getFullMatchDetails(id);
        log.info("GET /api/tennis/matches/{}/full-detail", id);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ODDS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/tennis/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> tennisMatchOdds(
            @PathVariable String id) {
        List<Map<String, Object>> odds = tennisMatchService.getMatchOdds(id);
        log.info("GET /api/tennis/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/api/tennis/matches/{id}/odds/db")
    public ResponseEntity<ApiResponse<List<Odds>>> tennisMatchOddsDb(@PathVariable String id) {
        List<Odds> odds = tennisMatchService.getOddsForMatch(id);
        log.info("GET /api/tennis/matches/{}/odds/db — {} DB odds entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ESPN SCOREBOARD PASS-THROUGHS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/tennis/atp/tournaments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> atpTournaments() {
        List<Map<String, Object>> tournaments = tennisMatchService.getAtpTournaments();
        log.info("GET /api/tennis/atp/tournaments — {} tournament(s)", tournaments.size());
        return ResponseEntity.ok(ApiResponse.ok(tournaments));
    }

    @GetMapping("/api/tennis/wta/tournaments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> wtaTournaments() {
        List<Map<String, Object>> tournaments = tennisMatchService.getWtaTournaments();
        log.info("GET /api/tennis/wta/tournaments — {} tournament(s)", tournaments.size());
        return ResponseEntity.ok(ApiResponse.ok(tournaments));
    }

    @GetMapping("/api/tennis/atp/matches")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> allAtpMatches() {
        List<Map<String, Object>> matches = tennisMatchService.getAllAtpMatches();
        log.info("GET /api/tennis/atp/matches — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/api/tennis/wta/matches")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> allWtaMatches() {
        List<Map<String, Object>> matches = tennisMatchService.getAllWtaMatches();
        log.info("GET /api/tennis/wta/matches — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/api/tennis/atp/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> atpLive() {
        List<Map<String, Object>> live = tennisMatchService.getAtpLiveMatches();
        log.info("GET /api/tennis/atp/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/tennis/wta/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> wtaLive() {
        List<Map<String, Object>> live = tennisMatchService.getWtaLiveMatches();
        log.info("GET /api/tennis/wta/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    @GetMapping("/api/tennis/atp/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> atpUpcoming() {
        List<Map<String, Object>> upcoming = tennisMatchService.getAtpUpcomingMatches();
        log.info("GET /api/tennis/atp/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/tennis/wta/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> wtaUpcoming() {
        List<Map<String, Object>> upcoming = tennisMatchService.getWtaUpcomingMatches();
        log.info("GET /api/tennis/wta/upcoming — {} match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    @GetMapping("/api/tennis/atp/rankings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> atpRankings() {
        Map<String, Object> rankings = tennisMatchService.getAtpRankings();
        log.info("GET /api/tennis/atp/rankings");
        return ResponseEntity.ok(ApiResponse.ok(rankings));
    }

    @GetMapping("/api/tennis/wta/rankings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> wtaRankings() {
        Map<String, Object> rankings = tennisMatchService.getWtaRankings();
        log.info("GET /api/tennis/wta/rankings");
        return ResponseEntity.ok(ApiResponse.ok(rankings));
    }
}
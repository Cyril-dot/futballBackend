package com.speedbet.api.match.controller;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.MmaMatchService;
import com.speedbet.api.odds.Odds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for MMA (sport = "mma") match data.
 *
 * ── Route conventions ─────────────────────────────────────────────────────
 *
 *   Public (no auth):     /api/public/mma/...
 *   Authenticated:        /api/mma/...
 *
 * ── Buckets ───────────────────────────────────────────────────────────────
 *
 *   live      – LIVE matches
 *   upcoming  – SCHEDULED / UPCOMING within 7 days
 *   results   – FINISHED within 72 hours
 *   featured  – flagged featured = true
 *
 * ── Odds ──────────────────────────────────────────────────────────────────
 *
 *   LIVE matches  → live moneyline cache (refreshed every 2 min by poller)
 *   UPCOMING      → pre-match deterministic odds
 *   FINISHED      → empty list
 *
 * ── ESPN pass-throughs ────────────────────────────────────────────────────
 *
 *   Raw ESPN event data (scoreboard, fight cards, fighter profiles, etc.)
 *   are forwarded directly from {@link MmaMatchService} with no
 *   additional transformation.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MmaMatchController {

    private final MmaMatchService mmaMatchService;

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — LOBBY (all buckets in one response)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All MMA buckets in a single response — live, upcoming, results.
     * Mirrors {@code /api/public/matches}.
     */
    @GetMapping("/api/public/mma/matches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMmaMatches() {
        log.debug("GET /api/public/mma/matches");
        List<Match> live     = mmaMatchService.getLiveMatches();
        List<Match> upcoming = mmaMatchService.getUpcomingMatches();
        List<Match> results  = mmaMatchService.getRecentResults();
        log.info("GET /api/public/mma/matches — live={} upcoming={} results={}",
                live.size(), upcoming.size(), results.size());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "live",     live,
                "upcoming", upcoming,
                "results",  results
        )));
    }

    /**
     * All MMA buckets with moneyline odds attached.
     * Mirrors {@code /api/public/matches/with-odds}.
     */
    @GetMapping("/api/public/mma/matches/with-odds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMmaMatchesWithOdds() {
        log.debug("GET /api/public/mma/matches/with-odds");
        List<Match> live     = mmaMatchService.getLiveMatches();
        List<Match> upcoming = mmaMatchService.getUpcomingMatches();
        List<Match> results  = mmaMatchService.getRecentResults();
        log.info("GET /api/public/mma/matches/with-odds — live={} upcoming={} results={}",
                live.size(), upcoming.size(), results.size());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "live",     mmaMatchService.withOdds(live),
                "upcoming", mmaMatchService.withOdds(upcoming),
                "results",  results
        )));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All currently LIVE MMA matches with moneyline odds.
     */
    @GetMapping("/api/public/mma/matches/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicMmaLive() {
        List<Match> live = mmaMatchService.getLiveMatches();
        log.info("GET /api/public/mma/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(mmaMatchService.withOdds(live)));
    }

    /**
     * MMA matches scheduled within the next 7 days with pre-match odds.
     */
    @GetMapping("/api/public/mma/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicMmaUpcoming() {
        List<Match> upcoming = mmaMatchService.getUpcomingMatches();
        log.info("GET /api/public/mma/matches/upcoming — {} upcoming match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(mmaMatchService.withOdds(upcoming)));
    }

    /**
     * FINISHED MMA matches from the past 72 hours (default cap = 20).
     */
    @GetMapping("/api/public/mma/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> publicMmaResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = mmaMatchService.getRecentResults(limit);
        log.info("GET /api/public/mma/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    /**
     * Featured MMA matches (flagged in DB).
     */
    @GetMapping("/api/public/mma/matches/featured")
    public ResponseEntity<ApiResponse<List<Match>>> publicMmaFeatured() {
        List<Match> featured = mmaMatchService.getFeaturedMatches();
        log.info("GET /api/public/mma/matches/featured — {} featured match(es)", featured.size());
        return ResponseEntity.ok(ApiResponse.ok(featured));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A single MMA match by its internal UUID.
     */
    @GetMapping("/api/public/mma/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> publicMmaMatchById(@PathVariable String id) {
        Match match = mmaMatchService.getById(id);
        log.info("GET /api/public/mma/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    /**
     * Full detail bundle for a single MMA match: match row + ESPN event data + odds.
     */
    @GetMapping("/api/public/mma/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMmaMatchDetail(@PathVariable String id) {
        Match match                    = mmaMatchService.getById(id);
        Map<String, Object> detail     = mmaMatchService.getMatchDetail(id);
        List<Map<String, Object>> odds = mmaMatchService.getMatchOdds(id);
        log.info("GET /api/public/mma/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "match",  match,
                "detail", detail,
                "odds",   odds
        )));
    }

    /**
     * Moneyline odds for a single MMA match (live cache or pre-match generated).
     */
    @GetMapping("/api/public/mma/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicMmaMatchOdds(@PathVariable String id) {
        List<Map<String, Object>> odds = mmaMatchService.getMatchOdds(id);
        log.info("GET /api/public/mma/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    /**
     * All persisted {@link Odds} rows for a match (all markets).
     */
    @GetMapping("/api/public/mma/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<List<Odds>>> publicMmaMatchOddsAll(@PathVariable String id) {
        List<Odds> odds = mmaMatchService.getOddsForMatch(id);
        log.info("GET /api/public/mma/matches/{}/odds/all — {} DB entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    /**
     * Match-level events / metadata (falls back to ESPN summary if DB metadata absent).
     */
    @GetMapping("/api/public/mma/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMmaMatchEvents(@PathVariable String id) {
        Map<String, Object> events = mmaMatchService.getEvents(id);
        log.info("GET /api/public/mma/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — FIGHT CARD / EVENT DATA
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Compiled fight card for the UFC event containing this match.
     * Each entry includes boutOrder, fighters, weight class, state, result.
     */
    @GetMapping("/api/public/mma/matches/{id}/fight-card")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicMmaFightCard(@PathVariable String id) {
        List<Map<String, Object>> card = mmaMatchService.getEventFightCard(id);
        log.info("GET /api/public/mma/matches/{}/fight-card — {} bout(s)", id, card.size());
        return ResponseEntity.ok(ApiResponse.ok(card));
    }

    /**
     * Quick event snapshot: name, venue, main event fighters, state.
     */
    @GetMapping("/api/public/mma/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMmaEventScore(@PathVariable String id) {
        Map<String, Object> score = mmaMatchService.getEventScore(id);
        log.info("GET /api/public/mma/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    /**
     * Combined event details: score snapshot + full fight card + live/pre odds.
     */
    @GetMapping("/api/public/mma/matches/{id}/full")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMmaFullEventDetails(@PathVariable String id) {
        Map<String, Object> full = mmaMatchService.getFullEventDetails(id);
        log.info("GET /api/public/mma/matches/{}/full", id);
        return ResponseEntity.ok(ApiResponse.ok(full));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — FIGHTER INFO
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fighter profile by ESPN athlete ID — record, weight class, headshot, etc.
     */
    @GetMapping("/api/public/mma/fighters/{athleteId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicMmaFighterInfo(
            @PathVariable String athleteId) {
        Map<String, Object> info = mmaMatchService.getFighterInfo(athleteId);
        log.info("GET /api/public/mma/fighters/{}", athleteId);
        return ResponseEntity.ok(ApiResponse.ok(info));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC — ESPN SCOREBOARD PASS-THROUGHS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All UFC events visible on ESPN's scoreboard.
     */
    @GetMapping("/api/public/mma/espn/events")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnMmaEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaEvents();
        log.info("GET /api/public/mma/espn/events — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    /**
     * UFC events currently in progress — fresh, no cache.
     */
    @GetMapping("/api/public/mma/espn/events/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnMmaLiveEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaLiveEvents();
        log.info("GET /api/public/mma/espn/events/live — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    /**
     * UFC events that have not yet started.
     */
    @GetMapping("/api/public/mma/espn/events/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnMmaUpcomingEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaUpcomingEvents();
        log.info("GET /api/public/mma/espn/events/upcoming — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    /**
     * Recently completed UFC events.
     */
    @GetMapping("/api/public/mma/espn/events/finished")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> publicEspnMmaFinishedEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaFinishedEvents();
        log.info("GET /api/public/mma/espn/events/finished — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — STANDARD MATCH BUCKETS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All currently LIVE MMA matches (raw, no odds).
     */
    @GetMapping("/api/mma/matches/live")
    public ResponseEntity<ApiResponse<List<Match>>> mmaLiveMatches() {
        List<Match> live = mmaMatchService.getLiveMatches();
        log.info("GET /api/mma/matches/live — {} live match(es)", live.size());
        return ResponseEntity.ok(ApiResponse.ok(live));
    }

    /**
     * MMA matches scheduled within the next 7 days (raw, no odds).
     */
    @GetMapping("/api/mma/matches/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> mmaUpcomingMatches() {
        List<Match> upcoming = mmaMatchService.getUpcomingMatches();
        log.info("GET /api/mma/matches/upcoming — {} upcoming match(es)", upcoming.size());
        return ResponseEntity.ok(ApiResponse.ok(upcoming));
    }

    /**
     * FINISHED MMA matches from the past 72 hours.
     */
    @GetMapping("/api/mma/matches/results")
    public ResponseEntity<ApiResponse<List<Match>>> mmaRecentResults(
            @RequestParam(defaultValue = "20") int limit) {
        List<Match> results = mmaMatchService.getRecentResults(limit);
        log.info("GET /api/mma/matches/results?limit={} — {} result(s)", limit, results.size());
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    /**
     * Featured MMA matches.
     */
    @GetMapping("/api/mma/matches/featured")
    public ResponseEntity<ApiResponse<List<Match>>> mmaFeaturedMatches() {
        List<Match> featured = mmaMatchService.getFeaturedMatches();
        log.info("GET /api/mma/matches/featured — {} featured match(es)", featured.size());
        return ResponseEntity.ok(ApiResponse.ok(featured));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — SINGLE MATCH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Single MMA match by internal UUID.
     */
    @GetMapping("/api/mma/matches/{id}")
    public ResponseEntity<ApiResponse<Match>> mmaMatchById(@PathVariable String id) {
        Match match = mmaMatchService.getById(id);
        log.info("GET /api/mma/matches/{} — '{}' vs '{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    /**
     * Full detail bundle: match + ESPN event data + live or pre-match odds.
     */
    @GetMapping("/api/mma/matches/{id}/detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mmaMatchDetail(@PathVariable String id) {
        Match match                    = mmaMatchService.getById(id);
        Map<String, Object> detail     = mmaMatchService.getMatchDetail(id);
        List<Map<String, Object>> odds = mmaMatchService.getMatchOdds(id);
        log.info("GET /api/mma/matches/{}/detail — '{}' vs '{}'",
                id, match.getHomeTeam(), match.getAwayTeam());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "match",  match,
                "detail", detail,
                "odds",   odds
        )));
    }

    /**
     * Match events / metadata (ESPN summary fallback if DB metadata absent).
     */
    @GetMapping("/api/mma/matches/{id}/events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mmaMatchEvents(@PathVariable String id) {
        Map<String, Object> events = mmaMatchService.getEvents(id);
        log.info("GET /api/mma/matches/{}/events — source='{}'", id, events.get("source"));
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ODDS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Live or pre-match moneyline odds for a match.
     */
    @GetMapping("/api/mma/matches/{id}/odds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> mmaMatchOdds(@PathVariable String id) {
        List<Map<String, Object>> odds = mmaMatchService.getMatchOdds(id);
        log.info("GET /api/mma/matches/{}/odds — {} entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    /**
     * All persisted DB odds rows for a match.
     */
    @GetMapping("/api/mma/matches/{id}/odds/all")
    public ResponseEntity<ApiResponse<List<Odds>>> mmaMatchOddsDb(@PathVariable String id) {
        List<Odds> odds = mmaMatchService.getOddsForMatch(id);
        log.info("GET /api/mma/matches/{}/odds/all — {} DB entry/entries", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — FIGHT CARD / EVENT DATA
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Compiled fight card for the UFC event containing this match.
     */
    @GetMapping("/api/mma/matches/{id}/fight-card")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> mmaFightCard(@PathVariable String id) {
        List<Map<String, Object>> card = mmaMatchService.getEventFightCard(id);
        log.info("GET /api/mma/matches/{}/fight-card — {} bout(s)", id, card.size());
        return ResponseEntity.ok(ApiResponse.ok(card));
    }

    /**
     * Quick event snapshot: name, venue, main event fighters, state.
     */
    @GetMapping("/api/mma/matches/{id}/score")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mmaEventScore(@PathVariable String id) {
        Map<String, Object> score = mmaMatchService.getEventScore(id);
        log.info("GET /api/mma/matches/{}/score", id);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    /**
     * Combined full event details: score + fight card + odds.
     */
    @GetMapping("/api/mma/matches/{id}/full")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mmaFullEventDetails(@PathVariable String id) {
        Map<String, Object> full = mmaMatchService.getFullEventDetails(id);
        log.info("GET /api/mma/matches/{}/full", id);
        return ResponseEntity.ok(ApiResponse.ok(full));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — FIGHTER INFO
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Fighter profile by ESPN athlete ID.
     */
    @GetMapping("/api/mma/fighters/{athleteId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mmaFighterInfo(
            @PathVariable String athleteId) {
        Map<String, Object> info = mmaMatchService.getFighterInfo(athleteId);
        log.info("GET /api/mma/fighters/{}", athleteId);
        return ResponseEntity.ok(ApiResponse.ok(info));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUTHENTICATED — ESPN SCOREBOARD PASS-THROUGHS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All UFC events from ESPN's scoreboard (cached).
     */
    @GetMapping("/api/mma/espn/events")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnMmaEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaEvents();
        log.info("GET /api/mma/espn/events — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    /**
     * UFC events currently in progress — fresh from ESPN.
     */
    @GetMapping("/api/mma/espn/events/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnMmaLiveEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaLiveEvents();
        log.info("GET /api/mma/espn/events/live — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    /**
     * Upcoming UFC events (not yet started).
     */
    @GetMapping("/api/mma/espn/events/upcoming")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnMmaUpcomingEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaUpcomingEvents();
        log.info("GET /api/mma/espn/events/upcoming — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }

    /**
     * Recently completed UFC events.
     */
    @GetMapping("/api/mma/espn/events/finished")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> espnMmaFinishedEvents() {
        List<Map<String, Object>> events = mmaMatchService.getEspnMmaFinishedEvents();
        log.info("GET /api/mma/espn/events/finished — {} event(s)", events.size());
        return ResponseEntity.ok(ApiResponse.ok(events));
    }
}
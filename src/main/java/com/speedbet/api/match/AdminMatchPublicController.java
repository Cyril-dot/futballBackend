package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/public/admin-matches")
@RequiredArgsConstructor
public class AdminMatchPublicController {

    private final MatchRepository matchRepo;
    private final OddsRepository  oddsRepo;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Match>>> allAdminMatches() {
        List<Match> matches = matchRepo.findBySourceOrderByKickoffAtDesc(MatchSource.ADMIN_CREATED);
        log.info("GET /api/public/admin-matches — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/live")
    public ResponseEntity<ApiResponse<List<Match>>> liveAdminMatches() {
        List<Match> matches = matchRepo.findBySourceAndStatus(MatchSource.ADMIN_CREATED, "LIVE");
        log.info("GET /api/public/admin-matches/live — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/upcoming")
    public ResponseEntity<ApiResponse<List<Match>>> upcomingAdminMatches() {
        List<Match> matches = matchRepo.findBySourceAndStatus(MatchSource.ADMIN_CREATED, "SCHEDULED");
        log.info("GET /api/public/admin-matches/upcoming — {} match(es)", matches.size());
        return ResponseEntity.ok(ApiResponse.ok(matches));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Match>> adminMatchById(@PathVariable UUID id) {
        Match match = matchRepo.findById(id)
                .filter(m -> m.getSource() == MatchSource.ADMIN_CREATED)
                .orElseThrow(() -> new ApiException("Match not found: " + id, HttpStatus.NOT_FOUND));
        log.info("GET /api/public/admin-matches/{} — home='{}' away='{}' status={}",
                id, match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(match));
    }

    @GetMapping("/{id}/odds")
    public ResponseEntity<ApiResponse<List<Odds>>> adminMatchOdds(@PathVariable UUID id) {
        ensureAdminMatch(id);
        List<Odds> odds = oddsRepo.findByMatchId(id);
        log.info("GET /api/public/admin-matches/{}/odds — {} row(s)", id, odds.size());
        return ResponseEntity.ok(ApiResponse.ok(odds));
    }

    @GetMapping("/{id}/odds/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminMatchOddsAll(@PathVariable UUID id) {
        ensureAdminMatch(id);

        List<Odds> all = oddsRepo.findByMatchId(id);
        log.info("GET /api/public/admin-matches/{}/odds/all — {} total rows from DB", id, all.size());

        Map<String, List<Map<String, Object>>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        Odds::getMarket,
                        Collectors.mapping(AdminMatchPublicController::oddsToMap, Collectors.toList())
                ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("match_result",   grouped.getOrDefault("1X2",           List.of()));
        result.put("half_time",      grouped.getOrDefault("half_time",      List.of()));
        result.put("asian_handicap", grouped.getOrDefault("asian_handicap", List.of()));
        result.put("correct_score",  grouped.getOrDefault("correct_score",  List.of()));

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void ensureAdminMatch(UUID id) {
        matchRepo.findById(id)
                .filter(m -> m.getSource() == MatchSource.ADMIN_CREATED)
                .orElseThrow(() -> new ApiException("Match not found: " + id, HttpStatus.NOT_FOUND));
    }

    private static Map<String, Object> oddsToMap(Odds o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookmaker",  "SpeedBet");
        m.put("market",     o.getMarket());
        m.put("selection",  o.getSelection());
        m.put("odd",        o.getValue() != null ? o.getValue().toPlainString() : null);
        if (o.getHandicap() != null) {
            m.put("handicap", o.getHandicap().toPlainString());
        }
        return m;
    }
}
package com.speedbet.api.match;

import com.speedbet.api.match.Match;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only REST controller for match lifecycle management.
 *
 * Every endpoint:
 *   1. Requires the ADMIN role (enforced by @PreAuthorize).
 *   2. Receives the authenticated admin via @AuthenticationPrincipal — the
 *      identity is never taken from the request body.
 *   3. Delegates to AdminMatchService which enforces per-admin ownership,
 *      meaning each admin can only see and edit their own matches.
 *
 * Base path: /api/admin/matches
 *
 *   POST   /                  — create a match (odds auto-generated)
 *   GET    /                  — list MY matches
 *   GET    /{id}              — get one of MY matches
 *   PATCH  /{id}/status       — transition status (MY match only)
 *   PATCH  /{id}/score        — update live score (MY match only, non-FINISHED)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/matches")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMatchController {

    private final AdminMatchService adminMatchService;

    // ── Create ────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Match> createMatch(
            @Valid @RequestBody AdminMatchRequest req,
            @AuthenticationPrincipal User admin) {

        log.info("Admin[{}]: createMatch home='{}' away='{}'",
                admin.getId(), req.getHomeTeam(), req.getAwayTeam());
        Match created = adminMatchService.createMatch(req, admin);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── Read (scoped to this admin only) ──────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Match>> listMyMatches(
            @AuthenticationPrincipal User admin) {

        return ResponseEntity.ok(adminMatchService.getMyMatches(admin));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Match> getMyMatch(
            @PathVariable String id,
            @AuthenticationPrincipal User admin) {

        return ResponseEntity.ok(adminMatchService.getMyMatch(id, admin));
    }

    // ── Status transition ─────────────────────────────────────────────────

    /**
     * Moves the match through the state machine:
     *   SCHEDULED → LIVE → HALF_TIME → SECOND_HALF → FINISHED
     *
     * Returns 400 if the transition is invalid or the match is already FINISHED.
     * Returns 404 if the match doesn't exist or belongs to another admin.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<Match> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AdminStatusUpdateRequest req,
            @AuthenticationPrincipal User admin) {

        log.info("Admin[{}]: updateStatus matchId={} target={}", admin.getId(), id, req.getStatus());
        return ResponseEntity.ok(adminMatchService.updateStatus(id, req, admin));
    }

    // ── Score update ──────────────────────────────────────────────────────

    /**
     * Updates the score of a live match.
     * Blocked for FINISHED and SCHEDULED matches.
     * No match events (goalscorers, cards, substitutions) are accepted.
     */
    @PatchMapping("/{id}/score")
    public ResponseEntity<Match> updateScore(
            @PathVariable UUID id,
            @Valid @RequestBody AdminScoreUpdateRequest req,
            @AuthenticationPrincipal User admin) {

        log.info("Admin[{}]: updateScore matchId={} {}:{} minute={}",
                admin.getId(), id, req.getScoreHome(), req.getScoreAway(), req.getMinutePlayed());
        return ResponseEntity.ok(adminMatchService.updateScore(id, req, admin));
    }
}
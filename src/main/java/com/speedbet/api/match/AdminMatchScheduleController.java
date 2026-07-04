package com.speedbet.api.match;

import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin-facing endpoints for fully automated match lifecycles.
 *
 * The admin supplies only a kickoff time and a final score; the match is
 * created immediately, and its entire 45-minute lifecycle (kickoff, random
 * goal timings, half-time, second half, finish) then runs unattended,
 * driven by AdminMatchScheduleService on a background scheduler.
 *
 * These endpoints sit alongside — not instead of — the manual endpoints
 * exposed for AdminMatchService.
 */
@RestController
@RequestMapping("/admin/matches/auto")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMatchScheduleController {

    private final AdminMatchScheduleService adminMatchScheduleService;

    /**
     * Creates a match and schedules its full lifecycle automatically:
     *   kickoffAt            SCHEDULED → LIVE
     *   (random minutes)     goals scored, updating the live score
     *   kickoffAt + 22 min   LIVE → HALF_TIME
     *   kickoffAt + 27 min   HALF_TIME → SECOND_HALF
     *   kickoffAt + 49 min   → FINISHED (score already at the given final score)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Match scheduleMatch(@Valid @RequestBody AdminAutoMatchRequest req,
                                @AuthenticationPrincipal User admin) {
        return adminMatchScheduleService.scheduleMatch(req, admin);
    }

    /**
     * Returns the computed schedule for a match: exact kickoff / half-time /
     * second-half / finish timestamps, and the full randomized goal-by-goal
     * plan (minute, scorer, real timestamp, running score).
     */
    @GetMapping("/{matchId}")
    public Map<String, Object> getSchedule(@PathVariable UUID matchId,
                                            @AuthenticationPrincipal User admin) {
        return adminMatchScheduleService.getSchedule(matchId, admin);
    }

    /**
     * Cancels all pending automated transitions for a match. The match stays
     * at its current status/score — this does not reset it. Use the regular
     * AdminMatchService endpoints to take over manually afterward.
     */
    @DeleteMapping("/{matchId}")
    public ResponseEntity<Map<String, Object>> cancelSchedule(@PathVariable UUID matchId,
                                                                @AuthenticationPrincipal User admin) {
        int cancelled = adminMatchScheduleService.cancelSchedule(matchId, admin);
        return ResponseEntity.ok(Map.of("matchId", matchId, "jobsCancelled", cancelled));
    }
}
package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * AdminMatchScheduleService — fully automated match lifecycle.
 *
 * The admin sets only two things: kickoff time and the final score. This
 * service does the rest:
 *
 *   ── Fixed match clock (always the same shape) ───────────────────────────
 *     kickoffAt            SCHEDULED → LIVE
 *     kickoffAt + 22 min   LIVE      → HALF_TIME
 *     kickoffAt + 27 min   HALF_TIME → SECOND_HALF   (22 min play + 5 min break)
 *     kickoffAt + 49 min   → FINISHED                (+ 22 min second half)
 *
 *   ── Randomized goals ─────────────────────────────────────────────────────
 *     Each goal (up to finalScoreHome / finalScoreAway) is given a random,
 *     unique minute: home and away goals in minutes 1-22 fall in the first
 *     half, minutes 23-44 fall in the second half. At the real-world instant
 *     each minute maps to, AdminMatchService.updateScore() is called with
 *     the cumulative score at that point — so the odds table refreshes
 *     exactly the way it would for a real live match, goal by goal.
 *
 *   ── Why this calls AdminMatchService rather than duplicating logic ──────
 *     Every scheduled step is a normal call into the existing, already-
 *     audited methods (createMatch / updateStatus / updateScore). That means
 *     ownership checks, the HALF_TIME score-metadata snapshot, live odds
 *     regeneration, and the FINISHED terminal guard all apply automatically
 *     with zero duplicated logic here.
 *
 * ── Operational notes ────────────────────────────────────────────────────
 *   - Scheduling is in-memory via Spring's TaskScheduler. A restart before
 *     a job fires loses that job — the match is simply left at whatever
 *     stage it last reached. For multi-instance/durable deployments, persist
 *     the computed event list to a table and replace TaskScheduler with a
 *     periodic poller instead.
 *   - Jobs run off the request thread, so failures are logged, not thrown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMatchScheduleService {

    private static final int FIRST_HALF_MINUTES  = 45;
    private static final int BREAK_MINUTES       = 15;
    private static final int SECOND_HALF_MINUTES = 45;
    private static final int MATCH_MINUTES       = FIRST_HALF_MINUTES + SECOND_HALF_MINUTES; // 44

    private final AdminMatchService adminMatchService;
    private final TaskScheduler taskScheduler;
    private final Random random = new Random();

    /** matchId → active jobs + the schedule that produced them, for cancel/inspect. */
    private final Map<UUID, ScheduleHandle> scheduledJobs = new ConcurrentHashMap<>();

    @Getter
    @RequiredArgsConstructor
    private static class ScheduleHandle {
        private final List<ScheduledFuture<?>> jobs;
        private final Instant kickoffAt;
        private final Instant halfTimeAt;
        private final Instant secondHalfAt;
        private final Instant finishedAt;
        private final List<GoalEvent> goals;
    }

    private record GoalEvent(int minute, String team, Instant at, int cumHome, int cumAway) {}

    // ══════════════════════════════════════════════════════════════════════
    // SCHEDULE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates the match now (SCHEDULED, all pre-match odds saved) and
     * schedules its entire 45-minute lifecycle: kickoff, randomized goals,
     * half-time, second half, and finish — with the final score guaranteed
     * to match what the admin specified.
     */
    @Transactional
    public Match scheduleMatch(AdminAutoMatchRequest req, User admin) {
        validate(req);

        Instant kickoffAt    = req.getKickoffAt();
        Instant halfTimeAt   = kickoffAt.plus(FIRST_HALF_MINUTES, ChronoUnit.MINUTES);
        Instant secondHalfAt = halfTimeAt.plus(BREAK_MINUTES, ChronoUnit.MINUTES);
        Instant finishedAt   = secondHalfAt.plus(SECOND_HALF_MINUTES, ChronoUnit.MINUTES);

        AdminMatchRequest createReq = new AdminMatchRequest();
        createReq.setHomeTeam(req.getHomeTeam());
        createReq.setAwayTeam(req.getAwayTeam());
        createReq.setLeague(req.getLeague());
        createReq.setSport(req.getSport());
        createReq.setHomeLogo(req.getHomeLogo());
        createReq.setAwayLogo(req.getAwayLogo());
        createReq.setLeagueLogo(req.getLeagueLogo());
        createReq.setFeatured(req.isFeatured());
        createReq.setKickoffAt(kickoffAt);
        createReq.setStatus("SCHEDULED");

        Match match = adminMatchService.createMatch(createReq, admin);
        UUID matchId = match.getId();

        List<GoalEvent> goals = buildGoalSchedule(
                req.getFinalScoreHome(), req.getFinalScoreAway(),
                kickoffAt, halfTimeAt, secondHalfAt);

        log.info("AdminMatchScheduleService.scheduleMatch: adminId={} matchId={} " +
                        "kickoffAt={} halfTimeAt={} secondHalfAt={} finishedAt={} finalScore={}:{} goals={}",
                admin.getId(), matchId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt,
                req.getFinalScoreHome(), req.getFinalScoreAway(), goals.size());

        List<ScheduledFuture<?>> jobs = new ArrayList<>();

        // Kickoff
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "kickoff→LIVE", () ->
                        adminMatchService.updateStatus(matchId, statusReq("LIVE"), admin)),
                kickoffAt));

        // Goal-by-goal score updates, in chronological order
        for (GoalEvent g : goals) {
            jobs.add(taskScheduler.schedule(
                    () -> safeRun(matchId,
                            "goal min=" + g.minute() + " scorer=" + g.team() + " → " + g.cumHome() + ":" + g.cumAway(),
                            () -> adminMatchService.updateScore(matchId,
                                    scoreReq(g.cumHome(), g.cumAway(), g.minute()), admin)),
                    g.at()));
        }

        // Half-time (score already reflects any first-half goals by this point)
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "halfTime→HALF_TIME", () ->
                        adminMatchService.updateStatus(matchId, statusReq("HALF_TIME"), admin)),
                halfTimeAt));

        // Second half
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "secondHalf→SECOND_HALF", () ->
                        adminMatchService.updateStatus(matchId, statusReq("SECOND_HALF"), admin)),
                secondHalfAt));

        // Finish (score already reflects all goals by this point)
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "finish→FINISHED", () -> {
                    adminMatchService.updateStatus(matchId, statusReq("FINISHED"), admin);
                    scheduledJobs.remove(matchId); // lifecycle complete
                }),
                finishedAt));

        scheduledJobs.put(matchId, new ScheduleHandle(jobs, kickoffAt, halfTimeAt, secondHalfAt, finishedAt, goals));
        return match;
    }

    /**
     * Cancels all pending automated transitions for a match. The match stays
     * at whatever status/score it currently holds — an admin can then drive
     * it manually via AdminMatchService.
     */
    public int cancelSchedule(UUID matchId, User admin) {
        adminMatchService.getMyMatch(matchId.toString(), admin); // ownership check, 404s if not owned

        ScheduleHandle handle = scheduledJobs.remove(matchId);
        if (handle == null) return 0;

        int cancelled = 0;
        for (ScheduledFuture<?> job : handle.getJobs()) {
            if (job.cancel(false)) cancelled++;
        }
        log.info("AdminMatchScheduleService.cancelSchedule: adminId={} matchId={} cancelledJobs={}",
                admin.getId(), matchId, cancelled);
        return cancelled;
    }

    /**
     * Returns the computed schedule for a match (timings + the randomized
     * goal-by-goal plan) so the admin UI can display "what will happen and
     * when" without waiting for it to play out.
     *
     * @throws ApiException 404 if there is no active schedule for this match
     *         (never scheduled, already finished, or already cancelled)
     */
    public Map<String, Object> getSchedule(UUID matchId, User admin) {
        adminMatchService.getMyMatch(matchId.toString(), admin); // ownership check

        ScheduleHandle handle = scheduledJobs.get(matchId);
        if (handle == null) {
            throw ApiException.notFound("No active schedule for match: " + matchId);
        }

        List<Map<String, Object>> goalView = handle.getGoals().stream()
                .map(g -> Map.<String, Object>of(
                        "minute", g.minute(),
                        "scorer", g.team(),
                        "at", g.at(),
                        "scoreAfter", g.cumHome() + ":" + g.cumAway()))
                .toList();

        return Map.of(
                "matchId", matchId,
                "kickoffAt", handle.getKickoffAt(),
                "halfTimeAt", handle.getHalfTimeAt(),
                "secondHalfAt", handle.getSecondHalfAt(),
                "finishedAt", handle.getFinishedAt(),
                "goals", goalView,
                "pendingJobs", handle.getJobs().size()
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // GOAL RANDOMIZATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds a chronologically-sorted list of goal events for the whole
     * match. Home goals get unique random minutes in [1, MATCH_MINUTES],
     * away goals independently get their own unique random minutes in the
     * same range — a home and away goal CAN land in the same minute (kept
     * a few seconds apart so their scheduled jobs don't collide). Minutes
     * 1-22 map to first-half real time (kickoffAt + minute); minutes 23-44
     * map to second-half real time (secondHalfAt + (minute-22)), so the
     * 5-minute break is correctly skipped.
     */
    private List<GoalEvent> buildGoalSchedule(int finalHome, int finalAway,
                                               Instant kickoffAt, Instant halfTimeAt, Instant secondHalfAt) {
        List<int[]> raw = new ArrayList<>(); // [minute, teamFlag] teamFlag: 0=home,1=away

        for (int minute : uniqueRandomMinutes(finalHome)) raw.add(new int[]{minute, 0});
        for (int minute : uniqueRandomMinutes(finalAway)) raw.add(new int[]{minute, 1});

        // Sort by minute; break ties with a stable secondary order so we can
        // offset colliding minutes by a few seconds to avoid identical timestamps.
        raw.sort(Comparator.comparingInt(a -> a[0]));

        List<GoalEvent> events = new ArrayList<>();
        int cumHome = 0, cumAway = 0;
        Instant lastAt = null;

        for (int[] entry : raw) {
            int minute = entry[0];
            String team = entry[1] == 0 ? "HOME" : "AWAY";

            Instant at = minuteToInstant(minute, kickoffAt, secondHalfAt);
            // Guarantee strictly increasing timestamps even if two goals share a minute.
            if (lastAt != null && !at.isAfter(lastAt)) {
                at = lastAt.plusSeconds(5);
            }
            lastAt = at;

            if (entry[1] == 0) cumHome++; else cumAway++;
            events.add(new GoalEvent(minute, team, at, cumHome, cumAway));
        }

        return events;
    }

    /** Random, unique minutes (1..MATCH_MINUTES) for a given number of goals. */
    private List<Integer> uniqueRandomMinutes(int count) {
        if (count <= 0) return List.of();
        // Defensive cap — never realistically hit, but avoids an infinite
        // loop if someone schedules an absurd score line.
        int max = Math.min(count, MATCH_MINUTES);
        Set<Integer> minutes = new LinkedHashSet<>();
        while (minutes.size() < max) {
            minutes.add(1 + random.nextInt(MATCH_MINUTES));
        }
        return new ArrayList<>(minutes);
    }

    /** Maps a match minute (1-44) to the real-world instant it occurs at, skipping the break. */
    private Instant minuteToInstant(int minute, Instant kickoffAt, Instant secondHalfAt) {
        if (minute <= FIRST_HALF_MINUTES) {
            return kickoffAt.plus(minute, ChronoUnit.MINUTES);
        }
        return secondHalfAt.plus(minute - FIRST_HALF_MINUTES, ChronoUnit.MINUTES);
    }

    // ══════════════════════════════════════════════════════════════════════
    // JOB EXECUTION / VALIDATION / HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void safeRun(UUID matchId, String step, Runnable action) {
        try {
            action.run();
            log.info("AdminMatchScheduleService.safeRun: matchId={} step='{}' OK", matchId, step);
        } catch (Exception e) {
            log.error("AdminMatchScheduleService.safeRun: matchId={} step='{}' FAILED — {}",
                    matchId, step, e.getMessage(), e);
        }
    }

    private void validate(AdminAutoMatchRequest req) {
        if (req.getKickoffAt() == null) {
            throw ApiException.badRequest("kickoffAt is required.");
        }
        if (req.getFinalScoreHome() == null || req.getFinalScoreAway() == null) {
            throw ApiException.badRequest("finalScoreHome and finalScoreAway are required.");
        }
        if (req.getFinalScoreHome() < 0 || req.getFinalScoreAway() < 0) {
            throw ApiException.badRequest("Scores cannot be negative.");
        }
    }

    private AdminStatusUpdateRequest statusReq(String status) {
        AdminStatusUpdateRequest r = new AdminStatusUpdateRequest();
        r.setStatus(status);
        return r;
    }

    private AdminScoreUpdateRequest scoreReq(int home, int away, Integer minute) {
        AdminScoreUpdateRequest r = new AdminScoreUpdateRequest();
        r.setScoreHome(home);
        r.setScoreAway(away);
        r.setMinutePlayed(minute);
        return r;
    }
}
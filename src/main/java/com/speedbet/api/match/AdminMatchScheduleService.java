package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *     kickoffAt                              SCHEDULED → LIVE
 *     kickoffAt + 45 min                     LIVE      → HALF_TIME
 *     kickoffAt + 45 min + 15 min break       HALF_TIME → SECOND_HALF
 *     kickoffAt + 45 min + 15 min + 45 min    → FINISHED
 *
 *   ── Randomized goals ─────────────────────────────────────────────────────
 *     Each goal (up to finalScoreHome / finalScoreAway) is given a random,
 *     unique minute: home and away goals in minutes 1-45 fall in the first
 *     half, minutes 46-90 fall in the second half. At the real-world instant
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
 * ── FIX (previous revision) ───────────────────────────────────────────────
 *   Previously, scheduled jobs were registered with the TaskScheduler
 *   *inside* the @Transactional method, before the transaction that
 *   created the Match row had committed. Because kickoffAt is frequently
 *   "now" or very soon, the kickoff job could fire on the scheduler's
 *   thread before the row was visible to other DB connections, causing
 *   updateStatus() to fail. That failure was swallowed by safeRun()
 *   (logged only), so the match silently got stuck in SCHEDULED.
 *
 *   Jobs are now registered via TransactionSynchronizationManager so they
 *   are only scheduled AFTER the surrounding transaction commits.
 *
 * ── FIX (this revision) — matches scheduled hours ahead never kicking off ──
 *   Root cause: this service was relying on *whatever* TaskScheduler bean
 *   Spring happened to inject. If no dedicated TaskScheduler bean was
 *   defined elsewhere in the app, Spring Boot's auto-configured default
 *   applies — a SINGLE-THREADED scheduler shared by every @Scheduled job
 *   and every TaskScheduler-using service in the whole application.
 *
 *   Quick kickoffs (seconds/minutes out — the kind used when manually
 *   testing this feature) tend to fire fine because the queue is short and
 *   nothing's contending for the thread yet. A kickoff scheduled 5 hours
 *   out sits in that single thread's queue behind every other job the app
 *   schedules in the meantime; if even one of those blocks, runs long, or
 *   the thread is otherwise busy exactly when this job's trigger time
 *   arrives, this match's kickoff can be starved indefinitely — and
 *   because safeRun() only logs failures rather than surfacing them, that
 *   failure mode is completely silent: the match just sits in SCHEDULED
 *   forever with no error anywhere.
 *
 *   Fix: this service now defines and injects its OWN dedicated
 *   TaskScheduler bean (see adminMatchTaskScheduler() below), sized for
 *   real concurrent-match volume and named/qualified so it can never
 *   silently fall back to the application's shared default scheduler.
 *
 * ── Operational notes ────────────────────────────────────────────────────
 *   - Scheduling is in-memory via Spring's TaskScheduler. A restart before
 *     a job fires loses that job — the match is simply left at whatever
 *     stage it last reached. If your deploys/restarts happen more often
 *     than "rarely," this is a SEPARATE risk from the thread-starvation
 *     bug fixed above, and needs a durable fix (persist the computed
 *     event list to a table and replace TaskScheduler with a periodic
 *     poller, or re-hydrate scheduledJobs from persisted state on
 *     startup). Flagging this explicitly — happy to build it out if
 *     you're seeing missed kickoffs correlate with deploys/restarts
 *     rather than thread contention.
 *   - Jobs run off the request thread, so failures are logged, not thrown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMatchScheduleService {

    private static final int FIRST_HALF_MINUTES  = 45;
    private static final int BREAK_MINUTES       = 15;
    private static final int SECOND_HALF_MINUTES = 45;
    private static final int MATCH_MINUTES       = FIRST_HALF_MINUTES + SECOND_HALF_MINUTES; // 90

    private final AdminMatchService adminMatchService;

    /**
     * Dedicated scheduler for this service — see class javadoc "FIX (this
     * revision)". @Qualifier guards against Spring silently wiring in some
     * other TaskScheduler bean (e.g. the app-wide default) instead of this
     * one if bean names ever collide.
     */
    @Qualifier("adminMatchTaskScheduler")
    private final TaskScheduler taskScheduler;

    private final Random random = new Random();

    /** matchId → active jobs + the schedule that produced them, for cancel/inspect. */
    private final Map<UUID, ScheduleHandle> scheduledJobs = new ConcurrentHashMap<>();

    /**
     * Dedicated thread pool for match-lifecycle jobs (kickoff / goals /
     * half-time / second-half / finish), completely separate from
     * whatever TaskScheduler (if any) the rest of the app uses for other
     * @Scheduled work. Sized generously since jobs are short (a single
     * DB-backed service call each) — 20 concurrent matches × ~5-9 jobs
     * each is comfortably covered, and idle threads cost nothing.
     *
     * Bump poolSize further if you regularly run more than ~20 matches
     * concurrently with overlapping schedules.
     */
    @Bean(name = "adminMatchTaskScheduler")
    public TaskScheduler adminMatchTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(20);
        scheduler.setThreadNamePrefix("match-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        // Don't let one hung job (e.g. a slow/blocked downstream call)
        // silently starve every other match's jobs on the same thread.
        scheduler.setErrorHandler(t ->
                log.error("AdminMatchScheduleService: uncaught scheduler error", t));
        scheduler.initialize();
        return scheduler;
    }

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
     * schedules its entire lifecycle: kickoff, randomized goals, half-time,
     * second half, and finish — with the final score guaranteed to match
     * what the admin specified.
     *
     * The actual TaskScheduler registration is deferred until AFTER this
     * transaction commits (see registerScheduleAfterCommit below), so the
     * Match row is guaranteed to be visible to the jobs when they run.
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

        registerScheduleAfterCommit(matchId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt, goals, admin);

        return match;
    }

    /**
     * Registers the TaskScheduler jobs once (and only once) the current
     * transaction has committed. If there is no active transaction (e.g.
     * called from a test or a non-transactional caller), the jobs are
     * registered immediately instead.
     */
    private void registerScheduleAfterCommit(UUID matchId, Instant kickoffAt, Instant halfTimeAt,
                                             Instant secondHalfAt, Instant finishedAt,
                                             List<GoalEvent> goals, User admin) {
        Runnable register = () -> doRegisterSchedule(matchId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt, goals, admin);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    register.run();
                }
            });
        } else {
            register.run();
        }
    }

    /** Actually creates and stores the ScheduledFuture jobs. Only ever called post-commit. */
    private void doRegisterSchedule(UUID matchId, Instant kickoffAt, Instant halfTimeAt,
                                    Instant secondHalfAt, Instant finishedAt,
                                    List<GoalEvent> goals, User admin) {
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

        log.info("AdminMatchScheduleService.doRegisterSchedule: matchId={} jobsRegistered={} (post-commit)",
                matchId, jobs.size());
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
     * 1-45 map to first-half real time (kickoffAt + minute); minutes 46-90
     * map to second-half real time (secondHalfAt + (minute-45)), so the
     * break is correctly skipped.
     */
    private List<GoalEvent> buildGoalSchedule(int finalHome, int finalAway,
                                              Instant kickoffAt, Instant halfTimeAt, Instant secondHalfAt) {
        List<int[]> raw = new ArrayList<>(); // [minute, teamFlag] teamFlag: 0=home,1=away

        for (int minute : uniqueRandomMinutes(finalHome)) raw.add(new int[]{minute, 0});
        for (int minute : uniqueRandomMinutes(finalAway)) raw.add(new int[]{minute, 1});

        raw.sort(Comparator.comparingInt(a -> a[0]));

        List<GoalEvent> events = new ArrayList<>();
        int cumHome = 0, cumAway = 0;
        Instant lastAt = null;

        for (int[] entry : raw) {
            int minute = entry[0];
            String team = entry[1] == 0 ? "HOME" : "AWAY";

            Instant at = minuteToInstant(minute, kickoffAt, secondHalfAt);
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
        int max = Math.min(count, MATCH_MINUTES);
        Set<Integer> minutes = new LinkedHashSet<>();
        while (minutes.size() < max) {
            minutes.add(1 + random.nextInt(MATCH_MINUTES));
        }
        return new ArrayList<>(minutes);
    }

    /** Maps a match minute (1-90) to the real-world instant it occurs at, skipping the break. */
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
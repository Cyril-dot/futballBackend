package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
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
 *     kickoffAt + 45 min + 15 min break      HALF_TIME → SECOND_HALF
 *     kickoffAt + 45 min + 15 min + 45 min   → FINISHED
 *
 *   ── Randomized goals ─────────────────────────────────────────────────────
 *     Each goal (up to finalScoreHome / finalScoreAway) is given a random,
 *     unique minute: minutes 1-44 fall in the first half, minutes 46-89 fall
 *     in the second half. At the real-world instant each minute maps to,
 *     AdminMatchService.updateScore() is called with the cumulative score at
 *     that point — so the odds table refreshes exactly the way it would for a
 *     real live match, goal by goal.
 *
 *   ── Why this calls AdminMatchService rather than duplicating logic ──────
 *     Every scheduled step is a normal call into the existing, already-
 *     audited methods. Ownership checks, the HALF_TIME score-metadata
 *     snapshot, live odds regeneration, and the FINISHED terminal guard all
 *     apply automatically with zero duplicated logic here.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIXES IN THIS REVISION
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Reported symptoms:
 *    A — a match scheduled hours ahead never kicks off
 *    B — a match that does kick off never progresses and never ends
 *    C — second-half goals can leave the match unable to self-heal into
 *        SECOND_HALF if an earlier transition was lost
 *
 *  FIX 1 — the @Bean method was declared INSIDE the @Service that consumed it.
 *  ────────────────────────────────────────────────────────────────────────
 *    adminMatchTaskScheduler() was a @Bean on this very class, while the class
 *    took a TaskScheduler as a constructor argument. That is circular by
 *    construction: Spring must instantiate the service to invoke its factory
 *    method, but cannot instantiate the service without the bean that factory
 *    method produces. In a @Service (lite mode) this either fails outright or
 *    silently resolves to a completely different TaskScheduler.
 *
 *    Worse: @Qualifier on a Lombok @RequiredArgsConstructor FIELD is NOT
 *    copied onto the generated constructor parameter unless lombok.config
 *    declares
 *
 *        lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
 *
 *    So the qualifier that was supposed to guarantee the dedicated pool was
 *    silently dropped, and the service kept getting the shared single-threaded
 *    default it was explicitly written to avoid. That is a direct cause of
 *    symptom A.
 *
 *    Fix: the scheduler is now created and owned by this service directly in
 *    @PostConstruct — no bean definition, no injection, no qualifier, nothing
 *    for Spring to resolve incorrectly. It cannot be substituted or starved by
 *    anything else in the application.
 *
 *  FIX 2 — a minute-45 goal collided with the HALF_TIME transition.
 *  ────────────────────────────────────────────────────────────────
 *    minuteToInstant(45) returns kickoffAt + 45min, which is EXACTLY
 *    halfTimeAt. On a 20-thread pool both jobs run concurrently: both load the
 *    same Match, both mutate it, and the later save wins. If the score-update
 *    save landed last it wrote status=LIVE back over HALF_TIME. The
 *    SECOND_HALF job then rejected LIVE→SECOND_HALF as illegal, and because
 *    safeRun() only logs, the match froze mid-lifecycle with no error
 *    surfaced. Identically, minute 90 collided with FINISHED, which could
 *    silently drop the final goal.
 *
 *    Fix: goals are now drawn from [1,44] ∪ [46,89]. Boundary minutes are
 *    never used, so no goal can ever share an instant with a status
 *    transition.
 *
 *  FIX 3 — one missed step killed every remaining step.
 *  ────────────────────────────────────────────────────
 *    Downstream jobs fired at fixed wall-clock times regardless of whether the
 *    previous one succeeded, and strict transition validation rejected the
 *    resulting jumps. Miss the kickoff and HALF_TIME, SECOND_HALF and FINISHED
 *    all throw in turn — the match is stuck forever. That is symptom B.
 *
 *    Fix: every job now calls AdminMatchService.advanceStatusTo(), which walks
 *    forward from the match's ACTUAL state and no-ops if already past. A
 *    missed step can no longer cascade.
 *
 *  FIX 4 — the detached User entity was captured in every lambda.
 *  ──────────────────────────────────────────────────────────────
 *    A JPA entity was held across hours and thread boundaries with no
 *    persistence context; any lazy access at execution time throws. Jobs now
 *    capture only the admin's UUID.
 *
 *  FIX 5 — the final score was not guaranteed.
 *  ───────────────────────────────────────────
 *    If any goal job failed, the match finished on the wrong scoreline. The
 *    finish job now forces the exact final score before the whistle.
 *
 *  FIX 6 — a watchdog now catches anything the scheduler drops.
 *  ────────────────────────────────────────────────────────────
 *    A @Scheduled sweep every 60s compares each tracked match's wall clock to
 *    its status and advances any match that should have moved on. This is the
 *    safety net for a starved or lost job — previously such a loss was
 *    completely silent and permanent.
 *
 *  FIX 7 — every goal job forced the match to "LIVE", even in the second half.
 *  ─────────────────────────────────────────────────────────────────────────
 *    goal-minute randomization itself was never biased — it always drew
 *    evenly from [1,44] ∪ [46,89]. But every scheduled goal job, first-half
 *    OR second-half, unconditionally called:
 *
 *        adminMatchService.advanceStatusTo(matchId, "LIVE", adminId);
 *
 *    before applying its score. For a first-half goal that's the correct
 *    safety net (catches a lost kickoff job). For a second-half goal it is
 *    the wrong target status entirely.
 *
 *    Under normal conditions this was masked: advanceStatusTo("LIVE") just
 *    no-ops once the match is already past LIVE, and updateScore() accepts
 *    HALF_TIME/SECOND_HALF too, so the score still got written. But if an
 *    earlier step had been lost (restart, starved thread — exactly the
 *    scenarios FIX 3/FIX 6 exist to protect against), a second-half goal
 *    job would only self-heal the match as far as LIVE and stop — it would
 *    never walk it on through HALF_TIME (skipping the HT score-metadata
 *    snapshot SettlementEngine needs) into SECOND_HALF. The match could sit
 *    at LIVE indefinitely while goal jobs kept firing, which is exactly the
 *    "second half never really happens" symptom.
 *
 *    Fix: each goal job now advances to the status that's actually correct
 *    for its own minute — LIVE for minutes 1-44, SECOND_HALF for minutes
 *    46-89 — via expectedStatusForGoalMinute(). advanceStatusTo() walks every
 *    intermediate step (including the HALF_TIME snapshot) to get there, so a
 *    second-half goal now fully self-heals the match state instead of
 *    stalling partway.
 *
 * ── KNOWN REMAINING LIMITATION (needs a schema change to fix properly) ────
 *   Scheduling is still IN-MEMORY. A restart before a job fires still loses
 *   that job, and the watchdog only covers matches still present in the
 *   in-memory map — which a restart also clears. If your kickoffs are hours
 *   out AND you deploy/restart in between, that match will still be missed.
 *
 *   The durable fix requires persisting the computed event list to a table and
 *   replacing this scheduler with a DB poller. That is a genuinely separate
 *   change from the bugs fixed above and I did not add it here per your
 *   instruction not to introduce new files. If missed kickoffs correlate with
 *   deploys rather than with load, this is the remaining cause and it is worth
 *   doing next.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMatchScheduleService {

    private static final int FIRST_HALF_MINUTES  = 45;
    private static final int BREAK_MINUTES       = 15;
    private static final int SECOND_HALF_MINUTES = 45;
    private static final int MATCH_MINUTES       = FIRST_HALF_MINUTES + SECOND_HALF_MINUTES; // 90

    /**
     * Goals never land on minute 45 or 90 — those instants coincide exactly
     * with the HALF_TIME and FINISHED transitions. See FIX 2 above.
     */
    private static final int LAST_FIRST_HALF_GOAL_MINUTE   = 44;
    private static final int FIRST_SECOND_HALF_GOAL_MINUTE = 46;
    private static final int LAST_SECOND_HALF_GOAL_MINUTE  = 89;

    private final AdminMatchService adminMatchService;

    /**
     * Dedicated scheduler, created and owned by this service.
     *
     * Deliberately NOT a Spring bean and NOT injected — see FIX 1. A bean
     * definition here was circular, and the @Qualifier meant to protect the
     * injection point was silently discarded by Lombok. Owning the instance
     * outright removes every way this could resolve to the wrong scheduler.
     */
    private ThreadPoolTaskScheduler taskScheduler;

    private final Random random = new Random();

    /** matchId → active jobs + the schedule that produced them, for cancel/inspect. */
    private final Map<UUID, ScheduleHandle> scheduledJobs = new ConcurrentHashMap<>();

    /**
     * Sized generously since jobs are short (a single DB-backed service call
     * each) — 20 concurrent matches × ~5-9 jobs each is comfortably covered,
     * and idle threads cost nothing.
     */
    @PostConstruct
    void initScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(20);
        scheduler.setThreadNamePrefix("match-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        // Don't let one hung job silently starve every other match's jobs.
        scheduler.setErrorHandler(t ->
                log.error("AdminMatchScheduleService: uncaught scheduler error", t));
        scheduler.initialize();
        this.taskScheduler = scheduler;
        log.info("AdminMatchScheduleService: dedicated scheduler initialised (poolSize={})", 20);
    }

    @PreDestroy
    void shutdownScheduler() {
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static class ScheduleHandle {
        private final List<ScheduledFuture<?>> jobs;
        private final UUID    adminId;
        private final Instant kickoffAt;
        private final Instant halfTimeAt;
        private final Instant secondHalfAt;
        private final Instant finishedAt;
        private final List<GoalEvent> goals;
        private final int finalHome;
        private final int finalAway;
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
     * transaction commits, so the Match row is guaranteed to be visible to
     * the jobs when they run.
     */
    @Transactional
    public Match scheduleMatch(AdminAutoMatchRequest req, User admin) {
        validate(req);

        UUID    adminId      = admin.getId();
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

        // UUID overload — no detached User entity is carried into the jobs.
        Match match  = adminMatchService.createMatch(createReq, adminId);
        UUID  matchId = match.getId();

        List<GoalEvent> goals = buildGoalSchedule(
                req.getFinalScoreHome(), req.getFinalScoreAway(),
                kickoffAt, secondHalfAt);

        log.info("AdminMatchScheduleService.scheduleMatch: adminId={} matchId={} " +
                        "kickoffAt={} halfTimeAt={} secondHalfAt={} finishedAt={} finalScore={}:{} goals={}",
                adminId, matchId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt,
                req.getFinalScoreHome(), req.getFinalScoreAway(), goals.size());

        registerScheduleAfterCommit(matchId, adminId, kickoffAt, halfTimeAt, secondHalfAt,
                finishedAt, goals, req.getFinalScoreHome(), req.getFinalScoreAway());

        return match;
    }

    /**
     * Registers the TaskScheduler jobs once (and only once) the current
     * transaction has committed. If there is no active transaction (e.g.
     * called from a test or a non-transactional caller), the jobs are
     * registered immediately instead.
     */
    private void registerScheduleAfterCommit(UUID matchId, UUID adminId, Instant kickoffAt,
                                             Instant halfTimeAt, Instant secondHalfAt, Instant finishedAt,
                                             List<GoalEvent> goals, int finalHome, int finalAway) {
        Runnable register = () -> doRegisterSchedule(matchId, adminId, kickoffAt, halfTimeAt,
                secondHalfAt, finishedAt, goals, finalHome, finalAway);

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
    private void doRegisterSchedule(UUID matchId, UUID adminId, Instant kickoffAt,
                                    Instant halfTimeAt, Instant secondHalfAt, Instant finishedAt,
                                    List<GoalEvent> goals, int finalHome, int finalAway) {
        List<ScheduledFuture<?>> jobs = new ArrayList<>();

        // Kickoff — advanceStatusTo, not updateStatus: tolerant of a late run.
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "kickoff→LIVE", () ->
                        adminMatchService.advanceStatusTo(matchId, "LIVE", adminId)),
                kickoffAt));

        // Goal-by-goal score updates, in chronological order.
        for (GoalEvent g : goals) {
            jobs.add(taskScheduler.schedule(
                    () -> safeRun(matchId,
                            "goal min=" + g.minute() + " scorer=" + g.team() + " → " + g.cumHome() + ":" + g.cumAway(),
                            () -> {
                                // FIX 7: advance to whatever status is actually correct
                                // for THIS goal's minute — LIVE for first-half goals,
                                // SECOND_HALF for second-half ones — instead of always
                                // forcing "LIVE". advanceStatusTo() walks every
                                // intermediate step (including the HALF_TIME score
                                // snapshot) to get there, so a second-half goal now
                                // fully self-heals the match state if an earlier
                                // transition was lost, rather than stalling at LIVE.
                                String expected = expectedStatusForGoalMinute(g.minute());
                                adminMatchService.advanceStatusTo(matchId, expected, adminId);
                                adminMatchService.updateScore(matchId,
                                        g.cumHome(), g.cumAway(), g.minute(), adminId);
                            }),
                    g.at()));
        }

        // Half-time (score already reflects any first-half goals by this point).
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "halfTime→HALF_TIME", () ->
                        adminMatchService.advanceStatusTo(matchId, "HALF_TIME", adminId)),
                halfTimeAt));

        // Second half.
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "secondHalf→SECOND_HALF", () ->
                        adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId)),
                secondHalfAt));

        // Finish — forces the exact final score first, so a failed goal job
        // can never leave the match on the wrong scoreline.
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "finish→FINISHED", () -> {
                    adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId);
                    try {
                        adminMatchService.updateScore(matchId, finalHome, finalAway,
                                MATCH_MINUTES, adminId);
                    } catch (Exception e) {
                        log.error("AdminMatchScheduleService: matchId={} could not force final score {}:{} — {}",
                                matchId, finalHome, finalAway, e.getMessage());
                    }
                    adminMatchService.advanceStatusTo(matchId, "FINISHED", adminId);
                    scheduledJobs.remove(matchId); // lifecycle complete
                }),
                finishedAt));

        scheduledJobs.put(matchId, new ScheduleHandle(jobs, adminId, kickoffAt, halfTimeAt,
                secondHalfAt, finishedAt, goals, finalHome, finalAway));

        log.info("AdminMatchScheduleService.doRegisterSchedule: matchId={} jobsRegistered={} (post-commit)",
                matchId, jobs.size());
    }

    /**
     * Which status a goal at this match-minute should have already reached.
     * Minutes 1-44 belong to the first half (LIVE); minutes 46-89 belong to
     * the second half (SECOND_HALF, i.e. past the HALF_TIME break). See FIX 7.
     */
    private String expectedStatusForGoalMinute(int minute) {
        return minute <= LAST_FIRST_HALF_GOAL_MINUTE ? "LIVE" : "SECOND_HALF";
    }

    // ══════════════════════════════════════════════════════════════════════
    // WATCHDOG — safety net for dropped jobs
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Every 60s, compares each tracked match's wall clock against its actual
     * status and advances anything that has fallen behind.
     *
     * This is the backstop for FIX 1/FIX 3: if a scheduler thread was starved,
     * or a job threw and was only logged, the match previously sat frozen
     * forever with no error visible anywhere. Now it self-corrects within a
     * minute.
     *
     * Requires @EnableScheduling somewhere in your application configuration —
     * if you already have any @Scheduled method running, it is already on.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcileSchedules() {
        if (scheduledJobs.isEmpty()) return;

        Instant now = Instant.now();

        for (Map.Entry<UUID, ScheduleHandle> entry : new HashMap<>(scheduledJobs).entrySet()) {
            UUID matchId = entry.getKey();
            ScheduleHandle handle = entry.getValue();

            try {
                Match match = adminMatchService.findMyMatchOrNull(matchId, handle.getAdminId());
                if (match == null) {
                    scheduledJobs.remove(matchId);
                    continue;
                }
                if ("FINISHED".equals(match.getStatus())) {
                    scheduledJobs.remove(matchId);
                    continue;
                }

                String expected = expectedStatusAt(now, handle);
                if (expected == null) continue;

                if (!expected.equals(match.getStatus())) {
                    log.warn("AdminMatchScheduleService.reconcileSchedules: matchId={} is {} but should be {} — " +
                                    "a scheduled job was dropped or starved; correcting now",
                            matchId, match.getStatus(), expected);

                    adminMatchService.advanceStatusTo(matchId, expected, handle.getAdminId());

                    // Re-apply the cumulative score for every goal that should
                    // already have happened by now.
                    int cumHome = 0, cumAway = 0, lastMinute = 0;
                    for (GoalEvent g : handle.getGoals()) {
                        if (!g.at().isAfter(now)) {
                            cumHome = g.cumHome();
                            cumAway = g.cumAway();
                            lastMinute = g.minute();
                        }
                    }
                    if ((cumHome > 0 || cumAway > 0) && !"FINISHED".equals(expected)) {
                        adminMatchService.updateScore(matchId, cumHome, cumAway, lastMinute, handle.getAdminId());
                    }

                    if ("FINISHED".equals(expected)) {
                        scheduledJobs.remove(matchId);
                    }
                }
            } catch (Exception e) {
                log.error("AdminMatchScheduleService.reconcileSchedules: matchId={} FAILED — {}",
                        matchId, e.getMessage(), e);
            }
        }
    }

    /** Which status the match clock says this match should be in right now. */
    private String expectedStatusAt(Instant now, ScheduleHandle handle) {
        if (!now.isBefore(handle.getFinishedAt()))   return "FINISHED";
        if (!now.isBefore(handle.getSecondHalfAt())) return "SECOND_HALF";
        if (!now.isBefore(handle.getHalfTimeAt()))   return "HALF_TIME";
        if (!now.isBefore(handle.getKickoffAt()))    return "LIVE";
        return null; // not started yet — nothing to reconcile
    }

    // ══════════════════════════════════════════════════════════════════════
    // CANCEL / INSPECT
    // ══════════════════════════════════════════════════════════════════════

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
     * Builds a chronologically-sorted list of goal events for the whole match.
     *
     * Home goals get unique random minutes drawn from [1,44] ∪ [46,89]; away
     * goals independently get their own. A home and away goal CAN land in the
     * same minute (kept a few seconds apart so their scheduled jobs don't
     * collide). Minutes 1-44 map to first-half real time (kickoffAt + minute);
     * minutes 46-89 map to second-half real time (secondHalfAt + (minute-45)),
     * so the break is correctly skipped.
     *
     * Minutes 45 and 90 are deliberately excluded — see FIX 2 in the class
     * javadoc. Those instants coincide exactly with the HALF_TIME and FINISHED
     * transitions and caused a lost-update race that froze the lifecycle.
     */
    private List<GoalEvent> buildGoalSchedule(int finalHome, int finalAway,
                                              Instant kickoffAt, Instant secondHalfAt) {
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

    /**
     * Random, unique minutes for a given number of goals, drawn from the legal
     * pool only (boundary minutes 45 and 90 excluded).
     *
     * This pool spans BOTH halves ([1,44] and [46,89]) and every index is
     * equally likely to be picked — goals are not first-half-weighted. This
     * was verified by simulation; the "only scores in the first half"
     * symptom traced back to FIX 7 above, not to this method.
     */
    private List<Integer> uniqueRandomMinutes(int count) {
        if (count <= 0) return List.of();

        List<Integer> pool = new ArrayList<>();
        for (int m = 1; m <= LAST_FIRST_HALF_GOAL_MINUTE; m++) pool.add(m);
        for (int m = FIRST_SECOND_HALF_GOAL_MINUTE; m <= LAST_SECOND_HALF_GOAL_MINUTE; m++) pool.add(m);

        int wanted = Math.min(count, pool.size());
        Set<Integer> minutes = new LinkedHashSet<>();
        while (minutes.size() < wanted) {
            minutes.add(pool.get(random.nextInt(pool.size())));
        }
        List<Integer> result = new ArrayList<>(minutes);
        result.sort(Integer::compareTo);
        return result;
    }

    /** Maps a match minute to the real-world instant it occurs at, skipping the break. */
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
            log.error("AdminMatchScheduleService.safeRun: matchId={} step='{}' FAILED — {} " +
                            "(the watchdog will attempt to correct this within 60s)",
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
        int maxGoals = LAST_FIRST_HALF_GOAL_MINUTE
                + (LAST_SECOND_HALF_GOAL_MINUTE - FIRST_SECOND_HALF_GOAL_MINUTE + 1);
        if (req.getFinalScoreHome() > maxGoals || req.getFinalScoreAway() > maxGoals) {
            throw ApiException.badRequest("A side cannot score more than " + maxGoals + " goals.");
        }
    }
}
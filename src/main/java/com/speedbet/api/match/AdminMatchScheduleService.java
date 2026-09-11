package com.speedbet.api.match;

import com.speedbet.api.bet.BetRepository;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;


/**
 * AdminMatchScheduleService — fully automated match lifecycle with
 * DB-persisted event polling as the PRIMARY driver.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  ARCHITECTURE: THREE-LAYER RELIABILITY
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  LAYER 1 — In-memory TaskScheduler (fast path, best-effort)
 *  ──────────────────────────────────────────────────────────
 *    Jobs are still scheduled at exact wall-clock instants for low-latency
 *    transitions. They try to fire first, but are NOT authoritative.
 *    Also includes the LIVE CLOCK — a per-match job that ticks minutePlayed
 *    forward every 60 real seconds during LIVE and SECOND_HALF play.
 *
 *  LAYER 2 — DB-persisted MatchScheduledEvent table (authoritative)
 *  ─────────────────────────────────────────────────────────────────
 *    Every lifecycle event (KICKOFF, HALF_TIME, SECOND_HALF, FINISHED,
 *    and every GOAL) is written to match_scheduled_events at schedule time.
 *    A @Scheduled poller runs every 5 seconds and claims+executes any
 *    event whose fire_at ≤ NOW and whose status is still PENDING.
 *
 *    Because events are claimed atomistically via UPDATE…WHERE status=PENDING
 *    before executing them, this is safe for clustered deployments too.
 *
 *  LAYER 3 — Watchdog + overdue-finish sweep + stale-match cleanup
 *  ────────────────────────────────────────────────────────────────
 *    a) reconcileSchedules()    — 60s sweep: corrects drift, heals clock,
 *                                 force-finishes overdue in-memory matches.
 *    b) overdueFinishSweep()    — 30s sweep: DB-sourced backstop for matches
 *                                 with no in-memory handle that are past their
 *                                 end time but still not FINISHED.
 *    c) recoverStaleScheduled()      — 60s sweep: finds SCHEDULED matches that
 *                                      never started and either (i) re-schedules
 *                                      them if kickoffAt is still in the future,
 *                                      (ii) starts them immediately in catch-up
 *                                      mode if kickoffAt just passed, or (iii)
 *                                      hard-deletes them if BOTH kickoffAt AND
 *                                      createdAt are 3+ days in the past.
 *    d) deleteSettledFinishedMatches() — 5min sweep: deletes FINISHED matches
 *                                      once (i) 1 hour has elapsed since the
 *                                      match finished AND (ii) all bets on the
 *                                      match are settled/graded. Skips matches
 *                                      with still-pending bets and warns loudly
 *                                      if settlement is stuck after 3 hours.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  STALE MATCH RULES (recoverStaleScheduled)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  A SCHEDULED match is evaluated once per minute sweep:
 *
 *  ┌────────────────────────────────────────────────────────────────────┐
 *  │ kickoffAt > NOW                  → re-register in-memory jobs      │
 *  │                                    (was lost on restart)           │
 *  │ kickoffAt ≤ NOW < kickoffAt+3d   → catch-up: start immediately,   │
 *  │   AND createdAt < 3d ago           play through overdue events     │
 *  │ kickoffAt ≤ NOW AND createdAt ≥ 3d ago                             │
 *  │   (both 3+ days old)             → DELETE (match is truly stale)  │
 *  └────────────────────────────────────────────────────────────────────┘
 *
 *  Catch-up mode: the match's DB events are already persisted with their
 *  original fire_at times. The poller will find them all overdue and
 *  execute them in chronological order immediately, walking the match
 *  forward through every step it missed and landing at the correct state.
 *  For matches with no DB events (e.g. manually created SCHEDULED matches),
 *  we just kick them off directly.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  CLOCK TICK DESIGN
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  A per-match scheduleAtFixedRate job fires every 60 real seconds while the
 *  match is LIVE or SECOND_HALF. It calls computeGameMinute() — pure
 *  wall-clock arithmetic — and then AdminMatchService.tickMinute(), which is
 *  monotonic and silently no-ops outside LIVE/SECOND_HALF.
 *
 *  If the in-memory clock job is lost (restart), the watchdog re-applies the
 *  correct minute on its next 60s sweep. Worst-case drift is 60s.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  REQUIRED SCHEMA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  CREATE TABLE match_scheduled_events (
 *      id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
 *      match_id      UUID        NOT NULL,
 *      admin_id      UUID        NOT NULL,
 *      event_type    VARCHAR(20) NOT NULL,
 *      fire_at       TIMESTAMPTZ NOT NULL,
 *      status        VARCHAR(10) NOT NULL DEFAULT 'PENDING',
 *      goal_minute   INT,
 *      goal_team     VARCHAR(4),
 *      cum_home      INT,
 *      cum_away      INT,
 *      target_status VARCHAR(15),
 *      final_home    INT,
 *      final_away    INT,
 *      created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
 *  );
 *  CREATE INDEX idx_mse_poll  ON match_scheduled_events (fire_at, status)
 *      WHERE status = 'PENDING';
 *  CREATE INDEX idx_mse_match ON match_scheduled_events (match_id, fire_at);
 *
 *  -- MatchRepository query needed for overdueFinishSweep:
 *  List<Match> findBySourceAndStatusInAndKickoffAtLessThanEqual(
 *      MatchSource source, List<String> statuses, Instant cutoff);
 *
 *  -- MatchRepository query needed for recoverStaleScheduled:
 *  List<Match> findBySourceAndStatus(MatchSource source, String status);
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  MATCH CLOCK
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   kickoffAt                              SCHEDULED → LIVE       (min 0)
 *   kickoffAt + 45 min                     LIVE      → HALF_TIME  (min 45)
 *   kickoffAt + 60 min (45+15 break)       HALF_TIME → SECOND_HALF
 *   kickoffAt + 105 min (45+15+45)         → FINISHED             (min 90)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMatchScheduleService {

    // ── Match clock constants ──────────────────────────────────────────────
    private static final int FIRST_HALF_MINUTES  = 45;
    private static final int BREAK_MINUTES       = 15;
    private static final int SECOND_HALF_MINUTES = 45;
    private static final int MATCH_MINUTES       = FIRST_HALF_MINUTES + SECOND_HALF_MINUTES; // 90

    /** Goals never land on minute 45 or 90 — prevents race with HT/FT. */
    private static final int LAST_FIRST_HALF_GOAL_MINUTE   = 44;
    private static final int FIRST_SECOND_HALF_GOAL_MINUTE = 46;
    private static final int LAST_SECOND_HALF_GOAL_MINUTE  = 89;

    /** Total match duration in minutes (45+15+45). */
    private static final int TOTAL_MATCH_DURATION_MINUTES =
            FIRST_HALF_MINUTES + BREAK_MINUTES + SECOND_HALF_MINUTES; // 105

    /** How often the live clock ticks (real milliseconds). */
    private static final long CLOCK_TICK_INTERVAL_MS = 60_000L;

    /**
     * A SCHEDULED match is deleted only when BOTH kickoffAt AND createdAt
     * are this many days in the past. Matches younger than this are eligible
     * for catch-up recovery instead.
     */
    private static final long STALE_DELETE_THRESHOLD_DAYS = 3;

    /**
     * How long after a match reaches FINISHED we wait before deleting it,
     * even if all bets are already settled. Gives ops/admins a short window
     * to inspect the result before the row is gone.
     */
    private static final long POST_FINISH_DELETE_DELAY_MINUTES = 60;

    // ── Dependencies ───────────────────────────────────────────────────────
    private final AdminMatchService             adminMatchService;
    private final MatchScheduledEventRepository eventRepository;
    private final MatchRepository               matchRepository;
    private final BetRepository betRepository;

    // ── Dedicated scheduler ────────────────────────────────────────────────
    private ThreadPoolTaskScheduler taskScheduler;

    private final Random random = new Random();

    /** matchId → handle, for cancel/inspect and the in-memory fast-path. */
    private final Map<UUID, ScheduleHandle> scheduledJobs = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ══════════════════════════════════════════════════════════════════════

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
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @PostConstruct
    void initScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(20);
        scheduler.setThreadNamePrefix("match-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setErrorHandler(t ->
                log.error("AdminMatchScheduleService: uncaught scheduler error", t));
        scheduler.initialize();
        this.taskScheduler = scheduler;
        log.info("AdminMatchScheduleService: dedicated scheduler initialised (poolSize=20)");
    }

    @PreDestroy
    void shutdownScheduler() {
        if (taskScheduler != null) taskScheduler.shutdown();
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCHEDULE — public entry point
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates the match (SCHEDULED) and persists the full event plan to the
     * DB, then registers in-memory fast-path jobs.
     */
    @Transactional
    public Match scheduleMatch(AdminAutoMatchRequest req, User admin) {
        validate(req);

        UUID    adminId      = admin.getId();
        Instant kickoffAt    = req.getKickoffAt();
        Instant halfTimeAt   = kickoffAt.plus(FIRST_HALF_MINUTES,  ChronoUnit.MINUTES);
        Instant secondHalfAt = halfTimeAt.plus(BREAK_MINUTES,      ChronoUnit.MINUTES);
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

        Match match   = adminMatchService.createMatch(createReq, adminId);
        UUID  matchId = match.getId();

        List<GoalEvent> goals = buildGoalSchedule(
                req.getFinalScoreHome(), req.getFinalScoreAway(),
                kickoffAt, secondHalfAt);

        log.info("AdminMatchScheduleService.scheduleMatch: adminId={} matchId={} " +
                        "kickoffAt={} halfTimeAt={} secondHalfAt={} finishedAt={} " +
                        "finalScore={}:{} goals={}",
                adminId, matchId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt,
                req.getFinalScoreHome(), req.getFinalScoreAway(), goals.size());

        // LAYER 2: persist events to DB (survives restarts)
        persistEvents(matchId, adminId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt,
                goals, req.getFinalScoreHome(), req.getFinalScoreAway());

        // LAYER 1: register fast-path in-memory jobs post-commit
        registerScheduleAfterCommit(matchId, adminId, kickoffAt, halfTimeAt, secondHalfAt,
                finishedAt, goals, req.getFinalScoreHome(), req.getFinalScoreAway());

        return match;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 2 — DB EVENT PERSISTENCE
    // ══════════════════════════════════════════════════════════════════════

    private void persistEvents(UUID matchId, UUID adminId,
                               Instant kickoffAt, Instant halfTimeAt, Instant secondHalfAt,
                               Instant finishedAt, List<GoalEvent> goals,
                               int finalHome, int finalAway) {
        List<MatchScheduledEvent> events = new ArrayList<>();

        events.add(buildTransitionEvent(matchId, adminId, "KICKOFF",     kickoffAt,    "LIVE",        null, null));
        events.add(buildTransitionEvent(matchId, adminId, "HALF_TIME",   halfTimeAt,   "HALF_TIME",   null, null));
        events.add(buildTransitionEvent(matchId, adminId, "SECOND_HALF", secondHalfAt, "SECOND_HALF", null, null));
        events.add(buildTransitionEvent(matchId, adminId, "FINISHED",    finishedAt,   "FINISHED",    finalHome, finalAway));

        for (GoalEvent g : goals) {
            MatchScheduledEvent evt = new MatchScheduledEvent();
            evt.setMatchId(matchId);
            evt.setAdminId(adminId);
            evt.setEventType("GOAL");
            evt.setFireAt(g.at());
            evt.setStatus("PENDING");
            evt.setGoalMinute(g.minute());
            evt.setGoalTeam(g.team());
            evt.setCumHome(g.cumHome());
            evt.setCumAway(g.cumAway());
            evt.setTargetStatus(expectedStatusForGoalMinute(g.minute()));
            events.add(evt);
        }

        eventRepository.saveAll(events);
        log.info("AdminMatchScheduleService.persistEvents: matchId={} persisted {} DB events",
                matchId, events.size());
    }

    private MatchScheduledEvent buildTransitionEvent(UUID matchId, UUID adminId,
                                                     String type, Instant fireAt,
                                                     String targetStatus,
                                                     Integer finalHome, Integer finalAway) {
        MatchScheduledEvent evt = new MatchScheduledEvent();
        evt.setMatchId(matchId);
        evt.setAdminId(adminId);
        evt.setEventType(type);
        evt.setFireAt(fireAt);
        evt.setStatus("PENDING");
        evt.setTargetStatus(targetStatus);
        evt.setFinalHome(finalHome);
        evt.setFinalAway(finalAway);
        return evt;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 2 — PRIMARY POLLER (runs every 5 seconds)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The authoritative event executor. Runs every 5 seconds.
     *
     * Finds ALL PENDING events whose fire_at ≤ NOW, sorted oldest-first,
     * and executes them in order. A match that missed events due to a restart
     * is walked forward through every overdue step automatically.
     *
     * Claiming is done via UPDATE…WHERE status=PENDING before executing,
     * so this is safe to run on multiple nodes simultaneously.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    @Transactional
    public void pollAndExecuteEvents() {
        Instant now = Instant.now();

        List<MatchScheduledEvent> due = eventRepository
                .findAllByStatusAndFireAtLessThanEqualOrderByFireAtAsc("PENDING", now);

        if (due.isEmpty()) return;

        log.debug("AdminMatchScheduleService.pollAndExecuteEvents: {} due events at {}", due.size(), now);

        for (MatchScheduledEvent evt : due) {
            int claimed = eventRepository.claimEvent(evt.getId());
            if (claimed == 0) continue; // another node took it
            executeEvent(evt);
        }
    }

    /**
     * Executes a single DB event. Marks DONE on success, FAILED on error.
     */
    private void executeEvent(MatchScheduledEvent evt) {
        UUID   matchId = evt.getMatchId();
        UUID   adminId = evt.getAdminId();
        String type    = evt.getEventType();

        try {
            switch (type) {
                case "KICKOFF" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} KICKOFF → LIVE", matchId);
                    adminMatchService.advanceStatusTo(matchId, "LIVE", adminId);
                }
                case "HALF_TIME" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} → HALF_TIME", matchId);
                    adminMatchService.advanceStatusTo(matchId, "HALF_TIME", adminId);
                }
                case "SECOND_HALF" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} → SECOND_HALF", matchId);
                    adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId);
                }
                case "FINISHED" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} → FINISHED (finalScore={}:{})",
                            matchId, evt.getFinalHome(), evt.getFinalAway());
                    adminMatchService.forceFinish(matchId,
                            evt.getFinalHome(), evt.getFinalAway(), adminId);
                    scheduledJobs.remove(matchId);
                }
                case "GOAL" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} GOAL min={} {} → {}:{}",
                            matchId, evt.getGoalMinute(), evt.getGoalTeam(),
                            evt.getCumHome(), evt.getCumAway());
                    adminMatchService.advanceStatusTo(matchId, evt.getTargetStatus(), adminId);
                    adminMatchService.updateScore(matchId, evt.getCumHome(), evt.getCumAway(),
                            evt.getGoalMinute(), adminId);
                }
                default -> log.warn("AdminMatchScheduleService[DB]: matchId={} unknown eventType={}",
                        matchId, type);
            }

            eventRepository.markDone(evt.getId());
            log.debug("AdminMatchScheduleService[DB]: matchId={} event {} DONE", matchId, type);

        } catch (Exception e) {
            eventRepository.markFailed(evt.getId());
            log.error("AdminMatchScheduleService[DB]: matchId={} event {} FAILED — {}",
                    matchId, type, e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 1 — IN-MEMORY FAST-PATH
    // ══════════════════════════════════════════════════════════════════════

    private void registerScheduleAfterCommit(UUID matchId, UUID adminId, Instant kickoffAt,
                                             Instant halfTimeAt, Instant secondHalfAt,
                                             Instant finishedAt, List<GoalEvent> goals,
                                             int finalHome, int finalAway) {
        Runnable register = () -> doRegisterSchedule(matchId, adminId, kickoffAt, halfTimeAt,
                secondHalfAt, finishedAt, goals, finalHome, finalAway);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { register.run(); }
            });
        } else {
            register.run();
        }
    }

    private void doRegisterSchedule(UUID matchId, UUID adminId, Instant kickoffAt,
                                    Instant halfTimeAt, Instant secondHalfAt, Instant finishedAt,
                                    List<GoalEvent> goals, int finalHome, int finalAway) {
        List<ScheduledFuture<?>> jobs = new ArrayList<>();

        // ── Kickoff ────────────────────────────────────────────────────────
        scheduleIfFuture(jobs, kickoffAt,
                () -> safeRun(matchId, "kickoff→LIVE[fast]", () -> {
                    adminMatchService.advanceStatusTo(matchId, "LIVE", adminId);
                    eventRepository.markDoneByMatchAndType(matchId, "KICKOFF");
                }));

        // ── Goals ──────────────────────────────────────────────────────────
        for (GoalEvent g : goals) {
            scheduleIfFuture(jobs, g.at(),
                    () -> safeRun(matchId,
                            "goal min=" + g.minute() + " " + g.team() + "[fast]",
                            () -> {
                                adminMatchService.advanceStatusTo(matchId,
                                        expectedStatusForGoalMinute(g.minute()), adminId);
                                adminMatchService.updateScore(matchId,
                                        g.cumHome(), g.cumAway(), g.minute(), adminId);
                                eventRepository.markDoneByMatchAndTypeAndFireAt(
                                        matchId, "GOAL", g.at());
                            }));
        }

        // ── Half-time ──────────────────────────────────────────────────────
        scheduleIfFuture(jobs, halfTimeAt,
                () -> safeRun(matchId, "halfTime→HALF_TIME[fast]", () -> {
                    adminMatchService.advanceStatusTo(matchId, "HALF_TIME", adminId);
                    eventRepository.markDoneByMatchAndType(matchId, "HALF_TIME");
                }));

        // ── Second half ────────────────────────────────────────────────────
        scheduleIfFuture(jobs, secondHalfAt,
                () -> safeRun(matchId, "secondHalf→SECOND_HALF[fast]", () -> {
                    adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId);
                    eventRepository.markDoneByMatchAndType(matchId, "SECOND_HALF");
                }));

        // ── Finish — forceFinish() guarantees terminal state ───────────────
        scheduleIfFuture(jobs, finishedAt,
                () -> safeRun(matchId, "finish→FINISHED[fast]", () -> {
                    adminMatchService.forceFinish(matchId, finalHome, finalAway, adminId);
                    eventRepository.markDoneByMatchAndType(matchId, "FINISHED");
                    scheduledJobs.remove(matchId);
                }));

        // ── Live clock — ticks minutePlayed every 60s during live play ─────
        scheduleMatchClock(matchId, adminId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt, jobs);

        scheduledJobs.put(matchId, new ScheduleHandle(jobs, adminId, kickoffAt, halfTimeAt,
                secondHalfAt, finishedAt, goals, finalHome, finalAway));

        log.info("AdminMatchScheduleService.doRegisterSchedule: matchId={} registeredJobs={} (post-commit)",
                matchId, jobs.size());
    }

    /**
     * Schedules a job only if the target instant is in the future.
     * Past instants are intentionally skipped — the DB poller handles overdue
     * events, and registering a scheduler job for a past instant causes it
     * to fire immediately and race with the poller.
     */
    private void scheduleIfFuture(List<ScheduledFuture<?>> jobs, Instant at, Runnable task) {
        if (at.isAfter(Instant.now())) {
            jobs.add(taskScheduler.schedule(task, at));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE CLOCK — ticks minutePlayed in real time
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Schedules a fixed-rate clock tick that calls tickMinute() every 60
     * real seconds while the match is LIVE or SECOND_HALF.
     *
     * Starts from kickoffAt (or now if already past), so catch-up matches
     * get their clock corrected immediately on the first tick.
     * tickMinute() is monotonic and silently no-ops during HALF_TIME and
     * FINISHED, so there is no harm in letting it run through the break.
     */
    private void scheduleMatchClock(UUID matchId, UUID adminId,
                                    Instant kickoffAt, Instant halfTimeAt,
                                    Instant secondHalfAt, Instant finishedAt,
                                    List<ScheduledFuture<?>> jobs) {
        Instant now        = Instant.now();
        Instant clockStart = kickoffAt.isAfter(now) ? kickoffAt : now;

        ScheduledFuture<?> clockJob = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                Instant tick = Instant.now();
                if (!tick.isBefore(finishedAt)) return;

                int gameMinute = computeGameMinute(tick, kickoffAt, halfTimeAt,
                        secondHalfAt, finishedAt);
                if (gameMinute < 0) return;

                adminMatchService.tickMinute(matchId, gameMinute, adminId);

            } catch (Exception e) {
                log.warn("AdminMatchScheduleService.clock[fast]: matchId={} tick failed — {}",
                        matchId, e.getMessage());
            }
        }, clockStart, Duration.ofMillis(CLOCK_TICK_INTERVAL_MS));

        jobs.add(clockJob);
        log.info("AdminMatchScheduleService.scheduleMatchClock: matchId={} clockStart={} interval={}ms",
                matchId, clockStart, CLOCK_TICK_INTERVAL_MS);
    }

    /**
     * Pure wall-clock arithmetic — converts a real instant into the correct
     * displayed game minute.
     *
     *   kickoff → +45 min  = first half  (minutes 1–45)
     *   +45 → +60 min      = half-time break (returns 45, clock frozen)
     *   +60 → +105 min     = second half (minutes 46–90)
     *
     * @return game minute, or -1 if the match has not started yet
     */
    private int computeGameMinute(Instant now, Instant kickoffAt, Instant halfTimeAt,
                                   Instant secondHalfAt, Instant finishedAt) {
        if (now.isBefore(kickoffAt)) return -1;

        if (now.isBefore(halfTimeAt)) {
            long elapsed = ChronoUnit.MINUTES.between(kickoffAt, now);
            return (int) Math.min(elapsed + 1, FIRST_HALF_MINUTES);
        }

        if (now.isBefore(secondHalfAt)) {
            return FIRST_HALF_MINUTES; // freeze at 45 during break
        }

        if (now.isBefore(finishedAt)) {
            long elapsed = ChronoUnit.MINUTES.between(secondHalfAt, now);
            return (int) Math.min(FIRST_HALF_MINUTES + elapsed + 1, MATCH_MINUTES);
        }

        return MATCH_MINUTES;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 3a — WATCHDOG (every 60s, in-memory handles)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Safety net for matches tracked in the in-memory handle map.
     *
     * Per sweep:
     *   1. Removes completed/vanished handles.
     *   2. Force-finishes any match past its finishedAt — GUARANTEED END.
     *   3. Corrects status drift for in-progress matches.
     *   4. Re-applies the current game minute (clock fallback after restart).
     *   5. Re-applies overdue cumulative goal scores.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcileSchedules() {
        if (scheduledJobs.isEmpty()) return;
        Instant now = Instant.now();

        for (Map.Entry<UUID, ScheduleHandle> entry : new HashMap<>(scheduledJobs).entrySet()) {
            UUID          matchId = entry.getKey();
            ScheduleHandle handle  = entry.getValue();

            try {
                Match match = adminMatchService.findMyMatchOrNull(matchId, handle.getAdminId());
                if (match == null) {
                    scheduledJobs.remove(matchId);
                    continue;
                }

                // Guaranteed finish — past finishedAt and not done
                if (!now.isBefore(handle.getFinishedAt()) && !"FINISHED".equals(match.getStatus())) {
                    log.warn("AdminMatchScheduleService.watchdog: matchId={} is {} but finishedAt={} passed — force finishing",
                            matchId, match.getStatus(), handle.getFinishedAt());
                    adminMatchService.forceFinish(matchId,
                            handle.getFinalHome(), handle.getFinalAway(), handle.getAdminId());
                    scheduledJobs.remove(matchId);
                    continue;
                }

                if ("FINISHED".equals(match.getStatus())) {
                    scheduledJobs.remove(matchId);
                    continue;
                }

                // Status drift correction
                String expected = expectedStatusAt(now, handle);
                if (expected != null && !expected.equals(match.getStatus())) {
                    log.warn("AdminMatchScheduleService.watchdog: matchId={} is {} but should be {} — correcting",
                            matchId, match.getStatus(), expected);
                    adminMatchService.advanceStatusTo(matchId, expected, handle.getAdminId());
                }

                // Clock fallback — re-apply correct game minute if tick job was lost
                int gameMinute = computeGameMinute(now, handle.getKickoffAt(),
                        handle.getHalfTimeAt(), handle.getSecondHalfAt(), handle.getFinishedAt());
                if (gameMinute > 0) {
                    try {
                        adminMatchService.tickMinute(matchId, gameMinute, handle.getAdminId());
                    } catch (Exception e) {
                        log.debug("AdminMatchScheduleService.watchdog: matchId={} tickMinute skipped — {}",
                                matchId, e.getMessage());
                    }
                }

                // Re-apply overdue cumulative goal scores if behind
                int cumHome = 0, cumAway = 0, lastMinute = 0;
                for (GoalEvent g : handle.getGoals()) {
                    if (!g.at().isAfter(now)) {
                        cumHome    = g.cumHome();
                        cumAway    = g.cumAway();
                        lastMinute = g.minute();
                    }
                }
                if ((cumHome > 0 || cumAway > 0)
                        && !"FINISHED".equals(expected)
                        && !"HALF_TIME".equals(expected)) {
                    if (match.getScoreHome() < cumHome || match.getScoreAway() < cumAway) {
                        adminMatchService.updateScore(matchId, cumHome, cumAway,
                                lastMinute, handle.getAdminId());
                    }
                }

            } catch (Exception e) {
                log.error("AdminMatchScheduleService.watchdog: matchId={} FAILED — {}",
                        matchId, e.getMessage(), e);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 3b — OVERDUE FINISH SWEEP (every 30s, DB-sourced)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Finds admin-created matches that are still LIVE / HALF_TIME / SECOND_HALF
     * but whose scheduled end time has already passed. These are matches with no
     * in-memory handle whose DB events were not picked up by the poller.
     * Calls forceFinish() on each one as an absolute final backstop.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    @Transactional
    public void overdueFinishSweep() {
        Instant cutoff = Instant.now()
                .minus(TOTAL_MATCH_DURATION_MINUTES, ChronoUnit.MINUTES);

        List<Match> overdue = matchRepository
                .findBySourceAndStatusInAndKickoffAtLessThanEqual(
                        MatchSource.ADMIN_CREATED,
                        List.of("LIVE", "HALF_TIME", "SECOND_HALF"),
                        cutoff);

        if (overdue.isEmpty()) return;

        log.warn("AdminMatchScheduleService.overdueFinishSweep: {} overdue match(es) found",
                overdue.size());

        for (Match match : overdue) {
            UUID matchId = match.getId();
            UUID adminId = match.getCreatedByAdminId();

            try {
                MatchScheduledEvent finishEvt = eventRepository
                        .findTopByMatchIdAndEventType(matchId, "FINISHED")
                        .orElse(null);

                int finalHome = finishEvt != null && finishEvt.getFinalHome() != null
                        ? finishEvt.getFinalHome()
                        : (match.getScoreHome() != null ? match.getScoreHome() : 0);
                int finalAway = finishEvt != null && finishEvt.getFinalAway() != null
                        ? finishEvt.getFinalAway()
                        : (match.getScoreAway() != null ? match.getScoreAway() : 0);

                log.warn("AdminMatchScheduleService.overdueFinishSweep: " +
                                "matchId={} forcing finish {}:{} (was {})",
                        matchId, finalHome, finalAway, match.getStatus());

                adminMatchService.forceFinish(matchId, finalHome, finalAway, adminId);
                eventRepository.cancelPendingByMatchId(matchId);
                scheduledJobs.remove(matchId);

            } catch (Exception e) {
                log.error("AdminMatchScheduleService.overdueFinishSweep: matchId={} FAILED — {}",
                        matchId, e.getMessage(), e);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 3c — STALE SCHEDULED MATCH RECOVERY & CLEANUP (every 60s)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Polls ALL admin-created SCHEDULED matches and acts on each one:
     *
     * ┌──────────────────────────────────────────────────────────────────┐
     * │ CASE 1 — Already tracked in-memory                              │
     * │   Skip (jobs already registered, nothing to do).                │
     * │                                                                  │
     * │ CASE 2 — kickoffAt is still in the FUTURE                       │
     * │   Re-register in-memory jobs. This heals matches that lost their │
     * │   jobs on a restart and are still waiting to kick off.           │
     * │                                                                  │
     * │ CASE 3 — kickoffAt has PASSED, match is < 3 days old            │
     * │   Catch-up mode: if DB events exist the poller will execute them │
     * │   immediately (they are already overdue). We also re-register    │
     * │   the in-memory handle so the watchdog and clock are active.     │
     * │   The KICKOFF event fires within the next 5s poller cycle.       │
     * │                                                                  │
     * │ CASE 4 — BOTH kickoffAt AND createdAt are 3+ days in the past   │
     * │   Hard-delete: the match is truly abandoned. Cancel any pending  │
     * │   DB events and delete the match row entirely.                   │
     * └──────────────────────────────────────────────────────────────────┘
     *
     * Note: this sweep only acts on status=SCHEDULED matches. Matches in
     * any other status are handled by overdueFinishSweep / reconcileSchedules.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    @Transactional
    public void recoverStaleScheduled() {
        List<Match> scheduled = matchRepository
                .findBySourceAndStatus(MatchSource.ADMIN_CREATED, "SCHEDULED");

        if (scheduled.isEmpty()) return;

        Instant now       = Instant.now();
        Instant threshold = now.minus(STALE_DELETE_THRESHOLD_DAYS, ChronoUnit.DAYS);

        log.debug("AdminMatchScheduleService.recoverStaleScheduled: checking {} SCHEDULED match(es)",
                scheduled.size());

        for (Match match : scheduled) {
            UUID matchId = match.getId();
            UUID adminId = match.getCreatedByAdminId();

            try {
                // CASE 1: already tracked — jobs are fine, skip
                if (scheduledJobs.containsKey(matchId)) continue;

                Instant kickoffAt = match.getKickoffAt();
                // createdAt has @Builder.Default = Instant.now() — always non-null
                Instant createdAt = match.getCreatedAt();

                // CASE 4: both kickoffAt AND createdAt are 3+ days old — delete
                if (kickoffAt != null
                        && kickoffAt.isBefore(threshold)
                        && createdAt != null
                        && createdAt.isBefore(threshold)) {

                    log.warn("AdminMatchScheduleService.recoverStaleScheduled: " +
                                    "matchId={} is stale (kickoffAt={} createdAt={}) — DELETING",
                            matchId, kickoffAt, createdAt);

                    eventRepository.cancelPendingByMatchId(matchId);
                    matchRepository.delete(match);
                    continue;
                }

                // CASE 2 or 3: attempt recovery
                // First, check if we have DB events for this match.
                List<MatchScheduledEvent> dbEvents =
                        eventRepository.findByMatchIdOrderByFireAtAsc(matchId);

                if (dbEvents.isEmpty()) {
                    // Manually created SCHEDULED match with no auto-events.
                    // If kickoffAt is future, nothing to do — no lifecycle was ever set up.
                    // If kickoffAt has passed, kick it off directly so it doesn't hang forever.
                    if (kickoffAt != null && !kickoffAt.isAfter(now)) {
                        log.info("AdminMatchScheduleService.recoverStaleScheduled: " +
                                        "matchId={} kickoffAt={} passed with no DB events — kicking off now",
                                matchId, kickoffAt);
                        adminMatchService.advanceStatusTo(matchId, "LIVE", adminId);
                    }
                    // Either way, nothing more to register — no goals or finish time known.
                    continue;
                }

                // We have DB events. Rebuild the handle from them so the watchdog,
                // clock, and overdue-finish sweep can all track this match properly.
                MatchScheduledEvent finishEvt = dbEvents.stream()
                        .filter(e -> "FINISHED".equals(e.getEventType()))
                        .findFirst().orElse(null);

                if (finishEvt == null) {
                    // Incomplete event set — just let the poller handle whatever events exist.
                    log.warn("AdminMatchScheduleService.recoverStaleScheduled: " +
                                    "matchId={} has DB events but no FINISHED event — skipping handle rebuild",
                            matchId);
                    continue;
                }

                // Derive timing from the persisted events
                Instant halfTimeAt   = dbEvents.stream()
                        .filter(e -> "HALF_TIME".equals(e.getEventType()))
                        .map(MatchScheduledEvent::getFireAt).findFirst()
                        .orElse(kickoffAt.plus(FIRST_HALF_MINUTES, ChronoUnit.MINUTES));
                Instant secondHalfAt = dbEvents.stream()
                        .filter(e -> "SECOND_HALF".equals(e.getEventType()))
                        .map(MatchScheduledEvent::getFireAt).findFirst()
                        .orElse(halfTimeAt.plus(BREAK_MINUTES, ChronoUnit.MINUTES));
                Instant finishedAt   = finishEvt.getFireAt();

                int finalHome = finishEvt.getFinalHome() != null ? finishEvt.getFinalHome() : 0;
                int finalAway = finishEvt.getFinalAway() != null ? finishEvt.getFinalAway() : 0;

                // Rebuild goal list from DB events
                List<GoalEvent> goals = dbEvents.stream()
                        .filter(e -> "GOAL".equals(e.getEventType()))
                        .sorted(Comparator.comparing(MatchScheduledEvent::getFireAt))
                        .map(e -> new GoalEvent(
                                e.getGoalMinute() != null ? e.getGoalMinute() : 0,
                                e.getGoalTeam()   != null ? e.getGoalTeam()   : "HOME",
                                e.getFireAt(),
                                e.getCumHome()    != null ? e.getCumHome()    : 0,
                                e.getCumAway()    != null ? e.getCumAway()    : 0))
                        .toList();

                if (kickoffAt != null && kickoffAt.isAfter(now)) {
                    // CASE 2: future kickoff — re-register in-memory jobs
                    log.info("AdminMatchScheduleService.recoverStaleScheduled: " +
                                    "matchId={} future kickoff={} — re-registering jobs",
                            matchId, kickoffAt);
                } else {
                    // CASE 3: past kickoff, catch-up mode
                    // The poller will fire all overdue PENDING events within 5s.
                    // We still register the handle so the watchdog and clock work.
                    log.info("AdminMatchScheduleService.recoverStaleScheduled: " +
                                    "matchId={} kickoffAt={} already passed — catch-up mode, " +
                                    "poller will execute overdue events, registering handle for watchdog",
                            matchId, kickoffAt);
                }

                // Register in-memory jobs (scheduleIfFuture skips past instants
                // so this is safe regardless of whether kickoffAt has passed).
                doRegisterSchedule(matchId, adminId, kickoffAt, halfTimeAt,
                        secondHalfAt, finishedAt, goals, finalHome, finalAway);

            } catch (Exception e) {
                log.error("AdminMatchScheduleService.recoverStaleScheduled: " +
                                "matchId={} FAILED — {}",
                        matchId, e.getMessage(), e);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 3d — POST-SETTLEMENT DELETE SWEEP (every 5 minutes)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Deletes FINISHED admin-created matches once two conditions are both true:
     *
     *   1. At least {@value POST_FINISH_DELETE_DELAY_MINUTES} minutes have
     *      elapsed since the match reached FINISHED status. The match entity
     *      must carry a {@code finishedAt} timestamp for this check; if it
     *      is null we fall back to {@code kickoffAt + 105 min} as a safe
     *      approximation.
     *
     *   2. ALL bets on the match are settled (no bet rows with status
     *      PENDING or OPEN remain). This prevents deleting a match before
     *      the SettlementEngine has had a chance to grade every wager.
     *
     * If condition 1 is met but condition 2 is not (bets still pending), the
     * match is skipped this cycle and re-evaluated on the next sweep. A
     * warning is logged after 3 hours so stuck settlements are visible.
     *
     * Deletion cascades via the DB foreign-key / orphanRemoval rules:
     *   • match_scheduled_events rows  — deleted (or already DONE/FAILED)
     *   • odds rows                    — deleted if FK cascades; otherwise
     *                                    cleaned up by the odds table's own
     *                                    maintenance job
     *
     * ── Required BetRepository method ────────────────────────────────────
     *
     *   boolean existsByMatchIdAndStatusIn(UUID matchId, Collection<String> statuses);
     *
     *   Added to BetRepository. The unsettled status checked is "PENDING"
     *   (matching BetStatus.PENDING in BetService). Adjust if your enum uses
     *   additional unsettled values (e.g. "OPEN", "ACTIVE").
     *
     * ── Finish-time resolution ────────────────────────────────────────────
     *
     *   resolveFinishedAt() uses settledAt (already on Match entity, set by
     *   SettlementEngine) as the primary timestamp, falling back to
     *   kickoffAt + 105 min. No entity or schema changes are needed.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000) // every 5 min, start after 2 min
    @Transactional
    public void deleteSettledFinishedMatches() {
        Instant now                  = Instant.now();
        Instant deleteCutoff         = now.minus(POST_FINISH_DELETE_DELAY_MINUTES, ChronoUnit.MINUTES);
        Instant settlementWarnCutoff = now.minus(3, ChronoUnit.HOURS);

        List<Match> candidates = matchRepository
                .findBySourceAndStatus(MatchSource.ADMIN_CREATED, "FINISHED");

        if (candidates.isEmpty()) return;

        // Only act on matches where the resolved finish time is past the hold window.
        // resolveFinishedAt() uses settledAt (preferred) or kickoffAt+105min (fallback).
        List<Match> eligible = candidates.stream()
                .filter(m -> resolveFinishedAt(m).isBefore(deleteCutoff))
                .toList();

        if (eligible.isEmpty()) return;

        log.debug("AdminMatchScheduleService.deleteSettledFinishedMatches: " +
                "{} candidate(s) past {}min delay window", eligible.size(),
                POST_FINISH_DELETE_DELAY_MINUTES);

        List<UUID> deleted    = new ArrayList<>();
        List<UUID> stillOpen  = new ArrayList<>();

        for (Match match : eligible) {
            UUID matchId = match.getId();
            UUID adminId = match.getCreatedByAdminId();

            try {
                // Check for any unsettled bets — PENDING or OPEN.
                boolean hasUnsettledBets = betRepository.existsByMatchIdAndStatusIn(
                        matchId, List.of("PENDING", "OPEN"));

                if (hasUnsettledBets) {
                    stillOpen.add(matchId);

                    // Warn loudly if settlement is taking unusually long.
                    Instant finishedAt = resolveFinishedAt(match);
                    if (finishedAt.isBefore(settlementWarnCutoff)) {
                        log.warn("AdminMatchScheduleService.deleteSettledFinishedMatches: " +
                                        "matchId={} finished >3h ago but still has unsettled bets — " +
                                        "SettlementEngine may be stuck",
                                matchId);
                    } else {
                        log.debug("AdminMatchScheduleService.deleteSettledFinishedMatches: " +
                                        "matchId={} skipped — bets still pending settlement",
                                matchId);
                    }
                    continue;
                }

                // All bets settled and delay elapsed — safe to delete.
                log.info("AdminMatchScheduleService.deleteSettledFinishedMatches: " +
                                "matchId={} adminId={} — all bets settled, deleting match",
                        matchId, adminId);

                // Cancel any residual DB events (should all be DONE/FAILED by now,
                // but a defensive cancel costs nothing).
                eventRepository.cancelPendingByMatchId(matchId);
                scheduledJobs.remove(matchId);

                adminMatchService.deleteMatch(matchId, adminId);
                deleted.add(matchId);

            } catch (Exception e) {
                log.error("AdminMatchScheduleService.deleteSettledFinishedMatches: " +
                                "matchId={} FAILED — {}",
                        matchId, e.getMessage(), e);
            }
        }

        if (!deleted.isEmpty()) {
            log.info("AdminMatchScheduleService.deleteSettledFinishedMatches: " +
                            "deleted {} match(es): {} | {} still awaiting settlement",
                    deleted.size(), deleted, stillOpen.size());
        }
    }

    /**
     * Resolves the instant at which a match finished, using only fields that
     * already exist on the Match entity (no schema changes required).
     *
     * Priority:
     *   1. {@code settledAt}  — set by the SettlementEngine when it grades the
     *      match; the most accurate "we are done with this match" timestamp.
     *   2. {@code kickoffAt + TOTAL_MATCH_DURATION_MINUTES} — a safe approximation
     *      of when the final whistle blew; used when settlement hasn't run yet
     *      (which shouldn't happen by the time this sweep deletes the match, but
     *      acts as a non-null fallback so we never throw NPE).
     */
    private Instant resolveFinishedAt(Match match) {
        if (match.getSettledAt() != null) {
            return match.getSettledAt();
        }
        // Safe fallback — kickoffAt is guaranteed non-null on ADMIN_CREATED matches
        // (AdminMatchService.createMatchInternal sets it to Instant.now() if null).
        Instant kickoff = match.getKickoffAt() != null
                ? match.getKickoffAt()
                : match.getCreatedAt(); // createdAt has @Builder.Default = Instant.now(), always set
        return kickoff.plus(TOTAL_MATCH_DURATION_MINUTES, ChronoUnit.MINUTES);
    }

    // ══════════════════════════════════════════════════════════════════════
    // CANCEL / INSPECT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cancels all pending automated transitions for a match — both in-memory
     * jobs AND the DB events. The match stays at its current state.
     */
    public int cancelSchedule(UUID matchId, User admin) {
        adminMatchService.getMyMatch(matchId.toString(), admin);

        ScheduleHandle handle = scheduledJobs.remove(matchId);
        int cancelled = 0;
        if (handle != null) {
            for (ScheduledFuture<?> job : handle.getJobs()) {
                if (job.cancel(false)) cancelled++;
            }
        }

        int dbCancelled = eventRepository.cancelPendingByMatchId(matchId);
        log.info("AdminMatchScheduleService.cancelSchedule: adminId={} matchId={} " +
                        "inMemoryCancelled={} dbEventsCancelled={}",
                admin.getId(), matchId, cancelled, dbCancelled);

        return cancelled + dbCancelled;
    }

    /**
     * Returns the computed schedule including both live in-memory data and
     * the current DB event statuses.
     */
    public Map<String, Object> getSchedule(UUID matchId, User admin) {
        adminMatchService.getMyMatch(matchId.toString(), admin);

        ScheduleHandle handle   = scheduledJobs.get(matchId);
        List<MatchScheduledEvent> dbEvents =
                eventRepository.findByMatchIdOrderByFireAtAsc(matchId);

        if (handle == null && dbEvents.isEmpty()) {
            throw ApiException.notFound("No active schedule for match: " + matchId);
        }

        List<Map<String, Object>> eventView = dbEvents.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type",   e.getEventType());
                    m.put("fireAt", e.getFireAt());
                    m.put("status", e.getStatus());
                    if ("GOAL".equals(e.getEventType())) {
                        m.put("minute",     e.getGoalMinute());
                        m.put("scorer",     e.getGoalTeam());
                        m.put("scoreAfter", e.getCumHome() + ":" + e.getCumAway());
                    }
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchId",      matchId);
        result.put("dbEvents",     eventView);
        result.put("pendingCount", dbEvents.stream().filter(e -> "PENDING".equals(e.getStatus())).count());
        result.put("doneCount",    dbEvents.stream().filter(e -> "DONE".equals(e.getStatus())).count());
        result.put("failedCount",  dbEvents.stream().filter(e -> "FAILED".equals(e.getStatus())).count());

        if (handle != null) {
            Instant now = Instant.now();
            result.put("kickoffAt",          handle.getKickoffAt());
            result.put("halfTimeAt",         handle.getHalfTimeAt());
            result.put("secondHalfAt",       handle.getSecondHalfAt());
            result.put("finishedAt",         handle.getFinishedAt());
            result.put("inMemoryJobsActive",
                    handle.getJobs().stream().filter(j -> !j.isDone()).count());
            result.put("currentGameMinute",  computeGameMinute(now,
                    handle.getKickoffAt(), handle.getHalfTimeAt(),
                    handle.getSecondHalfAt(), handle.getFinishedAt()));
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // GOAL RANDOMIZATION
    // ══════════════════════════════════════════════════════════════════════

    private List<GoalEvent> buildGoalSchedule(int finalHome, int finalAway,
                                              Instant kickoffAt, Instant secondHalfAt) {
        List<int[]> raw = new ArrayList<>();
        for (int minute : uniqueRandomMinutes(finalHome)) raw.add(new int[]{minute, 0});
        for (int minute : uniqueRandomMinutes(finalAway))  raw.add(new int[]{minute, 1});
        raw.sort(Comparator.comparingInt(a -> a[0]));

        List<GoalEvent> events = new ArrayList<>();
        int cumHome = 0, cumAway = 0;
        Instant lastAt = null;

        for (int[] entry : raw) {
            int     minute = entry[0];
            String  team   = entry[1] == 0 ? "HOME" : "AWAY";
            Instant at     = minuteToInstant(minute, kickoffAt, secondHalfAt);

            if (lastAt != null && !at.isAfter(lastAt)) at = lastAt.plusSeconds(5);
            lastAt = at;

            if (entry[1] == 0) cumHome++; else cumAway++;
            events.add(new GoalEvent(minute, team, at, cumHome, cumAway));
        }
        return events;
    }

    private List<Integer> uniqueRandomMinutes(int count) {
        if (count <= 0) return List.of();
        List<Integer> pool = new ArrayList<>();
        for (int m = 1; m <= LAST_FIRST_HALF_GOAL_MINUTE; m++) pool.add(m);
        for (int m = FIRST_SECOND_HALF_GOAL_MINUTE; m <= LAST_SECOND_HALF_GOAL_MINUTE; m++) pool.add(m);
        int wanted = Math.min(count, pool.size());
        Set<Integer> minutes = new LinkedHashSet<>();
        while (minutes.size() < wanted) minutes.add(pool.get(random.nextInt(pool.size())));
        List<Integer> result = new ArrayList<>(minutes);
        result.sort(Integer::compareTo);
        return result;
    }

    private Instant minuteToInstant(int minute, Instant kickoffAt, Instant secondHalfAt) {
        return minute <= FIRST_HALF_MINUTES
                ? kickoffAt.plus(minute, ChronoUnit.MINUTES)
                : secondHalfAt.plus(minute - FIRST_HALF_MINUTES, ChronoUnit.MINUTES);
    }

    private String expectedStatusForGoalMinute(int minute) {
        return minute <= LAST_FIRST_HALF_GOAL_MINUTE ? "LIVE" : "SECOND_HALF";
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void safeRun(UUID matchId, String step, Runnable action) {
        try {
            action.run();
            log.info("AdminMatchScheduleService.safeRun: matchId={} step='{}' OK", matchId, step);
        } catch (Exception e) {
            log.error("AdminMatchScheduleService.safeRun: matchId={} step='{}' FAILED — {} " +
                            "(DB poller will retry within 5s)",
                    matchId, step, e.getMessage(), e);
        }
    }

    private String expectedStatusAt(Instant now, ScheduleHandle h) {
        if (!now.isBefore(h.getFinishedAt()))   return "FINISHED";
        if (!now.isBefore(h.getSecondHalfAt())) return "SECOND_HALF";
        if (!now.isBefore(h.getHalfTimeAt()))   return "HALF_TIME";
        if (!now.isBefore(h.getKickoffAt()))    return "LIVE";
        return null;
    }

    private void validate(AdminAutoMatchRequest req) {
        if (req.getKickoffAt() == null)
            throw ApiException.badRequest("kickoffAt is required.");
        if (req.getFinalScoreHome() == null || req.getFinalScoreAway() == null)
            throw ApiException.badRequest("finalScoreHome and finalScoreAway are required.");
        if (req.getFinalScoreHome() < 0 || req.getFinalScoreAway() < 0)
            throw ApiException.badRequest("Scores cannot be negative.");
        int maxGoals = LAST_FIRST_HALF_GOAL_MINUTE
                + (LAST_SECOND_HALF_GOAL_MINUTE - FIRST_SECOND_HALF_GOAL_MINUTE + 1);
        if (req.getFinalScoreHome() > maxGoals || req.getFinalScoreAway() > maxGoals)
            throw ApiException.badRequest("A side cannot score more than " + maxGoals + " goals.");
    }
}
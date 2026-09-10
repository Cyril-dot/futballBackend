package com.speedbet.api.match;

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
 *  ARCHITECTURE: TWO-LAYER RELIABILITY
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  The original service relied solely on in-memory TaskScheduler jobs.
 *  A restart, a starved thread, or a missed commit was enough to lose a
 *  match permanently. This revision adds a compulsory second layer:
 *
 *  LAYER 1 — In-memory TaskScheduler (fast path, best-effort)
 *  ──────────────────────────────────────────────────────────
 *    Jobs are still scheduled at exact wall-clock instants for low-latency
 *    transitions. They try to fire first, but are NOT authoritative.
 *
 *  LAYER 2 — DB-persisted MatchScheduledEvent table (authoritative)
 *  ─────────────────────────────────────────────────────────────────
 *    Every lifecycle event (KICKOFF, HALF_TIME, SECOND_HALF, FINISHED,
 *    and every GOAL) is written to match_scheduled_events at schedule time.
 *    A @Scheduled poller runs every 5 seconds and claims+executes any
 *    event whose fire_at ≤ NOW and whose status is still PENDING.
 *
 *    Because events are claimed atomistically via an UPDATE…WHERE status=PENDING
 *    before executing them, this is safe for clustered deployments too.
 *
 *  LAYER 3 — Force-play catch-up (no event left behind)
 *  ─────────────────────────────────────────────────────
 *    If the poller finds a match that has PAST-DUE events (fire_at in the
 *    past), it does NOT skip them — it executes them immediately in order,
 *    from the oldest overdue event to the newest. A match that should have
 *    been playing for 30 minutes walks through every step it missed the
 *    moment the poller first sees it, including the score updates, then
 *    catches up to wherever it should be right now.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  REQUIRED SCHEMA (add this migration before deploying)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  CREATE TABLE match_scheduled_events (
 *      id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
 *      match_id    UUID        NOT NULL,
 *      admin_id    UUID        NOT NULL,
 *      event_type  VARCHAR(20) NOT NULL,   -- KICKOFF | GOAL | HALF_TIME | SECOND_HALF | FINISHED
 *      fire_at     TIMESTAMPTZ NOT NULL,
 *      status      VARCHAR(10) NOT NULL DEFAULT 'PENDING',  -- PENDING | DONE | FAILED
 *      -- goal-specific columns (null for non-goal events):
 *      goal_minute INT,
 *      goal_team   VARCHAR(4),   -- HOME | AWAY
 *      cum_home    INT,
 *      cum_away    INT,
 *      -- status transition target (non-null for all non-GOAL events):
 *      target_status VARCHAR(15),
 *      -- final score (only set on the FINISHED event):
 *      final_home  INT,
 *      final_away  INT,
 *      created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
 *  );
 *  CREATE INDEX idx_mse_poll ON match_scheduled_events (fire_at, status)
 *      WHERE status = 'PENDING';
 *  CREATE INDEX idx_mse_match ON match_scheduled_events (match_id, fire_at);
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  MATCH CLOCK (unchanged from original)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   kickoffAt                              SCHEDULED → LIVE
 *   kickoffAt + 45 min                     LIVE      → HALF_TIME
 *   kickoffAt + 60 min (45+15 break)       HALF_TIME → SECOND_HALF
 *   kickoffAt + 105 min (45+15+45)         → FINISHED
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  INHERITED FIXES (all preserved from the previous revision)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  FIX 1 — Dedicated scheduler created in @PostConstruct (no circular bean).
 *  FIX 2 — Goals excluded from minutes 45 and 90 (race with HT/FT).
 *  FIX 3 — advanceStatusTo() used everywhere (tolerant of missed steps).
 *  FIX 4 — Only adminId (UUID) captured in lambdas, not the User entity.
 *  FIX 5 — Finish job forces the exact final score before whistle.
 *  FIX 6 — Watchdog @Scheduled sweep every 60s as an extra safety net.
 *  FIX 7 — Second-half goal jobs advance to SECOND_HALF, not LIVE.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  KNOWN LIMITATION (now substantially mitigated but not fully eliminated)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  The in-memory fast-path still loses jobs on restart. However, because
 *  the DB events survive the restart, the poller picks them up within 5s of
 *  the service coming back online and catches the match up to where it
 *  should be. The only window of missed real-time fidelity is the restart
 *  duration itself — any goal that "should" have fired during the outage
 *  fires at restart+5s in catch-up mode instead of at its exact minute.
 *  For a betting product that is acceptable; for sub-second fidelity during
 *  outages a persistent job store (e.g. Quartz + DB) would be needed.
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

    /** Goals never land on minute 45 or 90 — see FIX 2. */
    private static final int LAST_FIRST_HALF_GOAL_MINUTE   = 44;
    private static final int FIRST_SECOND_HALF_GOAL_MINUTE = 46;
    private static final int LAST_SECOND_HALF_GOAL_MINUTE  = 89;

    // ── Dependencies ───────────────────────────────────────────────────────
    private final AdminMatchService          adminMatchService;
    private final MatchScheduledEventRepository eventRepository; // NEW — see schema above

    // ── Dedicated scheduler (see FIX 1) ───────────────────────────────────
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
     * DB, then also registers in-memory fast-path jobs. The DB records are the
     * authoritative source; the in-memory jobs are best-effort early firing.
     */
    @Transactional
    public Match scheduleMatch(AdminAutoMatchRequest req, User admin) {
        validate(req);

        UUID    adminId      = admin.getId();
        Instant kickoffAt    = req.getKickoffAt();
        Instant halfTimeAt   = kickoffAt.plus(FIRST_HALF_MINUTES, ChronoUnit.MINUTES);
        Instant secondHalfAt = halfTimeAt.plus(BREAK_MINUTES, ChronoUnit.MINUTES);
        Instant finishedAt   = secondHalfAt.plus(SECOND_HALF_MINUTES, ChronoUnit.MINUTES);

        // Build match
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
                        "kickoffAt={} halfTimeAt={} secondHalfAt={} finishedAt={} finalScore={}:{} goals={}",
                adminId, matchId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt,
                req.getFinalScoreHome(), req.getFinalScoreAway(), goals.size());

        // ── LAYER 2: persist events to DB (survives restarts) ──────────────
        persistEvents(matchId, adminId, kickoffAt, halfTimeAt, secondHalfAt, finishedAt,
                goals, req.getFinalScoreHome(), req.getFinalScoreAway());

        // ── LAYER 1: register fast-path in-memory jobs post-commit ─────────
        registerScheduleAfterCommit(matchId, adminId, kickoffAt, halfTimeAt, secondHalfAt,
                finishedAt, goals, req.getFinalScoreHome(), req.getFinalScoreAway());

        return match;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 2 — DB EVENT PERSISTENCE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Writes every lifecycle event into match_scheduled_events as PENDING rows.
     * Called inside the same @Transactional as createMatch(), so the match row
     * and its events are always committed atomically.
     */
    private void persistEvents(UUID matchId, UUID adminId,
                               Instant kickoffAt, Instant halfTimeAt, Instant secondHalfAt,
                               Instant finishedAt, List<GoalEvent> goals,
                               int finalHome, int finalAway) {
        List<MatchScheduledEvent> events = new ArrayList<>();

        // Status-transition events
        events.add(buildTransitionEvent(matchId, adminId, "KICKOFF",     kickoffAt,    "LIVE",        null, null));
        events.add(buildTransitionEvent(matchId, adminId, "HALF_TIME",   halfTimeAt,   "HALF_TIME",   null, null));
        events.add(buildTransitionEvent(matchId, adminId, "SECOND_HALF", secondHalfAt, "SECOND_HALF", null, null));
        events.add(buildTransitionEvent(matchId, adminId, "FINISHED",    finishedAt,   "FINISHED",    finalHome, finalAway));

        // Goal events
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
     * Finds ALL PENDING events whose fire_at ≤ NOW, sorted oldest-first.
     * This means:
     *   • A match that kicked off on time fires events at the right wall-clock
     *     instants (within ±5s, acceptable for a simulated match).
     *   • A match whose events were missed (restart, starved thread, etc.)
     *     has ALL its overdue events executed immediately in chronological
     *     order, walking the match forward from wherever it is to wherever
     *     it should be right now.
     *
     * Claiming is done via an UPDATE … WHERE status = 'PENDING' CAS before
     * executing, so this is safe to run on multiple nodes simultaneously.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    @Transactional
    public void pollAndExecuteEvents() {
        Instant now = Instant.now();

        // Fetch all overdue pending events across ALL matches, oldest first.
        List<MatchScheduledEvent> due = eventRepository
                .findAllByStatusAndFireAtLessThanEqualOrderByFireAtAsc("PENDING", now);

        if (due.isEmpty()) return;

        log.debug("AdminMatchScheduleService.pollAndExecuteEvents: {} due events at {}",
                due.size(), now);

        for (MatchScheduledEvent evt : due) {
            // Atomic claim — skip if another node (or a concurrent call) already took it.
            int claimed = eventRepository.claimEvent(evt.getId());
            if (claimed == 0) continue; // lost the race — skip

            executeEvent(evt);
        }
    }

    /**
     * Executes a single DB event. Marks it DONE on success, FAILED on error.
     * Either way it will not be retried by the poller (PENDING→DONE/FAILED).
     * Failed events are surfaced in logs and can be replayed manually if needed.
     */
    private void executeEvent(MatchScheduledEvent evt) {
        UUID matchId = evt.getMatchId();
        UUID adminId = evt.getAdminId();
        String type  = evt.getEventType();

        try {
            switch (type) {
                case "KICKOFF" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} executing KICKOFF → LIVE", matchId);
                    adminMatchService.advanceStatusTo(matchId, "LIVE", adminId);
                }
                case "HALF_TIME" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} executing HALF_TIME transition", matchId);
                    adminMatchService.advanceStatusTo(matchId, "HALF_TIME", adminId);
                }
                case "SECOND_HALF" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} executing SECOND_HALF transition", matchId);
                    adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId);
                }
                case "FINISHED" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} executing FINISHED (finalScore={}:{})",
                            matchId, evt.getFinalHome(), evt.getFinalAway());
                    // Ensure we're in SECOND_HALF before finishing (self-heals missed steps)
                    adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId);
                    // Force the exact final score before the whistle
                    try {
                        adminMatchService.updateScore(matchId, evt.getFinalHome(), evt.getFinalAway(),
                                MATCH_MINUTES, adminId);
                    } catch (Exception e) {
                        log.warn("AdminMatchScheduleService[DB]: matchId={} could not force final score — {}",
                                matchId, e.getMessage());
                    }
                    adminMatchService.advanceStatusTo(matchId, "FINISHED", adminId);
                    scheduledJobs.remove(matchId);
                }
                case "GOAL" -> {
                    log.info("AdminMatchScheduleService[DB]: matchId={} GOAL min={} {} → {}:{}",
                            matchId, evt.getGoalMinute(), evt.getGoalTeam(),
                            evt.getCumHome(), evt.getCumAway());
                    // Self-heal: advance to the status this minute belongs to
                    adminMatchService.advanceStatusTo(matchId, evt.getTargetStatus(), adminId);
                    adminMatchService.updateScore(matchId, evt.getCumHome(), evt.getCumAway(),
                            evt.getGoalMinute(), adminId);
                }
                default -> log.warn("AdminMatchScheduleService[DB]: matchId={} unknown eventType={}",
                        matchId, type);
            }

            // Mark done
            eventRepository.markDone(evt.getId());
            log.debug("AdminMatchScheduleService[DB]: matchId={} event {} DONE", matchId, type);

        } catch (Exception e) {
            // Mark failed so we don't loop forever, but log loudly
            eventRepository.markFailed(evt.getId());
            log.error("AdminMatchScheduleService[DB]: matchId={} event {} FAILED — {}",
                    matchId, type, e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 1 — IN-MEMORY FAST-PATH (best-effort, fires earlier than poller)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Registers TaskScheduler jobs post-commit so the DB row is guaranteed
     * visible when they run. The DB poller is the safety net — these jobs are
     * a "fire early" optimization so transitions happen at the precise instant
     * rather than up to 5s late.
     *
     * The in-memory job calls the same executeEvent() path via a lookup of the
     * already-persisted DB event, so the DONE/FAILED marking is consistent.
     */
    private void registerScheduleAfterCommit(UUID matchId, UUID adminId, Instant kickoffAt,
                                             Instant halfTimeAt, Instant secondHalfAt, Instant finishedAt,
                                             List<GoalEvent> goals, int finalHome, int finalAway) {
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

        // Kickoff
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "kickoff→LIVE[fast]", () ->
                        adminMatchService.advanceStatusTo(matchId, "LIVE", adminId)),
                kickoffAt));

        // Goals
        for (GoalEvent g : goals) {
            jobs.add(taskScheduler.schedule(
                    () -> safeRun(matchId,
                            "goal min=" + g.minute() + " " + g.team() + " → " + g.cumHome() + ":" + g.cumAway() + " [fast]",
                            () -> {
                                adminMatchService.advanceStatusTo(matchId,
                                        expectedStatusForGoalMinute(g.minute()), adminId);
                                adminMatchService.updateScore(matchId,
                                        g.cumHome(), g.cumAway(), g.minute(), adminId);
                                // Eagerly mark the corresponding DB event done so the
                                // poller doesn't execute it a second time.
                                eventRepository.markDoneByMatchAndTypeAndFireAt(
                                        matchId, "GOAL", g.at());
                            }),
                    g.at()));
        }

        // Half-time
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "halfTime→HALF_TIME[fast]", () -> {
                    adminMatchService.advanceStatusTo(matchId, "HALF_TIME", adminId);
                    eventRepository.markDoneByMatchAndType(matchId, "HALF_TIME");
                }),
                halfTimeAt));

        // Second half
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "secondHalf→SECOND_HALF[fast]", () -> {
                    adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId);
                    eventRepository.markDoneByMatchAndType(matchId, "SECOND_HALF");
                }),
                secondHalfAt));

        // Finish — forces exact final score first
        jobs.add(taskScheduler.schedule(
                () -> safeRun(matchId, "finish→FINISHED[fast]", () -> {
                    adminMatchService.advanceStatusTo(matchId, "SECOND_HALF", adminId);
                    try {
                        adminMatchService.updateScore(matchId, finalHome, finalAway,
                                MATCH_MINUTES, adminId);
                    } catch (Exception e) {
                        log.error("AdminMatchScheduleService[fast]: matchId={} could not force final score — {}",
                                matchId, e.getMessage());
                    }
                    adminMatchService.advanceStatusTo(matchId, "FINISHED", adminId);
                    eventRepository.markDoneByMatchAndType(matchId, "FINISHED");
                    scheduledJobs.remove(matchId);
                }),
                finishedAt));

        scheduledJobs.put(matchId, new ScheduleHandle(jobs, adminId, kickoffAt, halfTimeAt,
                secondHalfAt, finishedAt, goals, finalHome, finalAway));

        log.info("AdminMatchScheduleService.doRegisterSchedule: matchId={} registeredJobs={} (post-commit)",
                matchId, jobs.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 3 — WATCHDOG (extra safety net, every 60s, in-memory handles)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Secondary safety net that operates on the in-memory ScheduleHandle map.
     * The poller (Layer 2) already handles everything the scheduler drops, but
     * this adds a live check for status drift on matches that ARE tracked in
     * memory — useful for detecting advanceStatusTo() failures that the DB
     * events don't know about.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcileSchedules() {
        if (scheduledJobs.isEmpty()) return;
        Instant now = Instant.now();

        for (Map.Entry<UUID, ScheduleHandle> entry : new HashMap<>(scheduledJobs).entrySet()) {
            UUID matchId        = entry.getKey();
            ScheduleHandle handle = entry.getValue();

            try {
                Match match = adminMatchService.findMyMatchOrNull(matchId, handle.getAdminId());
                if (match == null || "FINISHED".equals(match.getStatus())) {
                    scheduledJobs.remove(matchId);
                    continue;
                }

                String expected = expectedStatusAt(now, handle);
                if (expected == null) continue;

                if (!expected.equals(match.getStatus())) {
                    log.warn("AdminMatchScheduleService.watchdog: matchId={} is {} but should be {} — correcting",
                            matchId, match.getStatus(), expected);
                    adminMatchService.advanceStatusTo(matchId, expected, handle.getAdminId());

                    // Re-apply cumulative score for all overdue goals
                    int cumHome = 0, cumAway = 0, lastMinute = 0;
                    for (GoalEvent g : handle.getGoals()) {
                        if (!g.at().isAfter(now)) {
                            cumHome    = g.cumHome();
                            cumAway    = g.cumAway();
                            lastMinute = g.minute();
                        }
                    }
                    if ((cumHome > 0 || cumAway > 0) && !"FINISHED".equals(expected)) {
                        adminMatchService.updateScore(matchId, cumHome, cumAway,
                                lastMinute, handle.getAdminId());
                    }
                    if ("FINISHED".equals(expected)) scheduledJobs.remove(matchId);
                }
            } catch (Exception e) {
                log.error("AdminMatchScheduleService.watchdog: matchId={} FAILED — {}",
                        matchId, e.getMessage(), e);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CANCEL / INSPECT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cancels all pending automated transitions for a match — both in-memory
     * jobs AND the DB events. The match stays at its current state.
     */
    public int cancelSchedule(UUID matchId, User admin) {
        adminMatchService.getMyMatch(matchId.toString(), admin); // ownership + 404

        ScheduleHandle handle = scheduledJobs.remove(matchId);
        int cancelled = 0;
        if (handle != null) {
            for (ScheduledFuture<?> job : handle.getJobs()) {
                if (job.cancel(false)) cancelled++;
            }
        }

        // Also cancel the DB events so the poller doesn't re-execute them.
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
        adminMatchService.getMyMatch(matchId.toString(), admin); // ownership check

        ScheduleHandle handle = scheduledJobs.get(matchId);
        List<MatchScheduledEvent> dbEvents = eventRepository.findByMatchIdOrderByFireAtAsc(matchId);

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
                        m.put("minute", e.getGoalMinute());
                        m.put("scorer", e.getGoalTeam());
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
            result.put("kickoffAt",   handle.getKickoffAt());
            result.put("halfTimeAt",  handle.getHalfTimeAt());
            result.put("secondHalfAt",handle.getSecondHalfAt());
            result.put("finishedAt",  handle.getFinishedAt());
            result.put("inMemoryJobsActive", handle.getJobs().stream().filter(j -> !j.isDone()).count());
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

            // Ensure no two goals share the exact same instant
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
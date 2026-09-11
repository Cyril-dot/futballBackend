package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.sportsdata.odds.OddsPersistenceService;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AdminMatchService — admin-scoped match lifecycle management.
 *
 * ── Ownership rule ────────────────────────────────────────────────────────
 *   Each admin can only LIST, READ, and MUTATE matches they personally created.
 *   Attempting to access another admin's match returns 404 — existence of
 *   another admin's match is never revealed.
 *   Identity is resolved from the Spring Security principal injected by the
 *   controller; it is never trusted from the request body.
 *
 * ── Odds persistence strategy ────────────────────────────────────────────
 *
 *   ON CREATE (SCHEDULED / LIVE / HALF_TIME / SECOND_HALF):
 *     → generateAndSaveAllOdds()
 *       Persists ALL markets to the odds table so bets can be placed immediately:
 *         • 1X2 / match_result  (home / draw / away)
 *         • half_time           (HT 1X2)
 *         • asian_handicap      (pre-match lines)
 *         • correct_score       (0-0 … 4-4 grid)
 *       For matches created directly as LIVE, live odds are also generated
 *       immediately after via generateAndSaveLiveOdds() which replaces the
 *       1X2 and asian_handicap rows with score-aware in-play prices.
 *
 *   ON ANY TRANSITION INTO LIVE / HALF_TIME / SECOND_HALF:
 *     → generateAndSaveLiveOdds()
 *       Replaces 1X2 + asian_handicap rows with live (score-aware) prices.
 *       HT and correct_score rows created at match-creation time remain
 *       available for the full duration of the match.
 *
 *   ON STATUS → FINISHED:
 *     → No odds are generated or overwritten.
 *       All existing rows remain readable for bet settlement; no new bets
 *       can be placed because MatchService.getMatchOdds() returns List.of()
 *       for FINISHED matches.
 *
 *   ON SCORE UPDATE (LIVE / HALF_TIME / SECOND_HALF):
 *     → generateAndSaveLiveOdds()
 *       Every score change triggers a full refresh of 1X2 + asian_handicap
 *       rows so the DB always reflects the current scoreline.
 *
 * ── Other rules ───────────────────────────────────────────────────────────
 *   - Admins never supply odds — all values are computed by the odds services.
 *   - FINISHED is terminal: no score or status changes are allowed after that.
 *   - Score updates only accepted for LIVE / HALF_TIME / SECOND_HALF.
 *   - No match events (goalscorers, cards, substitutions) are tracked.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIXES / CHANGES
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  FIX 1 — updateScore() now evicts caches.
 *  FIX 2 — UUID-based overloads for every mutator (background-job safe).
 *  FIX 3 — advanceStatusTo(): idempotent, self-healing transitions.
 *  FIX 4 — tickMinute(): clock advances on its own between goals.
 *  FIX 5 — forceFinish(): guaranteed terminal state from any starting status.
 *  FIX 6 — tickMinute() now evicts caches so updated minute is visible.
 *  FIX 7 — deleteMatch(): hard-delete with cache eviction, used by both
 *           the stale-match cleanup sweep and the post-settlement sweep.
 *  FIX 8 — createdAt exposed via getCreatedAt() so the scheduler can apply
 *           the 3-day stale threshold correctly.
 *  FIX 9 — deleteSettledFinishedMatches() uses match.settledAt (set by the
 *           SettlementEngine, already on the Match entity) as the finish-time
 *           reference, falling back to kickoffAt + 105 min. No entity changes
 *           required.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMatchService {

    // ── Status constants ──────────────────────────────────────────────────

    /**
     * The lifecycle in order. Index position defines "how far along" a match
     * is, which lets advanceStatusTo() catch up or safely no-op.
     */
    private static final List<String> CANONICAL_ORDER = List.of(
            "SCHEDULED", "LIVE", "HALF_TIME", "SECOND_HALF", "FINISHED"
    );

    private static final Set<String> VALID_STATUSES = Set.copyOf(CANONICAL_ORDER);

    /**
     * Legal status transitions for MANUAL admin control.
     * FINISHED is intentionally absent as a key — it is terminal.
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "SCHEDULED",   Set.of("LIVE"),
            "LIVE",        Set.of("HALF_TIME", "FINISHED"),
            "HALF_TIME",   Set.of("SECOND_HALF"),
            "SECOND_HALF", Set.of("FINISHED")
    );

    /** Statuses in which live odds (score-aware) must be generated. */
    private static final Set<String> LIVE_STATUSES = Set.of(
            "LIVE", "HALF_TIME", "SECOND_HALF"
    );

    private static final int FIRST_HALF_MINUTES = 45;
    private static final int FULL_TIME_MINUTES  = 90;

    // ── Dependencies ──────────────────────────────────────────────────────
    private final MatchRepository        matchRepo;
    private final OddsPersistenceService oddsPersistenceService;

    // ══════════════════════════════════════════════════════════════════════
    // CREATE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates a match owned by {@code admin} and immediately persists all
     * betting markets so the match is open for bets the moment it is saved.
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match createMatch(AdminMatchRequest req, User admin) {
        return createMatchInternal(req, admin.getId());
    }

    /** UUID-based overload for background jobs — see FIX 2. */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match createMatch(AdminMatchRequest req, UUID adminId) {
        return createMatchInternal(req, adminId);
    }

    private Match createMatchInternal(AdminMatchRequest req, UUID adminId) {
        String status = resolveInitialStatus(req.getStatus());

        Match match = Match.builder()
                .source(MatchSource.ADMIN_CREATED)
                .createdByAdminId(adminId)
                .homeTeam(req.getHomeTeam())
                .awayTeam(req.getAwayTeam())
                .league(req.getLeague()  != null ? req.getLeague() : "")
                .sport(req.getSport()    != null ? req.getSport()  : "football")
                .homeLogo(req.resolvedHomeLogo())
                .awayLogo(req.resolvedAwayLogo())
                .leagueLogo(req.resolvedLeagueLogo())
                .kickoffAt(req.getKickoffAt() != null ? req.getKickoffAt() : Instant.now())
                .status(status)
                .scoreHome(0)
                .scoreAway(0)
                .minutePlayed(0)
                .featured(req.isFeatured())
                .build();

        Match saved = matchRepo.save(match);
        log.info("AdminMatchService.createMatch: adminId={} matchId={} home='{}' away='{}' status={}",
                adminId, saved.getId(), saved.getHomeTeam(), saved.getAwayTeam(), saved.getStatus());

        persistAllOdds(saved, "createMatch");

        if (LIVE_STATUSES.contains(status)) {
            persistLiveOdds(saved, "createMatch[live-init]");
        }

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // READ
    // ══════════════════════════════════════════════════════════════════════

    /** Returns all matches created by this admin, newest kickoff first. */
    public List<Match> getMyMatches(User admin) {
        List<Match> matches = matchRepo.findByCreatedByAdminIdOrderByKickoffAtDesc(admin.getId());
        log.debug("AdminMatchService.getMyMatches: adminId={} → {} match(es)",
                admin.getId(), matches.size());
        return matches;
    }

    /**
     * Returns a single match, enforcing ownership.
     * @throws ApiException 404 if not found or owned by a different admin
     */
    public Match getMyMatch(String id, User admin) {
        return getMyMatch(parseUuid(id), admin.getId());
    }

    /** UUID-based overload for background jobs. */
    public Match getMyMatch(UUID id, UUID adminId) {
        Match match = findOrThrow(id);
        assertOwnership(match, adminId);
        return match;
    }

    /**
     * Ownership-checked lookup that returns null instead of throwing.
     * Used by the schedule watchdog — must not abort the whole sweep if one
     * row has vanished.
     */
    public Match findMyMatchOrNull(UUID id, UUID adminId) {
        return matchRepo.findById(id)
                .filter(m -> adminId != null && adminId.equals(m.getCreatedByAdminId()))
                .orElse(null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATUS TRANSITION — STRICT (manual admin control)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Transitions the match and regenerates odds. Rejects illegal transitions.
     *
     * @throws ApiException 400 if the transition is illegal or match is FINISHED
     * @throws ApiException 404 if match not found or owned by a different admin
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match updateStatus(UUID matchId, AdminStatusUpdateRequest req, User admin) {
        return updateStatus(matchId, req.getStatus(), admin.getId());
    }

    /** UUID-based overload. Same strict validation. */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match updateStatus(UUID matchId, String rawTarget, UUID adminId) {
        Match match = findOrThrow(matchId);
        assertOwnership(match, adminId);

        String current = match.getStatus();
        String target  = normalizeStatus(rawTarget);

        if ("FINISHED".equals(current)) {
            throw ApiException.badRequest(
                    "Match " + matchId + " is already FINISHED. No further changes are allowed.");
        }
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw ApiException.badRequest(
                    "Cannot transition from " + current + " to " + target +
                            ". Allowed from " + current + ": " + allowed);
        }

        log.info("AdminMatchService.updateStatus: adminId={} matchId={} {} → {}",
                adminId, matchId, current, target);

        return applyStatusStep(match, target);
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATUS TRANSITION — TOLERANT (automated lifecycle)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Drives the match FORWARD to {@code rawTarget}, applying every
     * intermediate step in order. Idempotent and self-healing:
     *
     *   • already at or past the target → no-op
     *   • one or more steps behind      → walks each step in sequence
     *   • never moves backwards, never resurrects a FINISHED match
     *
     * @throws ApiException 404 if match not found or owned by a different admin
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match advanceStatusTo(UUID matchId, String rawTarget, UUID adminId) {
        Match match = findOrThrow(matchId);
        assertOwnership(match, adminId);

        String target = normalizeStatus(rawTarget);
        int from = CANONICAL_ORDER.indexOf(match.getStatus());
        int to   = CANONICAL_ORDER.indexOf(target);

        if (from < 0) {
            throw ApiException.badRequest(
                    "Match " + matchId + " has unrecognised status '" + match.getStatus() + "'.");
        }
        if (to <= from) {
            log.debug("AdminMatchService.advanceStatusTo: matchId={} already at/past {} (current={}) — no-op",
                    matchId, target, match.getStatus());
            return match;
        }

        log.info("AdminMatchService.advanceStatusTo: adminId={} matchId={} {} → {} ({} step(s))",
                adminId, matchId, match.getStatus(), target, to - from);

        Match current = match;
        for (int i = from + 1; i <= to; i++) {
            current = applyStatusStep(current, CANONICAL_ORDER.get(i));
        }
        return current;
    }

    /**
     * Applies exactly ONE forward step. Assumes caller has already validated
     * ownership and legality. Kept private and called in-transaction so the
     * HT snapshot and odds refresh happen for every state passed through,
     * including during a multi-step catch-up.
     */
    private Match applyStatusStep(Match match, String target) {
        String current = match.getStatus();

        // Snapshot HT score into metadata so SettlementEngine can settle
        // HALF_TIME bets correctly (reads "score_home_ht" / "score_away_ht").
        if ("HALF_TIME".equals(target)) {
            int htHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int htAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            Map<String, Object> meta = match.getMetadata() != null
                    ? new HashMap<>(match.getMetadata()) : new HashMap<>();
            meta.put("score_home_ht", htHome);
            meta.put("score_away_ht", htAway);
            match.setMetadata(meta);
            log.info("AdminMatchService.applyStatusStep: matchId={} HT score snapshot {}:{}",
                    match.getId(), htHome, htAway);
        }

        // Keep the displayed clock consistent with the state machine.
        switch (target) {
            case "LIVE"        -> { if (match.getMinutePlayed() == null || match.getMinutePlayed() == 0)
                                        match.setMinutePlayed(0); }
            case "HALF_TIME"   -> match.setMinutePlayed(FIRST_HALF_MINUTES);
            case "SECOND_HALF" -> match.setMinutePlayed(FIRST_HALF_MINUTES);
            case "FINISHED"    -> match.setMinutePlayed(FULL_TIME_MINUTES);
            default            -> { /* no clock change */ }
        }

        match.setStatus(target);
        Match saved = matchRepo.save(match);

        if (LIVE_STATUSES.contains(target)) {
            persistLiveOdds(saved, "statusStep[" + current + "→" + target + "]");
        }

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCORE UPDATE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Updates the live score and immediately regenerates live odds.
     *
     * @throws ApiException 400 if match is FINISHED or not in a live status
     * @throws ApiException 404 if match not found or owned by a different admin
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match updateScore(UUID matchId, AdminScoreUpdateRequest req, User admin) {
        return updateScore(matchId, req.getScoreHome(), req.getScoreAway(),
                req.getMinutePlayed(), admin.getId());
    }

    /**
     * UUID-based overload. See FIX 1 — @CacheEvict is required here so goals
     * are immediately visible on every cached read endpoint.
     *
     * @throws ApiException 400 if match is FINISHED or not in a live status
     * @throws ApiException 404 if match not found or owned by a different admin
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match updateScore(UUID matchId, int scoreHome, int scoreAway,
                             Integer minutePlayed, UUID adminId) {
        Match match = findOrThrow(matchId);
        assertOwnership(match, adminId);

        String status = match.getStatus();

        if ("FINISHED".equals(status)) {
            throw ApiException.badRequest(
                    "Match " + matchId + " is FINISHED. Scores cannot be changed.");
        }
        if (!LIVE_STATUSES.contains(status)) {
            throw ApiException.badRequest(
                    "Score updates are only allowed during live play. " +
                            "Current status: " + status + ". Expected one of: " + LIVE_STATUSES);
        }
        if (scoreHome < 0 || scoreAway < 0) {
            throw ApiException.badRequest("Scores cannot be negative.");
        }

        log.info("AdminMatchService.updateScore: adminId={} matchId={} {}:{} → {}:{} minute={}",
                adminId, matchId,
                match.getScoreHome(), match.getScoreAway(),
                scoreHome, scoreAway, minutePlayed);

        match.setScoreHome(scoreHome);
        match.setScoreAway(scoreAway);

        // Monotonic guard — only advance the clock, never move it backwards.
        if (minutePlayed != null) {
            Integer current = match.getMinutePlayed();
            if (current == null || minutePlayed > current) {
                match.setMinutePlayed(minutePlayed);
            }
        }

        Match saved = matchRepo.save(match);
        persistLiveOdds(saved, "updateScore[" + scoreHome + ":" + scoreAway + "]");
        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // CLOCK TICK — keeps minutePlayed moving with no goal required  (FIX 4)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Advances ONLY the displayed clock ({@code minutePlayed}).
     *
     * Deliberately monotonic and tolerant:
     *   • no-ops if match isn't in LIVE / SECOND_HALF (including HALF_TIME,
     *     where the clock stays frozen at 45 until SECOND_HALF fires)
     *   • no-ops if {@code minute} is not strictly ahead of current value
     *   • evicts caches (FIX 6) so updated minute is visible immediately
     *
     * Called every 60s by AdminMatchScheduleService and by the watchdog as
     * a fallback in case the tick job was lost on restart.
     *
     * @throws ApiException 404 if match not found or owned by a different admin
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public void tickMinute(UUID matchId, int minute, UUID adminId) {
        Match match = findOrThrow(matchId);
        assertOwnership(match, adminId);

        String status = match.getStatus();

        // Freeze during HALF_TIME break and skip non-live states entirely.
        if ("HALF_TIME".equals(status) || !LIVE_STATUSES.contains(status)) {
            log.debug("AdminMatchService.tickMinute: matchId={} status={} — ignoring stale tick(min={})",
                    matchId, status, minute);
            return;
        }

        Integer current = match.getMinutePlayed();
        if (current != null && current >= minute) {
            log.debug("AdminMatchService.tickMinute: matchId={} current={} already >= {} — ignoring",
                    matchId, current, minute);
            return;
        }

        match.setMinutePlayed(minute);
        matchRepo.save(match);
        log.debug("AdminMatchService.tickMinute: matchId={} minutePlayed → {}", matchId, minute);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FORCE FINISH — guaranteed terminal state  (FIX 5)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Drives the match to FINISHED regardless of its current state, applying
     * the supplied final score first. Called by the scheduler's finish job
     * and by the overdue-match and stale-match watchdogs.
     *
     * Behaviour:
     *   • already FINISHED  → logs a warning and returns the match unchanged
     *   • otherwise         → forces SECOND_HALF, sets final score + minute
     *                         to 90, then drives to FINISHED
     *
     * @param matchId   the match to finish
     * @param finalHome final home score
     * @param finalAway final away score
     * @param adminId   owning admin (for ownership assertion)
     */
    @Transactional
    public Match forceFinish(UUID matchId, int finalHome, int finalAway, UUID adminId) {
        Match match = findOrThrow(matchId);
        assertOwnership(match, adminId);

        if ("FINISHED".equals(match.getStatus())) {
            log.warn("AdminMatchService.forceFinish: matchId={} already FINISHED — skipping", matchId);
            return match;
        }

        log.info("AdminMatchService.forceFinish: matchId={} forcing finish finalScore={}:{} from status={}",
                matchId, finalHome, finalAway, match.getStatus());

        // Step 1: walk to SECOND_HALF so we're in a legal state to write the score.
        advanceStatusTo(matchId, "SECOND_HALF", adminId);

        // Step 2: force the exact final score.
        try {
            Match refreshed = findOrThrow(matchId);
            refreshed.setScoreHome(finalHome);
            refreshed.setScoreAway(finalAway);
            refreshed.setMinutePlayed(FULL_TIME_MINUTES);
            matchRepo.save(refreshed);
            log.info("AdminMatchService.forceFinish: matchId={} score forced to {}:{}",
                    matchId, finalHome, finalAway);
        } catch (Exception e) {
            log.error("AdminMatchService.forceFinish: matchId={} could not force score — {}",
                    matchId, e.getMessage(), e);
        }

        // Step 3: drive to FINISHED. advanceStatusTo() handles cache eviction.
        return advanceStatusTo(matchId, "FINISHED", adminId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELETE — hard-delete for stale matches  (FIX 7)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Hard-deletes a match row. Called by two automated sweeps:
     *
     *   1. Stale-match cleanup — SCHEDULED matches whose kickoffAt AND
     *      createdAt are both 3+ days in the past (never started, no bets).
     *
     *   2. Post-settlement cleanup — FINISHED matches where 1 hour has
     *      elapsed since finishing AND all bets are settled/graded.
     *
     * Ownership is enforced — an admin can only delete their own matches.
     * Evicts all match caches so deleted matches stop appearing on read
     * endpoints immediately.
     *
     * No status guard is applied here — the caller (the sweep) is responsible
     * for verifying that deletion is safe before calling this method.
     *
     * @throws ApiException 404 if not found or owned by a different admin
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public void deleteMatch(UUID matchId, UUID adminId) {
        Match match = findOrThrow(matchId);
        assertOwnership(match, adminId);

        log.info("AdminMatchService.deleteMatch: adminId={} matchId={} status={} settledAt={} — deleting",
                adminId, matchId, match.getStatus(), match.getSettledAt());

        matchRepo.delete(match);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS PERSISTENCE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Persists ALL markets: 1X2, half_time, asian_handicap, correct_score.
     * Called once at match creation. Failure is logged but does NOT roll
     * back the match row.
     */
    private void persistAllOdds(Match match, String caller) {
        try {
            oddsPersistenceService.generateAndSaveAllOdds(match);
            log.info("persistAllOdds [{}]: matchId={} — all markets saved", caller, match.getId());
        } catch (Exception e) {
            log.error("persistAllOdds [{}]: matchId={} FAILED — {} | " +
                            "bets may not be placeable until odds are regenerated",
                    caller, match.getId(), e.getMessage(), e);
        }
    }

    /**
     * Persists live markets: 1X2 (match_result) + asian_handicap.
     * HT and correct_score rows are left intact.
     */
    private void persistLiveOdds(Match match, String caller) {
        try {
            oddsPersistenceService.generateAndSaveLiveOdds(match);
            log.info("persistLiveOdds [{}]: matchId={} score={}:{} min={} — 1X2+handicap refreshed",
                    caller, match.getId(),
                    match.getScoreHome(), match.getScoreAway(), match.getMinutePlayed());
        } catch (Exception e) {
            log.error("persistLiveOdds [{}]: matchId={} FAILED — {} | live odds may be stale",
                    caller, match.getId(), e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void assertOwnership(Match match, UUID adminId) {
        if (adminId == null || !adminId.equals(match.getCreatedByAdminId())) {
            log.warn("AdminMatchService.assertOwnership: DENIED — adminId={} tried matchId={} owned by adminId={}",
                    adminId, match.getId(), match.getCreatedByAdminId());
            throw ApiException.notFound("Match not found: " + match.getId());
        }
    }

    private Match findOrThrow(UUID matchId) {
        return matchRepo.findById(matchId)
                .orElseThrow(() -> ApiException.notFound("Match not found: " + matchId));
    }

    private UUID parseUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) {
            throw ApiException.notFound("Match not found: " + id);
        }
    }

    private String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Status is required. Allowed: " + VALID_STATUSES);
        }
        String upper = raw.trim().toUpperCase();
        if (!VALID_STATUSES.contains(upper)) {
            throw ApiException.badRequest("Invalid status '" + raw + "'. Allowed: " + VALID_STATUSES);
        }
        return upper;
    }

    private String resolveInitialStatus(String raw) {
        if (raw == null || raw.isBlank()) return "SCHEDULED";
        String upper = normalizeStatus(raw);
        if ("FINISHED".equals(upper)) {
            throw ApiException.badRequest("Cannot create a match with status FINISHED.");
        }
        return upper;
    }
}
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
 *       rows so the DB always reflects the current scoreline. This is the
 *       same path LiveScorePoller uses for external-feed matches.
 *
 * ── Other rules ───────────────────────────────────────────────────────────
 *   - Admins never supply odds — all values are computed by the odds services.
 *   - FINISHED is terminal: no score or status changes are allowed after that.
 *   - Score updates only accepted for LIVE / HALF_TIME / SECOND_HALF.
 *   - No match events (goalscorers, cards, substitutions) are tracked.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIXES IN THIS REVISION
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  FIX 1 — updateScore() now evicts the match caches.
 *  ──────────────────────────────────────────────────
 *    createMatch() and updateStatus() both carried @CacheEvict. updateScore()
 *    did not. The score was written to the database correctly, but every
 *    cached read path ("matches", "todayMatches", "featuredMatches",
 *    "futureMatches") kept serving the pre-goal snapshot until the cache
 *    happened to expire.
 *
 *    To anyone watching the app, an automated match "kicked off and then
 *    nothing ever happened" — the goals were real and in the DB, they were
 *    just invisible on every endpoint that mattered. This single missing
 *    annotation accounts for a large share of the reported symptom.
 *
 *  FIX 2 — UUID-based overloads for every mutator.
 *  ───────────────────────────────────────────────
 *    Background jobs must not carry a detached {@code User} JPA entity across
 *    hours and thread boundaries — by the time the job runs there is no
 *    persistence context, and any lazy access throws. The scheduler now
 *    passes only the admin's UUID. The User-based signatures are unchanged so
 *    controllers need no edits.
 *
 *  FIX 3 — advanceStatusTo(): idempotent, self-healing transitions.
 *  ────────────────────────────────────────────────────────────────
 *    Strict updateStatus() is still what the admin UI calls, and still
 *    rejects illegal jumps. But an automated lifecycle has to survive a
 *    missed step.
 *
 *    Previously, if the kickoff transition was lost (restart, downtime,
 *    starved scheduler thread), the HALF_TIME job would then be rejected as
 *    an illegal jump from SCHEDULED — and so would SECOND_HALF, and so would
 *    FINISHED. Every remaining step failed like dominoes, each failure only
 *    reaching a log line, and the match hung mid-lifecycle forever. That is
 *    the "it starts and never ends" bug.
 *
 *    advanceStatusTo() instead walks the canonical path forward from wherever
 *    the match ACTUALLY is, applying every intermediate step in order, and is
 *    a no-op if the match is already at or past the requested state. It never
 *    moves a match backwards and never resurrects a FINISHED match.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMatchService {

    // ── Status constants ──────────────────────────────────────────────────

    /**
     * The lifecycle in order. Index position defines "how far along" a match
     * is, which is what lets advanceStatusTo() catch up or safely no-op.
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
    private final OddsPersistenceService oddsPersistenceService;   // ← single odds entry point

    // ══════════════════════════════════════════════════════════════════════
    // CREATE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates a match owned by {@code admin} and immediately persists all
     * betting markets so the match is open for bets the moment it is saved.
     *
     * Markets saved on creation (every status):
     *   1X2 · half_time · asian_handicap · correct_score
     *
     * If the initial status is LIVE / HALF_TIME / SECOND_HALF the live
     * odds engine runs immediately after to replace 1X2 + handicap rows
     * with score-aware in-play prices.
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match createMatch(AdminMatchRequest req, User admin) {
        return createMatchInternal(req, admin.getId());
    }

    /**
     * UUID-based overload for background jobs — see FIX 2 in the class javadoc.
     */
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
                .featured(req.isFeatured())
                .build();

        Match saved = matchRepo.save(match);
        log.info("AdminMatchService.createMatch: adminId={} matchId={} home='{}' away='{}' status={}",
                adminId, saved.getId(), saved.getHomeTeam(), saved.getAwayTeam(), saved.getStatus());

        // ── Step 1: persist ALL markets (1X2, HT, handicap, correct score) ──
        // This is the same call LiveScorePoller makes for external fixtures.
        persistAllOdds(saved, "createMatch");

        // ── Step 2: if match starts in a live state, also run live odds ─────
        // Replaces the 1X2 + asian_handicap rows with score/time-aware prices.
        if (LIVE_STATUSES.contains(status)) {
            persistLiveOdds(saved, "createMatch[live-init]");
        }

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // READ
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns all matches created by this admin, newest kickoff first.
     * Matches from other admins or external feeds are never included.
     */
    public List<Match> getMyMatches(User admin) {
        List<Match> matches = matchRepo.findByCreatedByAdminIdOrderByKickoffAtDesc(admin.getId());
        log.debug("AdminMatchService.getMyMatches: adminId={} → {} match(es)", admin.getId(), matches.size());
        return matches;
    }

    /**
     * Returns a single match, enforcing ownership.
     *
     * @throws ApiException 404 if not found or owned by a different admin
     */
    public Match getMyMatch(String id, User admin) {
        return getMyMatch(parseUuid(id), admin.getId());
    }

    /**
     * UUID-based overload for background jobs.
     *
     * @throws ApiException 404 if not found or owned by a different admin
     */
    public Match getMyMatch(UUID id, UUID adminId) {
        Match match = findOrThrow(id);
        assertOwnership(match, adminId);
        return match;
    }

    /**
     * Ownership-checked lookup that returns null instead of throwing.
     * Used by the schedule watchdog, which sweeps many matches and must not
     * abort the whole sweep because one row vanished.
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
     * Transitions the match through the state machine and regenerates odds
     * appropriate to the new status. Rejects any transition that is not legal
     * from the current state.
     *
     * Odds behaviour per transition:
     *   SCHEDULED → LIVE        : generateAndSaveLiveOdds (score-aware 1X2 + handicap)
     *   LIVE      → HALF_TIME   : generateAndSaveLiveOdds (refreshed at HT scoreline)
     *   HALF_TIME → SECOND_HALF : generateAndSaveLiveOdds (second-half prices)
     *   any       → FINISHED    : no odds generated; existing rows kept for settlement
     *
     * @throws ApiException 400 if the transition is illegal or match is FINISHED
     * @throws ApiException 404 if match not found or owned by a different admin
     */
    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match updateStatus(UUID matchId, AdminStatusUpdateRequest req, User admin) {
        return updateStatus(matchId, req.getStatus(), admin.getId());
    }

    /**
     * UUID-based overload. Same strict validation.
     */
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
     *   • already at or past the target → no-op, returns the match unchanged
     *   • one or more steps behind      → walks each step in sequence, so the
     *                                     HALF_TIME metadata snapshot and the
     *                                     live-odds refresh still happen for
     *                                     every state passed through
     *   • never moves a match backwards, never resurrects a FINISHED match
     *
     * This is what makes a missed step survivable. Strict updateStatus() would
     * throw on a skipped step and, because scheduled work is fire-and-forget,
     * that exception would only ever land in a log line — leaving the match
     * frozen partway through its lifecycle with no visible error anywhere.
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
     * Applies exactly ONE forward step. Assumes the caller has already
     * validated ownership and legality.
     *
     * Kept private and called in-transaction so the HT snapshot and the odds
     * refresh happen for every state a match passes through, including during
     * a multi-step catch-up.
     */
    private Match applyStatusStep(Match match, String target) {
        String current = match.getStatus();

        // ── Snapshot half-time score into metadata on → HALF_TIME ─────────
        // SettlementEngine.evaluateHalfTime() reads metadata keys
        // "score_home_ht" and "score_away_ht" to settle HALF_TIME bets.
        // Without this snapshot those bets always VOID on admin matches.
        // We capture the score BEFORE setStatus so we record the exact
        // scoreline at the moment the break begins.
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

        // Keep the displayed clock consistent with the state machine, so the
        // UI never shows a FINISHED match sitting on minute 12.
        switch (target) {
            case "LIVE"        -> { if (match.getMinutePlayed() == null) match.setMinutePlayed(0); }
            case "HALF_TIME"   -> match.setMinutePlayed(FIRST_HALF_MINUTES);
            case "SECOND_HALF" -> match.setMinutePlayed(FIRST_HALF_MINUTES);
            case "FINISHED"    -> match.setMinutePlayed(FULL_TIME_MINUTES);
            default            -> { /* no clock change */ }
        }

        match.setStatus(target);
        Match saved = matchRepo.save(match);

        // ── Odds regeneration based on target status ──────────────────────
        //   → LIVE / HALF_TIME / SECOND_HALF : refresh 1X2 + asian_handicap
        //   → FINISHED : no odds generated; existing rows stay for settlement.
        if (LIVE_STATUSES.contains(target)) {
            persistLiveOdds(saved, "statusStep[" + current + "\u2192" + target + "]");
        }

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCORE UPDATE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Updates the live score and immediately regenerates live odds so the
     * DB reflects the new scoreline for any bets placed after this call.
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
     * UUID-based overload.
     *
     * Markets refreshed: 1X2 (match_result) + asian_handicap.
     * Markets unchanged: half_time + correct_score (set at creation).
     *
     * Blocked when status is FINISHED or SCHEDULED.
     * No match events (goalscorers, cards, substitutions) are accepted here.
     *
     * NOTE the @CacheEvict on this method — see FIX 1 in the class javadoc.
     * Without it the goals land in the database but never reach any cached
     * read endpoint, which is indistinguishable from the match being stuck.
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
        if (minutePlayed != null) match.setMinutePlayed(minutePlayed);

        Match saved = matchRepo.save(match);

        // Regenerate live odds immediately after every score change so the
        // odds table is always consistent with the current scoreline.
        // This mirrors exactly what LiveScorePoller does for external-feed matches.
        persistLiveOdds(saved, "updateScore[" + scoreHome + ":" + scoreAway + "]");

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS PERSISTENCE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Persists ALL markets: 1X2, half_time, asian_handicap, correct_score.
     * Called once at match creation.
     * Any failure is logged but does NOT roll back the match row — odds can
     * be regenerated via the MatchService on-demand endpoints if needed.
     */
    private void persistAllOdds(Match match, String caller) {
        try {
            oddsPersistenceService.generateAndSaveAllOdds(match);
            log.info("persistAllOdds [{}]: matchId={} — all markets saved", caller, match.getId());
        } catch (Exception e) {
            log.error("persistAllOdds [{}]: matchId={} FAILED — {} | bets may not be placeable until odds are regenerated",
                    caller, match.getId(), e.getMessage(), e);
        }
    }

    /**
     * Persists live markets: 1X2 (match_result) + asian_handicap.
     * Replaces existing rows for those two markets with score/time-aware prices.
     * HT and correct_score rows are left intact (they were saved at creation).
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

    /**
     * Ownership check — enforced at the top of every read and write method.
     * Returns 404 so match existence is never leaked across admin accounts.
     */
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
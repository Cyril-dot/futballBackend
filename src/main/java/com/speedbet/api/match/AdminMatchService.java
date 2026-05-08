package com.speedbet.api.match;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchRepository;
import com.speedbet.api.match.MatchSource;
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
 *   ON STATUS → LIVE (SCHEDULED → LIVE):
 *     → generateAndSaveLiveOdds()
 *       Replaces 1X2 + asian_handicap rows with live (score-aware) prices.
 *       HT and correct_score rows created at match-creation time remain
 *       available for the full duration of the match.
 *
 *   ON STATUS → HALF_TIME (LIVE → HALF_TIME):
 *     → generateAndSaveLiveOdds()
 *       Refreshes 1X2 + asian_handicap with current score at the break.
 *       HT market is now settled for the first half; second-half prices reflect
 *       the updated scoreline.
 *
 *   ON STATUS → SECOND_HALF (HALF_TIME → SECOND_HALF):
 *     → generateAndSaveLiveOdds()
 *       Refreshes live 1X2 + asian_handicap for the second half.
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMatchService {

    // ── Status constants ──────────────────────────────────────────────────
    private static final Set<String> VALID_STATUSES = Set.of(
            "SCHEDULED", "LIVE", "HALF_TIME", "SECOND_HALF", "FINISHED"
    );

    /**
     * Legal status transitions.
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
        String status = resolveInitialStatus(req.getStatus());

        Match match = Match.builder()
                .source(MatchSource.ADMIN_CREATED)
                .createdByAdminId(admin.getId())
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
                admin.getId(), saved.getId(), saved.getHomeTeam(), saved.getAwayTeam(), saved.getStatus());

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
        Match match = findOrThrow(parseUuid(id));
        assertOwnership(match, admin);
        return match;
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATUS TRANSITION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Transitions the match through the state machine and regenerates odds
     * appropriate to the new status.
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
        Match match = findOrThrow(matchId);
        assertOwnership(match, admin);

        String current = match.getStatus();
        String target  = req.getStatus().toUpperCase();

        if (!VALID_STATUSES.contains(target)) {
            throw ApiException.badRequest(
                    "Invalid status '" + target + "'. Allowed: " + VALID_STATUSES);
        }
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
                admin.getId(), matchId, current, target);

        // ── Snapshot half-time score into metadata on LIVE → HALF_TIME ────
        // SettlementEngine.evaluateHalfTime() reads metadata keys
        // "score_home_ht" and "score_away_ht" to settle HALF_TIME bets.
        // Without this snapshot those bets always VOID on admin matches.
        // We capture the score BEFORE calling setStatus so we record the
        // exact scoreline at the moment the admin pressed "Half Time".
        if ("LIVE".equals(current) && "HALF_TIME".equals(target)) {
            int htHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int htAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            Map<String, Object> meta = match.getMetadata() != null
                    ? new HashMap<>(match.getMetadata()) : new HashMap<>();
            meta.put("score_home_ht", htHome);
            meta.put("score_away_ht", htAway);
            match.setMetadata(meta);
            log.info("AdminMatchService.updateStatus: matchId={} HT score snapshot {}:{}",
                    matchId, htHome, htAway);
        }

        match.setStatus(target);
        Match saved = matchRepo.save(match);

        // ── Odds regeneration based on target status ──────────────────────
        //   → LIVE / HALF_TIME / SECOND_HALF : refresh 1X2 + asian_handicap
        //     with score-aware in-play prices.
        //   → FINISHED : no odds generated; existing rows stay for settlement.
        if (LIVE_STATUSES.contains(target)) {
            persistLiveOdds(saved, "updateStatus[" + current + "\u2192" + target + "]");
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
     * Markets refreshed: 1X2 (match_result) + asian_handicap.
     * Markets unchanged: half_time + correct_score (set at creation).
     *
     * Blocked when status is FINISHED or SCHEDULED.
     * No match events (goalscorers, cards, substitutions) are accepted here.
     *
     * @throws ApiException 400 if match is FINISHED or not in a live status
     * @throws ApiException 404 if match not found or owned by a different admin
     */
    @Transactional
    public Match updateScore(UUID matchId, AdminScoreUpdateRequest req, User admin) {
        Match match = findOrThrow(matchId);
        assertOwnership(match, admin);

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

        log.info("AdminMatchService.updateScore: adminId={} matchId={} {}:{} → {}:{} minute={}",
                admin.getId(), matchId,
                match.getScoreHome(), match.getScoreAway(),
                req.getScoreHome(),   req.getScoreAway(),
                req.getMinutePlayed());

        match.setScoreHome(req.getScoreHome());
        match.setScoreAway(req.getScoreAway());
        if (req.getMinutePlayed() != null) match.setMinutePlayed(req.getMinutePlayed());

        Match saved = matchRepo.save(match);

        // Regenerate live odds immediately after every score change so the
        // odds table is always consistent with the current scoreline.
        // This mirrors exactly what LiveScorePoller does for external-feed matches.
        persistLiveOdds(saved, "updateScore[" + req.getScoreHome() + ":" + req.getScoreAway() + "]");

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
     * Called on: SCHEDULED→LIVE, →HALF_TIME, →SECOND_HALF, every score update.
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
    private void assertOwnership(Match match, User admin) {
        if (!admin.getId().equals(match.getCreatedByAdminId())) {
            log.warn("AdminMatchService.assertOwnership: DENIED — adminId={} tried matchId={} owned by adminId={}",
                    admin.getId(), match.getId(), match.getCreatedByAdminId());
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

    private String resolveInitialStatus(String raw) {
        if (raw == null || raw.isBlank()) return "SCHEDULED";
        String upper = raw.toUpperCase();
        if (!VALID_STATUSES.contains(upper)) {
            throw ApiException.badRequest(
                    "Invalid initial status '" + raw + "'. Allowed: " + VALID_STATUSES);
        }
        if ("FINISHED".equals(upper)) {
            throw ApiException.badRequest("Cannot create a match with status FINISHED.");
        }
        return upper;
    }
}
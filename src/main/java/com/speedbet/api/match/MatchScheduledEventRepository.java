package com.speedbet.api.match;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for match_scheduled_events.
 *
 * The claimEvent() method is the critical one: it does an atomic
 * UPDATE … WHERE status = 'PENDING' before the caller executes the event.
 * This ensures that even on a multi-node deployment, only one node
 * executes each event — the one that wins the CAS.
 */
public interface MatchScheduledEventRepository extends JpaRepository<MatchScheduledEvent, UUID> {

    // ── Poller query ────────────────────────────────────────────────────

    /**
     * All PENDING events whose fire_at is in the past or right now, oldest first.
     * This is the primary query the 5-second poller uses to discover work.
     */
    List<MatchScheduledEvent> findAllByStatusAndFireAtLessThanEqualOrderByFireAtAsc(
            String status, Instant fireAt);

    // ── Atomic claim (CAS) ─────────────────────────────────────────────

    /**
     * Atomically transitions one event from PENDING to CLAIMED.
     *
     * Returns 1 if the claim succeeded (this node owns it), 0 if another
     * node beat us to it (or the row was already done/failed). The caller
     * MUST check the return value and skip on 0.
     *
     * We use a transient CLAIMED state here so that the event is invisible
     * to the poller's WHERE status = 'PENDING' filter while we execute it,
     * preventing a slow execution from being picked up twice.
     */
    @Modifying
    @Transactional
    @Query("UPDATE MatchScheduledEvent e SET e.status = 'CLAIMED' " +
           "WHERE e.id = :id AND e.status = 'PENDING'")
    int claimEvent(@Param("id") UUID id);

    // ── Mark terminal states ────────────────────────────────────────────

    @Modifying
    @Transactional
    @Query("UPDATE MatchScheduledEvent e SET e.status = 'DONE' WHERE e.id = :id")
    void markDone(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("UPDATE MatchScheduledEvent e SET e.status = 'FAILED' WHERE e.id = :id")
    void markFailed(@Param("id") UUID id);

    /**
     * Used by the in-memory fast-path to mark a GOAL event done by its
     * natural key (matchId + type + exact fireAt instant) so the 5-second
     * poller does not double-execute it.
     */
    @Modifying
    @Transactional
    @Query("UPDATE MatchScheduledEvent e SET e.status = 'DONE' " +
           "WHERE e.matchId = :matchId AND e.eventType = :type AND e.fireAt = :fireAt " +
           "AND e.status IN ('PENDING', 'CLAIMED')")
    void markDoneByMatchAndTypeAndFireAt(
            @Param("matchId") UUID matchId,
            @Param("type")    String type,
            @Param("fireAt")  Instant fireAt);

    /**
     * Marks all PENDING/CLAIMED events of a given type for a match as DONE.
     * Used by the fast-path after executing status-transition events (HALF_TIME,
     * SECOND_HALF, FINISHED) so the poller doesn't re-run them.
     */
    @Modifying
    @Transactional
    @Query("UPDATE MatchScheduledEvent e SET e.status = 'DONE' " +
           "WHERE e.matchId = :matchId AND e.eventType = :type " +
           "AND e.status IN ('PENDING', 'CLAIMED')")
    void markDoneByMatchAndType(
            @Param("matchId") UUID matchId,
            @Param("type")    String type);

    // ── Cancel ─────────────────────────────────────────────────────────

    /**
     * Marks all PENDING events for a match as CANCELLED. Used when an admin
     * cancels the automated schedule so the poller stops picking them up.
     */
    @Modifying
    @Transactional
    @Query("UPDATE MatchScheduledEvent e SET e.status = 'CANCELLED' " +
           "WHERE e.matchId = :matchId AND e.status = 'PENDING'")
    int cancelPendingByMatchId(@Param("matchId") UUID matchId);

    // ── Inspect ─────────────────────────────────────────────────────────

    List<MatchScheduledEvent> findByMatchIdOrderByFireAtAsc(UUID matchId);

    /**
     * Finds the single FINISHED event for a match — used by the overdue-finish
     * sweep and stale-match recovery to retrieve the intended final score when
     * no in-memory handle is available.
     *
     * Returns Optional.empty() if no FINISHED event was ever persisted (e.g.
     * a manually created SCHEDULED match with no auto-events).
     */
    Optional<MatchScheduledEvent> findTopByMatchIdAndEventType(
            @Param("matchId")   UUID matchId,
            @Param("eventType") String eventType);
}
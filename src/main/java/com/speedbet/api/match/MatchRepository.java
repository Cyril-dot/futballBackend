package com.speedbet.api.match;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MatchRepository extends JpaRepository<Match, UUID> {

    // ── Existing methods (unchanged signatures) ──────────────────────────

    List<Match> findByStatusOrderByKickoffAt(String status);
    List<Match> findByFeaturedTrueOrderByKickoffAt();
    List<Match> findByStatusIn(List<String> statuses);

    @Query("SELECT m FROM Match m WHERE m.kickoffAt BETWEEN :from AND :to ORDER BY m.kickoffAt")
    List<Match> findUpcoming(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT m FROM Match m WHERE m.status = 'FINISHED' AND m.settledAt IS NULL")
    List<Match> findUnsettledFinished();

    List<Match> findBySourceOrderByKickoffAtDesc(MatchSource source);

    /**
     * Convenience — find by source and status (useful for future admin filters).
     */
    List<Match> findBySourceAndStatus(MatchSource source, String status);

    List<Match> findByCreatedByAdminIdOrderByKickoffAtDesc(UUID createdByAdminId);

    /**
     * Matches by a specific admin filtered by status (e.g. "LIVE").
     * Optional — available for future admin dashboard filters.
     */
    List<Match> findByCreatedByAdminIdAndStatus(UUID createdByAdminId, String status);

    Optional<Match> findByExternalId(String externalId);

    Page<Match> findByLeagueContainingIgnoreCaseOrHomeTeamContainingIgnoreCaseOrAwayTeamContainingIgnoreCase(
            String league, String home, String away, Pageable pageable);

    // ── New methods for live/today/future wiring ─────────────────────────

    /**
     * Any match (regardless of status) kicking off within [from, to).
     * Used by MatchService.getTodayMatches.
     */
    @Query("""
            SELECT m FROM Match m
             WHERE m.kickoffAt >= :from
               AND m.kickoffAt <  :to
             ORDER BY m.kickoffAt ASC
            """)
    List<Match> findByKickoffBetween(@Param("from") Instant from,
                                     @Param("to")   Instant to);

    /**
     * Future matches (status = UPCOMING) within [from, to), kickoff-ordered.
     * Used by MatchService.getFutureMatches.
     */
    @Query("""
            SELECT m FROM Match m
             WHERE m.status = 'UPCOMING'
               AND m.kickoffAt >= :from
               AND m.kickoffAt <  :to
             ORDER BY m.kickoffAt ASC
            """)
    List<Match> findUpcomingScheduled(@Param("from") Instant from,
                                      @Param("to")   Instant to);

    /**
     * LIVE matches that are stale — either:
     *   1. kickoff_at IS NULL  (API never provided a kickoff time), OR
     *   2. kickoff_at is older than the given cutoff (match ran >4 hours ago)
     *
     * FIX: Previously only checked kickoffAt < :cutoff, which silently skipped
     * NULL kickoff rows because NULL < anything = NULL (falsy) in SQL.
     * Bayern vs PSG was stuck LIVE forever because its kickoff_at was NULL.
     */
    @Query("""
            SELECT m FROM Match m
             WHERE m.status = 'LIVE'
               AND (m.kickoffAt IS NULL OR m.kickoffAt < :cutoff)
            """)
    List<Match> findStaleLive(@Param("cutoff") Instant cutoff);

}
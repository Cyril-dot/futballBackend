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

    // ── Existing methods (unchanged) ──────────────────────────────────────

    // Keep the original method but deprecate it – use sport‑specific one instead
    @Deprecated
    List<Match> findByStatusOrderByKickoffAt(String status);

    List<Match> findByFeaturedTrueOrderByKickoffAt();
    List<Match> findByStatusIn(List<String> statuses);

    @Query("SELECT m FROM Match m WHERE m.kickoffAt BETWEEN :from AND :to ORDER BY m.kickoffAt")
    List<Match> findUpcoming(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT m FROM Match m WHERE m.status = 'FINISHED' AND m.settledAt IS NULL")
    List<Match> findUnsettledFinished();

    List<Match> findBySourceOrderByKickoffAtDesc(MatchSource source);
    List<Match> findBySourceAndStatus(MatchSource source, String status);
    List<Match> findByCreatedByAdminIdOrderByKickoffAtDesc(UUID createdByAdminId);
    List<Match> findByCreatedByAdminIdAndStatus(UUID createdByAdminId, String status);
    Optional<Match> findByExternalId(String externalId);

    Page<Match> findByLeagueContainingIgnoreCaseOrHomeTeamContainingIgnoreCaseOrAwayTeamContainingIgnoreCase(
            String league, String home, String away, Pageable pageable);

    @Query("""
            SELECT m FROM Match m
            WHERE m.kickoffAt >= :from
              AND m.kickoffAt <  :to
            ORDER BY m.kickoffAt ASC
            """)
    List<Match> findByKickoffBetween(@Param("from") Instant from,
                                     @Param("to")   Instant to);

    @Query("""
            SELECT m FROM Match m
            WHERE m.status = 'UPCOMING'
              AND m.kickoffAt >= :from
              AND m.kickoffAt <  :to
            ORDER BY m.kickoffAt ASC
            """)
    List<Match> findUpcomingScheduled(@Param("from") Instant from,
                                      @Param("to")   Instant to);

    @Query("""
            SELECT m FROM Match m
            WHERE m.status = 'LIVE'
              AND (m.kickoffAt IS NULL OR m.kickoffAt < :cutoff)
            """)
    List<Match> findStaleLive(@Param("cutoff") Instant cutoff);


    @Query("""
        SELECT m FROM Match m
        WHERE m.status = 'FINISHED'
          AND m.settledAt IS NOT NULL
        ORDER BY m.settledAt DESC
        """)
    List<Match> findSettledFinished();

    // ── New sport‑scoped queries (using Sport enum) ───────────────────────

    /**
     * Fetch matches by status and sport (using the Sport enum).
     * The enum's key is used to match the stored {@code sport} column.
     *
     * @param sport  the sport (can be null to skip filtering)
     * @param status match status
     * @return list of matches ordered by kickoff time
     */
    @Query("""
            SELECT m FROM Match m
            WHERE (:sport IS NULL OR m.sport = :sport)
              AND m.status = :status
            ORDER BY m.kickoffAt
            """)
    List<Match> findByStatusAndSport(@Param("sport") String sport,
                                     @Param("status") String status);

    // Convenience method – converts Sport enum to its key
    default List<Match> findByStatusAndSport(Sport sport, String status) {
        return findByStatusAndSport(sport != null ? sport.key() : null, status);
    }
    // MatchRepository.java
    @Query("""
        SELECT m FROM Match m
        WHERE m.sport = :sport
          AND m.status = :status
          AND m.kickoffAt > :cutoff
        ORDER BY m.kickoffAt DESC
        """)
    List<Match> findRecentFinishedBySportAndStatus(
            @Param("sport")   String  sport,
            @Param("status")  String  status,
            @Param("cutoff")  Instant cutoff,
            Pageable pageable);

    // ── Existing sport‑specific methods (remain) ─────────────────────────

    List<Match> findBySportAndStatusOrderByKickoffAt(String sport, String status);

    @Query("""
            SELECT m FROM Match m
            WHERE m.sport = :sport
              AND m.status = 'UPCOMING'
              AND m.kickoffAt >= :from
              AND m.kickoffAt <  :to
            ORDER BY m.kickoffAt ASC
            """)
    List<Match> findUpcomingScheduledBySport(@Param("sport") String sport,
                                             @Param("from")  Instant from,
                                             @Param("to")    Instant to);

    @Query("""
            SELECT m FROM Match m
            WHERE m.sport = :sport
              AND m.kickoffAt >= :from
              AND m.kickoffAt <  :to
            ORDER BY m.kickoffAt ASC
            """)
    List<Match> findByKickoffBetweenAndSport(@Param("sport") String sport,
                                             @Param("from")  Instant from,
                                             @Param("to")    Instant to);

    @Query("""
            SELECT m FROM Match m
            WHERE m.sport = :sport
              AND m.status = 'LIVE'
              AND (m.kickoffAt IS NULL OR m.kickoffAt < :cutoff)
            """)
    List<Match> findStaleLiveBySport(@Param("sport") String sport,
                                     @Param("cutoff") Instant cutoff);

    @Query("""
            SELECT m FROM Match m
            WHERE m.sport = :sport
              AND m.status = 'FINISHED'
              AND m.settledAt IS NULL
            """)
    List<Match> findUnsettledFinishedBySport(@Param("sport") String sport);
}

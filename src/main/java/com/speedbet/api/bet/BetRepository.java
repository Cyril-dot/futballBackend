package com.speedbet.api.bet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BetRepository extends JpaRepository<Bet, UUID> {

    /** MSport expiry job: any bet at all counts as money history (funded). */
    boolean existsByUserId(UUID userId);

    /** MSport expiry job: remove a deleted account's bets. */
    @Modifying
    @Query("delete from Bet b where b.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    Page<Bet> findByUserIdOrderByPlacedAtDesc(UUID userId, Pageable pageable);

    Page<Bet> findByStatus(BetStatus status, Pageable pageable);

    List<Bet> findByUserIdAndStatus(UUID userId, BetStatus status);

    @Query("SELECT b FROM Bet b WHERE b.userId = :userId AND b.status = 'WON' AND b.winSeen = false")
    List<Bet> findUnseenWins(UUID userId);

    @Query("SELECT b FROM Bet b JOIN b.selections s WHERE s.matchId = :matchId AND b.status = 'PENDING'")
    List<Bet> findPendingByMatchId(UUID matchId);

    /**
     * Returns true if any bet touching this match still has an unsettled status.
     * Used by the post-settlement delete sweep before removing a FINISHED match.
     *
     * The query joins through BetSelection (which holds the matchId) because
     * Bet itself doesn't carry a direct matchId column — selections do.
     * Statuses checked: PENDING (placed, not yet graded).
     * If your BetStatus enum uses additional unsettled values (e.g. OPEN,
     * ACTIVE) add them to the call-site list in AdminMatchScheduleService.
     */
    @Query("SELECT COUNT(b) > 0 FROM Bet b JOIN b.selections s " +
           "WHERE s.matchId = :matchId AND b.status IN :statuses")
    boolean existsByMatchIdAndStatusIn(
            @Param("matchId")  UUID matchId,
            @Param("statuses") Collection<String> statuses);
}
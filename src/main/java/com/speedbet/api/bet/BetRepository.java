package com.speedbet.api.bet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface BetRepository extends JpaRepository<Bet, UUID> {
    Page<Bet> findByUserIdOrderByPlacedAtDesc(UUID userId, Pageable pageable);
    List<Bet> findByUserIdAndStatus(UUID userId, BetStatus status);

    @Query("SELECT b FROM Bet b WHERE b.userId = :userId AND b.status = 'WON' AND b.winSeen = false")
    List<Bet> findUnseenWins(UUID userId);

    @Query("SELECT b FROM Bet b JOIN b.selections s WHERE s.matchId = :matchId AND b.status = 'PENDING'")
    List<Bet> findPendingByMatchId(UUID matchId);
}

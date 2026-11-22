package com.speedbet.api.casinoGames.spindaBottle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface SpinBottleRoundRepository extends JpaRepository<SpinBottleRound, UUID> {

    /** MSport expiry job: remove a deleted account's game rounds. */
    @Modifying
    @Query("delete from SpinBottleRound r where r.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    Page<SpinBottleRound> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
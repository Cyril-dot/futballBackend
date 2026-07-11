package com.speedbet.api.casinoGames.spindaBottle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpinBottleRoundRepository extends JpaRepository<SpinBottleRound, UUID> {
    Page<SpinBottleRound> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
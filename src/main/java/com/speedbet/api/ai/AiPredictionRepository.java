package com.speedbet.api.ai;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AiPredictionRepository extends JpaRepository<AiPrediction, UUID> {
    Page<AiPrediction> findBySharedByAdminIdOrderByGeneratedAtDesc(UUID adminId, Pageable pageable);
    Page<AiPrediction> findByPublishedToUsersTrueOrderByGeneratedAtDesc(Pageable pageable);
    Optional<AiPrediction> findTopByMatchIdOrderByGeneratedAtDesc(UUID matchId);

    @Query("SELECT p FROM AiPrediction p ORDER BY p.generatedAt DESC")
    Page<AiPrediction> findAllOrderByGeneratedAtDesc(Pageable pageable);
}

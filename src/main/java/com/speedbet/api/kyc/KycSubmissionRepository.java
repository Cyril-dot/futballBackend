package com.speedbet.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, UUID> {

    Optional<KycSubmission> findFirstByUserIdOrderBySubmittedAtDesc(UUID userId);

    List<KycSubmission> findByUserIdOrderBySubmittedAtDesc(UUID userId);

    List<KycSubmission> findByStatusOrderBySubmittedAtDesc(KycStatus status);

    List<KycSubmission> findAllByOrderBySubmittedAtDesc();
}

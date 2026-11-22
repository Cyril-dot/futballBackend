package com.speedbet.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, UUID> {

    /** MSport expiry job: remove a deleted account's KYC submissions. */
    @Modifying
    @Query("delete from KycSubmission k where k.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    Optional<KycSubmission> findFirstByUserIdOrderBySubmittedAtDesc(UUID userId);

    List<KycSubmission> findByUserIdOrderBySubmittedAtDesc(UUID userId);

    List<KycSubmission> findByStatusOrderBySubmittedAtDesc(KycStatus status);

    List<KycSubmission> findAllByOrderBySubmittedAtDesc();
}

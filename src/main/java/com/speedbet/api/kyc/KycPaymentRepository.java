package com.speedbet.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KycPaymentRepository extends JpaRepository<KycPayment, UUID> {

    /** MSport expiry job: a KYC fee payment is money history (funded). */
    boolean existsByUserId(UUID userId);

    /** MSport expiry job: remove a deleted account's KYC fee payments. */
    @Modifying
    @Query("delete from KycPayment k where k.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    List<KycPayment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<KycPayment> findByStatusOrderByCreatedAtDesc(KycPaymentStatus status);

    List<KycPayment> findAllByOrderByCreatedAtDesc();
}

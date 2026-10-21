package com.speedbet.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycPaymentRepository extends JpaRepository<KycPayment, UUID> {

    List<KycPayment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<KycPayment> findByStatusOrderByCreatedAtDesc(KycPaymentStatus status);

    List<KycPayment> findAllByOrderByCreatedAtDesc();
}

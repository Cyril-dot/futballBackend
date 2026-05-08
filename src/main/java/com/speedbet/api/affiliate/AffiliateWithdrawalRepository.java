package com.speedbet.api.affiliate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface AffiliateWithdrawalRepository extends JpaRepository<AffiliateWithdrawalRequest, UUID> {

    Page<AffiliateWithdrawalRequest> findByUserIdOrderByRequestedAtDesc(UUID userId, Pageable pageable);

    List<AffiliateWithdrawalRequest> findByStatus(AffiliateWithdrawalStatus status);
}
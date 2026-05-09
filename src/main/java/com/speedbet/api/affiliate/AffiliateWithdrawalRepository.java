package com.speedbet.api.affiliate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AffiliateWithdrawalRepository extends JpaRepository<AffiliateWithdrawalRequest, UUID> {

    // ── Existing ──────────────────────────────────────────────────────────────

    Page<AffiliateWithdrawalRequest> findByUserIdOrderByRequestedAtDesc(UUID userId, Pageable pageable);

    /** Used by existing WithdrawalAdminController — non-paginated PENDING list. */
    List<AffiliateWithdrawalRequest> findByStatus(AffiliateWithdrawalStatus status);

    // ── New: paginated by status ──────────────────────────────────────────────

    /** Used by GET /api/super-admin/affiliate-withdrawals?status=PENDING|PROCESSED|REJECTED */
    Page<AffiliateWithdrawalRequest> findByStatusOrderByRequestedAtDesc(
            AffiliateWithdrawalStatus status, Pageable pageable);
}
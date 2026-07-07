package com.speedbet.api.affiliate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PayoutRequestRepository extends JpaRepository<PayoutRequest, UUID> {

    /** Paginated payout history for a specific admin. */
    Page<PayoutRequest> findByAdminIdOrderByCreatedAtDesc(UUID adminId, Pageable pageable);

    /** All requests with a given status — used by super admin. */
    List<PayoutRequest> findByStatus(PayoutStatus status);

    /** Check if this admin already has an open (non-terminal) payout request. */
    boolean existsByAdminIdAndStatusIn(UUID adminId, List<PayoutStatus> statuses);

    /**
     * Most recent payout request for this admin, of ANY status. Used as the
     * cutoff for how far back to look for un-credited Nigerian deposits —
     * see AdminAffiliateService.requestPayout() for why this must be based
     * on request creation time rather than commBalance.getLastPayoutAt().
     */
    Optional<PayoutRequest> findTopByAdminIdOrderByCreatedAtDesc(UUID adminId);
}
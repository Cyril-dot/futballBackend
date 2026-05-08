package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Super-admin endpoints for managing admin affiliate payout requests.
 *
 * Lifecycle:  REQUESTED → APPROVED → PAID
 *                       ↘ REJECTED
 *
 * Base path: /api/super-admin/payout-requests
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/payout-requests")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PayoutSuperAdminController {

    private final AdminAffiliateService adminAffiliateService;

    /**
     * GET /api/super-admin/payout-requests
     * List all payout requests in REQUESTED status awaiting review.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PayoutRequest>>> getPending() {
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.getPayoutRequestsByStatus(PayoutStatus.REQUESTED)));
    }

    /**
     * POST /api/super-admin/payout-requests/{id}/approve
     * Approve a REQUESTED payout. Wallet is not debited yet.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<PayoutRequest>> approve(@PathVariable UUID id) {
        log.info("approve: payoutId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.approve(id)));
    }

    /**
     * POST /api/super-admin/payout-requests/{id}/reject
     * Reject a REQUESTED payout with an optional reason.
     * Wallet is not touched (no debit was made at request time).
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<PayoutRequest>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        log.info("reject: payoutId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.reject(id, body.get("reason"))));
    }

    /**
     * POST /api/super-admin/payout-requests/{id}/mark-paid
     * Mark an APPROVED payout as PAID.
     * This triggers WalletService.debit — the snapshotted amount is removed
     * from the admin's affiliate wallet balance.
     */
    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<ApiResponse<PayoutRequest>> markPaid(@PathVariable UUID id) {
        log.info("markPaid: payoutId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.markPaid(id)));
    }
}
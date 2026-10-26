package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Super-admin endpoints for processing affiliate withdrawal requests.
 * Only SUPER_ADMIN role can access these.
 *
 * IMPORTANT — balance separation:
 *   These withdrawals represent COMMISSION earnings, not the user's main wallet.
 *   Rejecting a withdrawal reverses the amount back to the commission balance
 *   (via AffiliateCommissionService), NOT the main wallet (WalletService).
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/affiliate-withdrawals")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class WithdrawalAdminController {

    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final AffiliateCommissionService commissionService;   // ← commission balance, NOT wallet

    /** List all PENDING affiliate withdrawal requests. */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<AffiliateWithdrawalRequest>>> getPending() {
        return ResponseEntity.ok(ApiResponse.ok(
                withdrawalRepo.findByStatus(AffiliateWithdrawalStatus.PENDING)));
    }

    /**
     * Mark an affiliate withdrawal as processed (bank/MoMo transfer completed by ops team).
     *
     * The commission deduction happens HERE, at payment time — not at request
     * creation. Requests queued by the daily scheduler after the
     * don't-clear-until-paid change carry balanceSwept=false, so processing
     * deducts their amount from the commission balance now. Older requests
     * (balanceSwept NULL) were swept at creation, so processing deducts
     * nothing further. User wallet withdrawals never touch the commission
     * balance and are unaffected either way.
     */
    @PostMapping("/{id}/process")
    public ResponseEntity<ApiResponse<AffiliateWithdrawalRequest>> process(@PathVariable UUID id) {
        log.info("process: affiliateWithdrawalId={}", id);
        var withdrawal = getById(id);

        if (withdrawal.getStatus() != AffiliateWithdrawalStatus.PENDING)
            throw ApiException.badRequest("Withdrawal is not PENDING");

        boolean alreadySwept = withdrawal.getBalanceSwept() == null || withdrawal.getBalanceSwept();
        if (!alreadySwept) {
            commissionService.deductProcessedPayout(withdrawal.getUserId(), withdrawal.getAmount());
        }

        withdrawal.setStatus(AffiliateWithdrawalStatus.PROCESSED);
        withdrawal.setProcessedAt(Instant.now());

        return ResponseEntity.ok(ApiResponse.ok(withdrawalRepo.save(withdrawal)));
    }

    /**
     * Reject a withdrawal. Only requests whose balance was swept at creation
     * (pre-change scheduler rows, balanceSwept NULL/true) get their amount
     * returned to the affiliate's COMMISSION balance (not their main wallet —
     * these are two separate ledgers). Requests queued without sweeping
     * (balanceSwept=false) never moved the balance, so rejecting them
     * reverses nothing; the amount simply stays owed until a later run.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<AffiliateWithdrawalRequest>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {

        log.info("reject: affiliateWithdrawalId={}", id);
        var withdrawal = getById(id);

        if (withdrawal.getStatus() != AffiliateWithdrawalStatus.PENDING)
            throw ApiException.badRequest("Withdrawal is not PENDING");

        // ── Reverse into COMMISSION balance, not main wallet — but ONLY when
        // the balance was actually swept at creation. Queued-without-sweep
        // requests never moved the balance, so reversing them would create
        // money. Reversing to the main wallet would incorrectly inflate the
        // user's spendable balance with funds they earned as referral commission.
        boolean alreadySwept = withdrawal.getBalanceSwept() == null || withdrawal.getBalanceSwept();
        if (alreadySwept) {
            commissionService.reverseCommissionPayout(
                    withdrawal.getUserId(),
                    withdrawal.getAmount());

            log.info("reject: commission balance re-credited userId={} amount={} (NOT main wallet)",
                    withdrawal.getUserId(), withdrawal.getAmount());
        }

        withdrawal.setStatus(AffiliateWithdrawalStatus.REJECTED);
        withdrawal.setRejectReason(body.getOrDefault("reason", "No reason provided"));
        withdrawal.setProcessedAt(Instant.now());

        return ResponseEntity.ok(ApiResponse.ok(withdrawalRepo.save(withdrawal)));
    }

    private AffiliateWithdrawalRequest getById(UUID id) {
        return withdrawalRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Affiliate withdrawal request not found"));
    }
}
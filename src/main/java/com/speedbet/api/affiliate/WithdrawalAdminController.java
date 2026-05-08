package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/affiliate-withdrawals")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class WithdrawalAdminController {

    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final WalletService walletService;

    /** List all PENDING affiliate withdrawal requests. */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<AffiliateWithdrawalRequest>>> getPending() {
        return ResponseEntity.ok(ApiResponse.ok(
                withdrawalRepo.findByStatus(AffiliateWithdrawalStatus.PENDING)));
    }

    /** Mark an affiliate withdrawal as processed (bank/MoMo transfer completed). */
    @PostMapping("/{id}/process")
    public ResponseEntity<ApiResponse<AffiliateWithdrawalRequest>> process(@PathVariable UUID id) {
        log.info("process: affiliateWithdrawalId={}", id);
        var withdrawal = getById(id);

        if (withdrawal.getStatus() != AffiliateWithdrawalStatus.PENDING)
            throw ApiException.badRequest("Withdrawal is not PENDING");

        withdrawal.setStatus(AffiliateWithdrawalStatus.PROCESSED);
        withdrawal.setProcessedAt(Instant.now());

        return ResponseEntity.ok(ApiResponse.ok(withdrawalRepo.save(withdrawal)));
    }

    /** Reject an affiliate withdrawal and re-credit the user's wallet. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<AffiliateWithdrawalRequest>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {

        log.info("reject: affiliateWithdrawalId={}", id);
        var withdrawal = getById(id);

        if (withdrawal.getStatus() != AffiliateWithdrawalStatus.PENDING)
            throw ApiException.badRequest("Withdrawal is not PENDING");

        walletService.credit(
                withdrawal.getUserId(),
                withdrawal.getAmount(),
                TxKind.WITHDRAWAL_REFUND,
                "REFUND-" + withdrawal.getReference(),
                Map.of("reason", "affiliate_withdrawal_rejected",
                        "originalRef", withdrawal.getReference()));

        withdrawal.setStatus(AffiliateWithdrawalStatus.REJECTED);
        withdrawal.setRejectReason(body.getOrDefault("reason", "No reason provided"));
        withdrawal.setProcessedAt(Instant.now());

        log.info("reject: wallet re-credited userId={} amount={}",
                withdrawal.getUserId(), withdrawal.getAmount());

        return ResponseEntity.ok(ApiResponse.ok(withdrawalRepo.save(withdrawal)));
    }

    private AffiliateWithdrawalRequest getById(UUID id) {
        return withdrawalRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Affiliate withdrawal request not found"));
    }
}
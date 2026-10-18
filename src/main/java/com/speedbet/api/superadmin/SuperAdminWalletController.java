package com.speedbet.api.superadmin;

import com.speedbet.api.referral.ReferralService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/super-admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminWalletController {

    private final SuperAdminWalletService superAdminWalletService;
    private final ReferralService referralService;

    public record AddFundsRequest(String amount, String reason, String idempotencyKey) {
        BigDecimal parsedAmount() {
            if (amount == null || amount.isBlank()) return null;
            return new BigDecimal(amount.trim());
        }
    }

    /** Works for any user — regular USER or ADMIN. */
    @PostMapping("/users/{userId}/add-funds")
    public ResponseEntity<SuperAdminDtos.WalletCreditDto> addFundsToUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal(expression = "id") UUID adminId,
            @RequestBody AddFundsRequest request) {

        log.info("POST /super-admin/users/{}/add-funds — adminId='{}'", userId, adminId);
        var credited = superAdminWalletService.addFunds(userId, adminId, request.parsedAmount(), request.reason(), request.idempotencyKey());

        // addFunds records the credit as a normal DEPOSIT, and the affiliate
        // reports count it in the referring admin's deposit totals — so it
        // must attribute commission exactly like a real deposit, otherwise
        // the admin's deposits rise with no commission beside them. Runs
        // after the credit has committed (same pattern as the payment
        // controllers' handleDeposit) and never fails the credit itself.
        try {
            referralService.attributeCommission(userId, credited.amount());
            log.info("addFunds: commission attributed for userId='{}' deposit={}", userId, credited.amount());
        } catch (Exception ex) {
            log.error("addFunds: commission attribution failed for userId='{}' — investigate", userId, ex);
        }

        return ResponseEntity.ok(credited);
    }
}
package com.speedbet.api.superadmin;

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

    public record AddFundsRequest(String amount, String reason) {
        BigDecimal parsedAmount() {
            if (amount == null || amount.isBlank()) return null;
            return new BigDecimal(amount.trim());
        }
    }

    /** Canonical route — works for any user, regular or admin. */
    @PostMapping("/users/{userId}/add-funds")
    public ResponseEntity<SuperAdminDtos.WalletCreditDto> addFundsToUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal(expression = "id") UUID adminId,
            @RequestBody AddFundsRequest request) {

        log.info("POST /super-admin/users/{}/add-funds — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(
                superAdminWalletService.addFunds(userId, adminId, request.parsedAmount(), request.reason())
        );
    }

    /** Back-compat alias — same behavior, kept because the Admins page already calls this path. */
    @PostMapping("/admins/{adminId}/add-funds")
    public ResponseEntity<SuperAdminDtos.WalletCreditDto> addFundsToAdmin(
            @PathVariable UUID adminId,
            @AuthenticationPrincipal(expression = "id") UUID actingAdminId,
            @RequestBody AddFundsRequest request) {

        log.info("POST /super-admin/admins/{}/add-funds — actingAdminId='{}'", adminId, actingAdminId);
        return ResponseEntity.ok(
                superAdminWalletService.addFunds(adminId, actingAdminId, request.parsedAmount(), request.reason())
        );
    }
}
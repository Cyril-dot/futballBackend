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

    /** Works for any user — regular USER or ADMIN. */
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
}
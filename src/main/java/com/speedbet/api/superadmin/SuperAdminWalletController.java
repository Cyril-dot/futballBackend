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
@RequestMapping("/api/v1/super-admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminWalletController {

    private final SuperAdminWalletService superAdminWalletService;

    public record AddFundsRequest(BigDecimal amount, String reason) {}

    /**
     * POST /api/v1/super-admin/users/{userId}/wallet/add-funds
     */
    @PostMapping("/{userId}/wallet/add-funds")
    public ResponseEntity<SuperAdminDtos.WalletCreditDto> addFunds(
            @PathVariable UUID userId,
            @AuthenticationPrincipal(expression = "id") UUID adminId,
            @RequestBody AddFundsRequest request) {

        log.info("POST /super-admin/users/{}/wallet/add-funds — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(
                superAdminWalletService.addFunds(userId, adminId, request.amount(), request.reason())
        );
    }
}
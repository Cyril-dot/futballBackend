package com.speedbet.api.wallet;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.kyc.KycService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final KycService kycService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWallet(@AuthenticationPrincipal User user) {
        var wallet = walletService.getWallet(user.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "balance", wallet.getBalance(),
            "currency", wallet.getCurrency()
        )));
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<PageResponse<Transaction>>> transactions(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = walletService.getTransactions(user.getId(), PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<Transaction>> withdraw(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {
        // Instant wallet debits are a STAFF action. Players withdraw through
        // the request flow (POST /api/wallet/withdrawals), which enforces
        // KYC verification — this endpoint must never be a bypass around it.
        if (kycService.isEnforcementEnabled() && user.getRole() == UserRole.USER) {
            throw ApiException.forbidden(
                    "Players withdraw by request from the wallet page. Direct withdrawal is staff only.");
        }
        var amount = new BigDecimal(req.get("amount").toString());
        var tx = walletService.debit(user.getId(), amount, TxKind.WITHDRAW, null,
            Map.of("type", "withdrawal", "method", req.getOrDefault("method", "mobile_money")));
        return ResponseEntity.ok(ApiResponse.ok(tx, "Withdrawal requested"));
    }
}

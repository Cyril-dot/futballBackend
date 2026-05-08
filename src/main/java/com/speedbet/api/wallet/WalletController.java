package com.speedbet.api.wallet;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
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
        var amount = new BigDecimal(req.get("amount").toString());
        var tx = walletService.debit(user.getId(), amount, TxKind.WITHDRAW, null,
            Map.of("type", "withdrawal", "method", req.getOrDefault("method", "mobile_money")));
        return ResponseEntity.ok(ApiResponse.ok(tx, "Withdrawal requested"));
    }
}

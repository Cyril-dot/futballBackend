package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User-side RoyalBet KYC / gate-fee payment endpoints. NOT a deposit
 * route: recording a payment here never credits the wallet — the super
 * admin confirms the fee in the dedicated KYC payments queue.
 * POST /api/kyc/payments       — record a fee payment (MoMo ref / proof).
 * GET  /api/kyc/payments/mine  — the user's fee payments + statuses.
 */
@RestController
@RequestMapping("/api/kyc/payments")
@RequiredArgsConstructor
public class KycPaymentController {

    private final KycPaymentService paymentService;

    @PostMapping
    public ResponseEntity<ApiResponse<KycPaymentDtos.KycPaymentResponse>> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody KycPaymentDtos.CreateKycPaymentRequest req) {
        KycPayment saved = paymentService.create(user, req);
        return ResponseEntity.ok(ApiResponse.ok(
                KycPaymentDtos.KycPaymentResponse.from(saved), "KYC payment recorded — awaiting confirmation"));
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<KycPaymentDtos.KycPaymentResponse>>> mine(
            @AuthenticationPrincipal User user) {
        List<KycPaymentDtos.KycPaymentResponse> rows = paymentService.mine(user).stream()
                .map(KycPaymentDtos.KycPaymentResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }
}

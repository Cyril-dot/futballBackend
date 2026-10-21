package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Super-admin KYC / gate-fee payment queue (secured by /api/super-admin/**).
 * Completely separate from the deposit queues: confirming here marks the
 * KYC fee paid and credits NOTHING to any wallet.
 * GET  /api/super-admin/kyc/payments?status=PENDING|CONFIRMED|REJECTED
 * POST /api/super-admin/kyc/payments/{id}/confirm  { note? }
 * POST /api/super-admin/kyc/payments/{id}/reject   { note }  (required)
 */
@RestController
@RequestMapping("/api/super-admin/kyc/payments")
@RequiredArgsConstructor
public class SuperAdminKycPaymentController {

    private final KycPaymentService paymentService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<KycPaymentDtos.KycPaymentResponse>>> list(
            @RequestParam(required = false) String status) {
        List<KycPaymentDtos.KycPaymentResponse> rows = paymentService.list(status).stream()
                .map(KycPaymentDtos.KycPaymentResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ApiResponse<KycPaymentDtos.KycPaymentResponse>> confirm(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @RequestBody(required = false) KycPaymentDtos.ReviewKycPaymentRequest req) {
        KycPayment saved = paymentService.review(admin, id, true, req == null ? null : req.getNote());
        return ResponseEntity.ok(ApiResponse.ok(
                KycPaymentDtos.KycPaymentResponse.from(saved), "KYC payment confirmed"));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<KycPaymentDtos.KycPaymentResponse>> reject(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @RequestBody(required = false) KycPaymentDtos.ReviewKycPaymentRequest req) {
        KycPayment saved = paymentService.review(admin, id, false, req == null ? null : req.getNote());
        return ResponseEntity.ok(ApiResponse.ok(
                KycPaymentDtos.KycPaymentResponse.from(saved), "KYC payment rejected"));
    }
}

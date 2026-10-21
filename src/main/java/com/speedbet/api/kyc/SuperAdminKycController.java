package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Super-admin KYC review queue (secured by the /api/super-admin/** rule).
 * GET  /api/super-admin/kyc/submissions?status=PENDING|APPROVED|REJECTED
 * POST /api/super-admin/kyc/submissions/{id}/review  { decision, note }
 */
@RestController
@RequestMapping("/api/super-admin/kyc")
@RequiredArgsConstructor
public class SuperAdminKycController {

    private final KycService kycService;

    @GetMapping("/submissions")
    public ResponseEntity<ApiResponse<List<KycDtos.KycSubmissionResponse>>> list(
            @RequestParam(required = false) String status) {
        List<KycDtos.KycSubmissionResponse> rows = kycService.list(status).stream()
                .map(KycDtos.KycSubmissionResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @PostMapping("/submissions/{id}/review")
    public ResponseEntity<ApiResponse<KycDtos.KycSubmissionResponse>> review(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody KycDtos.ReviewKycRequest req) {
        KycSubmission saved = kycService.review(admin, id, req);
        return ResponseEntity.ok(ApiResponse.ok(
                KycDtos.KycSubmissionResponse.from(saved), "KYC " + saved.getStatus().name()));
    }
}

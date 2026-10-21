package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * User-side RoyalBet KYC endpoints (any authenticated user).
 * POST /api/kyc/submit — submit or refresh the user's ID details.
 * GET  /api/kyc/mine   — the user's latest submission + its status.
 */
@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<KycDtos.KycSubmissionResponse>> submit(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody KycDtos.SubmitKycRequest req) {
        KycSubmission saved = kycService.submit(user, req);
        return ResponseEntity.ok(ApiResponse.ok(
                KycDtos.KycSubmissionResponse.from(saved), "KYC submitted — awaiting review"));
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<KycDtos.KycSubmissionResponse>> mine(
            @AuthenticationPrincipal User user) {
        KycSubmission s = kycService.mine(user);
        return ResponseEntity.ok(ApiResponse.ok(
                s == null ? null : KycDtos.KycSubmissionResponse.from(s)));
    }
}

package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Super-admin KYC on/off switch (secured by the /api/super-admin/** rule).
 * GET  /api/super-admin/kyc/settings — current state.
 * POST /api/super-admin/kyc/settings — { submissionsOpen: true|false }.
 * While closed, POST /api/kyc/submit is rejected and clients hide the
 * submission flow entirely. Existing submissions stay reviewable.
 */
@RestController
@RequestMapping("/api/super-admin/kyc")
@RequiredArgsConstructor
public class SuperAdminKycSettingsController {

    private final KycSettingsService settingsService;

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<KycSettingsDtos.SettingsResponse>> get() {
        return ResponseEntity.ok(ApiResponse.ok(
                KycSettingsDtos.SettingsResponse.from(settingsService.current())));
    }

    @PostMapping("/settings")
    public ResponseEntity<ApiResponse<KycSettingsDtos.SettingsResponse>> update(
            @AuthenticationPrincipal User admin,
            @RequestBody KycSettingsDtos.UpdateSettingsRequest req) {
        if (req == null || req.submissionsOpen() == null) {
            throw ApiException.badRequest("submissionsOpen is required.");
        }
        KycSetting saved = settingsService.setSubmissionsOpen(
                req.submissionsOpen(), admin != null ? admin.getEmail() : null);
        return ResponseEntity.ok(ApiResponse.ok(
                KycSettingsDtos.SettingsResponse.from(saved),
                saved.isSubmissionsOpen() ? "KYC submissions are OPEN" : "KYC submissions are CLOSED"));
    }
}

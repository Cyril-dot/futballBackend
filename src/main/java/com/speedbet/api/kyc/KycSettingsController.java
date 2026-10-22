package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-side read of the KYC switch so every client can hide/disable the
 * submission flow while it is off.
 * GET /api/kyc/settings — { submissionsOpen } (default false).
 */
@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
public class KycSettingsController {

    private final KycSettingsService settingsService;

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<KycSettingsDtos.SettingsResponse>> settings() {
        return ResponseEntity.ok(ApiResponse.ok(
                KycSettingsDtos.SettingsResponse.from(settingsService.current())));
    }
}

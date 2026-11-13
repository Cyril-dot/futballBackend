package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Super-admin AlphaPay settings (secured by the /api/super-admin/**
 * rule, same as the platform-amounts controller).
 * GET /api/super-admin/settings/alphapay — current settings, key masked.
 * PUT /api/super-admin/settings/alphapay — save any of the five fields;
 * a missing or blank field keeps its stored value.
 */
@RestController
@RequestMapping("/api/super-admin/settings/alphapay")
@RequiredArgsConstructor
public class SuperAdminAlphaPaySettingsController {

    private final AlphaPaySettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<AlphaPaySettingsDtos.SettingsResponse>> get() {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.maskedView()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<AlphaPaySettingsDtos.SettingsResponse>> update(
            @AuthenticationPrincipal User admin,
            @RequestBody AlphaPaySettingsDtos.UpdateSettingsRequest req) {
        AlphaPaySettingsDtos.SettingsResponse saved =
                settingsService.update(req, admin != null ? admin.getEmail() : null);
        return ResponseEntity.ok(ApiResponse.ok(saved, "AlphaPay settings saved."));
    }
}

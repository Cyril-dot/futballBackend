package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Super-admin Payteller settings (secured by the /api/super-admin/**
 * rule, same as the AlphaPay settings controller).
 * GET /api/super-admin/settings/payteller — current settings, secrets masked, merchant ID + username in full.
 * PUT /api/super-admin/settings/payteller — save any of the fields;
 * a missing or blank field keeps its stored value, except {@code enabled}
 * which is set as given when present.
 */
@RestController
@RequestMapping("/api/super-admin/settings/payteller")
@RequiredArgsConstructor
public class SuperAdminPaytellerSettingsController {

    private final PaytellerSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<PaytellerSettingsDtos.SettingsResponse>> get() {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.maskedView()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<PaytellerSettingsDtos.SettingsResponse>> update(
            @AuthenticationPrincipal User admin,
            @RequestBody PaytellerSettingsDtos.UpdateSettingsRequest req) {
        PaytellerSettingsDtos.SettingsResponse saved =
                settingsService.update(req, admin != null ? admin.getEmail() : null);
        return ResponseEntity.ok(ApiResponse.ok(saved, "Payteller settings saved."));
    }
}

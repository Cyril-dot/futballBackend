package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Super-admin Reoon settings (secured by the /api/super-admin/**
 * rule, same as the AlphaPay / Payteller settings controllers).
 * GET /api/super-admin/settings/reoon — current settings, key masked.
 * PUT /api/super-admin/settings/reoon — save the API key and/or the
 * enabled switch; a missing or blank key keeps its stored value, except
 * {@code enabled} which is set as given when present.
 */
@RestController
@RequestMapping("/api/super-admin/settings/reoon")
@RequiredArgsConstructor
public class SuperAdminReoonSettingsController {

    private final ReoonSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<ReoonSettingsDtos.SettingsResponse>> get() {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.maskedView()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<ReoonSettingsDtos.SettingsResponse>> update(
            @AuthenticationPrincipal User admin,
            @RequestBody ReoonSettingsDtos.UpdateSettingsRequest req) {
        ReoonSettingsDtos.SettingsResponse saved =
                settingsService.update(req, admin != null ? admin.getEmail() : null);
        return ResponseEntity.ok(ApiResponse.ok(saved, "Reoon settings saved."));
    }
}

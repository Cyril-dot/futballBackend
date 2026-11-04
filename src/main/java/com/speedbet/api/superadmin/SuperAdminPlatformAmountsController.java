package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Super-admin platform amounts (secured by the /api/super-admin/** rule,
 * same as the KYC settings controller).
 * GET  /api/super-admin/platform-amounts — current amounts.
 * POST /api/super-admin/platform-amounts — save all five amounts.
 * Enforcement reads the same row: AkwaPay min deposit, sportsbook +
 * casino min stake, max-win caps; the app reads the public endpoint for
 * the deposit minimum and the withdrawal unlock threshold.
 */
@RestController
@RequestMapping("/api/super-admin/platform-amounts")
@RequiredArgsConstructor
public class SuperAdminPlatformAmountsController {

    private final PlatformSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<PlatformAmountsDtos.AmountsResponse>> get() {
        return ResponseEntity.ok(ApiResponse.ok(
                PlatformAmountsDtos.AmountsResponse.from(settingsService.current())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PlatformAmountsDtos.AmountsResponse>> update(
            @AuthenticationPrincipal User admin,
            @RequestBody PlatformAmountsDtos.UpdateAmountsRequest req) {
        if (req == null
                || req.minDepositGhs() == null || req.minDepositGhs().signum() <= 0
                || req.minStakeGhs() == null || req.minStakeGhs().signum() <= 0
                || req.maxWinGhs() == null || req.maxWinGhs().signum() <= 0
                || req.maxWinNgn() == null || req.maxWinNgn().signum() <= 0
                || req.withdrawalUnlockGhs() == null || req.withdrawalUnlockGhs().signum() <= 0) {
            throw ApiException.badRequest("All five amounts are required and must be above 0.");
        }
        PlatformSetting saved = settingsService.save(req, admin != null ? admin.getEmail() : null);
        return ResponseEntity.ok(ApiResponse.ok(
                PlatformAmountsDtos.AmountsResponse.from(saved), "Platform amounts saved."));
    }
}

package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public read of the platform amounts (covered by the /api/public/**
 * permitAll rule). The app's deposit page and wallet page read this for
 * the min deposit and the withdrawal unlock threshold; nothing sensitive
 * lives here — amounts are platform policy, not user data.
 */
@RestController
@RequestMapping("/api/public/platform-amounts")
@RequiredArgsConstructor
public class PublicPlatformAmountsController {

    private final PlatformSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<PlatformAmountsDtos.AmountsResponse>> get() {
        return ResponseEntity.ok(ApiResponse.ok(
                PlatformAmountsDtos.AmountsResponse.from(settingsService.current())));
    }
}

package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public read of whether Payteller deposits are available (covered by the
 * /api/public/** permitAll rule). The deposit page reads this to decide
 * whether to show the Payteller option at all — nothing sensitive lives
 * here, just one boolean. Available is true only when the stored toggle
 * is ON and all three credentials (merchant ID, API key, username)
 * resolve (stored value or env fallback).
 */
@RestController
@RequestMapping("/api/public/payteller/available")
@RequiredArgsConstructor
public class PublicPaytellerAvailabilityController {

    private final PaytellerSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> get() {
        PaytellerSettingsService.Resolved r = settingsService.resolved();
        boolean available = r.enabled()
                && r.merchantId() != null && !r.merchantId().isBlank()
                && r.apiKey() != null && !r.apiKey().isBlank()
                && r.username() != null && !r.username().isBlank();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("available", available)));
    }
}

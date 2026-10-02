package com.speedbet.api.bet;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Public platform configuration the frontend needs before sign-in —
 * currently the stake limits, so the betslip and booking-code pages render
 * the live values instead of hardcoded ones. Permit-all via the existing
 * /api/public/** rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicConfigController {

    private final BettingLimits bettingLimits;

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(ApiResponse.ok(Map.of(
                        "minStake", bettingLimits.minStake(),
                        "maxStake", bettingLimits.maxStake())));
    }
}

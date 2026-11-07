package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({
        "/api/super-admin/deposits",
        "/api/superadmin/deposits"
})
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminDepositController {

    private final SuperAdminDepositService depositService;

    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> daily(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(depositService.getDaily(days)));
    }

    @GetMapping("/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> weekly(
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(depositService.getWeekly(weeks)));
    }

    /**
     * GET /api/super-admin/deposits/by-country/totals?days=365
     *
     * Platform-wide deposit roll-up per country — the whole-range totals
     * without the period dimension. This is the correct source for the
     * Dashboard "Payments by country" panel: the per-admin endpoint under
     * /api/admin/affiliate/deposits/by-country/totals answers for one
     * admin only and must not be rendered as platform totals.
     *
     * Response shape: {"GH": {"amount": ..., "count": ..., "currency": "GHS"},
     *                  "NG": {"amount": ..., "count": ..., "currency": "NGN"}}
     */
    @GetMapping("/by-country/totals")
    public ResponseEntity<ApiResponse<Map<String, SuperAdminDepositService.CountryTotals>>> totalsByCountry(
            @RequestParam(defaultValue = "365") int days) {
        return ResponseEntity.ok(ApiResponse.ok(depositService.getTotalsByCountry(days)));
    }
}
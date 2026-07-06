package com.speedbet.api.admin;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * New controller for deposit-by-country breakdowns. Mounted at
 * /api/admin/affiliate/deposits/** — separate from both
 * AdminAffiliateController and AdminCommissionController.
 */
@RestController
@RequestMapping("/api/admin/affiliate/deposits")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCountryDepositController {

    private final AdminCountryDepositService depositService;

    @GetMapping("/by-country/daily")
    public ResponseEntity<ApiResponse<List<CountryDepositPeriodDTO>>> daily(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(depositService.getDaily(admin.getId(), days)));
    }

    @GetMapping("/by-country/weekly")
    public ResponseEntity<ApiResponse<List<CountryDepositPeriodDTO>>> weekly(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(depositService.getWeekly(admin.getId(), weeks)));
    }

    @GetMapping("/by-country/monthly")
    public ResponseEntity<ApiResponse<List<CountryDepositPeriodDTO>>> monthly(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "12") int months) {
        return ResponseEntity.ok(ApiResponse.ok(depositService.getMonthly(admin.getId(), months)));
    }

    @GetMapping("/by-country/totals")
    public ResponseEntity<ApiResponse<List<CountryDepositPeriodDTO>>> totals(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "365") int days) {
        return ResponseEntity.ok(ApiResponse.ok(depositService.getTotalsByCountry(admin.getId(), days)));
    }
}
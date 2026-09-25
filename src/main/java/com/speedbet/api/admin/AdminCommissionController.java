package com.speedbet.api.admin;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

/**
 * New controller for commission period breakdowns.
 *
 * Deliberately separate from AdminAffiliateController (which stays
 * untouched) to avoid editing an existing file. Mounted at
 * /api/admin/affiliate/commission/** — a sub-path AdminAffiliateController
 * does not claim, so both controllers coexist without route conflicts.
 */
@RestController
@RequestMapping("/api/admin/affiliate/commission")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCommissionController {

    private final AdminCommissionService adminCommissionService;

    /** One UTC day: commission earned plus every referred user's deposit count and total. */
    @GetMapping("/daily-summary")
    public ResponseEntity<ApiResponse<AdminCommissionDtos.DailyCommissionDto>> getDailySummary(
            @AuthenticationPrincipal User admin,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminCommissionService.getDailyCommission(admin.getId(), date)));
    }

    /** Poll this endpoint after payout processing to show the admin a paid notification. */
    @GetMapping("/payout-notification")
    public ResponseEntity<ApiResponse<AdminCommissionDtos.CommissionPayoutNotificationDto>> getPayoutNotification(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminCommissionService.latestPaidPayout(admin.getId())));
    }

    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<List<AffiliateCommissionPeriodDTO>>> getDailyCommission(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminCommissionService.getDailyCommission(admin.getId(), days)));
    }

    @GetMapping("/weekly")
    public ResponseEntity<ApiResponse<List<AffiliateCommissionPeriodDTO>>> getWeeklyCommission(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminCommissionService.getWeeklyCommission(admin.getId(), weeks)));
    }

    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<List<AffiliateCommissionPeriodDTO>>> getMonthlyCommission(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "12") int months) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminCommissionService.getMonthlyCommission(admin.getId(), months)));
    }
}

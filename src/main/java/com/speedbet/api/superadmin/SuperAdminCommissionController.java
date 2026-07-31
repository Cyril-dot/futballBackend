package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mapped under BOTH "/api/super-admin/**" (hyphenated — what the frontend and
 * every other super-admin controller uses) and the legacy "/api/superadmin/**"
 * so any existing callers don't break.
 *
 * The missing hyphen here was the original cause of the 500s: no handler
 * matched, and the catch-all @ExceptionHandler(Exception.class) converted the
 * resulting NoResourceFoundException into a 500 instead of a 404.
 */
@RestController
@RequestMapping({
        "/api/super-admin/commission",
        "/api/superadmin/commission"
})
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminCommissionController {

    private final SuperAdminCommissionService commissionService;

    @GetMapping("/by-admin/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionPeriodDto>>> dailyByAdmin(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyCommissionByAdmin(days)));
    }

    @GetMapping("/by-admin/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionPeriodDto>>> weeklyByAdmin(
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyCommissionByAdmin(weeks)));
    }

    @GetMapping("/totals/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> dailyTotals(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyCommissionTotals(days)));
    }

    @GetMapping("/totals/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.PlatformPeriodTotalDto>>> weeklyTotals(
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyCommissionTotals(weeks)));
    }
}
package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mapped under both the hyphenated path the frontend uses and the legacy
 * unhyphenated one, so existing callers keep working.
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
    private final SuperAdminCountryReportService countryReportService;

    // ─── Per-admin (legacy shape) ───────────────────────────────────────────

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

    // ─── Platform totals (legacy shape) ─────────────────────────────────────

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

    // ─── Split by the referred user's country ───────────────────────────────

    @GetMapping("/by-country/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.CountryPeriodTotalDto>>> dailyByCountry(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyByCountry(days)));
    }

    @GetMapping("/by-country/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.CountryPeriodTotalDto>>> weeklyByCountry(
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyByCountry(weeks)));
    }

    @GetMapping("/by-admin-country/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionCountryDto>>> dailyByAdminCountry(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getDailyByAdminAndCountry(days)));
    }

    @GetMapping("/by-admin-country/weekly")
    public ResponseEntity<ApiResponse<List<SuperAdminDtos.AdminCommissionCountryDto>>> weeklyByAdminCountry(
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(commissionService.getWeeklyByAdminAndCountry(weeks)));
    }

    // ─── Combined report — one call, everything the analytics page needs ─────

    @GetMapping("/country-report/daily")
    public ResponseEntity<ApiResponse<SuperAdminDtos.CountrySplitReportDto>> dailyReport(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(countryReportService.buildDaily(days)));
    }

    @GetMapping("/country-report/weekly")
    public ResponseEntity<ApiResponse<SuperAdminDtos.CountrySplitReportDto>> weeklyReport(
            @RequestParam(defaultValue = "12") int weeks) {
        return ResponseEntity.ok(ApiResponse.ok(countryReportService.buildWeekly(weeks)));
    }
}
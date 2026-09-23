package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

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
@Slf4j
public class SuperAdminCommissionController {

    private final SuperAdminCommissionService commissionService;
    private final SuperAdminCountryReportService countryReportService;
    private final SuperAdminCommissionOperationsService operationsService;

    /**
     * Daily commission and referred-deposit detail. If adminId is supplied,
     * the response contains only that admin; otherwise it contains all admins.
     * Date is UTC and defaults to today.
     */
    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto>>> daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID adminId) {
        return ResponseEntity.ok(ApiResponse.ok(operationsService.daily(date, adminId)));
    }

    @GetMapping("/daily/{adminId}")
    public ResponseEntity<ApiResponse<List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto>>> dailyForAdmin(
            @PathVariable UUID adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(operationsService.daily(date, adminId)));
    }

    /** Mark one UTC commission day as paid, without requiring an admin request. */
    @PostMapping("/admins/{adminId}/pay")
    public ResponseEntity<ApiResponse<SuperAdminCommissionOperationsDtos.CommissionPayoutDto>> markPaid(
            @PathVariable UUID adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String operationId = UUID.randomUUID().toString();
        try {
            return ResponseEntity.ok(ApiResponse.ok(operationsService.markPaid(adminId, date), "Commission day marked as paid"));
        } catch (RuntimeException ex) {
            log.error("commission.pay.http-failed operationId={} adminId={} date={} type={} message={}", operationId, adminId, date, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Commission payout failed. operationId=" + operationId + " reason=" + safeMessage(ex)));
        }
    }

    /** Pay and clear unpaid commission entries for one UTC day across all admins. */
    @PostMapping("/clear")
    public ResponseEntity<ApiResponse<SuperAdminCommissionOperationsDtos.ClearCommissionResult>> clearAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String operationId = UUID.randomUUID().toString();
        try {
            return ResponseEntity.ok(ApiResponse.ok(operationsService.clearAll(date), "Commission day cleared"));
        } catch (RuntimeException ex) {
            log.error("commission.clear.http-failed operationId={} date={} type={} message={}", operationId, date, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Commission clear failed. operationId=" + operationId + " reason=" + safeMessage(ex)));
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

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

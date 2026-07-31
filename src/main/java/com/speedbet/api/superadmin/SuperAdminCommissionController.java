package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/superadmin/commission")
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
package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
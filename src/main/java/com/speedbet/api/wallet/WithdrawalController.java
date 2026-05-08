package com.speedbet.api.wallet;

import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/wallet/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final AuditService auditService;

    // ==================== USER ENDPOINTS ====================

    @PostMapping
    public ResponseEntity<ApiResponse<WithdrawalRequest>> submitWithdrawal(
            @Valid @RequestBody WithdrawalRequestDto req,
            @AuthenticationPrincipal User user) {
        var request = withdrawalService.submitRequest(user.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(request, "Withdrawal request submitted"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<WithdrawalRequest>>> getMyWithdrawals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) WithdrawalStatus status,
            @AuthenticationPrincipal User user) {
        Pageable pageable = PageRequest.of(page, size);
        Page<WithdrawalRequest> withdrawals;
        
        if (status != null) {
            // Filter by status - would need to add this method to service
            withdrawals = withdrawalService.getUserWithdrawals(user.getId(), pageable);
        } else {
            withdrawals = withdrawalService.getUserWithdrawals(user.getId(), pageable);
        }
        
        return ResponseEntity.ok(ApiResponse.ok(withdrawals));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WithdrawalRequest>> getWithdrawal(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        var request = withdrawalService.getByIdAndUser(id, user.getId());
        return ResponseEntity.ok(ApiResponse.ok(request));
    }

    // ==================== ADMIN ENDPOINTS ====================

    @GetMapping("/admin/all")
    public ResponseEntity<ApiResponse<Page<WithdrawalRequest>>> getAllWithdrawalsForAdmin(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) WithdrawalStatus status,
            @AuthenticationPrincipal User user) {
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Admin access required");
        }
        
        Pageable pageable = PageRequest.of(page, size);
        Page<WithdrawalRequest> withdrawals = withdrawalService.getAllWithdrawals(status, pageable);
        
        return ResponseEntity.ok(ApiResponse.ok(withdrawals));
    }

    @GetMapping("/admin/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAdminStats(
            @AuthenticationPrincipal User user) {
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Admin access required");
        }
        
        var stats = withdrawalService.getAdminStats();
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @PostMapping("/admin/{id}/approve")
    public ResponseEntity<ApiResponse<WithdrawalRequest>> approveWithdrawal(
            @PathVariable UUID id,
            @RequestBody Map<String, String> req,
            @AuthenticationPrincipal User user) {
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Admin access required");
        }
        
        var note = req.getOrDefault("note", "");
        var request = withdrawalService.approve(id, user.getId(), note);
        
        return ResponseEntity.ok(ApiResponse.ok(request, "Withdrawal approved"));
    }

    @PostMapping("/admin/{id}/reject")
    public ResponseEntity<ApiResponse<WithdrawalRequest>> rejectWithdrawal(
            @PathVariable UUID id,
            @RequestBody Map<String, String> req,
            @AuthenticationPrincipal User user) {
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Admin access required");
        }
        
        var note = req.getOrDefault("note", "");
        var request = withdrawalService.reject(id, user.getId(), note);
        
        return ResponseEntity.ok(ApiResponse.ok(request, "Withdrawal rejected"));
    }

    // ==================== SUPER ADMIN ENDPOINTS ====================

    @PostMapping("/super-admin/{id}/settle")
    public ResponseEntity<ApiResponse<WithdrawalRequest>> settleWithdrawal(
            @PathVariable UUID id,
            @RequestBody Map<String, String> req,
            @AuthenticationPrincipal User user) {
        if (user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Super admin access required");
        }
        
        var note = req.getOrDefault("note", "");
        var request = withdrawalService.settle(id, user.getId(), note);
        
        return ResponseEntity.ok(ApiResponse.ok(request, "Withdrawal settled"));
    }

    @PostMapping("/super-admin/{id}/mark-failed")
    public ResponseEntity<ApiResponse<WithdrawalRequest>> markWithdrawalFailed(
            @PathVariable UUID id,
            @RequestBody Map<String, String> req,
            @AuthenticationPrincipal User user) {
        if (user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Super admin access required");
        }
        
        var note = req.getOrDefault("note", "");
        var request = withdrawalService.markFailed(id, user.getId(), note);
        
        return ResponseEntity.ok(ApiResponse.ok(request, "Withdrawal marked as failed"));
    }

    @GetMapping("/super-admin/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSuperAdminStats(
            @AuthenticationPrincipal User user) {
        if (user.getRole() != UserRole.SUPER_ADMIN) {
            throw ApiException.forbidden("Super admin access required");
        }
        
        var stats = withdrawalService.getSuperAdminStats();
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }
}

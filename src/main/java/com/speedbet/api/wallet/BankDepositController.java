package com.speedbet.api.wallet;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for Nigerian bank transfer deposit submissions and admin review.
 *
 * User-facing:
 *   POST  /api/wallet/bank-deposits           — submit proof
 *   GET   /api/wallet/bank-deposits           — user's own history
 *
 * Admin-facing (ROLE_SUPER_ADMIN required):
 *   GET   /api/admin/bank-deposits            — all deposits
 *   GET   /api/admin/bank-deposits/pending    — pending queue
 *   GET   /api/admin/bank-deposits/{id}       — single deposit detail
 *   POST  /api/admin/bank-deposits/{id}/approve
 *   POST  /api/admin/bank-deposits/{id}/reject
 */
@RestController
@RequiredArgsConstructor
public class BankDepositController {

    private final BankDepositService service;

    // ── User endpoints ────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/bank-deposits")
    public ResponseEntity<ApiResponse<BankDepositDtos.DepositResponse>> submit(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BankDepositDtos.SubmitRequest req) {

        var deposit = service.submit(user.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                BankDepositDtos.DepositResponse.from(deposit),
                "Transfer proof submitted. An admin will verify and credit your wallet within 5–10 minutes."
        ));
    }

    @GetMapping("/api/wallet/bank-deposits")
    public ResponseEntity<ApiResponse<PageResponse<BankDepositDtos.DepositResponse>>> myDeposits(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getMyDeposits(user.getId(), PageRequest.of(page, size))
                            .map(BankDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    // ── Admin endpoints ───────────────────────────────────────────────────────

    @GetMapping("/api/admin/bank-deposits")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<BankDepositDtos.DepositResponse>>> allDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getAll(PageRequest.of(page, size))
                            .map(BankDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    @GetMapping("/api/admin/bank-deposits/pending")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<BankDepositDtos.DepositResponse>>> pendingDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getPending(PageRequest.of(page, size))
                            .map(BankDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    @GetMapping("/api/admin/bank-deposits/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BankDepositDtos.DepositResponse>> getDeposit(
            @PathVariable UUID id) {

        return ResponseEntity.ok(ApiResponse.ok(
                BankDepositDtos.DepositResponse.from(service.getById(id))
        ));
    }

    @PostMapping("/api/admin/bank-deposits/{id}/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BankDepositDtos.DepositResponse>> approve(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody BankDepositDtos.ApproveRequest req) {

        var deposit = service.approve(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                BankDepositDtos.DepositResponse.from(deposit),
                "Deposit approved. ₦" + req.getCreditedNgnAmount() + " credited to user wallet."
        ));
    }

    @PostMapping("/api/admin/bank-deposits/{id}/reject")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BankDepositDtos.DepositResponse>> reject(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody BankDepositDtos.RejectRequest req) {

        var deposit = service.reject(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                BankDepositDtos.DepositResponse.from(deposit),
                "Deposit rejected."
        ));
    }
}
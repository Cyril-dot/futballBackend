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
 * REST endpoints for mobile money deposit submissions and admin review.
 *
 * User-facing:
 *   POST  /api/wallet/simple-deposits           — submit deposit (amount, phone, account name, network, purpose)
 *   GET   /api/wallet/simple-deposits           — user's own history
 *
 * Admin-facing (ROLE_SUPER_ADMIN required):
 *   GET   /api/admin/simple-deposits            — all deposits
 *   GET   /api/admin/simple-deposits/pending    — pending queue
 *   GET   /api/admin/simple-deposits/{id}       — single deposit detail
 *   POST  /api/admin/simple-deposits/{id}/approve
 *   POST  /api/admin/simple-deposits/{id}/reject
 */
@RestController
@RequiredArgsConstructor
public class SimpleDepositController {

    private final SimpleDepositService service;

    // ── User endpoints ────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/simple-deposits")
    public ResponseEntity<ApiResponse<SimpleDepositDtos.DepositResponse>> submit(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody SimpleDepositDtos.SubmitRequest req) {

        var deposit = service.submit(user.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                SimpleDepositDtos.DepositResponse.from(deposit),
                "Deposit submitted. An admin will verify and credit your wallet within 5–10 minutes."
        ));
    }

    @GetMapping("/api/wallet/simple-deposits")
    public ResponseEntity<ApiResponse<PageResponse<SimpleDepositDtos.DepositResponse>>> myDeposits(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getMyDeposits(user.getId(), PageRequest.of(page, size))
                            .map(SimpleDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    // ── Admin endpoints ───────────────────────────────────────────────────────

    @GetMapping("/api/admin/simple-deposits")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<SimpleDepositDtos.DepositResponse>>> allDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getAll(PageRequest.of(page, size))
                            .map(SimpleDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    @GetMapping("/api/admin/simple-deposits/pending")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<SimpleDepositDtos.DepositResponse>>> pendingDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getPending(PageRequest.of(page, size))
                            .map(SimpleDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    @GetMapping("/api/admin/simple-deposits/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<SimpleDepositDtos.DepositResponse>> getDeposit(
            @PathVariable UUID id) {

        return ResponseEntity.ok(ApiResponse.ok(
                SimpleDepositDtos.DepositResponse.from(service.getById(id))
        ));
    }

    @PostMapping("/api/admin/simple-deposits/{id}/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<SimpleDepositDtos.DepositResponse>> approve(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody SimpleDepositDtos.ApproveRequest req) {

        var deposit = service.approve(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                SimpleDepositDtos.DepositResponse.from(deposit),
                "Deposit approved. " + req.getCreditedAmount() + " credited to user wallet."
        ));
    }

    @PostMapping("/api/admin/simple-deposits/{id}/reject")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<SimpleDepositDtos.DepositResponse>> reject(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody SimpleDepositDtos.RejectRequest req) {

        var deposit = service.reject(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                SimpleDepositDtos.DepositResponse.from(deposit),
                "Deposit rejected."
        ));
    }
}
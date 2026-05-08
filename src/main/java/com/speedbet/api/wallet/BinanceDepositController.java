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
 * REST endpoints for Binance / crypto deposit submissions and admin review.
 *
 * User-facing:
 *   POST   /api/wallet/binance-deposits          — submit proof
 *   GET    /api/wallet/binance-deposits           — user's own history
 *
 * Admin-facing (ROLE_SUPER_ADMIN required):
 *   GET    /api/admin/binance-deposits            — all deposits
 *   GET    /api/admin/binance-deposits/pending    — pending queue
 *   GET    /api/admin/binance-deposits/{id}       — single deposit detail
 *   POST   /api/admin/binance-deposits/{id}/approve
 *   POST   /api/admin/binance-deposits/{id}/reject
 */
@RestController
@RequiredArgsConstructor
public class BinanceDepositController {

    private final BinanceDepositService service;

    // ══════════════════════════════════════════════════════════════════════════
    // User endpoints
    // ══════════════════════════════════════════════════════════════════════════

    /** Submit a new crypto deposit proof */
    @PostMapping("/api/wallet/binance-deposits")
    public ResponseEntity<ApiResponse<BinanceDepositDtos.DepositResponse>> submit(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BinanceDepositDtos.SubmitRequest req) {

        var deposit = service.submit(user.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                BinanceDepositDtos.DepositResponse.from(deposit),
                "Deposit submitted successfully. An admin will review it shortly."
        ));
    }

    /** List the authenticated user's own deposit submissions */
    @GetMapping("/api/wallet/binance-deposits")
    public ResponseEntity<ApiResponse<PageResponse<BinanceDepositDtos.DepositResponse>>> myDeposits(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getMyDeposits(user.getId(), PageRequest.of(page, size))
                            .map(BinanceDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Super-admin endpoints
    // ══════════════════════════════════════════════════════════════════════════

    /** All deposits — paginated, newest first */
    @GetMapping("/api/admin/binance-deposits")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<BinanceDepositDtos.DepositResponse>>> allDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getAll(PageRequest.of(page, size))
                            .map(BinanceDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    /** Pending-only queue — oldest first (review in FIFO order) */
    @GetMapping("/api/admin/binance-deposits/pending")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<BinanceDepositDtos.DepositResponse>>> pendingDeposits(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var result = service.getPending(PageRequest.of(page, size))
                            .map(BinanceDepositDtos.DepositResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(new PageResponse<>(result)));
    }

    /** Single deposit detail */
    @GetMapping("/api/admin/binance-deposits/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BinanceDepositDtos.DepositResponse>> getDeposit(
            @PathVariable UUID id) {

        return ResponseEntity.ok(ApiResponse.ok(
                BinanceDepositDtos.DepositResponse.from(service.getById(id))
        ));
    }

    /**
     * Approve a deposit — credits the user's wallet with the specified GHS amount.
     * Admin may override the expectedGhsAmount if needed.
     */
    @PostMapping("/api/admin/binance-deposits/{id}/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BinanceDepositDtos.DepositResponse>> approve(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody BinanceDepositDtos.ApproveRequest req) {

        var deposit = service.approve(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                BinanceDepositDtos.DepositResponse.from(deposit),
                "Deposit approved. GH₵ " + req.getCreditedGhsAmount() + " credited to user wallet."
        ));
    }

    /** Reject a deposit — wallet is NOT credited; reason stored for user. */
    @PostMapping("/api/admin/binance-deposits/{id}/reject")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<BinanceDepositDtos.DepositResponse>> reject(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID id,
            @Valid @RequestBody BinanceDepositDtos.RejectRequest req) {

        var deposit = service.reject(id, admin.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(
                BinanceDepositDtos.DepositResponse.from(deposit),
                "Deposit rejected."
        ));
    }
}
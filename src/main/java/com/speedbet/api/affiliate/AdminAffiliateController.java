package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.referral.ReferralLink;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.referral.ReferredUserDTO;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin affiliate dashboard endpoints.
 *
 * Requires ADMIN role. Admins earn 60% commission on referred deposits
 * and can request weekly payouts (Fridays only).
 *
 * Base path: /api/admin/affiliate
 */
@RestController
@RequestMapping("/api/admin/affiliate")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAffiliateController {

    private final ReferralService referralService;
    private final AdminAffiliateService adminAffiliateService;

    // ─── Referral Links ───────────────────────────────────────────────────────

    /**
     * GET /api/admin/affiliate/links
     * Returns all referral links created by this admin.
     */
    @GetMapping("/links")
    public ResponseEntity<ApiResponse<List<ReferralLink>>> getLinks(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                referralService.getLinksForAdmin(admin.getId())));
    }

    /**
     * POST /api/admin/affiliate/links
     * Create a new referral link. Commission is fixed at 60% for admins.
     */
    @PostMapping("/links")
    public ResponseEntity<ApiResponse<ReferralLink>> createLink(
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody CreateLinkRequest req) {
        ReferralLink link = referralService.createLink(
                admin.getId(),
                req.label(),
                BigDecimal.valueOf(60),   // fixed 60% for admins
                req.expiresAt()
        );
        return ResponseEntity.ok(ApiResponse.ok(link));
    }

    // ─── Stats & Referred Users ───────────────────────────────────────────────

    /**
     * GET /api/admin/affiliate/stats
     * Aggregate stats: total referrals, lifetime stake, lifetime commission,
     * current wallet balance.
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AffiliateStatsDTO>> getStats(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.getStats(admin.getId())));
    }

    /**
     * GET /api/admin/affiliate/referred-users
     * Full list of users referred via any of this admin's links,
     * including name, email, join date, and lifetime deposit/commission stats.
     */
    @GetMapping("/referred-users")
    public ResponseEntity<ApiResponse<List<ReferredUserDTO>>> getReferredUsers(
            @AuthenticationPrincipal User admin) {
        // FIX: renamed from getReferredUserDTOsForAdmin → getReferredUserDTOs in ReferralService
        return ResponseEntity.ok(ApiResponse.ok(
                referralService.getReferredUserDTOs(admin.getId())));
    }

    // ─── Payout Window ────────────────────────────────────────────────────────

    /**
     * GET /api/admin/affiliate/payout-window
     * Check whether the payout window is currently open.
     * Returns: { "open": true } or { "open": false }
     */
    @GetMapping("/payout-window")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> getPayoutWindow() {
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("open", adminAffiliateService.isPayoutWindowOpen())));
    }

    // ─── Payout Requests ─────────────────────────────────────────────────────

    /**
     * POST /api/admin/affiliate/payout-request
     * Submit a payout request for the admin's full current wallet balance.
     * Only accepted when the payout window is open (Fridays).
     * Wallet is NOT debited until super admin marks the request as PAID.
     */
    @PostMapping("/payout-request")
    public ResponseEntity<ApiResponse<PayoutRequest>> requestPayout(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.requestPayout(admin.getId())));
    }

    /**
     * GET /api/admin/affiliate/payout-requests
     * Paginated history of all payout requests submitted by this admin.
     */
    @GetMapping("/payout-requests")
    public ResponseEntity<ApiResponse<PageResponse<PayoutRequest>>> getPayoutHistory(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(adminAffiliateService.getPayoutHistory(
                        admin.getId(), PageRequest.of(page, size)))));
    }

    // ─── Request DTOs ─────────────────────────────────────────────────────────

    public record CreateLinkRequest(
            @Size(max = 100) String label,
            Instant expiresAt
    ) {}
}
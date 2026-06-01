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

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/affiliate")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAffiliateController {

    private final ReferralService       referralService;
    private final AdminAffiliateService adminAffiliateService;

    // ─── Referral Links ───────────────────────────────────────────────────────

    @GetMapping("/links")
    public ResponseEntity<ApiResponse<List<ReferralLink>>> getLinks(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                referralService.getLinksForAdmin(admin.getId())));
    }

    /**
     * Creates a new referral link using the admin's current stored commission rate.
     * Rate is read from their active link — not hardcoded — so any rate Super Admin
     * has set is respected.
     */
    @PostMapping("/links")
    public ResponseEntity<ApiResponse<ReferralLink>> createLink(
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody CreateLinkRequest req) {

        var activeLinks = referralService.getLinksForAdmin(admin.getId())
                .stream()
                .filter(ReferralLink::isActive)
                .toList();

        var currentRate = activeLinks.isEmpty()
                ? java.math.BigDecimal.valueOf(60)
                : activeLinks.get(0).getCommissionPercent();

        return ResponseEntity.ok(ApiResponse.ok(
                referralService.createLink(admin.getId(), req.label(), currentRate, req.expiresAt())));
    }

    // ─── Stats & Referred Users ───────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AffiliateStatsDTO>> getStats(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.getStats(admin.getId())));
    }

    @GetMapping("/referred-users")
    public ResponseEntity<ApiResponse<List<ReferredUserDTO>>> getReferredUsers(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                referralService.getReferredUserDTOs(admin.getId())));
    }

    // ─── Payout Requests ─────────────────────────────────────────────────────

    /**
     * POST /api/admin/affiliate/payout-request
     * Request payout of full current commission balance. Available any day.
     * Only one pending request allowed at a time.
     */
    @PostMapping("/payout-request")
    public ResponseEntity<ApiResponse<PayoutRequest>> requestPayout(
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminAffiliateService.requestPayout(admin.getId())));
    }

    @GetMapping("/payout-requests")
    public ResponseEntity<ApiResponse<PageResponse<PayoutRequest>>> getPayoutHistory(
            @AuthenticationPrincipal User admin,
            @RequestParam(defaultValue = "0")  int page,
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
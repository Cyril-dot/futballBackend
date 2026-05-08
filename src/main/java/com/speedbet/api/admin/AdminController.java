package com.speedbet.api.admin;

import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.referral.Referral;
import com.speedbet.api.referral.ReferralLink;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.referral.ReferredUserDTO;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final ReferralService referralService;
    private final WalletService walletService;
    private final AuditService auditService;

    @GetMapping("/analytics")
    @Cacheable(value = "adminKpis", key = "#user.id")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analytics(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "7d") String range) {
        var referrals = referralService.getReferralsForAdmin(user.getId());
        var wallet = walletService.getWallet(user.getId());
        var since = switch (range) {
            case "today" -> Instant.now().minus(1, ChronoUnit.DAYS);
            case "30d"   -> Instant.now().minus(30, ChronoUnit.DAYS);
            default      -> Instant.now().minus(7, ChronoUnit.DAYS);
        };
        var commission = walletService.getTransactions(user.getId(),
                        PageRequest.of(0, 1000)).getContent().stream()
                .filter(t -> t.getKind() == TxKind.REFERRAL_COMMISSION && t.getCreatedAt().isAfter(since))
                .map(t -> t.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "totalReferrals", referrals.size(),
                "walletBalance", wallet.getBalance(),
                "commissionInPeriod", commission,
                "referrals", referrals.stream().map(r -> Map.of(
                        "userId", r.getUserId(),
                        "joinedAt", r.getJoinedAt(),
                        "lifetimeStake", r.getLifetimeStake(),
                        "lifetimeCommission", r.getLifetimeCommission()
                )).toList()
        )));
    }

    /**
     * GET /api/admin/referred-users
     * Returns referred users with full profile info (name, email, joinedAt, stake, commission)
     * for the admin dashboard table.
     */
    @GetMapping("/referred-users")
    public ResponseEntity<ApiResponse<List<ReferredUserDTO>>> referredUsers(
            @AuthenticationPrincipal User user) {
        // FIX: renamed from getReferredUserDTOsForAdmin → getReferredUserDTOs in ReferralService
        return ResponseEntity.ok(
                ApiResponse.ok(referralService.getReferredUserDTOs(user.getId())));
    }

    @PostMapping("/referral-links")
    public ResponseEntity<ApiResponse<ReferralLink>> createLink(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {
        var label = req.getOrDefault("label", "My Link").toString();
        var commission = req.get("commissionPercent") != null
                ? new BigDecimal(req.get("commissionPercent").toString()) : null;
        var link = referralService.createLink(user.getId(), label, commission, null);
        return ResponseEntity.ok(ApiResponse.ok(link, "Referral link created"));
    }

    @GetMapping("/referral-links")
    public ResponseEntity<ApiResponse<List<ReferralLink>>> getLinks(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(referralService.getLinksForAdmin(user.getId())));
    }

    @GetMapping("/audit-log")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<com.speedbet.api.audit.AuditLog>>> auditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(auditService.getAll(PageRequest.of(page, size)))));
    }
}
package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.referral.ReferralLink;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.referral.ReferredUserDTO;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Affiliate endpoints available to all authenticated users (USER role).
 * Users can generate referral links, view their stats, and request withdrawals.
 */
@RestController
@RequestMapping("/api/affiliate")
@RequiredArgsConstructor
public class UserAffiliateController {

    private final ReferralService referralService;
    private final UserAffiliateService userAffiliateService;

    // ─── Links ───────────────────────────────────────────────────────────────

    @PostMapping("/links")
    public ResponseEntity<ApiResponse<ReferralLink>> createLink(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateLinkRequest req) {
        ReferralLink link = referralService.createLink(
                user.getId(),
                req.label(),
                BigDecimal.valueOf(2),
                req.expiresAt()
        );
        return ResponseEntity.ok(ApiResponse.ok(link));
    }

    @GetMapping("/links")
    public ResponseEntity<ApiResponse<List<ReferralLink>>> getLinks(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
                referralService.getLinksForAdmin(user.getId())));
    }

    // ─── Stats ───────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<UserAffiliateStatsDTO>> getStats(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
                userAffiliateService.getStats(user.getId())));
    }

    @GetMapping("/referred-users")
    public ResponseEntity<ApiResponse<List<ReferredUserDTO>>> getReferredUsers(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(
                referralService.getReferredUserDTOs(user.getId())));
    }

    // ─── Withdrawals ─────────────────────────────────────────────────────────

    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<AffiliateWithdrawalRequest>> requestWithdrawal(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody WithdrawalRequestDTO req) {
        AffiliateWithdrawalRequest withdrawal = userAffiliateService.requestWithdrawal(
                user.getId(), req.amount(), req.accountDetails());
        return ResponseEntity.ok(ApiResponse.ok(withdrawal));
    }

    @GetMapping("/withdrawals")
    public ResponseEntity<ApiResponse<PageResponse<AffiliateWithdrawalRequest>>> getWithdrawals(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(userAffiliateService.getWithdrawals(
                        user.getId(), PageRequest.of(page, size)))));
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBalance(
            @AuthenticationPrincipal User user) {
        UserAffiliateStatsDTO stats = userAffiliateService.getStats(user.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "balance", stats.availableBalance(),
                "currency", stats.currency()
        )));
    }

    // ─── Request DTOs ─────────────────────────────────────────────────────────

    public record CreateLinkRequest(
            @Size(max = 100) String label,
            Instant expiresAt
    ) {}

    public record WithdrawalRequestDTO(
            @DecimalMin(value = "1.00", message = "Minimum withdrawal is 1.00")
            BigDecimal amount,
            @Valid AccountDetailsDTO accountDetails
    ) {}

    public record AccountDetailsDTO(
            @NotBlank String bankName,
            @NotBlank String accountNumber,
            @NotBlank String accountName,
            String mobileMoneyNumber
    ) {}
}
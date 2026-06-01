package com.speedbet.api.superadmin;

import com.speedbet.api.wallet.TxKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class SuperAdminDtos {

    // ─── Revenue / Deposit Overview ───────────────────────────────────────────

    public record RevenueOverviewDto(
            BigDecimal totalDepositsAllTime,
            BigDecimal totalDepositsThisMonth,
            BigDecimal totalDepositsToday,
            BigDecimal totalWithdrawalsAllTime,
            BigDecimal totalWithdrawalsThisMonth,
            long totalDepositCount,
            long totalWithdrawalCount,
            String currency
    ) {}

    // ─── Paginated User List ──────────────────────────────────────────────────

    public record UserSummaryDto(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String phone,
            String country,
            String role,
            boolean emailVerified,
            Instant createdAt
    ) {}

    // ─── Single User Detail ───────────────────────────────────────────────────

    public record UserDetailDto(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String phone,
            String country,
            String role,
            boolean emailVerified,
            Instant createdAt,
            WalletSummaryDto wallet
    ) {}

    public record WalletSummaryDto(
            UUID walletId,
            BigDecimal balance,
            String currency,
            long totalTransactions,
            BigDecimal totalDeposited,
            BigDecimal totalWithdrawn
    ) {}

    // ─── Single Admin Detail ──────────────────────────────────────────────────

    public record AdminDetailDto(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String phone,
            String country,
            boolean emailVerified,
            Instant createdAt,
            WalletSummaryDto wallet,
            ReferralSummaryDto referral
    ) {}

    public record ReferralSummaryDto(
            UUID linkId,
            String code,
            BigDecimal commissionPercent,
            Integer totalReferrals,   // not stored on ReferralLink — null unless computed separately
            BigDecimal totalEarnings  // not stored on ReferralLink — null unless computed separately
    ) {}

    // ─── Transaction (platform-wide) ─────────────────────────────────────────

    public record TransactionDto(
            UUID id,
            UUID walletId,
            UUID userId,
            String userEmail,
            TxKind kind,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String providerRef,
            String status,
            Map<String, Object> metadata,
            Instant createdAt
    ) {}

    // ─── User Deposit History ─────────────────────────────────────────────────

    public record UserDepositDto(
            UUID transactionId,
            UUID walletId,
            UUID userId,
            String userEmail,
            String firstName,
            String lastName,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String providerRef,
            String status,
            Instant createdAt
    ) {}

    // ─── Withdrawal History ───────────────────────────────────────────────────

    // Uses existing AffiliateWithdrawalRequest entity directly —
    // no extra DTO needed; the entity is already serialisable.
    // If you need a projection, add one here.
}
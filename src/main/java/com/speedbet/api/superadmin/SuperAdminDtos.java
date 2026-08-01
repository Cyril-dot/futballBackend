package com.speedbet.api.superadmin;

import com.speedbet.api.wallet.TxKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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

    public record AdminCommissionPeriodDto(
            String periodLabel,
            UUID adminId,
            String adminEmail,
            BigDecimal amount,
            String currency
    ) {}

    public record PlatformPeriodTotalDto(
            String periodLabel,
            BigDecimal amount,
            long count,
            String currency
    ) {}

    // ─── Country-split analytics ──────────────────────────────────────────────
    // Ghanaian users deposit in cedis (MoMo / normal funding); Nigerian users
    // deposit predominantly by bank transfer in naira. Amounts in different
    // currencies must never be summed, so every aggregate below is keyed by
    // country and carries its own currency.

    /** One period × country bucket, for deposits or commission. */
    public record CountryPeriodTotalDto(
            String periodLabel,
            String country,      // GH | NG | OTHER | UNKNOWN
            String countryName,
            BigDecimal amount,
            long count,
            String currency
    ) {}

    /** One period × admin × country bucket, for commission attribution. */
    public record AdminCommissionCountryDto(
            String periodLabel,
            UUID adminId,
            String adminEmail,
            String country,
            String countryName,
            BigDecimal amount,
            long count,
            String currency
    ) {}

    /** Roll-up per country across the whole selected range. */
    public record CountrySummaryDto(
            String country,
            String countryName,
            String currency,
            BigDecimal depositTotal,
            long depositCount,
            BigDecimal commissionTotal,
            long commissionCount,
            BigDecimal averageDeposit,
            BigDecimal effectiveCommissionRate  // commission ÷ deposits, as a percentage
    ) {}

    /** Everything the analytics page needs, in one response. */
    public record CountrySplitReportDto(
            String period,                              // "daily" | "weekly"
            String rangeLabel,
            List<CountrySummaryDto> summaries,
            List<CountryPeriodTotalDto> depositsByPeriod,
            List<CountryPeriodTotalDto> commissionByPeriod,
            List<AdminCommissionCountryDto> commissionByAdmin
    ) {}

    // ─── User status update ───────────────────────────────────────────────────

    public record UserStatusUpdateDto(
            UUID userId,
            String email,
            String firstName,
            String lastName,
            String status,
            String message,
            LocalDateTime updatedAt
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
            String status,
            boolean emailVerified,
            Instant createdAt
    ) {}

    // ─── Single User Detail ───────────────────────────────────────────────────

    public record WalletCreditDto(
            UUID userId,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String message,
            UUID transactionId
    ) {}

    public record UserDetailDto(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String phone,
            String country,
            String role,
            String status,
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
            String userCountry,   // normalised bucket, so the UI can pick ₵ vs ₦
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
            String userCountry,
            String currency,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String providerRef,
            String status,
            Instant createdAt
    ) {}

    // ─── Withdrawal History ───────────────────────────────────────────────────

    // Uses existing AffiliateWithdrawalRequest entity directly —
    // no extra DTO needed; the entity is already serialisable.
}
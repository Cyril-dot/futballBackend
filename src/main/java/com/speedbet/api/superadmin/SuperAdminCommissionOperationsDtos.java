package com.speedbet.api.superadmin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SuperAdminCommissionOperationsDtos {
    private SuperAdminCommissionOperationsDtos() {}

    public record DailyAdminCommissionDto(
            LocalDate date,
            UUID adminId,
            String adminEmail,
            String adminName,
            BigDecimal commissionPercent,
            BigDecimal commissionEarned,
            String commissionCurrency,
            BigDecimal commissionBalance,
            BigDecimal totalDeposits,
            long depositCount
    ) {}

    public record CommissionPayoutDto(
            UUID payoutId,
            UUID adminId,
            String adminEmail,
            BigDecimal amount,
            String currency,
            String reference,
            String status,
            java.time.Instant paidAt
    ) {}

    public record ClearCommissionResult(
            long adminsCleared,
            BigDecimal amountCleared,
            List<UUID> adminIds,
            java.time.Instant clearedAt
    ) {}
}

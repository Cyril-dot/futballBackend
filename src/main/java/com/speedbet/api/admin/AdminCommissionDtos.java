package com.speedbet.api.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AdminCommissionDtos {
    private AdminCommissionDtos() {}

    public record DailyCommissionDto(
            LocalDate date,
            BigDecimal commissionAmount,
            String commissionCurrency,
            BigDecimal totalDeposits,
            long totalDepositCount,
            List<UserDailyDepositDto> depositsByUser
    ) {}

    public record UserDailyDepositDto(
            UUID userId,
            String email,
            String firstName,
            String lastName,
            String country,
            BigDecimal depositTotal,
            long depositCount
    ) {}

    public record CommissionPayoutNotificationDto(
            UUID payoutId,
            BigDecimal amount,
            String currency,
            String reference,
            String status,
            Instant paidAt,
            String message
    ) {}
}

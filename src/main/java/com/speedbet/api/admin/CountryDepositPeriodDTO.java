package com.speedbet.api.admin;

import java.math.BigDecimal;

public record CountryDepositPeriodDTO(
        String periodLabel,  // "2026-07-06" (daily) | "2026-W27" (weekly, ISO) | "2026-07" (monthly)
        String country,      // ISO country code, or "UNKNOWN" if User.country was null
        BigDecimal amount,
        long depositCount
) {}
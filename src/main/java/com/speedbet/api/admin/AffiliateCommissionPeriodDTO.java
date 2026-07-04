package com.speedbet.api.admin;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One bucketed period of commission earnings.
 *
 * periodLabel format depends on which endpoint produced it:
 *   daily   -> "2026-07-03"
 *   weekly  -> "2026-W27"       (ISO week)
 *   monthly -> "2026-07"
 */
public record AffiliateCommissionPeriodDTO(
        String periodLabel,
        Instant periodStart,
        BigDecimal amount,
        String currency
) {}
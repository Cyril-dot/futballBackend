package com.speedbet.api.affiliate;

import java.math.BigDecimal;

/**
 * Aggregate affiliate stats returned to the user dashboard.
 *
 * @param totalReferrals      Total number of users referred
 * @param lifetimeStake       Sum of all deposits made by referred users
 * @param lifetimeCommission  Total commission earned (credited to wallet)
 * @param availableBalance    Current withdrawable wallet balance
 * @param currency            Wallet currency (e.g. "GHS")
 */
record AffiliateStatsDTO(
        int totalReferrals,
        BigDecimal lifetimeStake,
        BigDecimal lifetimeCommission,
        BigDecimal availableBalance,
        String currency
) {}
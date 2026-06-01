package com.speedbet.api.affiliate;

import java.math.BigDecimal;

/**
 * Aggregate affiliate stats returned by GET /api/affiliate/stats
 *
 * availableBalance — current wallet balance available for withdrawal.
 * lifetimeStake    — total stake placed by all referred users.
 * lifetimeCommission — total commission earned across all referrals.
 */
public record UserAffiliateStatsDTO(
        int        totalReferrals,
        BigDecimal lifetimeStake,
        BigDecimal lifetimeCommission,
        BigDecimal availableBalance,
        String     currency
) {}
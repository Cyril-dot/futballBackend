package com.speedbet.api.affiliate;

import java.math.BigDecimal;

/**
 * Aggregate affiliate stats returned by GET /api/affiliate/stats
 *
 * availableBalance — current wallet balance available for withdrawal.
 * commissionBalance — current balance in the separate commission ledger
 *   (affiliate_commission_balances), the only pot a commission withdrawal
 *   may draw from. It is NEVER the wallet balance.
 * lifetimeStake    — total stake placed by all referred users.
 * lifetimeCommission — total commission earned across all referrals.
 */
public record UserAffiliateStatsDTO(
        int        totalReferrals,
        BigDecimal lifetimeStake,
        BigDecimal lifetimeCommission,
        BigDecimal availableBalance,
        String     currency,
        BigDecimal commissionBalance
) {}
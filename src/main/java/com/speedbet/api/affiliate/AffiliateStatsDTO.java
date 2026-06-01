package com.speedbet.api.affiliate;

import java.math.BigDecimal;

/**
 * Aggregate affiliate stats returned by GET /api/admin/affiliate/stats
 *
 * commissionBalance  — current un-paid commission sitting in the commission ledger.
 *                      This is NOT the admin's main wallet balance.
 *                      Paid out daily by AffiliateDailyPayoutScheduler.
 *
 * totalEarnedLifetime  — all commission ever earned (paid out + current balance)
 * totalPaidOutLifetime — all commission successfully paid out to date
 */
public record AffiliateStatsDTO(
        int          totalReferrals,
        BigDecimal   lifetimeStake,
        BigDecimal   lifetimeCommission,
        BigDecimal   commissionBalance,      // current un-paid commission (NOT wallet balance)
        BigDecimal   totalEarnedLifetime,    // all-time earned
        BigDecimal   totalPaidOutLifetime,   // all-time paid out
        String       currency,
        String       lastPayoutAt            // ISO string or empty string if never paid out
) {}
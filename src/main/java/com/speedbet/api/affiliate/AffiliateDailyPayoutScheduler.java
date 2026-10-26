package com.speedbet.api.affiliate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Daily scheduler that queues affiliate commission balances for payout by
 * creating withdrawal requests for super-admin processing.
 *
 * The balance is NOT swept here. Sweeping it at queue time cleared what
 * was owed every morning while the money was still unpaid in a PENDING
 * request, so every admin looked "fully paid" before a transfer happened.
 * Now the request is only a marker: the balance (and every "owed" figure
 * derived from it) stays put until the super-admin marks the request
 * PROCESSED, which is when the deduction happens. Rejecting a queued
 * request reverses nothing, because nothing ever left.
 *
 * The user's main wallet balance is NEVER mutated here.
 * Commission balance and wallet balance are entirely separate ledgers.
 *
 * Runs at 01:00 UTC daily by default (configurable via
 * speedbet.affiliate.payout.cron).
 *
 * Minimum payout threshold configurable via
 * speedbet.affiliate.payout.min-amount (default GHS 1.00)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AffiliateDailyPayoutScheduler {

    private final AffiliateCommissionBalanceRepository commissionRepo;
    private final AffiliateWithdrawalRepository withdrawalRepo;

    @Value("${speedbet.affiliate.payout.min-amount:1.00}")
    private BigDecimal minimumPayoutAmount;

    /**
     * Main daily queue run.
     * Default cron: 01:00 UTC every day.
     * Override via speedbet.affiliate.payout.cron in application.yml.
     */
    @Scheduled(cron = "${speedbet.affiliate.payout.cron:0 0 1 * * *}", zone = "UTC")
    public void runDailyPayout() {
        log.info("AffiliateDailyPayoutScheduler: starting daily commission payout queue");

        List<AffiliateCommissionBalance> eligible =
                commissionRepo.findAllEligibleForPayout(minimumPayoutAmount);

        if (eligible.isEmpty()) {
            log.info("AffiliateDailyPayoutScheduler: no eligible balances today, done.");
            return;
        }

        log.info("AffiliateDailyPayoutScheduler: {} affiliates eligible for payout", eligible.size());

        int success = 0, skipped = 0, failed = 0;

        for (AffiliateCommissionBalance commBalance : eligible) {
            try {
                boolean processed = processSingleAffiliate(commBalance.getUserId());
                if (processed) success++; else skipped++;
            } catch (Exception ex) {
                failed++;
                log.error("AffiliateDailyPayoutScheduler: failed for userId={} — {}",
                        commBalance.getUserId(), ex.getMessage(), ex);
                // Continue processing other affiliates even if one fails
            }
        }

        log.info("AffiliateDailyPayoutScheduler: queue complete — success={} skipped={} failed={}",
                success, skipped, failed);
    }

    /**
     * Queue one affiliate's commission for payout WITHOUT touching the balance.
     *
     * The balance stays put until the super-admin marks the request
     * PROCESSED (transfer actually done) — that is when the deduction
     * happens. Skips admins who already have a PENDING request (the balance
     * is only queued once) and balances below the configured minimum (they
     * keep accruing instead of being swept away).
     *
     * @return true if a payout request was created, false if skipped
     */
    private boolean processSingleAffiliate(UUID userId) {
        var balance = commissionRepo.findByUserId(userId).orElse(null);

        if (balance == null || balance.getBalance().compareTo(minimumPayoutAmount) < 0) {
            log.debug("processSingleAffiliate: userId={} balance below minimum={}, skipping",
                    userId, minimumPayoutAmount);
            return false;
        }

        if (withdrawalRepo.existsByUserIdAndStatus(userId, AffiliateWithdrawalStatus.PENDING)) {
            log.debug("processSingleAffiliate: userId={} already has a PENDING withdrawal, skipping", userId);
            return false;
        }

        String reference = generatePayoutReference(userId);

        AffiliateWithdrawalRequest withdrawalRequest = withdrawalRepo.save(
                AffiliateWithdrawalRequest.builder()
                        .userId(userId)
                        .amount(balance.getBalance())
                        .currency(balance.getCurrency())
                        .status(AffiliateWithdrawalStatus.PENDING)
                        .balanceSwept(false)
                        .reference(reference)
                        .requestedAt(Instant.now())
                        .build());

        log.info("processSingleAffiliate: queued withdrawal request id={} userId={} amount={} {} (balance untouched until processed)",
                withdrawalRequest.getId(), userId, balance.getBalance(), balance.getCurrency());

        return true;
    }

    private String generatePayoutReference(UUID userId) {
        // Format: COMM-PAYOUT-{shortUserId}-{epochSeconds}
        // Unique enough for daily payouts; guaranteed unique by the DB unique constraint on reference.
        return "COMM-PAYOUT-"
                + userId.toString().substring(0, 8).toUpperCase()
                + "-"
                + Instant.now().getEpochSecond();
    }
}
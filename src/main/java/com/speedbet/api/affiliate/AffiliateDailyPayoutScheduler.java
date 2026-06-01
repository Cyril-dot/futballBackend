package com.speedbet.api.affiliate;

import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily scheduler that sweeps affiliate commission balances and creates
 * payout withdrawal requests for super-admin processing.
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Commission Balance  ──(sweep)──▶  AffiliateWithdrawalRequest       │
 * │                                   (status = PENDING)                │
 * │                                        │                            │
 * │                                   WalletService.recordExternalDebit │
 * │                                   (audit row only — balance intact) │
 * └─────────────────────────────────────────────────────────────────────┘
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
    private final AffiliateCommissionService commissionService;
    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final WalletService walletService;

    @Value("${speedbet.affiliate.payout.min-amount:1.00}")
    private BigDecimal minimumPayoutAmount;

    /**
     * Main daily sweep.
     * Default cron: 01:00 UTC every day.
     * Override via speedbet.affiliate.payout.cron in application.yml.
     */
    @Scheduled(cron = "${speedbet.affiliate.payout.cron:0 0 1 * * *}", zone = "UTC")
    public void runDailyPayout() {
        log.info("AffiliateDailyPayoutScheduler: starting daily commission payout sweep");

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
                boolean processed = processSingleAffiliate(commBalance.getUserId(), commBalance.getCurrency());
                if (processed) success++; else skipped++;
            } catch (Exception ex) {
                failed++;
                log.error("AffiliateDailyPayoutScheduler: failed for userId={} — {}",
                        commBalance.getUserId(), ex.getMessage(), ex);
                // Continue processing other affiliates even if one fails
            }
        }

        log.info("AffiliateDailyPayoutScheduler: sweep complete — success={} skipped={} failed={}",
                success, skipped, failed);
    }

    /**
     * Process payout for a single affiliate.
     *
     * Steps:
     *   1. Sweep (zero-out) the commission balance  →  returns swept amount
     *   2. Create an AffiliateWithdrawalRequest (PENDING, awaiting super-admin)
     *   3. Record an audit-only transaction via WalletService.recordExternalDebit
     *      so the transaction log shows the commission outflow — wallet balance unchanged.
     *
     * @return true if a payout request was created, false if balance was zero/below threshold
     */
    private boolean processSingleAffiliate(UUID userId, String currency) {
        // Step 1 — Sweep commission balance to ZERO
        BigDecimal sweptAmount = commissionService.sweepCommissionBalance(userId);

        if (sweptAmount.compareTo(minimumPayoutAmount) < 0) {
            log.debug("processSingleAffiliate: userId={} swept={} below minimum={}, skipping",
                    userId, sweptAmount, minimumPayoutAmount);
            return false;
        }

        // Step 2 — Create withdrawal request for super-admin to process (bank/MoMo transfer)
        String reference = generatePayoutReference(userId);

        AffiliateWithdrawalRequest withdrawalRequest = withdrawalRepo.save(
                AffiliateWithdrawalRequest.builder()
                        .userId(userId)
                        .amount(sweptAmount)
                        .currency(currency)
                        .status(AffiliateWithdrawalStatus.PENDING)
                        .reference(reference)
                        .requestedAt(Instant.now())
                        .build());

        log.info("processSingleAffiliate: created withdrawal request id={} userId={} amount={} {}",
                withdrawalRequest.getId(), userId, sweptAmount, currency);

        // Step 3 — Record audit transaction against the user's main wallet.
        //   NOTE: This does NOT change the wallet balance.
        //   It is purely a ledger/audit row so the transaction history shows
        //   the commission outflow and reconciles against the Paystack/MoMo dashboard.
        try {
            walletService.recordExternalDebit(
                    userId,
                    sweptAmount,
                    TxKind.AFFILIATE_COMMISSION_PAYOUT,
                    reference,
                    Map.of(
                            "type", "affiliate_commission_daily_payout",
                            "withdrawalRequestId", withdrawalRequest.getId().toString(),
                            "source", "commission_balance"   // clarifies this is NOT from wallet balance
                    ));
        } catch (Exception ex) {
            // Audit row failure is non-fatal — the withdrawal request is already created.
            // Log loudly so ops can create the audit row manually if needed.
            log.error("processSingleAffiliate: audit record FAILED for userId={} ref={} — withdrawal request still created",
                    userId, reference, ex);
        }

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
package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Manages the affiliate commission balance — a balance that is COMPLETELY
 * separate from the user's main SpeedBet wallet.
 *
 * Key invariant:
 *   commission balance  ≠  wallet balance
 *
 * Commissions are earned from referral activity and paid out daily via
 * {@link AffiliateDailyPayoutScheduler}. The main wallet is never touched
 * by this service; conversely, WalletService never touches commission balances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffiliateCommissionService {

    private final AffiliateCommissionBalanceRepository commissionRepo;

    // ─── Read ────────────────────────────────────────────────────────────────────

    /**
     * Get the commission balance for a user, creating it if it doesn't exist yet.
     */
    @Transactional
    public AffiliateCommissionBalance getOrCreate(UUID userId) {
        return commissionRepo.findByUserId(userId)
                .orElseGet(() -> commissionRepo.save(
                        AffiliateCommissionBalance.builder()
                                .userId(userId)
                                .build()));
    }

    // ─── Credit ──────────────────────────────────────────────────────────────────

    /**
     * Add commission earnings to the affiliate's commission balance.
     *
     * Called when a referred user completes a qualifying bet or action.
     * This does NOT touch the main wallet — it only updates the commission ledger.
     *
     * @param userId   affiliate user
     * @param amount   positive commission amount to credit
     * @param currency must match the existing commission balance currency
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AffiliateCommissionBalance creditCommission(UUID userId, BigDecimal amount, String currency) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("Commission credit amount must be positive");

        var balance = commissionRepo.findByUserIdForUpdate(userId)
                .orElseGet(() -> commissionRepo.save(
                        AffiliateCommissionBalance.builder()
                                .userId(userId)
                                .currency(currency)
                                .build()));

        if (!balance.getCurrency().equalsIgnoreCase(currency))
            throw ApiException.badRequest("Commission currency mismatch: expected "
                    + balance.getCurrency() + ", got " + currency);

        balance.setBalance(balance.getBalance().add(amount));
        balance.setTotalEarnedLifetime(balance.getTotalEarnedLifetime().add(amount));

        log.debug("creditCommission: userId={} +{} {} → newBalance={}",
                userId, amount, currency, balance.getBalance());

        return commissionRepo.save(balance);
    }

    // ─── Debit (used by payout scheduler) ────────────────────────────────────────

    /**
     * Debit the full current commission balance in preparation for payout.
     *
     * This sweeps the balance to ZERO and returns the amount that was swept.
     * The caller (scheduler) is responsible for creating the
     * {@link AffiliateWithdrawalRequest} row and the wallet audit record.
     *
     * @return the amount swept (may be ZERO if nothing was pending)
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public BigDecimal sweepCommissionBalance(UUID userId) {
        var balance = commissionRepo.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("Commission balance not found for user: " + userId));

        BigDecimal swept = balance.getBalance();
        if (swept.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("sweepCommissionBalance: userId={} — nothing to sweep", userId);
            return BigDecimal.ZERO;
        }

        balance.setBalance(BigDecimal.ZERO);
        balance.setTotalPaidOutLifetime(balance.getTotalPaidOutLifetime().add(swept));
        balance.setLastPayoutAt(Instant.now());
        commissionRepo.save(balance);

        log.info("sweepCommissionBalance: userId={} swept={} {}", userId, swept, balance.getCurrency());
        return swept;
    }

    // ─── Reversal (used when super-admin rejects a payout) ───────────────────────

    /**
     * Reverse a commission payout back into the commission balance.
     *
     * Called by {@link WithdrawalAdminController#reject} when super-admin
     * rejects an affiliate withdrawal request.
     *
     * IMPORTANT: this reverses into the COMMISSION balance, NOT the main wallet.
     * The user's regular spending balance remains unchanged.
     *
     * @param userId affiliate user
     * @param amount positive amount to re-credit back into commission balance
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AffiliateCommissionBalance reverseCommissionPayout(UUID userId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("Reversal amount must be positive");

        var balance = commissionRepo.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("Commission balance not found for user: " + userId));

        // Re-add to commission balance
        balance.setBalance(balance.getBalance().add(amount));
        // Subtract from lifetime paid-out so reporting stays accurate
        balance.setTotalPaidOutLifetime(
                balance.getTotalPaidOutLifetime().subtract(amount).max(BigDecimal.ZERO));

        log.info("reverseCommissionPayout: userId={} reversed={} {} → newCommissionBalance={}",
                userId, amount, balance.getCurrency(), balance.getBalance());

        return commissionRepo.save(balance);
    }
}
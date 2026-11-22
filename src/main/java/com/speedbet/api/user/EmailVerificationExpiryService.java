package com.speedbet.api.user;

import com.speedbet.api.affiliate.AffiliateCommissionBalanceRepository;
import com.speedbet.api.affiliate.AffiliateWithdrawalRepository;
import com.speedbet.api.bet.BetRepository;
import com.speedbet.api.casinoGames.spindaBottle.SpinBottleRoundRepository;
import com.speedbet.api.chat.AdminUpgradeChatRepository;
import com.speedbet.api.kyc.KycPaymentRepository;
import com.speedbet.api.kyc.KycSubmissionRepository;
import com.speedbet.api.payment.akwapay.AkwaPayPendingIntentRepository;
import com.speedbet.api.payment.flutterWave.FlutterwaveV4PendingChargeRepository;
import com.speedbet.api.referral.ReferralRepository;
import com.speedbet.api.wallet.BankDepositRepository;
import com.speedbet.api.wallet.BinanceDepositRepository;
import com.speedbet.api.wallet.SimpleDepositRepository;
import com.speedbet.api.wallet.TransactionRepository;
import com.speedbet.api.wallet.WalletRepository;
import com.speedbet.api.wallet.WithdrawalRequestRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * MSport only: the per-account half of the 24h email-verification
 * expiry (the scheduler in {@link EmailVerificationExpiryScheduler}
 * owns the hourly loop; this bean owns one account at a time so each
 * account gets its own transaction — one account failing must never
 * roll back the others).
 *
 * FUNDED accounts (any money history — see {@link #isFunded}) are
 * NEVER deleted: they are blocked only, their money records stay
 * intact, and the owner reviews them. Only a zero-balance account
 * with no money history at all is permanently deleted, together with
 * its directly-owned non-financial rows (wallet + ledger rows,
 * referral row, KYC rows, abandoned in-flight intents, game rounds,
 * upgrade chats). Verifying at any point before deletion restores
 * the account (see AuthController verify-email / verify-email-now).
 *
 * Staff accounts never reach this service — the scheduler only
 * queries role USER, and the 24h deadline is never set for staff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationExpiryService {

    public enum Outcome { DELETED, SKIPPED }

    private final UserRepository userRepo;
    private final WalletRepository walletRepo;
    private final TransactionRepository txRepo;
    private final BetRepository betRepo;
    private final WithdrawalRequestRepository withdrawalRepo;
    private final SimpleDepositRepository simpleDepositRepo;
    private final BankDepositRepository bankDepositRepo;
    private final BinanceDepositRepository binanceDepositRepo;
    private final KycSubmissionRepository kycSubmissionRepo;
    private final KycPaymentRepository kycPaymentRepo;
    private final ReferralRepository referralRepo;
    private final AffiliateCommissionBalanceRepository commissionBalanceRepo;
    private final AffiliateWithdrawalRepository affiliateWithdrawalRepo;
    private final AkwaPayPendingIntentRepository akwaPayIntentRepo;
    private final FlutterwaveV4PendingChargeRepository flutterwaveChargeRepo;
    private final SpinBottleRoundRepository spinBottleRoundRepo;
    private final AdminUpgradeChatRepository adminUpgradeChatRepo;

    /**
     * True when this account has any money history at all — a positive
     * wallet balance, any wallet transaction, any bet, any withdrawal
     * request, any manual deposit (even pending — money in flight), any
     * KYC fee payment, any commission payout request, or a positive
     * commission balance. Such accounts are blocked only, never deleted.
     */
    public boolean isFunded(UUID userId) {
        var wallet = walletRepo.findByUserId(userId);
        if (wallet.isPresent()) {
            if (wallet.get().getBalance() != null
                    && wallet.get().getBalance().compareTo(BigDecimal.ZERO) > 0) return true;
            if (txRepo.countByWalletId(wallet.get().getId()) > 0) return true;
        }
        if (betRepo.existsByUserId(userId)) return true;
        if (withdrawalRepo.existsByUserId(userId)) return true;
        if (simpleDepositRepo.existsByUserId(userId)) return true;
        if (bankDepositRepo.existsByUserId(userId)) return true;
        if (binanceDepositRepo.existsByUserId(userId)) return true;
        if (kycPaymentRepo.existsByUserId(userId)) return true;
        if (affiliateWithdrawalRepo.existsByUserId(userId)) return true;
        var commission = commissionBalanceRepo.findByUserId(userId);
        if (commission.isPresent() && commission.get().getBalance() != null
                && commission.get().getBalance().compareTo(BigDecimal.ZERO) > 0) return true;
        return false;
    }

    /** Mark an account blocked (idempotent). Used for funded accounts and for delete failures. */
    @Transactional
    public void blockAccount(UUID userId) {
        userRepo.findById(userId).ifPresent(user -> {
            if (!user.isAccountBlocked()) {
                user.setAccountBlocked(true);
                userRepo.save(user);
            }
        });
    }

    /**
     * Permanently delete one account and its directly-owned rows.
     * Re-loads the user inside the transaction and re-checks the
     * guards, so a user who verified between the scheduler's query
     * and this call is skipped, not deleted. Throws on any failure —
     * the caller leaves the account blocked and retries next run.
     */
    @Transactional
    public Outcome deleteAccount(UUID userId) {
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return Outcome.SKIPPED;
        if (user.isEmailVerified() || user.isAccountBlocked()) return Outcome.SKIPPED;

        // Children first: transactions reference the wallet, and
        // withdrawal requests hold the only real FK to the users table.
        // Every delete below is a no-op when the user owns no such row.
        var wallet = walletRepo.findByUserId(userId);
        wallet.ifPresent(w -> txRepo.deleteByWalletId(w.getId()));
        walletRepo.deleteByUserId(userId);
        betRepo.deleteByUserId(userId);
        withdrawalRepo.deleteByUserId(userId);
        simpleDepositRepo.deleteByUserId(userId);
        bankDepositRepo.deleteByUserId(userId);
        binanceDepositRepo.deleteByUserId(userId);
        kycSubmissionRepo.deleteByUserId(userId);
        kycPaymentRepo.deleteByUserId(userId);
        referralRepo.deleteByUserId(userId);
        commissionBalanceRepo.deleteByUserId(userId);
        affiliateWithdrawalRepo.deleteByUserId(userId);
        akwaPayIntentRepo.deleteByUserId(userId);
        flutterwaveChargeRepo.deleteByUserId(userId);
        spinBottleRoundRepo.deleteByUserId(userId);
        adminUpgradeChatRepo.deleteByUserId(userId);

        userRepo.delete(user);
        return Outcome.DELETED;
    }
}

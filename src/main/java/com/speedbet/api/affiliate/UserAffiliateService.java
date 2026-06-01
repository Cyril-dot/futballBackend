package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.Referral;
import com.speedbet.api.referral.ReferralRepository;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAffiliateService {

    private static final BigDecimal MIN_WITHDRAWAL = BigDecimal.valueOf(10);

    private final ReferralRepository referralRepo;
    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final WalletService walletService;

    /**
     * Aggregate affiliate stats for a user.
     * Sums across all referrals tied to any link owned by this user.
     */
    public UserAffiliateStatsDTO getStats(UUID userId) {
        log.info("getStats: userId={}", userId);

        List<Referral> referrals = referralRepo.findByAdminId(userId);

        BigDecimal totalStake = referrals.stream()
                .map(Referral::getLifetimeStake)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCommission = referrals.stream()
                .map(Referral::getLifetimeCommission)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var wallet = walletService.getWallet(userId);

        return new UserAffiliateStatsDTO(
                referrals.size(),
                totalStake,
                totalCommission,
                wallet.getBalance(),
                wallet.getCurrency()
        );
    }

    /**
     * Request a withdrawal of commission earnings.
     *
     * Rules:
     * - Minimum withdrawal: GHS 10
     * - Amount cannot exceed current wallet balance
     * - Debits wallet immediately; funds held pending transfer
     * - Withdrawal is processed within 1–3 business days
     */
    @Transactional
    public AffiliateWithdrawalRequest requestWithdrawal(
            UUID userId,
            BigDecimal amount,
            UserAffiliateController.AccountDetailsDTO accountDetails) {

        log.info("requestWithdrawal: userId={} amount={}", userId, amount);

        if (amount.compareTo(MIN_WITHDRAWAL) < 0) {
            throw ApiException.badRequest(
                    "Minimum withdrawal amount is " + MIN_WITHDRAWAL + " GHS");
        }

        var wallet = walletService.getWallet(userId);

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw ApiException.badRequest(
                    "Insufficient balance. Available: " + wallet.getBalance()
                            + " " + wallet.getCurrency());
        }

        // Debit wallet immediately — funds are held pending transfer
        String ref = "AFFILIATE-WITHDRAW-" + userId + "-" + System.currentTimeMillis();
        walletService.debit(userId, amount, TxKind.WITHDRAW, ref,
                Map.of("reason", "affiliate_withdrawal"));

        var withdrawal = withdrawalRepo.save(
                AffiliateWithdrawalRequest.builder()
                        .userId(userId)
                        .amount(amount)
                        .currency(wallet.getCurrency())
                        .status(AffiliateWithdrawalStatus.PENDING)
                        .bankName(accountDetails.bankName())
                        .accountNumber(accountDetails.accountNumber())
                        .accountName(accountDetails.accountName())
                        .mobileMoneyNumber(accountDetails.mobileMoneyNumber())
                        .reference(ref)
                        .requestedAt(Instant.now())
                        .build());

        log.info("requestWithdrawal: created id={} userId={} amount={}",
                withdrawal.getId(), userId, amount);

        return withdrawal;
    }

    /**
     * Paginated withdrawal history for a user.
     */
    public Page<AffiliateWithdrawalRequest> getWithdrawals(UUID userId, Pageable pageable) {
        log.info("getWithdrawals: userId={}", userId);
        return withdrawalRepo.findByUserIdOrderByRequestedAtDesc(userId, pageable);
    }
}
package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.Referral;
import com.speedbet.api.referral.ReferralRepository;
import com.speedbet.api.wallet.DepositRow;
import com.speedbet.api.wallet.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAffiliateService {

    // Hardcoded NGN -> GHS conversion rate: 1 GHS = 135 NGN (i.e. cedis = naira / 135).
    // NOTE: this is a fixed approximation and WILL drift from the real market
    // rate over time — update this constant manually if/when the business
    // wants to re-peg it.
    private static final BigDecimal NGN_PER_GHS = BigDecimal.valueOf(135);
    private static final String NG_COUNTRY_CODE = "NG";

    private final ReferralRepository          referralRepo;
    private final PayoutRequestRepository     payoutRequestRepo;
    private final AffiliateCommissionService  commissionService;
    private final TransactionRepository       transactionRepo;

    // ─── Stats ────────────────────────────────────────────────────────────────

    public AffiliateStatsDTO getStats(UUID adminId) {
        log.info("getStats: adminId={}", adminId);

        List<Referral> referrals = referralRepo.findByAdminId(adminId);

        BigDecimal lifetimeStake = referrals.stream()
                .map(Referral::getLifetimeStake)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal lifetimeCommission = referrals.stream()
                .map(Referral::getLifetimeCommission)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var commBalance = commissionService.getOrCreate(adminId);

        return new AffiliateStatsDTO(
                referrals.size(),
                lifetimeStake,
                lifetimeCommission,
                commBalance.getBalance(),
                commBalance.getTotalEarnedLifetime(),
                commBalance.getTotalPaidOutLifetime(),
                commBalance.getCurrency(),
                commBalance.getLastPayoutAt() != null
                        ? commBalance.getLastPayoutAt().toString()
                        : ""
        );
    }

    // ─── Payout Requests ─────────────────────────────────────────────────────

    /**
     * Convert a Nigerian-country deposit total (NGN) into Cedis (GHS).
     * 1 GHS = 135 NGN (hardcoded), i.e. cedis = naira / 135.
     */
    private BigDecimal ngnDepositsToGhs(UUID adminId, Instant since) {
        BigDecimal totalNaira = transactionRepo.findDepositsByAdminSince(adminId, since).stream()
                .filter(row -> NG_COUNTRY_CODE.equalsIgnoreCase(row.country()))
                .map(DepositRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return totalNaira.divide(NGN_PER_GHS, 2, RoundingMode.HALF_UP);
    }

    /**
     * Submit a payout request for the admin's current commission balance,
     * after crediting into it any Nigerian-deposit-derived amount (converted
     * NGN → GHS at the hardcoded rate) accrued since their last payout.
     *
     * Admin can request any day — no day-of-week restriction.
     *
     * Rules:
     *   - No existing REQUESTED or APPROVED payout already pending
     *   - Balance (existing commission + freshly-credited NG deposits) must be > 0
     *
     * NG deposits are converted and credited exactly once per payout cycle,
     * right here. markPaid() does not recompute them — it just sweeps the
     * balance this call already topped up.
     */
    @Transactional
    public PayoutRequest requestPayout(UUID adminId) {
        log.info("requestPayout: adminId={}", adminId);

        boolean hasPending = payoutRequestRepo.existsByAdminIdAndStatusIn(
                adminId, List.of(PayoutStatus.REQUESTED, PayoutStatus.APPROVED));

        if (hasPending)
            throw ApiException.badRequest(
                    "You already have a pending payout request. "
                            + "Please wait for it to be resolved before submitting a new one.");

        var commBalance = commissionService.getOrCreate(adminId);
        Instant since = commBalance.getLastPayoutAt() != null ? commBalance.getLastPayoutAt() : Instant.EPOCH;

        BigDecimal ngDepositsInGhs = ngnDepositsToGhs(adminId, since);

        BigDecimal currentBalance = ngDepositsInGhs.compareTo(BigDecimal.ZERO) > 0
                ? commissionService.creditCommission(adminId, ngDepositsInGhs, commBalance.getCurrency()).getBalance()
                : commBalance.getBalance();

        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("No commission balance available to request a payout.");

        var request = payoutRequestRepo.save(
                PayoutRequest.builder()
                        .adminId(adminId)
                        .amount(currentBalance)
                        .status(PayoutStatus.REQUESTED)
                        .periodEnd(Instant.now())
                        .build());

        log.info("requestPayout: created id={} adminId={} ngDepositsInGhs={} creditedBalance={}",
                request.getId(), adminId, ngDepositsInGhs, currentBalance);

        return request;
    }

    public Page<PayoutRequest> getPayoutHistory(UUID adminId, Pageable pageable) {
        return payoutRequestRepo.findByAdminIdOrderByCreatedAtDesc(adminId, pageable);
    }

    // ─── Super Admin Actions ──────────────────────────────────────────────────

    @Transactional
    public PayoutRequest approve(UUID payoutId) {
        log.info("approve: payoutId={}", payoutId);
        var request = getById(payoutId);

        if (request.getStatus() != PayoutStatus.REQUESTED)
            throw ApiException.badRequest("Payout is not in REQUESTED status.");

        request.setStatus(PayoutStatus.APPROVED);
        log.info("approve: payoutId={} adminId={} approved", payoutId, request.getAdminId());
        return payoutRequestRepo.save(request);
    }

    /**
     * Reject a payout. Commission balance is untouched —
     * the admin keeps their commission and can request again immediately.
     */
    @Transactional
    public PayoutRequest reject(UUID payoutId, String reason) {
        log.info("reject: payoutId={}", payoutId);
        var request = getById(payoutId);

        if (request.getStatus() != PayoutStatus.REQUESTED)
            throw ApiException.badRequest("Payout is not in REQUESTED status.");

        request.setStatus(PayoutStatus.REJECTED);
        request.setRejectReason(reason != null ? reason : "No reason provided");
        log.info("reject: payoutId={} adminId={} rejected", payoutId, request.getAdminId());
        return payoutRequestRepo.save(request);
    }

    /**
     * Mark an APPROVED payout as PAID: sweeps the commission balance.
     *
     * NG-deposit conversion already happened at the hardcoded rate back in
     * requestPayout and was credited into the balance, so there is nothing
     * left to recompute here — just sweep. (Recomputing with the same
     * `since` cutoff here would double-count, since lastPayoutAt only
     * advances when the sweep actually happens.)
     *
     * Main wallet is never touched.
     */
    @Transactional
    public PayoutRequest markPaid(UUID payoutId) {
        log.info("markPaid: payoutId={}", payoutId);
        var request = getById(payoutId);

        if (request.getStatus() != PayoutStatus.APPROVED)
            throw ApiException.badRequest("Payout must be APPROVED before marking as PAID.");

        BigDecimal sweptCommission = commissionService.sweepCommissionBalance(request.getAdminId());

        if (sweptCommission.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("Nothing to pay out — commission balance is zero.");

        request.setStatus(PayoutStatus.PAID);
        request.setPaidAt(Instant.now());
        request.setAmount(sweptCommission);

        log.info("markPaid: payoutId={} adminId={} sweptCommission={} marked PAID",
                payoutId, request.getAdminId(), sweptCommission);

        return payoutRequestRepo.save(request);
    }

    public List<PayoutRequest> getPayoutRequestsByStatus(PayoutStatus status) {
        return payoutRequestRepo.findByStatus(status);
    }

    private PayoutRequest getById(UUID id) {
        return payoutRequestRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Payout request not found"));
    }
}
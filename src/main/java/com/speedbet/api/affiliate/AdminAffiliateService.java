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

    // Hardcoded NGN -> GHS conversion rate: 1 GHS = 120 NGN (i.e. cedis = naira / 120).
    // NOTE: this is a fixed approximation of the market rate at time of writing
    // (live mid-market rate was ~120.5 NGN/GHS as of Jul 2026). Since this is
    // hardcoded, it WILL drift from the real rate over time and needs to be
    // manually updated here if/when the business wants to re-peg it.
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
     * 1 GHS = 120 NGN (hardcoded), i.e. cedis = naira / 120.
     */
    private BigDecimal ngnDepositsToGhs(UUID adminId, Instant since) {
        BigDecimal totalNaira = transactionRepo.findDepositsByAdminSince(adminId, since).stream()
                .filter(row -> NG_COUNTRY_CODE.equalsIgnoreCase(row.country()))
                .map(DepositRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return totalNaira.divide(NGN_PER_GHS, 2, RoundingMode.HALF_UP);
    }

    /**
     * Submit a payout request for the admin's full current commission balance,
     * PLUS any Nigerian-deposit-derived amount (converted NGN → GHS at the
     * hardcoded rate) accrued since their last payout.
     *
     * Admin can request any day — no day-of-week restriction.
     *
     * Rules:
     *   - No existing REQUESTED or APPROVED payout already pending
     *   - Combined amount (commission balance + converted NG deposits) must be > 0
     *
     * Commission balance is NOT swept here — it is swept when super admin
     * calls markPaid(), so the admin keeps earning between request and payment.
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
        BigDecimal payoutAmount = commBalance.getBalance().add(ngDepositsInGhs);

        if (payoutAmount.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("No commission balance available to request a payout.");

        var request = payoutRequestRepo.save(
                PayoutRequest.builder()
                        .adminId(adminId)
                        .amount(payoutAmount)
                        .status(PayoutStatus.REQUESTED)
                        .periodEnd(Instant.now())
                        .build());

        log.info("requestPayout: created id={} adminId={} commissionBalance={} ngDepositsInGhs={} totalAmount={}",
                request.getId(), adminId, commBalance.getBalance(), ngDepositsInGhs, payoutAmount);

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
     * Mark an APPROVED payout as PAID: sweeps the commission balance AND
     * recomputes the NG-deposit conversion (NGN → GHS at the hardcoded rate)
     * for the same "since" window used at request time, so the final paid
     * amount = swept commission + converted NG deposits (may be slightly more
     * than the snapshotted request amount if more commission or NG deposits
     * accrued between request and payment — admin gets it all).
     *
     * Main wallet is never touched.
     */
    @Transactional
    public PayoutRequest markPaid(UUID payoutId) {
        log.info("markPaid: payoutId={}", payoutId);
        var request = getById(payoutId);

        if (request.getStatus() != PayoutStatus.APPROVED)
            throw ApiException.badRequest("Payout must be APPROVED before marking as PAID.");

        var commBalanceBefore = commissionService.getOrCreate(request.getAdminId());
        Instant since = commBalanceBefore.getLastPayoutAt() != null
                ? commBalanceBefore.getLastPayoutAt() : Instant.EPOCH;

        BigDecimal ngDepositsInGhs = ngnDepositsToGhs(request.getAdminId(), since);
        BigDecimal sweptCommission = commissionService.sweepCommissionBalance(request.getAdminId());
        BigDecimal totalPaid = sweptCommission.add(ngDepositsInGhs);

        if (totalPaid.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("Nothing to pay out — commission balance and NG deposits are both zero.");

        request.setStatus(PayoutStatus.PAID);
        request.setPaidAt(Instant.now());
        request.setAmount(totalPaid);

        log.info("markPaid: payoutId={} adminId={} sweptCommission={} ngDepositsInGhs={} totalPaid={} marked PAID",
                payoutId, request.getAdminId(), sweptCommission, ngDepositsInGhs, totalPaid);

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
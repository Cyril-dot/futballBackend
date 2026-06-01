package com.speedbet.api.affiliate;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.Referral;
import com.speedbet.api.referral.ReferralRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAffiliateService {

    private final ReferralRepository          referralRepo;
    private final PayoutRequestRepository     payoutRequestRepo;
    private final AffiliateCommissionService  commissionService;

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
     * Submit a payout request for the admin's full current commission balance.
     *
     * Admin can request any day — no day-of-week restriction.
     *
     * Rules:
     *   - No existing REQUESTED or APPROVED payout already pending
     *   - Commission balance must be > 0
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

        if (commBalance.getBalance().compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("No commission balance available to request a payout.");

        var request = payoutRequestRepo.save(
                PayoutRequest.builder()
                        .adminId(adminId)
                        .amount(commBalance.getBalance())
                        .status(PayoutStatus.REQUESTED)
                        .periodEnd(Instant.now())
                        .build());

        log.info("requestPayout: created id={} adminId={} commissionAmount={}",
                request.getId(), adminId, request.getAmount());

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
     * Mark an APPROVED payout as PAID and sweep the commission balance.
     *
     * Sweeps the FULL current commission balance at time of payment
     * (may be more than the snapshotted amount if more commission
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

        BigDecimal actualSwept = commissionService.sweepCommissionBalance(request.getAdminId());

        if (actualSwept.compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.badRequest("Commission balance is already zero — nothing to pay out.");

        request.setStatus(PayoutStatus.PAID);
        request.setPaidAt(Instant.now());
        request.setAmount(actualSwept);

        log.info("markPaid: payoutId={} adminId={} commissionSwept={} marked PAID",
                payoutId, request.getAdminId(), actualSwept);

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
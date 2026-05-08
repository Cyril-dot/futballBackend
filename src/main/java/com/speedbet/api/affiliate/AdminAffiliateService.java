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
import org.springframework.scheduling.annotation.Scheduled;
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
public class AdminAffiliateService {

    /**
     * Whether the payout window is currently open.
     *
     * NOTE: This is currently in-memory. On multi-instance deployments this
     * must be moved to a DB flag or Redis key so all instances stay in sync.
     * See TODOs in the system design doc.
     */
    private volatile boolean payoutWindowOpen = false;

    private final ReferralRepository referralRepo;
    private final PayoutRequestRepository payoutRequestRepo;
    private final WalletService walletService;

    // ─── Payout Window ────────────────────────────────────────────────────────

    /** Opens the payout window every Friday at midnight. */
    @Scheduled(cron = "0 0 0 * * FRI")
    public void openPayoutWindow() {
        payoutWindowOpen = true;
        log.info("openPayoutWindow: payout window is now OPEN");
    }

    /** Closes the payout window every Saturday at midnight. */
    @Scheduled(cron = "0 0 0 * * SAT")
    public void closePayoutWindow() {
        payoutWindowOpen = false;
        log.info("closePayoutWindow: payout window is now CLOSED");
    }

    public boolean isPayoutWindowOpen() {
        return payoutWindowOpen;
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    /**
     * Aggregate affiliate stats for an admin.
     * Sums lifetime stake and commission across all referrals owned by this admin.
     */
    public AffiliateStatsDTO getStats(UUID adminId) {
        log.info("getStats: adminId={}", adminId);

        List<Referral> referrals = referralRepo.findByAdminId(adminId);

        BigDecimal totalStake = referrals.stream()
                .map(Referral::getLifetimeStake)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCommission = referrals.stream()
                .map(Referral::getLifetimeCommission)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var wallet = walletService.getWallet(adminId);

        return new AffiliateStatsDTO(
                referrals.size(),
                totalStake,
                totalCommission,
                wallet.getBalance(),
                wallet.getCurrency()
        );
    }

    // ─── Payout Requests ─────────────────────────────────────────────────────

    /**
     * Submit a payout request for the admin's full current wallet balance.
     *
     * Rules:
     * - Payout window must be open (Fridays only)
     * - Admin must not have an existing REQUESTED or APPROVED payout pending
     * - Balance must be greater than zero
     * - Wallet is NOT debited here — debit happens only when super admin marks PAID
     */
    @Transactional
    public PayoutRequest requestPayout(UUID adminId) {
        log.info("requestPayout: adminId={}", adminId);

        if (!payoutWindowOpen) {
            throw ApiException.badRequest(
                    "Payout requests are only accepted on Fridays.");
        }

        boolean hasPending = payoutRequestRepo.existsByAdminIdAndStatusIn(
                adminId, List.of(PayoutStatus.REQUESTED, PayoutStatus.APPROVED));

        if (hasPending) {
            throw ApiException.badRequest(
                    "You already have a pending payout request. "
                    + "Please wait for it to be resolved before submitting a new one.");
        }

        var wallet = walletService.getWallet(adminId);

        if (wallet.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("No balance available to request a payout.");
        }

        var request = payoutRequestRepo.save(
                PayoutRequest.builder()
                        .adminId(adminId)
                        .amount(wallet.getBalance())   // snapshot at request time
                        .status(PayoutStatus.REQUESTED)
                        .periodEnd(Instant.now())
                        .build());

        log.info("requestPayout: created id={} adminId={} amount={}",
                request.getId(), adminId, request.getAmount());

        return request;
    }

    /**
     * Paginated payout request history for an admin.
     */
    public Page<PayoutRequest> getPayoutHistory(UUID adminId, Pageable pageable) {
        log.info("getPayoutHistory: adminId={}", adminId);
        return payoutRequestRepo.findByAdminIdOrderByCreatedAtDesc(adminId, pageable);
    }

    // ─── Super Admin Actions ──────────────────────────────────────────────────

    /**
     * Approve a REQUESTED payout. Does not debit the wallet yet.
     */
    @Transactional
    public PayoutRequest approve(UUID payoutId) {
        log.info("approve: payoutId={}", payoutId);
        var request = getById(payoutId);

        if (request.getStatus() != PayoutStatus.REQUESTED) {
            throw ApiException.badRequest("Payout is not in REQUESTED status.");
        }

        request.setStatus(PayoutStatus.APPROVED);
        var saved = payoutRequestRepo.save(request);

        log.info("approve: payoutId={} adminId={} approved", payoutId, request.getAdminId());
        return saved;
    }

    /**
     * Reject a REQUESTED payout. Wallet is not touched — no debit was made.
     */
    @Transactional
    public PayoutRequest reject(UUID payoutId, String reason) {
        log.info("reject: payoutId={}", payoutId);
        var request = getById(payoutId);

        if (request.getStatus() != PayoutStatus.REQUESTED) {
            throw ApiException.badRequest("Payout is not in REQUESTED status.");
        }

        request.setStatus(PayoutStatus.REJECTED);
        request.setRejectReason(reason != null ? reason : "No reason provided");
        var saved = payoutRequestRepo.save(request);

        log.info("reject: payoutId={} adminId={} rejected", payoutId, request.getAdminId());
        return saved;
    }

    /**
     * Mark an APPROVED payout as PAID and debit the admin's wallet.
     *
     * This is the only point where the wallet is debited — the amount
     * is the snapshot taken when the request was originally submitted.
     */
    @Transactional
    public PayoutRequest markPaid(UUID payoutId) {
        log.info("markPaid: payoutId={}", payoutId);
        var request = getById(payoutId);

        if (request.getStatus() != PayoutStatus.APPROVED) {
            throw ApiException.badRequest("Payout must be APPROVED before marking as PAID.");
        }

        String ref = "PAYOUT-" + request.getAdminId() + "-" + payoutId;
        walletService.debit(
                request.getAdminId(),
                request.getAmount(),
                TxKind.PAYOUT,
                ref,
                Map.of("reason", "affiliate_payout", "payoutRequestId", payoutId.toString()));

        request.setStatus(PayoutStatus.PAID);
        request.setPaidAt(Instant.now());
        var saved = payoutRequestRepo.save(request);

        log.info("markPaid: payoutId={} adminId={} amount={} debited and marked PAID",
                payoutId, request.getAdminId(), request.getAmount());

        return saved;
    }

    // Add to AdminAffiliateService
    public List<PayoutRequest> getPayoutRequestsByStatus(PayoutStatus status) {
        return payoutRequestRepo.findByStatus(status);
    }

    private PayoutRequest getById(UUID id) {
        return payoutRequestRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Payout request not found"));
    }
}
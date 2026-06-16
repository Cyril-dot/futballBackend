package com.speedbet.api.wallet;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.speedbet.api.referral.ReferralService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankDepositService {

    private final BankDepositRepository repo;
    private final WalletService         walletService;
    private final ReferralService       referralService;

    // ── User: submit proof ────────────────────────────────────────────────────

    @Transactional
    public BankDeposit submit(UUID userId, BankDepositDtos.SubmitRequest req) {

        if (repo.existsByTransferReference(req.getTransferReference())) {
            throw new IllegalArgumentException(
                    "A deposit with this transfer reference already exists.");
        }

        BankDeposit deposit = BankDeposit.builder()
                .userId(userId)
                .transferReference(req.getTransferReference())
                .ngnAmountSent(req.getNgnAmountSent())
                .expectedNgnCredit(req.getExpectedNgnCredit())
                .senderAccountName(req.getSenderAccountName())
                .screenshotUrl(req.getScreenshotUrl())
                .userNote(req.getUserNote())
                .status(BankDepositStatus.PENDING)
                .build();

        return repo.save(deposit);
    }

    // ── User: own history ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<BankDeposit> getMyDeposits(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    // ── Admin: queries ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<BankDeposit> getAll(Pageable pageable) {
        return repo.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<BankDeposit> getPending(Pageable pageable) {
        return repo.findByStatusOrderByCreatedAtAsc(BankDepositStatus.PENDING, pageable);
    }

    @Transactional(readOnly = true)
    public BankDeposit getById(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank deposit not found: " + id));
    }

    // ── Admin: approve ────────────────────────────────────────────────────────

    @Transactional
    public BankDeposit approve(UUID depositId, UUID adminId, BankDepositDtos.ApproveRequest req) {

        BankDeposit deposit = getById(depositId);

        if (deposit.getStatus() != BankDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be approved. Current status: " + deposit.getStatus());
        }

        // Credit the wallet
        Transaction tx = walletService.credit(
                deposit.getUserId(),
                req.getCreditedNgnAmount(),
                TxKind.DEPOSIT,
                "BANK_DEPOSIT:" + deposit.getTransferReference(),
                Map.of(
                        "depositId",         deposit.getId().toString(),
                        "transferReference", deposit.getTransferReference(),
                        "ngnAmountSent",     deposit.getNgnAmountSent().toPlainString(),
                        "approvedBy",        adminId.toString()
                )
        );

        // Credit referral commission if this user was referred
        referralService.attributeCommission(deposit.getUserId(), req.getCreditedNgnAmount());

        deposit.setStatus(BankDepositStatus.APPROVED);
        deposit.setCreditedNgnAmount(req.getCreditedNgnAmount());
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());
        deposit.setWalletTransactionId(tx.getId());

        return repo.save(deposit);
    }

    // ── Admin: reject ─────────────────────────────────────────────────────────

    @Transactional
    public BankDeposit reject(UUID depositId, UUID adminId, BankDepositDtos.RejectRequest req) {

        BankDeposit deposit = getById(depositId);

        if (deposit.getStatus() != BankDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be rejected. Current status: " + deposit.getStatus());
        }

        deposit.setStatus(BankDepositStatus.REJECTED);
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());

        return repo.save(deposit);
    }
}
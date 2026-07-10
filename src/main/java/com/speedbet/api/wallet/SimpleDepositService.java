package com.speedbet.api.wallet;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.wallet.Transaction;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SimpleDepositService {

    private final SimpleDepositRepository repo;
    private final WalletService           walletService;
    private final ReferralService         referralService;

    // ── User: submit deposit request ───────────────────────────────────────────

    @Transactional
    public SimpleDeposit submit(UUID userId, SimpleDepositDtos.SubmitRequest req) {

        SimpleDeposit deposit = SimpleDeposit.builder()
                .userId(userId)
                .amount(req.getAmount())
                .phoneNumber(req.getPhoneNumber())
                .accountName(req.getAccountName())
                .network(req.getNetwork())
                .purpose(req.getPurpose())
                .status(SimpleDepositStatus.PENDING)
                .build();

        return repo.save(deposit);
    }

    // ── User: own history ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<SimpleDeposit> getMyDeposits(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    // ── Admin: queries ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<SimpleDeposit> getAll(Pageable pageable) {
        return repo.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<SimpleDeposit> getPending(Pageable pageable) {
        return repo.findByStatusOrderByCreatedAtAsc(SimpleDepositStatus.PENDING, pageable);
    }

    @Transactional(readOnly = true)
    public SimpleDeposit getById(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Deposit not found: " + id));
    }

    // ── Super Admin: approve ─────────────────────────────────────────────────────

    @Transactional
    public SimpleDeposit approve(UUID depositId, UUID superAdminId, SimpleDepositDtos.ApproveRequest req) {

        SimpleDeposit deposit = getById(depositId);

        if (deposit.getStatus() != SimpleDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be approved. Current status: " + deposit.getStatus());
        }

        // Credit the wallet
        Transaction tx = walletService.credit(
                deposit.getUserId(),
                req.getCreditedAmount(),
                TxKind.DEPOSIT,
                "SIMPLE_DEPOSIT:" + deposit.getId(),
                Map.of(
                        "depositId",    deposit.getId().toString(),
                        "phoneNumber",  deposit.getPhoneNumber(),
                        "accountName",  deposit.getAccountName(),
                        "network",      deposit.getNetwork().name(),
                        "purpose",      deposit.getPurpose().name(),
                        "amountClaimed", deposit.getAmount().toPlainString(),
                        "approvedBy",   superAdminId.toString()
                )
        );

        // Credit referral commission if this user was referred
        referralService.attributeCommission(deposit.getUserId(), req.getCreditedAmount());

        deposit.setStatus(SimpleDepositStatus.APPROVED);
        deposit.setCreditedAmount(req.getCreditedAmount());
        deposit.setReviewedBy(superAdminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());
        deposit.setWalletTransactionId(tx.getId());

        return repo.save(deposit);
    }

    // ── Admin: reject ─────────────────────────────────────────────────────────

    @Transactional
    public SimpleDeposit reject(UUID depositId, UUID adminId, SimpleDepositDtos.RejectRequest req) {

        SimpleDeposit deposit = getById(depositId);

        if (deposit.getStatus() != SimpleDepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING deposits can be rejected. Current status: " + deposit.getStatus());
        }

        deposit.setStatus(SimpleDepositStatus.REJECTED);
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());

        return repo.save(deposit);
    }
}
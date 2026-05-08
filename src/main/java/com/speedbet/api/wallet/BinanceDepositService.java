package com.speedbet.api.wallet;

import com.speedbet.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BinanceDepositService {

    private final BinanceDepositRepository repo;
    private final WalletService walletService;

    // ── User: submit a new deposit proof ─────────────────────────────────────

    @Transactional
    public BinanceDeposit submit(UUID userId, BinanceDepositDtos.SubmitRequest req) {

        // Idempotency: same TXID must not be reused
        if (repo.existsByTxid(req.getTxid()))
            throw ApiException.conflict("A deposit with this TXID has already been submitted.");

        var deposit = BinanceDeposit.builder()
                .userId(userId)
                .txid(req.getTxid())
                .cryptoAmount(req.getCryptoAmount())
                .coin(req.getCoin().toUpperCase())
                .network(req.getNetwork().toUpperCase())
                .expectedGhsAmount(req.getExpectedGhsAmount())
                .senderAddress(req.getSenderAddress())
                .screenshotUrl(req.getScreenshotUrl())
                .userNote(req.getUserNote())
                .status(BinanceDepositStatus.PENDING)
                .build();

        return repo.save(deposit);
    }

    // ── User: view own deposits ───────────────────────────────────────────────

    public Page<BinanceDeposit> getMyDeposits(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    // ── Admin: list pending deposits ─────────────────────────────────────────

    public Page<BinanceDeposit> getPending(Pageable pageable) {
        return repo.findByStatusOrderByCreatedAtAsc(BinanceDepositStatus.PENDING, pageable);
    }

    // ── Admin: list all deposits ─────────────────────────────────────────────

    public Page<BinanceDeposit> getAll(Pageable pageable) {
        return repo.findAllByOrderByCreatedAtDesc(pageable);
    }

    // ── Admin: approve a deposit ─────────────────────────────────────────────

    @Transactional
    public BinanceDeposit approve(UUID depositId, UUID adminId, BinanceDepositDtos.ApproveRequest req) {

        var deposit = getOrThrow(depositId);

        if (deposit.getStatus() != BinanceDepositStatus.PENDING)
            throw ApiException.unprocessable("Deposit is already " + deposit.getStatus());

        // Credit the user's wallet — idempotent via providerRef = deposit.getId()
        var tx = walletService.credit(
                deposit.getUserId(),
                req.getCreditedGhsAmount(),
                TxKind.DEPOSIT,
                "binance:" + deposit.getId(),
                Map.of(
                    "source",   "binance",
                    "txid",     deposit.getTxid(),
                    "coin",     deposit.getCoin(),
                    "network",  deposit.getNetwork(),
                    "depositId", deposit.getId().toString()
                )
        );

        deposit.setStatus(BinanceDepositStatus.APPROVED);
        deposit.setCreditedGhsAmount(req.getCreditedGhsAmount());
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());
        deposit.setWalletTransactionId(tx.getId());

        return repo.save(deposit);
    }

    // ── Admin: reject a deposit ──────────────────────────────────────────────

    @Transactional
    public BinanceDeposit reject(UUID depositId, UUID adminId, BinanceDepositDtos.RejectRequest req) {

        var deposit = getOrThrow(depositId);

        if (deposit.getStatus() != BinanceDepositStatus.PENDING)
            throw ApiException.unprocessable("Deposit is already " + deposit.getStatus());

        deposit.setStatus(BinanceDepositStatus.REJECTED);
        deposit.setReviewedBy(adminId);
        deposit.setReviewedAt(Instant.now());
        deposit.setAdminNote(req.getAdminNote());

        return repo.save(deposit);
    }

    // ── Admin: get single deposit ─────────────────────────────────────────────

    public BinanceDeposit getById(UUID depositId) {
        return getOrThrow(depositId);
    }

    // ── Internal helper ───────────────────────────────────────────────────────

    private BinanceDeposit getOrThrow(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Binance deposit not found: " + id));
    }
}
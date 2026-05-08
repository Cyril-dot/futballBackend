package com.speedbet.api.wallet;

import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalService {

    private final WithdrawalRequestRepository withdrawalRepo;
    private final WalletRepository            walletRepo;
    private final TransactionRepository       txRepo;
    private final UserRepository              userRepo;
    private final AuditService                auditService;
    private final EntityManager               em;

    @Value("${app.withdrawal.min-amount:10}")
    private BigDecimal minWithdrawalAmount;

    @Value("${app.withdrawal.max-amount:5000}")
    private BigDecimal maxWithdrawalAmount;

    @Value("${app.withdrawal.daily-limit:10000}")
    private BigDecimal dailyWithdrawalLimit;

    // ─────────────────────────────────────────────────────────────────────────
    // User submits a withdrawal request.
    // 1. Guards: one pending at a time, min/max, daily limit, sufficient balance
    // 2. Deducts amount from wallet (PESSIMISTIC_WRITE lock)
    // 3. Creates WITHDRAW_HOLD transaction to record the reservation
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public WithdrawalRequest submitRequest(UUID userId, WithdrawalRequestDto req) {

        // ── 1. One pending at a time ──────────────────────────────────────────
        boolean hasPending = withdrawalRepo.existsByUserIdAndStatusIn(
                userId,
                List.of(WithdrawalStatus.PENDING, WithdrawalStatus.APPROVED)
        );
        if (hasPending) {
            throw ApiException.badRequest("You already have a pending withdrawal. "
                    + "Please wait for it to be processed before submitting a new one.");
        }

        // ── 2. Amount bounds ──────────────────────────────────────────────────
        if (req.getAmount().compareTo(minWithdrawalAmount) < 0) {
            throw ApiException.badRequest(
                    "Minimum withdrawal amount is " + minWithdrawalAmount);
        }
        if (req.getAmount().compareTo(maxWithdrawalAmount) > 0) {
            throw ApiException.badRequest(
                    "Maximum withdrawal amount is " + maxWithdrawalAmount);
        }

        // ── 3. Daily limit ────────────────────────────────────────────────────
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        BigDecimal todaySettled = withdrawalRepo
                .sumAmountByUserIdAndStatusAndCreatedAtAfter(userId, WithdrawalStatus.SETTLED, startOfDay);
        if (todaySettled == null) todaySettled = BigDecimal.ZERO;

        if (todaySettled.add(req.getAmount()).compareTo(dailyWithdrawalLimit) > 0) {
            BigDecimal remaining = dailyWithdrawalLimit.subtract(todaySettled);
            throw ApiException.badRequest(
                    "Daily withdrawal limit of " + dailyWithdrawalLimit
                            + " exceeded. Remaining allowance today: " + remaining);
        }

        // ── 4. Lock wallet and check balance ─────────────────────────────────
        var walletEntity = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));
        var wallet = em.find(Wallet.class, walletEntity.getId(), LockModeType.PESSIMISTIC_WRITE);

        if (wallet.getBalance().compareTo(req.getAmount()) < 0) {
            throw ApiException.unprocessable("Insufficient available balance");
        }

        // ── 5. Deduct from wallet (this is what was missing before) ──────────
        BigDecimal newBalance = wallet.getBalance().subtract(req.getAmount(), MathContext.DECIMAL64);
        wallet.setBalance(newBalance);
        walletRepo.save(wallet);

        // ── 6. Persist withdrawal request ─────────────────────────────────────
        var user = userRepo.findById(userId).orElseThrow();
        var request = WithdrawalRequest.builder()
                .user(user)
                .amount(req.getAmount())
                .currency(req.getCurrency() != null ? req.getCurrency() : "GHS")
                .status(WithdrawalStatus.PENDING)
                .method(req.getMethod() != null ? req.getMethod() : "mobile_money")
                .accountNumber(req.getAccountNumber())
                .accountName(req.getAccountName())
                .network(req.getNetwork())
                .build();

        request = withdrawalRepo.save(request);

        // ── 7. Record WITHDRAW_HOLD transaction ───────────────────────────────
        txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(TxKind.WITHDRAW_HOLD)
                .amount(req.getAmount().negate())
                .balanceAfter(newBalance)           // consistent — balance already updated
                .providerRef(request.getId().toString())
                .metadata(Map.of("withdrawalRequestId", request.getId().toString()))
                .build());

        auditService.log(
                user.getId(),
                "WITHDRAWAL_REQUESTED",
                "WithdrawalRequest",
                request.getId(),
                null,
                Map.of(
                        "amount",   req.getAmount().toString(),
                        "method",   req.getMethod() != null ? req.getMethod() : "mobile_money",
                        "currency", req.getCurrency() != null ? req.getCurrency() : "GHS"
                ),
                null
        );

        log.info("Withdrawal request {} created for user {} — amount {} {}",
                request.getId(), userId, req.getAmount(),
                req.getCurrency() != null ? req.getCurrency() : "GHS");

        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Admin approves a PENDING withdrawal.
    // Wallet is already debited — just update the status.
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public WithdrawalRequest approve(UUID requestId, UUID adminId, String note) {
        var request = withdrawalRepo.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Withdrawal request not found"));

        if (request.getStatus() != WithdrawalStatus.PENDING) {
            throw ApiException.badRequest("Can only approve PENDING withdrawals");
        }

        var admin = userRepo.findById(adminId).orElseThrow();
        request.setStatus(WithdrawalStatus.APPROVED);
        request.setAdmin(admin);
        request.setAdminNote(note);
        request.setReviewedAt(Instant.now());
        request = withdrawalRepo.save(request);

        auditService.log(
                adminId,
                "WITHDRAWAL_APPROVED",
                "WithdrawalRequest",
                requestId,
                null,
                Map.of(
                        "note",   note != null ? note : "",
                        "userId", request.getUser().getId().toString()
                ),
                null
        );

        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Admin rejects a PENDING withdrawal.
    // Releases the held amount back to user wallet.
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public WithdrawalRequest reject(UUID requestId, UUID adminId, String note) {
        var request = withdrawalRepo.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Withdrawal request not found"));

        if (request.getStatus() != WithdrawalStatus.PENDING) {
            throw ApiException.badRequest("Can only reject PENDING withdrawals");
        }

        var admin = userRepo.findById(adminId).orElseThrow();
        request.setStatus(WithdrawalStatus.REJECTED);
        request.setAdmin(admin);
        request.setAdminNote(note);
        request.setReviewedAt(Instant.now());
        request = withdrawalRepo.save(request);

        // Release reserved amount back to wallet
        var wallet = em.find(Wallet.class,
                walletRepo.findByUserId(request.getUser().getId()).orElseThrow().getId(),
                LockModeType.PESSIMISTIC_WRITE);

        BigDecimal restoredBalance = wallet.getBalance().add(request.getAmount(), MathContext.DECIMAL64);
        wallet.setBalance(restoredBalance);
        walletRepo.save(wallet);

        txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(TxKind.WITHDRAW_RELEASE)
                .amount(request.getAmount())
                .balanceAfter(restoredBalance)
                .providerRef(request.getId().toString())
                .metadata(Map.of(
                        "withdrawalRequestId", request.getId().toString(),
                        "reason", "rejected"
                ))
                .build());

        auditService.log(
                adminId,
                "WITHDRAWAL_REJECTED",
                "WithdrawalRequest",
                requestId,
                null,
                Map.of(
                        "note",   note != null ? note : "",
                        "userId", request.getUser().getId().toString()
                ),
                null
        );

        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Super admin settles an APPROVED withdrawal.
    // Converts the WITHDRAW_HOLD transaction to WITHDRAW (money left the platform).
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public WithdrawalRequest settle(UUID requestId, UUID superAdminId, String note) {
        var request = withdrawalRepo.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Withdrawal request not found"));

        if (request.getStatus() != WithdrawalStatus.APPROVED) {
            throw ApiException.badRequest("Can only settle APPROVED withdrawals");
        }

        var superAdmin = userRepo.findById(superAdminId).orElseThrow();
        request.setStatus(WithdrawalStatus.SETTLED);
        request.setSuperAdmin(superAdmin);
        request.setSuperAdminNote(note);
        request.setSettledAt(Instant.now());
        request = withdrawalRepo.save(request);

        // Convert WITHDRAW_HOLD → WITHDRAW (confirms funds physically sent)
        txRepo.findByProviderRef(request.getId().toString()).ifPresent(holdTx -> {
            holdTx.setKind(TxKind.WITHDRAW);
            txRepo.save(holdTx);
        });

        auditService.log(
                superAdminId,
                "WITHDRAWAL_SETTLED",
                "WithdrawalRequest",
                requestId,
                null,
                Map.of(
                        "note",   note != null ? note : "",
                        "userId", request.getUser().getId().toString()
                ),
                null
        );

        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Super admin marks an APPROVED withdrawal as failed.
    // Releases the held amount back to user wallet.
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public WithdrawalRequest markFailed(UUID requestId, UUID superAdminId, String note) {
        var request = withdrawalRepo.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Withdrawal request not found"));

        if (request.getStatus() != WithdrawalStatus.APPROVED) {
            throw ApiException.badRequest("Can only mark APPROVED withdrawals as failed");
        }

        var superAdmin = userRepo.findById(superAdminId).orElseThrow();
        request.setStatus(WithdrawalStatus.FAILED);
        request.setSuperAdmin(superAdmin);
        request.setSuperAdminNote(note);
        request.setSettledAt(Instant.now());
        request = withdrawalRepo.save(request);

        // Release reserved amount back to wallet
        var wallet = em.find(Wallet.class,
                walletRepo.findByUserId(request.getUser().getId()).orElseThrow().getId(),
                LockModeType.PESSIMISTIC_WRITE);

        BigDecimal restoredBalance = wallet.getBalance().add(request.getAmount(), MathContext.DECIMAL64);
        wallet.setBalance(restoredBalance);
        walletRepo.save(wallet);

        txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(TxKind.WITHDRAW_RELEASE)
                .amount(request.getAmount())
                .balanceAfter(restoredBalance)
                .providerRef(request.getId().toString())
                .metadata(Map.of(
                        "withdrawalRequestId", request.getId().toString(),
                        "reason", "failed"
                ))
                .build());

        auditService.log(
                superAdminId,
                "WITHDRAWAL_FAILED",
                "WithdrawalRequest",
                requestId,
                null,
                Map.of(
                        "note",   note != null ? note : "",
                        "userId", request.getUser().getId().toString()
                ),
                null
        );

        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    public Page<WithdrawalRequest> getUserWithdrawals(UUID userId, Pageable pageable) {
        return withdrawalRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<WithdrawalRequest> getAllWithdrawals(WithdrawalStatus status, Pageable pageable) {
        if (status != null) {
            return withdrawalRepo.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        return withdrawalRepo.findAll(pageable);
    }

    public WithdrawalRequest getById(UUID id) {
        return withdrawalRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Withdrawal request not found"));
    }

    public WithdrawalRequest getByIdAndUser(UUID id, UUID userId) {
        return withdrawalRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Withdrawal request not found"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats — now with real amounts (add these queries to your repository)
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getAdminStats() {
        long pending  = withdrawalRepo.countByStatus(WithdrawalStatus.PENDING);
        long approved = withdrawalRepo.countByStatus(WithdrawalStatus.APPROVED);

        BigDecimal totalPendingAmount  = withdrawalRepo.sumAmountByStatus(WithdrawalStatus.PENDING);
        BigDecimal totalApprovedAmount = withdrawalRepo.sumAmountByStatus(WithdrawalStatus.APPROVED);

        return Map.of(
                "pending",             pending,
                "approved",            approved,
                "totalPendingAmount",  totalPendingAmount  != null ? totalPendingAmount  : BigDecimal.ZERO,
                "totalApprovedAmount", totalApprovedAmount != null ? totalApprovedAmount : BigDecimal.ZERO
        );
    }

    public Map<String, Object> getSuperAdminStats() {
        long pendingCount  = withdrawalRepo.countByStatus(WithdrawalStatus.PENDING);
        long approvedCount = withdrawalRepo.countByStatus(WithdrawalStatus.APPROVED);
        long settledCount  = withdrawalRepo.countByStatus(WithdrawalStatus.SETTLED);
        long failedCount   = withdrawalRepo.countByStatus(WithdrawalStatus.FAILED);

        BigDecimal totalSettledAmount = withdrawalRepo.sumAmountByStatus(WithdrawalStatus.SETTLED);
        BigDecimal totalPendingAmount = withdrawalRepo.sumAmountByStatus(WithdrawalStatus.PENDING);

        return Map.of(
                "pendingCount",       pendingCount,
                "approvedCount",      approvedCount,
                "settledCount",       settledCount,
                "failedCount",        failedCount,
                "totalSettledAmount", totalSettledAmount != null ? totalSettledAmount : BigDecimal.ZERO,
                "totalPendingAmount", totalPendingAmount != null ? totalPendingAmount : BigDecimal.ZERO
        );
    }
}
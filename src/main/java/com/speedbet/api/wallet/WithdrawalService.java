package com.speedbet.api.wallet;

import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
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
import java.time.LocalDateTime;
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
    private final WithdrawalEmailService      withdrawalEmailService;
    private final WithdrawalSmsService        withdrawalSmsService;

    @Value("${app.withdrawal.min-amount:2000}")
    private BigDecimal minWithdrawalAmount;

    // ─────────────────────────────────────────────────────────────────────────
    // Submit
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public WithdrawalDto submitRequest(UUID userId, WithdrawalRequestDto req) {

        boolean hasPending = withdrawalRepo.existsByUserIdAndStatusIn(
                userId, List.of(WithdrawalStatus.PENDING, WithdrawalStatus.APPROVED));
        if (hasPending) {
            throw ApiException.badRequest("You already have a pending withdrawal. "
                    + "Please wait for it to be processed before submitting a new one.");
        }

        if (req.getAmount().compareTo(minWithdrawalAmount) < 0) {
            throw ApiException.badRequest("Minimum withdrawal amount is " + minWithdrawalAmount);
        }

        var walletEntity = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));
        var wallet = em.find(Wallet.class, walletEntity.getId(), LockModeType.PESSIMISTIC_WRITE);

        if (wallet.getBalance().compareTo(req.getAmount()) < 0) {
            throw ApiException.unprocessable("Insufficient available balance");
        }

        BigDecimal newBalance = wallet.getBalance().subtract(req.getAmount(), MathContext.DECIMAL64);
        wallet.setBalance(newBalance);
        walletRepo.save(wallet);

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

        Transaction holdTx = txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(TxKind.WITHDRAW_HOLD)
                .amount(req.getAmount().negate())
                .balanceAfter(newBalance)
                .providerRef(request.getId().toString())
                .metadata(Map.of("withdrawalRequestId", request.getId().toString()))
                .build());

        auditService.log(user.getId(), "WITHDRAWAL_REQUESTED", "WithdrawalRequest", request.getId(),
                null, Map.of(
                        "amount",   req.getAmount().toString(),
                        "method",   req.getMethod() != null ? req.getMethod() : "mobile_money",
                        "currency", req.getCurrency() != null ? req.getCurrency() : "GHS"),
                null);

        log.info("Withdrawal request {} created for user {} — amount {} {}",
                request.getId(), userId, req.getAmount(),
                req.getCurrency() != null ? req.getCurrency() : "GHS");

        return WithdrawalDto.from(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Approve  ← admins get email, user gets SMS
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public WithdrawalDto approve(UUID requestId, UUID adminId, String note) {
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
        final WithdrawalRequest savedRequest = withdrawalRepo.save(request);

        auditService.log(adminId, "WITHDRAWAL_APPROVED", "WithdrawalRequest", requestId,
                null, Map.of(
                        "note",   note != null ? note : "",
                        "userId", savedRequest.getUser().getId().toString()),
                null);

        final var u   = savedRequest.getUser();
        final LocalDateTime now = LocalDateTime.now();

        // ── Email → all ADMIN and SUPER_ADMIN users ──────────────────────────
        var admins = userRepo.findByRoleIn(List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN));
        admins.forEach(a -> withdrawalEmailService.notifyConfirmed(
                a.getEmail(),
                a.getFirstName(),
                a.getLastName(),
                a.getPhone(),
                a.getCountry(),
                savedRequest.getAmount(),
                savedRequest.getCurrency(),
                now
        ));

        // ── Resolve wallet balance after the hold deduction ───────────────────
        BigDecimal walletBalance = walletRepo.findByUserId(u.getId())
                .map(Wallet::getBalance)
                .orElse(null);

        // ── Fee is GHS 0.00 for MoMo withdrawals (no platform fee deducted) ──
        BigDecimal fee = BigDecimal.ZERO;

        // ── SMS → user's MoMo number (falls back to profile phone) ───────────
        String smsTarget = resolvePhoneForSms(savedRequest.getAccountNumber(), u.getPhone());
        withdrawalSmsService.notifyWithdrawalConfirmed(
                smsTarget,
                u.getFirstName(),
                savedRequest.getAmount(),
                fee,
                walletBalance,
                null,   // transactionId — not included in SMS
                null,   // reference — not included in SMS
                now
        );

        return WithdrawalDto.from(savedRequest);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reject  ← admins get email, user gets SMS
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public WithdrawalDto reject(UUID requestId, UUID adminId, String note) {
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
        final WithdrawalRequest savedRequest = withdrawalRepo.save(request);

        var wallet = em.find(Wallet.class,
                walletRepo.findByUserId(savedRequest.getUser().getId()).orElseThrow().getId(),
                LockModeType.PESSIMISTIC_WRITE);

        // ── Restore the held amount back to the wallet ────────────────────────
        BigDecimal restoredBalance = wallet.getBalance().add(savedRequest.getAmount(), MathContext.DECIMAL64);
        wallet.setBalance(restoredBalance);
        walletRepo.save(wallet);

        txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(TxKind.WITHDRAW_RELEASE)
                .amount(savedRequest.getAmount())
                .balanceAfter(restoredBalance)
                .providerRef(savedRequest.getId().toString())
                .metadata(Map.of(
                        "withdrawalRequestId", savedRequest.getId().toString(),
                        "reason", "rejected"))
                .build());

        auditService.log(adminId, "WITHDRAWAL_REJECTED", "WithdrawalRequest", requestId,
                null, Map.of(
                        "note",   note != null ? note : "",
                        "userId", savedRequest.getUser().getId().toString()),
                null);

        final var u   = savedRequest.getUser();
        final LocalDateTime now = LocalDateTime.now();

        // ── Email → all ADMIN and SUPER_ADMIN users ──────────────────────────
        var admins = userRepo.findByRoleIn(List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN));
        admins.forEach(a -> withdrawalEmailService.notifyRejected(
                a.getEmail(),
                a.getFirstName(),
                a.getLastName(),
                a.getPhone(),
                a.getCountry(),
                savedRequest.getAmount(),
                savedRequest.getCurrency(),
                note,
                now
        ));

        // ── SMS → user's MoMo number (falls back to profile phone) ───────────
        String smsTarget = resolvePhoneForSms(savedRequest.getAccountNumber(), u.getPhone());
        withdrawalSmsService.notifyWithdrawalRejected(
                smsTarget,
                u.getFirstName(),
                savedRequest.getAmount(),
                note,
                restoredBalance,
                null,   // transactionId — not included in SMS
                null,   // reference — not included in SMS
                now
        );

        return WithdrawalDto.from(savedRequest);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settle
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public WithdrawalDto settle(UUID requestId, UUID superAdminId, String note) {
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

        txRepo.findByProviderRef(request.getId().toString()).ifPresent(holdTx -> {
            holdTx.setKind(TxKind.WITHDRAW);
            txRepo.save(holdTx);
        });

        auditService.log(superAdminId, "WITHDRAWAL_SETTLED", "WithdrawalRequest", requestId,
                null, Map.of(
                        "note",   note != null ? note : "",
                        "userId", request.getUser().getId().toString()),
                null);

        return WithdrawalDto.from(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mark failed
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public WithdrawalDto markFailed(UUID requestId, UUID superAdminId, String note) {
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
                        "reason", "failed"))
                .build());

        auditService.log(superAdminId, "WITHDRAWAL_FAILED", "WithdrawalRequest", requestId,
                null, Map.of(
                        "note",   note != null ? note : "",
                        "userId", request.getUser().getId().toString()),
                null);

        return WithdrawalDto.from(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<WithdrawalDto> getUserWithdrawals(UUID userId, Pageable pageable) {
        return withdrawalRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(WithdrawalDto::from);
    }

    @Transactional(readOnly = true)
    public Page<WithdrawalDto> getAllWithdrawals(WithdrawalStatus status, Pageable pageable) {
        Page<WithdrawalRequest> page = (status != null)
                ? withdrawalRepo.findByStatusOrderByCreatedAtDesc(status, pageable)
                : withdrawalRepo.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(WithdrawalDto::from);
    }

    @Transactional(readOnly = true)
    public WithdrawalDto getById(UUID id) {
        return WithdrawalDto.from(
                withdrawalRepo.findById(id)
                        .orElseThrow(() -> ApiException.notFound("Withdrawal request not found")));
    }

    @Transactional(readOnly = true)
    public WithdrawalDto getByIdAndUser(UUID id, UUID userId) {
        return WithdrawalDto.from(
                withdrawalRepo.findByIdAndUserId(id, userId)
                        .orElseThrow(() -> ApiException.notFound("Withdrawal request not found")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
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
                "totalApprovedAmount", totalApprovedAmount != null ? totalApprovedAmount : BigDecimal.ZERO);
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
                "totalPendingAmount", totalPendingAmount != null ? totalPendingAmount : BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * For mobile-money withdrawals the accountNumber IS the MoMo number, so
     * that's the right place to send the confirmation SMS. Falls back to the
     * user's profile phone if accountNumber is blank.
     */
    private String resolvePhoneForSms(String accountNumber, String profilePhone) {
        if (accountNumber != null && !accountNumber.isBlank()) {
            return accountNumber;
        }
        return profilePhone;
    }
}
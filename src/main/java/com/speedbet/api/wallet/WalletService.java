package com.speedbet.api.wallet;

import com.speedbet.api.common.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepo;
    private final TransactionRepository txRepo;
    private final EntityManager em;

    public Wallet getWallet(UUID userId) {
        return walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));
    }

    // ── ADDED ────────────────────────────────────────────────────────
    // Everything below this line is new. Nothing above it was touched.

    /** Convenience read — same lookup as getWallet(), just returns the balance directly. */
    public BigDecimal getBalance(UUID userId) {
        return getWallet(userId).getBalance();
    }

    /**
     * Lean debit for callers that don't have a provider reference or extra
     * metadata to attach (e.g. an in-game stake). Delegates to the full
     * debit() below, so it gets the same SERIALIZABLE isolation, pessimistic
     * row lock, and insufficient-balance check — this is not a separate
     * code path, just a shorter call for the common case.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Transaction debit(UUID userId, BigDecimal amount, TxKind kind) {
        return debit(userId, amount, kind, null, Map.of());
    }

    /** Lean credit counterpart — e.g. a game payout with no external provider ref. */
    @Transactional
    public Transaction credit(UUID userId, BigDecimal amount, TxKind kind) {
        return credit(userId, amount, kind, null, Map.of());
    }

    // ── END ADDED ────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Transaction debit(UUID userId, BigDecimal amount, TxKind kind,
                             String providerRef, Map<String, Object> metadata) {
        var wallet = em.find(Wallet.class,
                walletRepo.findByUserId(userId).orElseThrow().getId(),
                LockModeType.PESSIMISTIC_WRITE);

        if (wallet.getBalance().compareTo(amount) < 0)
            throw ApiException.unprocessable("Insufficient balance");

        wallet.setBalance(wallet.getBalance().subtract(amount, MathContext.DECIMAL64));

        return txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(kind)
                .amount(amount.negate())
                .balanceAfter(wallet.getBalance())
                .providerRef(providerRef)
                .metadata(metadata)
                .build());
    }

    @Transactional
    public Transaction credit(UUID userId, BigDecimal amount, TxKind kind,
                              String providerRef, Map<String, Object> metadata) {
        // Idempotency check
        if (providerRef != null && txRepo.existsByProviderRef(providerRef))
            throw ApiException.conflict("Transaction already processed: " + providerRef);

        var wallet = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));
        wallet.setBalance(wallet.getBalance().add(amount, MathContext.DECIMAL64));
        walletRepo.save(wallet);

        return txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(kind)
                .amount(amount)
                .balanceAfter(wallet.getBalance())
                .providerRef(providerRef)
                .metadata(metadata)
                .build());
    }

    /**
     * Record an audit-only transaction for money collected externally by Paystack.
     *
     * The wallet balance is NOT mutated — Paystack took the funds directly.
     * This exists purely so the transaction log has a row that reconciles against
     * the Paystack dashboard (e.g. the GHS 200 admin upgrade fee).
     *
     * Idempotent: if providerRef already exists in the transaction table the
     * existing row is returned silently — safe for webhook retries.
     *
     * amount is stored as a negative value (matching the debit convention) so
     * reporting queries that sum amounts produce correct totals.
     */
    @Transactional
    public Transaction recordExternalDebit(UUID userId, BigDecimal amount, TxKind kind,
                                           String providerRef, Map<String, Object> metadata) {
        // Idempotency — same guard pattern as credit()
        if (providerRef != null && txRepo.existsByProviderRef(providerRef)) {
            return txRepo.findByProviderRef(providerRef)
                    .orElseThrow(() -> ApiException.internal("Idempotency read failed for ref: " + providerRef));
        }

        var wallet = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        // No balance mutation — external provider already collected the funds.
        // balanceAfter reflects the current balance unchanged so the audit row
        // is accurate at the time of recording.
        return txRepo.save(Transaction.builder()
                .walletId(wallet.getId())
                .kind(kind)
                .amount(amount.negate())      // negative = money left the user, matches debit convention
                .balanceAfter(wallet.getBalance())
                .providerRef(providerRef)
                .metadata(metadata)
                .build());
    }

    public Page<Transaction> getTransactions(UUID userId, Pageable pageable) {
        var wallet = getWallet(userId);
        return txRepo.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable);
    }
}
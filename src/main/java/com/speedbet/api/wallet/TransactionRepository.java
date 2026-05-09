package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // ── Existing ──────────────────────────────────────────────────────────────

    Page<Transaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(t.amount),0) FROM Transaction t WHERE t.walletId = :walletId AND t.kind = :kind AND t.createdAt >= :since")
    BigDecimal sumByKindSince(UUID walletId, TxKind kind, Instant since);

    Optional<Transaction> findByProviderRef(String providerRef);

    boolean existsByProviderRef(String providerRef);

    // ── Platform-wide (Super Admin) ───────────────────────────────────────────

    /**
     * All transactions across the platform with optional filters.
     * Pass null for any param to skip that filter.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE (:kind     IS NULL OR t.kind     = :kind)
              AND (:status   IS NULL OR t.status   = :status)
              AND (:walletId IS NULL OR t.walletId = :walletId)
              AND (:from     IS NULL OR t.createdAt >= :from)
              AND (:to       IS NULL OR t.createdAt <= :to)
            ORDER BY t.createdAt DESC
            """)
    Page<Transaction> findAllFiltered(
            @Param("kind")     TxKind kind,
            @Param("status")   String status,
            @Param("walletId") UUID walletId,
            @Param("from")     Instant from,
            @Param("to")       Instant to,
            Pageable pageable
    );

    /** Sum of all transactions of a given kind across the entire platform. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.kind = :kind")
    BigDecimal sumAllByKind(@Param("kind") TxKind kind);

    /** Sum of all transactions of a given kind since a given instant (platform-wide). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.kind = :kind AND t.createdAt >= :since")
    BigDecimal sumAllByKindSince(@Param("kind") TxKind kind, @Param("since") Instant since);

    /** Count transactions of a given kind across the platform. */
    long countByKind(TxKind kind);

    /** Count all transactions for a specific wallet. */
    long countByWalletId(UUID walletId);
}
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
     * Uses a native query with explicit PostgreSQL CASTs so that PostgreSQL
     * can resolve parameter types even when values are null — fixing the
     * "could not determine data type of parameter $N" error.
     *
     * kind and status are passed as Strings (not enums) because native queries
     * cannot bind Java enums directly.
     */
    @Query(value = """
            SELECT * FROM transactions t
            WHERE (:kind     IS NULL OR t.kind      = :kind)
              AND (:status   IS NULL OR t.status    = :status)
              AND (:walletId IS NULL OR t.wallet_id = CAST(:walletId AS uuid))
              AND (:from     IS NULL OR t.created_at >= CAST(:from AS timestamptz))
              AND (:to       IS NULL OR t.created_at <= CAST(:to   AS timestamptz))
            ORDER BY t.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM transactions t
            WHERE (:kind     IS NULL OR t.kind      = :kind)
              AND (:status   IS NULL OR t.status    = :status)
              AND (:walletId IS NULL OR t.wallet_id = CAST(:walletId AS uuid))
              AND (:from     IS NULL OR t.created_at >= CAST(:from AS timestamptz))
              AND (:to       IS NULL OR t.created_at <= CAST(:to   AS timestamptz))
            """,
            nativeQuery = true)
    Page<Transaction> findAllFiltered(
            @Param("kind")     String kind,
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
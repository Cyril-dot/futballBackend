package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository
        extends JpaRepository<Transaction, UUID>,
                JpaSpecificationExecutor<Transaction> {

    // ── Existing ──────────────────────────────────────────────────────────────

    Page<Transaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.walletId = :walletId AND t.kind = :kind AND t.createdAt >= :since")
    BigDecimal sumByKindSince(
            @Param("walletId") UUID walletId,
            @Param("kind")     TxKind kind,
            @Param("since")    Instant since);

    Optional<Transaction> findByProviderRef(String providerRef);

    boolean existsByProviderRef(String providerRef);

    // ── Platform-wide (Super Admin) ───────────────────────────────────────────
    // findAllFiltered is handled via Specification in TransactionSpecs.
    // JpaSpecificationExecutor provides:
    //   Page<Transaction> findAll(Specification<Transaction>, Pageable)
    // which builds a type-safe, null-safe dynamic WHERE clause — no raw SQL,
    // no untyped JDBC parameters, no PostgreSQL "could not determine data type" errors.

    /** Sum of all transactions of a given kind across the entire platform. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.kind = :kind")
    BigDecimal sumAllByKind(@Param("kind") TxKind kind);

    /** Sum of all transactions of a given kind since a given instant (platform-wide). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.kind = :kind AND t.createdAt >= :since")
    BigDecimal sumAllByKindSince(@Param("kind") TxKind kind, @Param("since") Instant since);

    /** Count transactions of a given kind across the platform. */
    long countByKind(TxKind kind);

    /** Count all transactions for a specific wallet. */
    long countByWalletId(UUID walletId);

    // ── User Deposit History ──────────────────────────────────────────────────

    /**
     * All DEPOSIT transactions for a specific wallet, newest first.
     * Spring Data derives the full WHERE + ORDER BY from the method name —
     * no JPQL or raw SQL required.
     */
    Page<Transaction> findByWalletIdAndKindOrderByCreatedAtDesc(
            UUID walletId, TxKind kind, Pageable pageable);
}
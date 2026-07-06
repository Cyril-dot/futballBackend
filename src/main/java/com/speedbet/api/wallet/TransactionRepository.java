package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository
        extends JpaRepository<Transaction, UUID>,
                JpaSpecificationExecutor<Transaction> {

    // ── Existing (unchanged) ──────────────────────────────────────────────────

    Page<Transaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.walletId = :walletId AND t.kind = :kind AND t.createdAt >= :since")
    BigDecimal sumByKindSince(
            @Param("walletId") UUID walletId,
            @Param("kind")     TxKind kind,
            @Param("since")    Instant since);

    Optional<Transaction> findByProviderRef(String providerRef);

    boolean existsByProviderRef(String providerRef);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.kind = :kind")
    BigDecimal sumAllByKind(@Param("kind") TxKind kind);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.kind = :kind AND t.createdAt >= :since")
    BigDecimal sumAllByKindSince(@Param("kind") TxKind kind, @Param("since") Instant since);

    long countByKind(TxKind kind);

    long countByWalletId(UUID walletId);

    Page<Transaction> findByWalletIdAndKindOrderByCreatedAtDesc(
            UUID walletId, TxKind kind, Pageable pageable);

    // ── NEW: added for country-split deposits (Ghana feed) ─────────────────────
    //
    // Paystack and Moolre deposits never create their own review-queue row —
    // they credit the wallet instantly via WalletService.credit(), so the
    // Transaction table (filtered by kind=DEPOSIT and metadata.provider) is
    // the ONLY place they exist. This is what AdminDepositService uses to
    // build the Ghana deposits feed alongside Binance deposits.
    //
    // ASSUMES POSTGRES: uses the jsonb ->> operator on the `metadata` column
    // (mapped via @JdbcTypeCode(SqlTypes.JSON) on Transaction.metadata). If
    // the actual database is MySQL/MariaDB, this needs to be rewritten using
    // JSON_EXTRACT(t.metadata, '$.provider') instead — flagging this as an
    // assumption to confirm before deploying.

    @Query(
        value = "SELECT * FROM transactions t " +
                "WHERE t.kind = 'DEPOSIT' " +
                "AND t.metadata ->> 'provider' = :provider " +
                "AND t.created_at >= :since " +
                "ORDER BY t.created_at DESC",
        countQuery = "SELECT count(*) FROM transactions t " +
                "WHERE t.kind = 'DEPOSIT' " +
                "AND t.metadata ->> 'provider' = :provider " +
                "AND t.created_at >= :since",
        nativeQuery = true
    )
    Page<Transaction> findDepositsByProviderSince(
            @Param("provider") String provider,
            @Param("since") Instant since,
            Pageable pageable);

    @Query("""
        SELECT new com.speedbet.api.wallet.DepositRow(t.createdAt, u.country, t.amount)
        FROM Transaction t, Wallet w, com.speedbet.api.user.User u
        WHERE t.walletId = w.id
          AND w.userId = u.id
          AND u.createdByAdminId = :adminId
          AND t.kind = com.speedbet.api.wallet.TxKind.DEPOSIT
          AND t.status = 'COMPLETED'
          AND t.createdAt >= :since
        """)
    List<DepositRow> findDepositsByAdminSince(@Param("adminId") UUID adminId, @Param("since") Instant since);

    // ── NEW: added for daily total-deposit-volume admin dashboard stat ─────────
    //
    // Covers every deposit source in one query: BankDeposit and BinanceDeposit
    // approvals both call walletService.credit(...) internally, and so do
    // Paystack/Moolre webhooks — so grouping all kind=DEPOSIT transactions by
    // day already gives a platform-wide total, no per-country union needed
    // for this particular stat.
    //
    // ASSUMES POSTGRES (date_trunc). MySQL equivalent would use
    // DATE(t.created_at) instead of date_trunc('day', t.created_at).

    @Query(
        value = "SELECT date_trunc('day', t.created_at) AS bucketDate, " +
                "COALESCE(SUM(t.amount), 0) AS total " +
                "FROM transactions t " +
                "WHERE t.kind = 'DEPOSIT' AND t.created_at >= :since " +
                "GROUP BY date_trunc('day', t.created_at) " +
                "ORDER BY date_trunc('day', t.created_at)",
        nativeQuery = true
    )
    java.util.List<DailyDepositTotalProjection> sumDepositsByDaySince(@Param("since") Instant since);

    /** Native-query projection for the daily deposit totals stat. */
    interface DailyDepositTotalProjection {
        Instant getBucketDate();
        BigDecimal getTotal();
    }
}
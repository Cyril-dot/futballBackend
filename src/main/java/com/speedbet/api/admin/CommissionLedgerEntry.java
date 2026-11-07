package com.speedbet.api.admin;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per commission credit event.
 *
 * Added specifically to support daily/weekly/monthly commission breakdowns.
 * AffiliateCommissionBalance only ever stores running totals (balance,
 * totalEarnedLifetime, totalPaidOutLifetime) — those can't be grouped by
 * time period. This table exists purely as an append-only event log,
 * populated by {@link CommissionLedgerAspect} whenever
 * AffiliateCommissionService.creditCommission(...) succeeds.
 *
 * This table is never read from or written to by AffiliateCommissionService
 * itself — that file is untouched. CommissionLedgerAspect is the only writer.
 */
@Entity
@Table(
    name = "commission_ledger_entries",
    indexes = {
        @Index(name = "idx_cle_admin_id",      columnList = "admin_id"),
        @Index(name = "idx_cle_created_at",    columnList = "created_at"),
        @Index(name = "idx_cle_admin_created",  columnList = "admin_id,created_at"),
        @Index(name = "idx_cle_user_id",        columnList = "user_id"),
        @Index(name = "idx_cle_deposit_reference", columnList = "deposit_reference")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommissionLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The admin whose commission balance was credited (same as creditCommission's userId param). */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    /**
     * The referred user (depositor) whose deposit generated this commission.
     * Null for legacy rows (written before this column existed) and for
     * reconcile top-ups, which cover a whole day of deposits from many
     * users. Reports fall back to currency / admin country when null.
     */
    @Column(name = "user_id")
    private UUID userId;

    /**
     * The deposit reference this commission was attributed from (the same
     * idempotency ref the wallet credit used, e.g. sbdep-... / SPB-NGT-...).
     * Null for legacy rows and reconcile top-ups. Re-attribution of the
     * same deposit is idempotent on (admin_id, deposit_reference): a retry
     * that finds an entry with this reference skips crediting.
     */
    @Column(name = "deposit_reference", length = 100)
    private String depositReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Builder.Default
    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    /** Set when a super admin settles this ledger entry for its commission day. */
    @Column(name = "paid_at")
    private Instant paidAt;
}

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
        @Index(name = "idx_cle_admin_created",  columnList = "admin_id,created_at")
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

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Builder.Default
    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
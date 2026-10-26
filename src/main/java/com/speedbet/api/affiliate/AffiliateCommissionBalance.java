package com.speedbet.api.affiliate;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks an affiliate's earned commission balance — entirely separate from
 * the user's main SpeedBet wallet (WalletService / Wallet entity).
 *
 * Commission balance lifecycle:
 *   1. Credited when a referred user places a qualifying bet  →  credit()
 *   2. Debited ONLY when a payout is actually processed      →  deductProcessedPayout()
 *      (the daily scheduler only queues a PENDING request now; it no longer
 *      sweeps the balance, so unpaid commission is never cleared early)
 *   3. If a swept payout is rejected by super-admin the amount is  →  credit() (reversal)
 *      re-added to the commission balance, NOT the main wallet.
 *
 * The main wallet balance is untouched by affiliate commission operations.
 */
@Entity
@Table(name = "affiliate_commission_balances",
        uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AffiliateCommissionBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /**
     * Current un-paid commission balance.
     * Always >= 0. Decremented to ZERO after each daily payout sweep.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "GHS";

    @Column(name = "total_earned_lifetime", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalEarnedLifetime = BigDecimal.ZERO;

    @Column(name = "total_paid_out_lifetime", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalPaidOutLifetime = BigDecimal.ZERO;

    @Column(name = "last_payout_at")
    private Instant lastPayoutAt;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
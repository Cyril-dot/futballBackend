package com.speedbet.api.affiliate;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a payout request submitted by an ADMIN affiliate.
 * Admins request their full wallet balance once per week (Fridays only).
 * Super admins approve, reject, or mark as paid.
 */
@Entity
@Table(name = "affiliate_payout_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayoutRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The admin affiliate who submitted this payout request. */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    /** Snapshot of the wallet balance at the time of the request. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PayoutStatus status = PayoutStatus.REQUESTED;

    /** Timestamp of when the request was submitted (acts as period marker). */
    @Column(name = "period_end", nullable = false)
    @Builder.Default
    private Instant periodEnd = Instant.now();

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(name = "paid_at")
    private Instant paidAt;

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
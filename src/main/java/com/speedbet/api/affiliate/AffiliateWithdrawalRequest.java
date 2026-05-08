package com.speedbet.api.affiliate;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a commission withdrawal request submitted by an affiliate user.
 * Wallet is debited immediately on creation; status tracks processing by super-admin.
 */
@Entity
@Table(name = "affiliate_withdrawal_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AffiliateWithdrawalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The affiliate user who requested the withdrawal. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AffiliateWithdrawalStatus status = AffiliateWithdrawalStatus.PENDING;

    // ─── Payment destination (bank or MoMo — stored at request time for audit) ───

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "account_name", length = 150)
    private String accountName;

    /** Optional Mobile Money number as an alternative to bank transfer. */
    @Column(name = "mobile_money_number", length = 50)
    private String mobileMoneyNumber;

    // ─── Tracking ────────────────────────────────────────────────────────────────

    /** Unique reference used for wallet debit and refund correlation. */
    @Column(nullable = false, unique = true, length = 100)
    private String reference;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(name = "requested_at", updatable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
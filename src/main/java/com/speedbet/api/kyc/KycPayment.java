package com.speedbet.api.kyc;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A RoyalBet KYC / withdrawal-gate activation-fee payment, recorded by
 * the user and confirmed or rejected by the super admin.
 *
 * Deliberately SEPARATE from wallet deposits: rows here never touch
 * WalletService, wallet transactions or any deposit queue, so fee money
 * can never be mixed into deposit reporting. Confirming a payment only
 * marks the fee as paid — it credits NOTHING to any wallet.
 */
@Entity
@Table(name = "kyc_payments", indexes = {
        @Index(name = "idx_kyc_payments_user", columnList = "user_id"),
        @Index(name = "idx_kyc_payments_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KycPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Snapshot so the admin queue is readable without joining users. */
    @Column(nullable = false)
    private String email;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "GHS";

    /** MOMO | BANK | CASH | OTHER — free-form so new rails need no migration. */
    @Column(nullable = false, length = 24)
    private String method;

    /** MoMo transaction id / bank reference supplied by the user. */
    private String reference;

    /** ImgBB-hosted receipt screenshot URL — a link, never the image bytes. */
    @Column(name = "proof_url", columnDefinition = "TEXT")
    private String proofUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private KycPaymentStatus status = KycPaymentStatus.PENDING;

    /** Super admin's note (rejection reason) — shown to the user. */
    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;
}

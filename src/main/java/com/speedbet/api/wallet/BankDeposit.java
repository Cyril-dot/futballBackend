package com.speedbet.api.wallet;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a user-submitted Nigerian bank transfer deposit request.
 * The super-admin reviews the screenshot proof, verifies the transfer,
 * then either APPROVES (credits the wallet) or REJECTS it.
 *
 * Lifecycle:  PENDING → APPROVED | REJECTED
 */
@Entity
@Table(
    name = "bank_deposits",
    indexes = {
        @Index(name = "idx_bkd_user_id",     columnList = "user_id"),
        @Index(name = "idx_bkd_reference",   columnList = "transfer_reference", unique = true),
        @Index(name = "idx_bkd_status",      columnList = "status"),
        @Index(name = "idx_bkd_reviewed_by", columnList = "reviewed_by")
    }
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BankDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Who submitted ────────────────────────────────────────────────────────
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // ── Payment proof fields ─────────────────────────────────────────────────

    /** Narration / reference the user used during the bank transfer */
    @Column(name = "transfer_reference", nullable = false, length = 128)
    private String transferReference;

    /** Amount the user claims to have sent (NGN) */
    @Column(name = "ngn_amount_sent", nullable = false, precision = 18, scale = 2)
    private BigDecimal ngnAmountSent;

    /** NGN equivalent the user expects to be credited (admin can override) */
    @Column(name = "expected_ngn_credit", nullable = false, precision = 18, scale = 2)
    private BigDecimal expectedNgnCredit;

    /** Actual NGN amount credited — set by admin on approval */
    @Column(name = "credited_ngn_amount", precision = 18, scale = 2)
    private BigDecimal creditedNgnAmount;

    /** Name on the sender's bank account */
    @Column(name = "sender_account_name", length = 256)
    private String senderAccountName;

    /** URL / storage key for the uploaded payment screenshot */
    @Column(name = "screenshot_url", length = 512)
    private String screenshotUrl;

    /** Optional note from the user */
    @Column(name = "user_note", length = 1000)
    private String userNote;

    // ── Status ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private BankDepositStatus status = BankDepositStatus.PENDING;

    // ── Admin review fields ──────────────────────────────────────────────────

    /** UUID of the super-admin who reviewed this deposit */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Admin's internal note (rejection reason, override explanation, etc.) */
    @Column(name = "admin_note", length = 1000)
    private String adminNote;

    /**
     * The wallet Transaction row created when this deposit was approved.
     * Null until approval.
     */
    @Column(name = "wallet_transaction_id")
    private UUID walletTransactionId;

    // ── Audit timestamps ─────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
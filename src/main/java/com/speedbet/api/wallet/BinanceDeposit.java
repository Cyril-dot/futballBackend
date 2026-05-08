package com.speedbet.api.wallet;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a user-submitted crypto deposit request made via Binance
 * (or any external crypto wallet). The super-admin reviews the submission,
 * verifies it on-chain, then either APPROVES (credits the wallet) or REJECTS it.
 *
 * Lifecycle:  PENDING → APPROVED | REJECTED
 */
@Entity
@Table(
    name = "binance_deposits",
    indexes = {
        @Index(name = "idx_bd_user_id",      columnList = "user_id"),
        @Index(name = "idx_bd_txid",         columnList = "txid", unique = true),
        @Index(name = "idx_bd_status",       columnList = "status"),
        @Index(name = "idx_bd_reviewed_by",  columnList = "reviewed_by")
    }
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BinanceDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Who submitted ────────────────────────────────────────────────────────
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // ── Payment proof fields ─────────────────────────────────────────────────

    /** Transaction hash / TXID from the blockchain — must be unique per deposit */
    @Column(name = "txid", nullable = false, length = 128)
    private String txid;

    /** Amount the user claims to have sent (in the crypto coin) */
    @Column(name = "crypto_amount", nullable = false, precision = 30, scale = 8)
    private BigDecimal cryptoAmount;

    /** e.g. USDT, BTC, ETH, BNB */
    @Column(name = "coin", nullable = false, length = 16)
    private String coin;

    /** e.g. TRC20, BEP20, ERC20 */
    @Column(name = "network", nullable = false, length = 32)
    private String network;

    /** GHS equivalent the user expects to be credited (admin can override) */
    @Column(name = "expected_ghs_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal expectedGhsAmount;

    /** Actual GHS amount credited — set by admin on approval */
    @Column(name = "credited_ghs_amount", precision = 18, scale = 2)
    private BigDecimal creditedGhsAmount;

    /** Sender wallet address provided by the user */
    @Column(name = "sender_address", length = 256)
    private String senderAddress;

    /** URL / storage key for the uploaded screenshot */
    @Column(name = "screenshot_url", length = 512)
    private String screenshotUrl;

    /** Optional note from the user */
    @Column(name = "user_note", length = 1000)
    private String userNote;

    // ── Status ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private BinanceDepositStatus status = BinanceDepositStatus.PENDING;

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
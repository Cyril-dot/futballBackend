package com.speedbet.api.payment.flutterWave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of a Flutterwave v4 charge we initiated and are still
 * waiting on.
 *
 * This REPLACES the in-memory {@code pendingCharges} ConcurrentHashMap that
 * used to live in {@link AbstractFlutterwaveV4DepositController}. Reasons:
 *
 *   1. v4 has no "look up a charge by MY reference" endpoint — the only
 *      handle is Flutterwave's own charge id, returned once, in the original
 *      charge response. If we lose that id we can never check the charge
 *      again. An in-memory map loses it on every restart/deploy.
 *   2. The reference no longer encodes the userId (it can't — v4 caps
 *      `reference` at 42 chars), so the reference -> userId mapping ALSO
 *      only exists here. Losing it means an arriving webhook 400s and the
 *      customer's money is never credited.
 *   3. The background reconciler needs a work queue that survives restarts
 *      and is visible to every instance.
 *
 * Concurrency: {@code @Version} optimistic locking. Two instances racing on
 * the same row is harmless anyway — crediting is idempotent on {@code ref}
 * in WalletService (duplicate ref -> 409 -> treated as already-processed).
 */
@Entity
@Table(
        name = "flutterwave_v4_pending_charge",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_flw_v4_pending_reference", columnNames = "reference"),
        indexes = {
                @Index(name = "idx_flw_v4_pending_poll",
                        columnList = "provider_tag,status,next_poll_at"),
                @Index(name = "idx_flw_v4_pending_user", columnList = "user_id"),
                @Index(name = "idx_flw_v4_pending_charge_id", columnList = "charge_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class FlutterwaveV4PendingCharge {

    /**
     * Poll backoff, in seconds, indexed by attempt count. MoMo push prompts
     * are usually approved within ~30s, so poll tightly at first, then ease
     * off so a stuck charge doesn't hammer Flutterwave for the full TTL.
     * Attempts beyond the array reuse the last value.
     */
    private static final int[] BACKOFF_SECONDS = {8, 10, 15, 20, 30, 45, 60, 90, 120};

    @Id
    @GeneratedValue
    private UUID id;

    /** OUR reference / txRef, e.g. "GHV4-<32 hex>". Unique. */
    @Column(name = "reference", nullable = false, unique = true, length = 64)
    private String reference;

    /** Flutterwave's charge id — the ONLY key usable with GET /charges/{id}. */
    @Column(name = "charge_id", nullable = false, length = 128)
    private String chargeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Amount we asked for. The amount actually credited comes from Flutterwave. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    /** e.g. "flutterwave_gh_v4" — lets the reconciler route rows to the right controller. */
    @Column(name = "provider_tag", nullable = false, length = 64)
    private String providerTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FlutterwaveV4ChargeStatus status = FlutterwaveV4ChargeStatus.PENDING;

    /** Last raw status string Flutterwave returned, for debugging. */
    @Column(name = "last_provider_status", length = 64)
    private String lastProviderStatus;

    /** Last error seen while polling (truncated), for debugging. */
    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "poll_attempts", nullable = false)
    private int pollAttempts = 0;

    /** Which path actually credited it: "webhook", "verify", or "reconciler". */
    @Column(name = "credited_via", length = 24)
    private String creditedVia;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The reconciler only picks up PENDING rows whose next_poll_at has passed. */
    @Column(name = "next_poll_at", nullable = false)
    private Instant nextPollAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    private long version;

    public FlutterwaveV4PendingCharge(String reference, String chargeId, UUID userId,
                                      BigDecimal amount, String currency, String providerTag) {
        this.reference   = reference;
        this.chargeId    = chargeId;
        this.userId      = userId;
        this.amount      = amount;
        this.currency    = currency;
        this.providerTag = providerTag;
        this.status      = FlutterwaveV4ChargeStatus.PENDING;
        this.nextPollAt  = Instant.now().plusSeconds(BACKOFF_SECONDS[0]);
    }

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (createdAt == null)  createdAt = now;
        if (nextPollAt == null) nextPollAt = now.plusSeconds(BACKOFF_SECONDS[0]);
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Records a poll that didn't settle anything and pushes out the next attempt. */
    public void scheduleNextPoll(String providerStatus) {
        this.lastProviderStatus = providerStatus;
        this.pollAttempts++;
        var idx = Math.min(pollAttempts, BACKOFF_SECONDS.length - 1);
        this.nextPollAt = Instant.now().plusSeconds(BACKOFF_SECONDS[idx]);
    }

    public void settle(FlutterwaveV4ChargeStatus terminalStatus, String providerStatus, String via) {
        this.status             = terminalStatus;
        this.lastProviderStatus = providerStatus;
        this.creditedVia        = via;
        this.settledAt          = Instant.now();
        // Park next_poll_at far out so it can never be picked up again even
        // if some future code path flips the status back to PENDING.
        this.nextPollAt = Instant.now().plusSeconds(3_600 * 24 * 365);
    }

    public boolean isPending() {
        return status == FlutterwaveV4ChargeStatus.PENDING;
    }

    public boolean isExpiredBy(Instant cutoff) {
        return createdAt != null && createdAt.isBefore(cutoff);
    }
}
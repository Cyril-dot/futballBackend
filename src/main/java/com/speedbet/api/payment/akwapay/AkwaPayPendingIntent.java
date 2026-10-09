package com.speedbet.api.payment.akwapay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment intent this service created that has not yet settled.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THIS IS A TABLE AND NOT A ConcurrentHashMap
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * It used to be a map in the controller. That worked right up until the process
 * restarted, which on a PaaS is constantly — the deploy log for 2026-08-12
 * shows three restarts inside ninety seconds.
 *
 * Why that is fatal here specifically:
 *
 *   1. AkwaPay has NO "list my payment intents" endpoint. GET only works for
 *      one id at a time (/v1/payment_intents/{id}). So an intent we forget the
 *      id of is an intent we can never ask about again — there is no way to
 *      rediscover it.
 *   2. Settlement is webhook-only (2026-10-09). An earlier design also ran a
 *      polling sweep over these rows; it double-credited in production and
 *      was removed. The row's job today is to be the durable record the
 *      signed webhook settles against.
 *
 * Together: intent created → deploy 30 seconds later → map empties → webhook
 * never comes → customer's money is collected by AkwaPay and this service has
 * no record that it should ever be credited. Silent, permanent, and invisible
 * until the customer complains.
 *
 * A row survives the restart, so when the webhook lands the service still
 * knows whose payment it is and can settle it.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SAFE WITH MULTIPLE INSTANCES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Webhook delivery is at-least-once, and more than one instance may receive
 * the same event. That is harmless: WalletService.credit() dedupes on
 * `reference` and returns 409, which the handler catches and skips. The row
 * is then deleted by whichever delivery finishes first; the second delete is
 * a no-op.
 */
@Entity
@Table(name = "akwapay_pending_intents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AkwaPayPendingIntent {

    /**
     * Our own reference string — the primary key.
     *
     * Deliberately NOT a generated id. The reference is what AkwaPay echoes
     * back on the webhook and what WalletService dedupes credits on, so making
     * it the key means the same string identifies this payment in all three
     * places with no join and no possibility of drift.
     *
     * Format is sbdep_<32-hex-userId>_<8-hex-nonce>; see
     * AkwaPayController.buildReference().
     */
    @Id
    @Column(name = "reference", length = 64, nullable = false, updatable = false)
    private String reference;

    /** The pi_... public id. The ONLY handle AkwaPay accepts for a status read. */
    @Column(name = "intent_id", length = 64, nullable = false)
    private String intentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * GHS, already converted from pesewas at creation time.
     *
     * Stored rather than re-read from AkwaPay because settlement credits this
     * recorded value. Reading the amount back from the provider at settlement
     * time would let a provider-side mistake move a different sum than the
     * customer agreed to.
     */
    @Column(name = "amount_ghs", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountGhs;

    /** True for admin upgrade payments; false for wallet deposits. */
    @Column(name = "admin_upgrade", nullable = false)
    private boolean adminUpgrade;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Legacy: polling-sweep check count from before the sweep was removed (2026-10). No longer incremented. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Legacy: when the removed polling sweep last asked AkwaPay about this
     * intent. Kept for schema compatibility; nothing polls any more —
     * settlement arrives by webhook only.
     */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    public void markChecked(Instant when) {
        this.lastCheckedAt = when;
        this.attempts = this.attempts + 1;
    }
}
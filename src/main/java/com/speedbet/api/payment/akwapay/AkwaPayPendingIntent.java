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
 *   2. The webhook has never fired in production. Every credit so far has come
 *      from the sweep. The "the webhook will catch it" fallback that justified
 *      an in-memory map is not, in fact, operating.
 *
 * Together: intent created → deploy 30 seconds later → map empties → webhook
 * never comes → customer's money is collected by AkwaPay and this service has
 * no record that it should ever be credited. Silent, permanent, and invisible
 * until the customer complains.
 *
 * A row survives the restart. The sweep picks it up on the next cycle and
 * credits normally.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SAFE WITH MULTIPLE INSTANCES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * If two instances run the sweep concurrently, both may poll AkwaPay for the
 * same reference and both may see `succeeded`. That is harmless:
 * WalletService.credit() dedupes on `reference` and returns 409, which both
 * callers catch and skip. The row is then deleted by whichever finishes first;
 * the second delete is a no-op.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REFERENCE FORMAT — UPDATED 2026-09-09
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Format is now sbdep-<16-char random token> / sbadm-<16-char random token>,
 * hyphens only, 22 characters total. See AkwaPayController.buildReference().
 *
 * The reference NO LONGER encodes the userId. The old scheme embedded the
 * full 32-hex UUID directly in the string (sbdep_<32hex>_<8hex>, 47 chars)
 * and NaloPay rejected it with "Invalid reference" — MoMo references get
 * forwarded to the telco side, which has a much shorter limit than 47 chars.
 * userId/amount/adminUpgrade now live only in this row, keyed by reference.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY intentId IS NULLABLE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * The row is now persisted BEFORE the AkwaPay create-intent call, not after,
 * so that (userId, amount, adminUpgrade) survive a crash between "we decided
 * to charge this user" and "AkwaPay confirmed the intent id". intentId is
 * filled in immediately afterward via attachIntentId(). A row with a null
 * intentId means the AkwaPay call itself never completed — the sweep skips
 * these (nothing to poll yet); if one lingers, it means create-intent failed
 * or the process died mid-call, which is visible in logs as
 * "attachIntentId: no pending row" or a create-intent error for that ref.
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
     * Format: sbdep-<16-char random token> or sbadm-<16-char random token>;
     * see AkwaPayController.buildReference(). Opaque — carries no userId.
     */
    @Id
    @Column(name = "reference", length = 64, nullable = false, updatable = false)
    private String reference;

    /**
     * The pi_... public id. The ONLY handle AkwaPay accepts for a status read.
     *
     * Nullable: null between the row being recorded and the AkwaPay
     * create-intent call returning. See class javadoc "WHY intentId IS
     * NULLABLE" above.
     */
    @Column(name = "intent_id", length = 64)
    private String intentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * GHS, already converted from pesewas at creation time.
     *
     * Stored rather than re-read from AkwaPay because the sweep credits this
     * value directly. Reading the amount back from the provider at settlement
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

    /** How many sweep cycles have polled AkwaPay for this. Diagnostics only. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * When AkwaPay was last asked about this intent.
     *
     * This is what makes tiered polling possible: the sweep ticks every few
     * seconds, but each individual row is only polled when enough time has
     * passed for ITS age band. Without this column the choice is one flat
     * interval for everything — either fast and wasteful, or cheap and slow.
     *
     * Null means never polled, which is treated as "due now" (but only once
     * intentId is set — see AkwaPayController.isDue()).
     */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    public void markChecked(Instant when) {
        this.lastCheckedAt = when;
        this.attempts = this.attempts + 1;
    }
}
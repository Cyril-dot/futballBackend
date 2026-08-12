package com.speedbet.api.payment.akwapay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Store for in-flight AkwaPay intents.
 *
 * Rows live only while a payment is unsettled. Settled, failed, and abandoned
 * intents are deleted — this is a work queue, not an audit log. The permanent
 * record of a payment is the wallet transaction written by WalletService, which
 * is the thing you reconcile against.
 */
public interface AkwaPayPendingIntentRepository
        extends JpaRepository<AkwaPayPendingIntent, String> {

    /**
     * Everything created before the cutoff — i.e. old enough that the webhook
     * has had its head start and we should now go ask AkwaPay directly.
     *
     * Ordered oldest-first so the longest-waiting customer is credited first if
     * the sweep is ever rate-limited partway through.
     */
    List<AkwaPayPendingIntent> findByCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff);
}
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
     * Everything created before the cutoff — i.e. past the webhook's head start.
     *
     * Which of these actually get polled on any given tick is decided in the
     * controller by age band, using lastCheckedAt. Filtering that in Java rather
     * than SQL keeps the tiering rules in one readable place; the row count here
     * is bounded by concurrent in-flight payments, so it stays small.
     *
     * Ordered oldest-first so the longest-waiting customer is served first if
     * the sweep is ever cut short.
     */
    List<AkwaPayPendingIntent> findByCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff);
}
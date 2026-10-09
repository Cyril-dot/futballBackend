package com.speedbet.api.payment.akwapay;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store for in-flight AkwaPay intents.
 *
 * Rows live only while a payment is unsettled. A row is written when an
 * intent is created and deleted by the webhook handler the moment the
 * payment settles — this is a work queue, not an audit log. The permanent
 * record of a payment is the wallet transaction written by WalletService,
 * which is the thing you reconcile against.
 *
 * Settlement is webhook-only (2026-10-09): nothing polls this table to
 * credit wallets any more.
 */
public interface AkwaPayPendingIntentRepository
        extends JpaRepository<AkwaPayPendingIntent, String> {
}

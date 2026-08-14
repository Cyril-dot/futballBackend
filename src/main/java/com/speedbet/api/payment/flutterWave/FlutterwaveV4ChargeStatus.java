package com.speedbet.api.payment.flutterWave;

/**
 * Lifecycle of a locally-tracked Flutterwave v4 charge.
 *
 * PENDING  — charge created at Flutterwave, awaiting customer approval.
 *            The reconciler keeps polling GET /charges/{id} while in this state.
 * CREDITED — funds credited to the wallet (or found already credited).
 *            Terminal. Rows are kept for audit/idempotency, not deleted.
 * FAILED   — Flutterwave reported a terminal failure (declined/cancelled/etc).
 * EXPIRED  — never reached a terminal state within the reconcile TTL.
 *            Terminal for polling purposes; investigate manually if the
 *            customer insists they were debited.
 */
public enum FlutterwaveV4ChargeStatus {
    PENDING,
    CREDITED,
    FAILED,
    EXPIRED
}
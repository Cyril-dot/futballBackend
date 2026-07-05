package com.speedbet.api.payment.flutterWave;

/**
 * Thrown when Flutterwave responds with an explicit error (4xx/5xx or
 * {@code status: "error"} in the JSON body). Deliberately excluded from
 * the retry predicate in {@link AbstractFlutterwaveDepositController} —
 * only transient network failures (timeouts, connection resets) should
 * be retried, never a deliberate rejection from Flutterwave.
 */
public class FlutterwaveApiException extends RuntimeException {
    public FlutterwaveApiException(String message) {
        super(message);
    }
}
package com.speedbet.api.payment.akwapay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Tracks incomplete (non-completed) payment attempts per normalised phone
 * number. A "completed" attempt is one whose reference later settles via
 * webhook, sweep, or status-probe — at that point the tracker row is deleted
 * so the count resets to zero for that number.
 *
 * Once attempts reaches ≥ BLOCK_THRESHOLD (3), the phone is permanently
 * blocked and this row is replaced by a PhoneBlockEntry.
 */
@Entity
@Table(name = "akwapay_phone_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PhoneAttemptTracker {

    /** Normalised 10-digit local number, e.g. "0241234567". */
    @Id
    @Column(name = "full_number", nullable = false, length = 15)
    private String fullNumber;

    /** Running count of attempts that have not completed. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** When the first attempt in this run was recorded. */
    @Column(name = "first_attempt_at", nullable = false)
    private Instant firstAttemptAt;

    /** When the most recent attempt was recorded. */
    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    public void increment(Instant now) {
        this.attemptCount++;
        this.lastAttemptAt = now;
    }
}
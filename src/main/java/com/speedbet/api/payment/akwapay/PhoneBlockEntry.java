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
 * A blocked phone pattern.
 *
 * When a number accumulates ≥3 failed/incomplete payment attempts it is
 * permanently blocked. The stored pattern is the normalised 10-digit local
 * number's first 7 digits (prefix slice), so that suffix-swapping (changing
 * the last 3 digits) is caught by the same rule.
 *
 * e.g. phone "0241234567" → pattern "0241234" (first 7 digits).
 * A later attempt with "0241234999" matches the same pattern → blocked.
 *
 * The full normalised number is also stored for exact-match blocking
 * (avoids penalising unrelated numbers that share only the prefix).
 * Blocking logic is: block if fullNumber matches OR if pattern matches.
 */
@Entity
@Table(name = "akwapay_phone_blocks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PhoneBlockEntry {

    /** Exact normalised 10-digit local number, e.g. "0241234567". Primary key. */
    @Id
    @Column(name = "full_number", nullable = false, length = 15)
    private String fullNumber;

    /**
     * First 7 digits of the normalised local number — used to catch
     * suffix-swapping, e.g. "0241234" blocks "024123400", "024123401", …
     */
    @Column(name = "prefix_pattern", nullable = false, length = 10)
    private String prefixPattern;

    /** When this block was applied. */
    @Column(name = "blocked_at", nullable = false)
    private Instant blockedAt;

    /** Human-readable reason for auditing. */
    @Column(name = "reason", length = 255)
    private String reason;
}
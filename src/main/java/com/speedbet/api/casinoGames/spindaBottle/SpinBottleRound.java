package com.speedbet.api.casinoGames.spindaBottle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per settled spin. Written inside the same transaction as the
 * wallet debit/credit in SpinBottleService, so a round can never exist
 * without a matching wallet movement (or vice versa).
 */
@Entity
@Table(name = "spin_bottle_rounds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpinBottleRound {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpinBottleChoice choice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpinBottleOutcome outcome;

    @Column(nullable = false)
    private boolean won;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal stake;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal payout;

    // ── Provably-fair audit trail ───────────────────────────────────
    @Column(nullable = false)
    private String serverSeed;

    @Column(nullable = false)
    private String serverSeedHash;

    @Column(nullable = false)
    private String clientSeed;

    @Column(nullable = false)
    private long nonce;

    @Column(nullable = false)
    private String resultHash;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
package com.speedbet.api.referral;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "referrals")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Referral {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "link_id", nullable = false)
    private UUID linkId;

    @Column(name = "user_id", unique = true, nullable = false)
    private UUID userId;

    @Builder.Default
    @Column(name = "joined_at", updatable = false)
    private Instant joinedAt = Instant.now();

    @Builder.Default
    @Column(name = "lifetime_stake", nullable = false, precision = 19, scale = 4)
    private BigDecimal lifetimeStake = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "lifetime_commission", nullable = false, precision = 19, scale = 4)
    private BigDecimal lifetimeCommission = BigDecimal.ZERO;
}
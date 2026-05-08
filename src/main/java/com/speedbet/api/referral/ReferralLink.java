package com.speedbet.api.referral;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "referral_links")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReferralLink {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(unique = true, nullable = false)
    private String code;

    private String label;

    @Column(name = "commission_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal commissionPercent = BigDecimal.TEN;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;
}
package com.speedbet.api.crash;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_crash_schedule")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GameCrashSchedule {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_slug", nullable = false)
    private String gameSlug;

    @Column(name = "round_number", nullable = false)
    private Long roundNumber;

    @Column(name = "crash_at", nullable = false, precision = 8, scale = 2)
    private BigDecimal crashAt;

    @Column(nullable = false)
    private String tier;

    @Builder.Default
    @Column(name = "is_high_crash", nullable = false)
    private boolean highCrash = false;

    @Builder.Default
    @Column(name = "is_extreme_crash", nullable = false)
    private boolean extremeCrash = false;

    @Builder.Default
    @Column(name = "generated_by", nullable = false)
    private String generatedBy = "AI";

    @Builder.Default
    @Column(name = "generated_at", updatable = false, nullable = false)
    private Instant generatedAt = Instant.now();

    @Column(name = "played_at")
    private Instant playedAt;

    @Builder.Default
    @Column(name = "admin_notified")
    private boolean adminNotified = false;

    @Column(name = "override_reason")
    private String overrideReason;
}
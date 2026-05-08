package com.speedbet.api.odds;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "odds",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_odds_match_market_selection",
                        columnNames = {"match_id", "market", "selection"}
                )
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Odds {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    @Column(nullable = false)
    private String market;

    @Column(nullable = false)
    private String selection;

    @Column(nullable = false, precision = 9, scale = 3)
    private BigDecimal value;

    @Column(precision = 5, scale = 2)
    private BigDecimal line;

    @Column(precision = 5, scale = 2)
    private BigDecimal handicap;

    @Column(name = "captured_at")
    private Instant capturedAt = Instant.now();
}
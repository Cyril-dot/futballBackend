package com.speedbet.api.bet;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Bet {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal stake;

    // Resolved from user's country at bet placement time in BetService
    @Builder.Default
    @Column(nullable = false)
    private String currency = "GHS";

    @Column(name = "total_odds", precision = 12, scale = 4)
    private BigDecimal totalOdds;

    @Column(name = "potential_return", precision = 19, scale = 4)
    private BigDecimal potentialReturn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BetStatus status = BetStatus.PENDING;



    @Builder.Default
    @Column(name = "win_seen")
    private boolean winSeen = false;

    @Builder.Default
    @Column(name = "placed_at", updatable = false)
    private Instant placedAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "booking_code_used_id")
    private UUID bookingCodeUsedId;

    @OneToMany(mappedBy = "bet", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @Builder.Default
    private List<BetSelection> selections = new ArrayList<>();
}
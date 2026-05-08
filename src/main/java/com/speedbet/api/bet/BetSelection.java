package com.speedbet.api.bet;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "bet_selections")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BetSelection {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bet_id", nullable = false)
    @JsonIgnore
    private Bet bet;

    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    @Column(nullable = false)
    private String market;

    @Column(nullable = false)
    private String selection;

    @Column(name = "odds_locked", nullable = false, precision = 9, scale = 3)
    private BigDecimal oddsLocked;

    @Builder.Default
    @Column(nullable = false)
    private String result = "PENDING";

    @Column(name = "home_team")
    private String homeTeam;

    @Column(name = "away_team")
    private String awayTeam;
}
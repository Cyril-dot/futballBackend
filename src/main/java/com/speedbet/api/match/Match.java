package com.speedbet.api.match;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "matches")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Match {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchSource source = MatchSource.LIVESCORE;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "minute_played")
    private Integer minutePlayed;

    private String sport;
    private String league;

    @Column(name = "home_team")
    private String homeTeam;

    @Column(name = "away_team")
    private String awayTeam;

    @Column(name = "kickoff_at")
    private Instant kickoffAt;

    @Builder.Default
    @Column(nullable = false)
    private String status = "UPCOMING";

    @Column(name = "score_home")
    private Integer scoreHome;

    @Column(name = "score_away")
    private Integer scoreAway;

    @Column(name = "home_logo")
    private String homeLogo;

    @Column(name = "away_logo")
    private String awayLogo;

    @Column(name = "created_by_admin_id")
    private UUID createdByAdminId;

    @Column(name = "league_logo")
    private String leagueLogo;

    @Builder.Default
    @Column(name = "is_featured")
    private boolean featured = false;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Builder.Default
    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
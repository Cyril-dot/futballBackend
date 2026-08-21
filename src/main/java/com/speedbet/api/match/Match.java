package com.speedbet.api.match;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "matches",
        indexes = {
                @Index(name = "idx_matches_external_id",        columnList = "external_id"),
                @Index(name = "idx_matches_sport",              columnList = "sport"),
                @Index(name = "idx_matches_league",             columnList = "league"),
                @Index(name = "idx_matches_status",             columnList = "status"),
                @Index(name = "idx_matches_kickoff_at",         columnList = "kickoff_at"),
                @Index(name = "idx_matches_created_at",         columnList = "created_at"),
                @Index(name = "idx_matches_featured",           columnList = "is_featured"),
                @Index(name = "idx_matches_created_by_admin_id",columnList = "created_by_admin_id"),
                @Index(name = "idx_matches_settled_at",         columnList = "settled_at"),
                @Index(name = "idx_matches_source",             columnList = "source"),
                @Index(name = "idx_matches_sport_status",       columnList = "sport,status"),
                @Index(name = "idx_matches_status_kickoff",     columnList = "status,kickoff_at"),
                @Index(name = "idx_matches_featured_status",    columnList = "is_featured,status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchSource source = MatchSource.LIVESCORE;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "minute_played")
    private Integer minutePlayed;

    @Column(name = "sport")
    private String sport;

    @Transient
    private Sport sportEnum;

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

    public void setSport(String sport) {
        this.sport = sport;
        this.sportEnum = Sport.fromKey(sport);
    }

    public void setSportEnum(Sport sportEnum) {
        this.sportEnum = sportEnum;
        this.sport = sportEnum != null ? sportEnum.key() : null;
    }

    @PostLoad
    private void hydrateSportEnum() {
        this.sportEnum = Sport.fromKey(this.sport);
    }
}
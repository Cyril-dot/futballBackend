package com.speedbet.api.match;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;

@Data
public class AdminMatchRequest {

    public static final String DEFAULT_TEAM_LOGO =
            "https://www.svgrepo.com/show/47421/soccer.svg";

    public static final String DEFAULT_LEAGUE_LOGO =
            "https://www.svgrepo.com/show/47421/soccer.svg";

    @NotBlank(message = "homeTeam is required")
    private String homeTeam;

    @NotBlank(message = "awayTeam is required")
    private String awayTeam;

    private String league;
    private String sport;
    private String homeLogo;
    private String awayLogo;
    private String leagueLogo;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    private Instant kickoffAt;

    private String status;
    private boolean featured;

    @JsonSetter("kickoffAt")
    public void setKickoffAt(String raw) {
        if (raw == null || raw.isBlank()) {
            this.kickoffAt = null;
            return;
        }
        String normalized = raw.matches(".*[Z+\\-]\\d*$") ? raw : raw + "Z";
        this.kickoffAt = Instant.parse(normalized);
    }

    public void setKickoffAt(Instant kickoffAt) {
        this.kickoffAt = kickoffAt;
    }

    public String resolvedHomeLogo() {
        return (homeLogo != null && !homeLogo.isBlank()) ? homeLogo : DEFAULT_TEAM_LOGO;
    }

    public String resolvedAwayLogo() {
        return (awayLogo != null && !awayLogo.isBlank()) ? awayLogo : DEFAULT_TEAM_LOGO;
    }

    public String resolvedLeagueLogo() {
        return (leagueLogo != null && !leagueLogo.isBlank()) ? leagueLogo : DEFAULT_LEAGUE_LOGO;
    }
}
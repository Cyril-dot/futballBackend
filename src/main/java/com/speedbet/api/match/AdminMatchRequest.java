package com.speedbet.api.match;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;

@Data
public class AdminMatchRequest {

    // ── Default placeholder logos ─────────────────────────────────────────
    // Used whenever the admin omits a logo URL.
    // Replace these URLs with your own CDN paths if you host custom assets.

    /** Generic football crest — shown for any team without a real badge. */
    public static final String DEFAULT_TEAM_LOGO =
            "https://www.svgrepo.com/show/47421/soccer.svg";

    /** Generic league badge — same placeholder used for the league logo. */
    public static final String DEFAULT_LEAGUE_LOGO =
            "https://www.svgrepo.com/show/47421/soccer.svg";

    // ── Required fields ───────────────────────────────────────────────────

    @NotBlank(message = "homeTeam is required")
    private String homeTeam;

    @NotBlank(message = "awayTeam is required")
    private String awayTeam;

    // ── Optional fields ───────────────────────────────────────────────────

    /** League name, e.g. "Premier League". Optional. */
    private String league;

    /** Sport type. Defaults to "football" if omitted. */
    private String sport;

    /**
     * Team and league logo URLs.
     * If null or blank, the system fills in {@link #DEFAULT_TEAM_LOGO} /
     * {@link #DEFAULT_LEAGUE_LOGO} automatically — no artwork is ever missing
     * in the frontend.
     */
    private String homeLogo;
    private String awayLogo;
    private String leagueLogo;

    /**
     * Kickoff time in ISO-8601 UTC, e.g. "2025-06-01T15:00:00Z".
     * Defaults to now if omitted.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant kickoffAt;

    /**
     * Initial status. Allowed: SCHEDULED, LIVE, HALF_TIME, SECOND_HALF.
     * Defaults to SCHEDULED if omitted.
     */
    private String status;

    /** Pin this match to the featured carousel. Defaults to false. */
    private boolean featured;

    // ── Resolved accessors (used by AdminMatchService) ────────────────────

    /** Returns the home logo, falling back to the generic crest placeholder. */
    public String resolvedHomeLogo() {
        return (homeLogo != null && !homeLogo.isBlank()) ? homeLogo : DEFAULT_TEAM_LOGO;
    }

    /** Returns the away logo, falling back to the generic crest placeholder. */
    public String resolvedAwayLogo() {
        return (awayLogo != null && !awayLogo.isBlank()) ? awayLogo : DEFAULT_TEAM_LOGO;
    }

    /** Returns the league logo, falling back to the generic crest placeholder. */
    public String resolvedLeagueLogo() {
        return (leagueLogo != null && !leagueLogo.isBlank()) ? leagueLogo : DEFAULT_LEAGUE_LOGO;
    }
}
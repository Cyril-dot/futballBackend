package com.speedbet.api.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

/**
 * Request to fully automate a match's lifecycle: creation, kickoff, half-time
 * (with score), second half, and full-time (with final score) — each fired
 * automatically at the given timestamp with no further admin action needed.
 *
 * All timestamps must be strictly increasing:
 *   kickoffAt < halfTimeAt < secondHalfAt < finishedAt
 *
 * halfTimeAt / secondHalfAt / finishedAt are optional — omit trailing stages
 * to leave the match live indefinitely at that stage (e.g. omit finishedAt
 * to leave it in SECOND_HALF until an admin manually finishes it).
 */
@Data
public class AdminMatchScheduleRequest {

    @NotBlank
    private String homeTeam;

    @NotBlank
    private String awayTeam;

    private String league;
    private String sport;
    private String homeLogo;
    private String awayLogo;
    private String leagueLogo;
    private boolean featured;

    /** SCHEDULED → LIVE fires at this instant. Required. */
    @NotNull
    private Instant kickoffAt;

    /** LIVE → HALF_TIME fires at this instant. Null = never auto-transitions past LIVE. */
    private Instant halfTimeAt;

    /** HALF_TIME → SECOND_HALF fires at this instant. Null = never auto-transitions past HALF_TIME. */
    private Instant secondHalfAt;

    /** → FINISHED fires at this instant. Null = never auto-finishes. */
    private Instant finishedAt;

    /** Score to set immediately before the HALF_TIME transition. Both or neither. */
    private Integer halfTimeScoreHome;
    private Integer halfTimeScoreAway;

    /** Score to set immediately before the FINISHED transition. Both or neither. */
    private Integer finalScoreHome;
    private Integer finalScoreAway;
}
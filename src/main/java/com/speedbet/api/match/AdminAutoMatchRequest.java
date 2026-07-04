package com.speedbet.api.match;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

/**
 * Request to fully automate a match end-to-end.
 *
 * The admin sets ONLY the kickoff time and the final score. Everything else
 * is computed by AdminMatchScheduleService:
 *
 *   - Fixed timings: 22 min first half + 5 min break + 22 min second half
 *     → kickoffAt    : SCHEDULED → LIVE        (admin-supplied)
 *     → +22 min      : LIVE → HALF_TIME
 *     → +27 min      : HALF_TIME → SECOND_HALF
 *     → +49 min      : → FINISHED
 *
 *   - Goal timing: each goal (home and away, up to the totals given) is
 *     assigned a random minute inside its half — home/away goals scored in
 *     minutes 1-22 land in the first half, minutes 23-44 in the second half.
 *     The match score is updated via AdminMatchService.updateScore() at the
 *     real-world instant each randomly-chosen goal minute falls on, so odds
 *     refresh exactly as they would for a real live match.
 */
@Data
public class AdminAutoMatchRequest {

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

    /** Final full-time score. Goals are distributed at random minutes for the admin. */
    @NotNull @Min(0)
    private Integer finalScoreHome;

    @NotNull @Min(0)
    private Integer finalScoreAway;
}
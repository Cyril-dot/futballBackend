package com.speedbet.api.match;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminScoreUpdateRequest {

    @NotNull(message = "scoreHome is required")
    @Min(value = 0, message = "scoreHome cannot be negative")
    private Integer scoreHome;

    @NotNull(message = "scoreAway is required")
    @Min(value = 0, message = "scoreAway cannot be negative")
    private Integer scoreAway;

    /**
     * Current match minute (1–120+).
     * Optional — used to refresh live odds after a score change.
     */
    private Integer minutePlayed;
}
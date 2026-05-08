package com.speedbet.api.match;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminStatusUpdateRequest {

    /**
     * Target status. Allowed transitions:
     *   SCHEDULED → LIVE
     *   LIVE      → HALF_TIME | FINISHED
     *   HALF_TIME → SECOND_HALF
     *   SECOND_HALF → FINISHED
     *
     * FINISHED is terminal — no further transitions permitted.
     */
    @NotBlank(message = "status is required")
    private String status;
}
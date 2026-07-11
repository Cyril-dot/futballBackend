package com.speedbet.api.casinoGames.spindaBottle;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SpinBottlePlayRequest {

    @NotNull(message = "choice is required")
    private SpinBottleChoice choice;

    @NotNull(message = "stake is required")
    @DecimalMin(value = "0.01", message = "stake must be greater than zero")
    private BigDecimal stake;

    /**
     * Optional client-supplied seed for the provably-fair hash. If the
     * caller doesn't send one, the server generates it on their behalf so
     * older/simpler clients still work.
     */
    private String clientSeed;
}
package com.speedbet.api.casinoGames;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PlayRequest(
    @NotNull @DecimalMin(value = "1.0", message = "Minimum stake is 1.00") BigDecimal stake,
    @NotNull BetType betType,
    BigDecimal odds
) {}
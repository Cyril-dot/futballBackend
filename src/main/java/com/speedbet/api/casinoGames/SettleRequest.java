package com.speedbet.api.casinoGames;

import jakarta.validation.constraints.NotBlank;

public record SettleRequest(
    @NotBlank String roundId
) {}
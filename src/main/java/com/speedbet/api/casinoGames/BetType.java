package com.speedbet.api.casinoGames;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BetType {
    HOME, DRAW, AWAY, OVER, UNDER;

    @JsonCreator
    public static BetType from(String raw) {
        return BetType.valueOf(raw.trim().toUpperCase());
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
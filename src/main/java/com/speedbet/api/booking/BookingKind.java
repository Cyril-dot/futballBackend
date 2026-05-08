package com.speedbet.api.booking;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BookingKind {

    MIXED,
    WIN_ONLY,
    BTTS,
    OVER_UNDER,
    HANDICAP,
    CORRECT_SCORE,
    HT_FT,
    ONE_X_TWO;

    /**
     * Serialise back to the human-readable label used everywhere in the API
     * and stored in the database ("1X2", "MIXED", etc.).
     */
    @JsonValue
    public String toValue() {
        return this == ONE_X_TWO ? "1X2" : this.name();
    }

    /**
     * Parse any string the frontend or service layer might send.
     * Never throws — unknown values fall back to MIXED.
     */
    public static BookingKind from(String value) {
        if (value == null || value.isBlank()) return MIXED;
        return switch (value.trim().toUpperCase()) {
            case "1X2"           -> ONE_X_TWO;
            case "WIN_ONLY"      -> WIN_ONLY;
            case "BTTS"          -> BTTS;
            case "OVER_UNDER"    -> OVER_UNDER;
            case "HANDICAP"      -> HANDICAP;
            case "CORRECT_SCORE" -> CORRECT_SCORE;
            case "HT_FT"         -> HT_FT;
            default              -> MIXED;
        };
    }
}
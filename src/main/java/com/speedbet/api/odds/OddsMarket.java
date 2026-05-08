package com.speedbet.api.odds;

public enum OddsMarket {
    ONE_X_TWO, HOME_WIN, AWAY_WIN, OVER_UNDER,
    HANDICAP, CORRECT_SCORE, HT_FT, DOUBLE_CHANCE,
    BTTS, FTS, LIVE;

    // Map DB string values
    public static OddsMarket fromString(String s) {
        return switch (s.toUpperCase()) {
            case "1X2" -> ONE_X_TWO;
            default -> valueOf(s.toUpperCase());
        };
    }
}

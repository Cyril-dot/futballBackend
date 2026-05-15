package com.speedbet.api.match;

public enum Sport {
    FOOTBALL("football"),
    BASKETBALL("basketball"),
    BASEBALL("baseball"),
    AMERICAN_FOOTBALL("american_football"),
    MMA("mma"),
    TENNIS("tennis");

    private final String key;

    Sport(String key) { this.key = key; }

    public String key() { return key; }

    public static Sport fromKey(String key) {
        if (key == null) return null;
        for (Sport s : values()) {
            if (s.key.equalsIgnoreCase(key)) return s;
        }
        return null;
    }

    public static boolean isValid(String key) {
        return fromKey(key) != null;
    }
}
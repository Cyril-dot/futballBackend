package com.speedbet.api.match;

/**
 * Canonical match status values used across the platform.
 *
 * Stored as a plain String column on the Match entity (the default value in
 * the schema is "UPCOMING"), so every producer/consumer should reference
 * these constants rather than hard-coding strings.
 */
public final class MatchStatus {

    /** Default status — match is scheduled but has not kicked off. */
    public static final String UPCOMING  = "UPCOMING";
    public static final String LIVE      = "LIVE";
    public static final String FINISHED  = "FINISHED";
    public static final String POSTPONED = "POSTPONED";
    public static final String CANCELLED = "CANCELLED";

    private MatchStatus() {}

    /**
     * Normalise any upstream status string to one of our canonical values.
     * Tolerates null, empty, and unknown inputs — never throws.
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) return UPCOMING;
        String s = raw.trim().toLowerCase();

        // LIVE markers
        if (s.equals("live")
                || s.equals("in_play")
                || s.equals("inplay")
                || s.contains("half")
                || s.contains("min")
                || s.contains("1st")
                || s.contains("2nd")
                || s.equals("ht")
                || s.equals("paused")
                || s.equals("interrupted")) {
            return LIVE;
        }

        // FINISHED markers
        if (s.equals("ft")
                || s.equals("aet")
                || s.equals("pen")
                || s.equals("finished")
                || s.equals("ended")
                || s.equals("full_time")
                || s.equals("after_extra_time")
                || s.equals("after_penalties")
                || s.equals("awarded")) {
            return FINISHED;
        }

        // POSTPONED / CANCELLED
        if (s.equals("postponed") || s.equals("suspended") || s.equals("delayed")) {
            return POSTPONED;
        }
        if (s.equals("cancelled") || s.equals("canceled") || s.equals("abandoned")) {
            return CANCELLED;
        }

        // SCHEDULED / UPCOMING / NOTSTARTED — anything else means it hasn't kicked off
        return UPCOMING;
    }
}
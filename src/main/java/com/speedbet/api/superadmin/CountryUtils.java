package com.speedbet.api.superadmin;

/**
 * Normalises the free-text {@code User.country} column, and currency codes,
 * into stable bucket keys.
 * <p>
 * Buckets: GH (Ghana, cedis, MoMo/normal funding), NG (Nigeria, naira, mostly
 * bank transfer), OTHER, UNKNOWN.
 */
public final class CountryUtils {

    public static final String GH      = "GH";
    public static final String NG      = "NG";
    public static final String OTHER   = "OTHER";
    public static final String UNKNOWN = "UNKNOWN";

    private CountryUtils() {}

    /** Map any spelling of the country column onto a bucket key. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        String c = raw.trim().toUpperCase();
        if (c.equals("GH") || c.equals("GHA") || c.equals("233") || c.startsWith("GHANA")) return GH;
        if (c.equals("NG") || c.equals("NGA") || c.equals("234") || c.startsWith("NIGERIA")) return NG;
        return OTHER;
    }

    /**
     * Derive the country bucket from a currency code. This is the most reliable
     * signal available on rows that record money but not a user — the currency
     * is what the amount was actually denominated in.
     *
     * @return a bucket key, or {@code null} when the code is unrecognised so the
     *         caller can fall back to another source.
     */
    public static String fromCurrency(String currency) {
        if (currency == null || currency.isBlank()) return null;
        String c = currency.trim().toUpperCase();
        if (c.equals("GHS") || c.equals("GH\u20B5") || c.equals("CEDI") || c.equals("CEDIS")) return GH;
        if (c.equals("NGN") || c.equals("NAIRA")) return NG;
        return null;
    }

    /** Human-readable label for the dashboard. */
    public static String displayName(String bucket) {
        return switch (bucket == null ? UNKNOWN : bucket) {
            case GH -> "Ghana";
            case NG -> "Nigeria";
            case OTHER -> "Other countries";
            default -> "Unknown country";
        };
    }

    /** Currency for a bucket. Used only when no recorded currency is available. */
    public static String currencyOf(String bucket) {
        return switch (bucket == null ? UNKNOWN : bucket) {
            case GH -> "GHS";
            case NG -> "NGN";
            default -> "GHS";
        };
    }

    /** Currency symbol, for services that format strings directly. */
    public static String symbolOf(String bucket) {
        return NG.equals(bucket) ? "\u20A6" : "\u20B5"; // naira : cedi
    }
}
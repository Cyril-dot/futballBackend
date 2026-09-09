package com.speedbet.api.superadmin;

import java.math.BigDecimal;

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

    /**
     * Amounts strictly below this are treated as GH (MoMo) when a row's
     * country can't be resolved to GH or NG. Amounts at or above this are
     * treated as NG (bank transfer). Only used as a fallback — see
     * {@link #classifyForRevenue(String, BigDecimal)}.
     */
    private static final BigDecimal REVENUE_AMOUNT_THRESHOLD = new BigDecimal("30000");

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

    /**
     * Resolves a deposit/withdrawal row to GH or NG specifically for revenue
     * reporting (the super-admin dashboard's country-split totals).
     * <p>
     * Strict priority order — do not reorder:
     * <ol>
     *   <li>If the user's own country normalizes to GH or NG, that ALWAYS
     *       wins, regardless of the amount. A GH user depositing ₵35,000 via
     *       bank transfer is still counted as GH.</li>
     *   <li>Only when the user's country is OTHER or UNKNOWN do we fall back
     *       to the amount heuristic: amount &lt; 30,000 =&gt; GH,
     *       amount &gt;= 30,000 =&gt; NG. This applies uniformly across every
     *       deposit source (wallet transactions, bank-transfer submissions,
     *       etc.) — the bank-deposit flow does not get its own override.</li>
     * </ol>
     * This method deliberately only ever returns {@link #GH} or {@link #NG}.
     * It is NOT a general-purpose classifier — for any other report that
     * needs to preserve OTHER/UNKNOWN as real buckets, use
     * {@link #normalize(String)} instead.
     *
     * @param rawCountry the user's raw, un-normalized country string (may be null/blank)
     * @param amount     the transaction amount this row represents (may be null, treated as zero)
     */
    public static String classifyForRevenue(String rawCountry, BigDecimal amount) {
        String normalized = normalize(rawCountry);

        if (GH.equals(normalized) || NG.equals(normalized)) {
            return normalized;
        }

        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        return safeAmount.compareTo(REVENUE_AMOUNT_THRESHOLD) < 0 ? GH : NG;
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
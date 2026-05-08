package com.speedbet.api.bet;

import java.util.Map;

/**
 * Maps a user's country to the appropriate currency code.
 * Add more countries as needed.
 */
public final class CurrencyResolver {

    private static final Map<String, String> COUNTRY_TO_CURRENCY = Map.ofEntries(
        Map.entry("GH", "GHS"),   // Ghana
        Map.entry("NG", "NGN"),   // Nigeria
        Map.entry("KE", "KES"),   // Kenya
        Map.entry("ZA", "ZAR"),   // South Africa
        Map.entry("UG", "UGX"),   // Uganda
        Map.entry("TZ", "TZS"),   // Tanzania
        Map.entry("RW", "RWF"),   // Rwanda
        Map.entry("ET", "ETB"),   // Ethiopia
        Map.entry("CM", "XAF"),   // Cameroon
        Map.entry("CI", "XOF"),   // Ivory Coast
        Map.entry("SN", "XOF"),   // Senegal
        Map.entry("GB", "GBP"),   // UK
        Map.entry("US", "USD"),   // USA
        Map.entry("EU", "EUR")    // Europe fallback
    );

    private static final String DEFAULT_CURRENCY = "GHS";

    private CurrencyResolver() {}

    /**
     * Resolves the currency for a given country code (ISO 3166-1 alpha-2).
     * Falls back to GHS if the country is unknown or null.
     */
    public static String forCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return DEFAULT_CURRENCY;
        return COUNTRY_TO_CURRENCY.getOrDefault(countryCode.toUpperCase(), DEFAULT_CURRENCY);
    }
}
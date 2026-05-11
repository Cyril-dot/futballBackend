package com.speedbet.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * GeoController
 *
 * Detects the user's currency from their IP address using ip-api.com.
 * The resolved currency is used by the frontend to show localised stake
 * amounts and currency symbols across the Casino and Promotions pages.
 *
 * Supported West African + global currencies (frontend CurrencyConfig):
 *   NGN  – Nigeria         ₦
 *   GHS  – Ghana           GH₵
 *   XOF  – Senegal / CIV   CFA  (WAEMU zone)
 *   KES  – Kenya           KSh
 *   ZAR  – South Africa    R
 *   TZS  – Tanzania        TSh
 *   UGX  – Uganda          USh
 *   GBP  – United Kingdom  £
 *   EUR  – Eurozone        €
 *   USD  – United States   $
 *
 * Fallback: NGN (primary target market)
 */
@RestController
@RequestMapping("/api/geo")
public class GeoController {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Currencies that the frontend has a full config for. */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of(
            "NGN", "GHS", "XOF", "KES", "ZAR", "TZS", "UGX", "GBP", "EUR", "USD"
    );

    /** Default currency when detection fails or currency is unsupported. */
    private static final String FALLBACK_CURRENCY    = "NGN";
    private static final String FALLBACK_COUNTRY     = "NG";

    /**
     * ip-api.com free tier: 45 req/min from the same IP.
     * Fields requested are the minimum necessary to keep the response small.
     */
    private static final String IP_API_URL =
            "https://ip-api.com/json/%s?fields=status,message,currency,countryCode";

    /** Proxy/localhost IPs that should not be sent to ip-api. */
    private static final Set<String> LOCAL_IPS = Set.of(
            "127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost"
    );

    private final RestTemplate restTemplate = new RestTemplate();

    // ── Endpoint ──────────────────────────────────────────────────────────────

    /**
     * GET /api/geo/currency
     *
     * Response body:
     * {
     *   "currency":    "NGN",
     *   "countryCode": "NG",
     *   "source":      "ip-api" | "fallback" | "local"
     * }
     *
     * Cache-Control: public, max-age=3600  (1 h — currency rarely changes
     * mid-session; the browser/CDN can cache this per-IP edge response)
     */
    @GetMapping("/currency")
    public ResponseEntity<Map<String, String>> getCurrency(HttpServletRequest request) {

        String ip = extractClientIp(request);

        // ── Local / dev environment ───────────────────────────────────────────
        if (LOCAL_IPS.contains(ip)) {
            return buildResponse(FALLBACK_CURRENCY, FALLBACK_COUNTRY, "local");
        }

        // ── Live geo-lookup ───────────────────────────────────────────────────
        try {
            String url      = String.format(IP_API_URL, ip);
            Map<?, ?> resp  = restTemplate.getForObject(url, Map.class);

            if (resp == null) {
                return buildResponse(FALLBACK_CURRENCY, FALLBACK_COUNTRY, "fallback");
            }

            String status = (String) resp.get("status");

            if (!"success".equals(status)) {
                // ip-api returns {"status":"fail","message":"..."}
                // e.g. private-range IPs, reserved blocks
                return buildResponse(FALLBACK_CURRENCY, FALLBACK_COUNTRY, "fallback");
            }

            String rawCurrency   = (String) resp.get("currency");
            String rawCountry    = (String) resp.get("countryCode");
            String currency      = rawCurrency  != null ? rawCurrency.trim().toUpperCase()  : "";
            String countryCode   = rawCountry   != null ? rawCountry.trim().toUpperCase()   : FALLBACK_COUNTRY;

            // Use detected currency only if the frontend knows about it;
            // otherwise fall back so the UI doesn't break.
            if (!currency.isBlank() && SUPPORTED_CURRENCIES.contains(currency)) {
                return buildResponse(currency, countryCode, "ip-api");
            }

            // Country detected but currency unsupported → still return countryCode
            // so the frontend can at least log it, but use the fallback currency.
            return buildResponse(FALLBACK_CURRENCY, countryCode, "fallback");

        } catch (Exception ex) {
            // Network error, timeout, or unexpected shape — degrade gracefully.
            return buildResponse(FALLBACK_CURRENCY, FALLBACK_COUNTRY, "fallback");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the response entity with a 1-hour public cache header.
     * The short cache lets the frontend avoid hammering the geo endpoint
     * on every page load while still refreshing if the user travels.
     */
    private ResponseEntity<Map<String, String>> buildResponse(
            String currency, String countryCode, String source) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(Map.of(
                        "currency",    currency,
                        "countryCode", countryCode,
                        "source",      source
                ));
    }

    /**
     * Extracts the real client IP from common proxy headers.
     *
     * Priority order:
     *   1. X-Forwarded-For   (standard, set by most reverse proxies / CDNs)
     *   2. X-Real-IP         (Nginx default)
     *   3. Proxy-Client-IP   (Apache)
     *   4. WL-Proxy-Client-IP (WebLogic)
     *   5. HTTP_X_FORWARDED_FOR (some legacy proxies send this as a header)
     *   6. request.getRemoteAddr() (direct connection — no proxy)
     *
     * X-Forwarded-For can be a comma-separated list:
     *   client, proxy1, proxy2
     * We always take the FIRST element (the original client IP).
     *
     * ⚠  SECURITY NOTE: X-Forwarded-For can be spoofed by end users.
     * In production, configure your reverse proxy / load balancer to
     * OVERWRITE (not append to) this header so only trusted upstream
     * values are used. Never trust client-supplied forwarding headers
     * without that guarantee.
     */
    private String extractClientIp(HttpServletRequest request) {
        String[] proxyHeaders = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR"
        };

        for (String header : proxyHeaders) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                // Take the leftmost IP (original client) from a forwarding chain
                String candidate = value.split(",")[0].trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }

        return request.getRemoteAddr();
    }
}
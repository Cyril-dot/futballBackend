package com.speedbet.api.wallet.jetsms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controls which of the 5+ sites that share this backend are allowed to
 * trigger SMS sends.
 *
 * Single source of truth: ONE env var, SMS_ALLOWED_ORIGINS, containing a
 * comma-separated list of the site origins permitted to send SMS through
 * this backend, e.g.:
 *
 *   SMS_ALLOWED_ORIGINS=https://siteone.com,https://sitetwo.com,https://admin.speedbet.com
 *
 * To let a new site send SMS: add its origin to that one env var and
 * restart/redeploy the backend. Nothing else needs to change — no code
 * change, no per-site secret to generate.
 *
 * If the env var is unset or empty, every request is denied (fail closed).
 */
@Configuration
public class SmsAccessConfig {

    /**
     * Comma-separated list of allowed site origins, resolved from the
     * SMS_ALLOWED_ORIGINS env var on the host.
     *
     * The default after the colon (siteone.com, sitetwo.com) is a
     * local/dev-only fallback — used ONLY if the env var isn't set
     * anywhere. Real deployments should always set SMS_ALLOWED_ORIGINS
     * explicitly on the host and not rely on this default.
     */
    @Value("${SMS_ALLOWED_ORIGINS:https://siteone.com,https://sitetwo.com}")
    private String allowedOriginsRaw;

    /**
     * Human-readable label for whichever site/origin set is currently
     * approved — shown in logs so it's obvious which site a request was
     * matched against, without having to cross-reference the raw origin.
     */
    @Value("${SMS_ALLOWED_SITE_NAME:Unnamed Site}")
    private String allowedSiteName;

    public String getAllowedSiteName() {
        return allowedSiteName;
    }

    /** Parsed, trimmed, case-insensitive-safe set of allowed origins. */
    public Set<String> getAllowedOrigins() {
        if (allowedOriginsRaw == null || allowedOriginsRaw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .collect(Collectors.toUnmodifiableSet());
    }
}
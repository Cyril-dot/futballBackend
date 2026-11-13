package com.speedbet.api.superadmin;

import java.time.Instant;

/** Wire shapes for the AlphaPay settings (super-admin tab). The full secret key is never returned. */
public final class AlphaPaySettingsDtos {

    private AlphaPaySettingsDtos() {
    }

    public record SettingsResponse(
            boolean secretKeySet,
            String secretKeyMasked,
            String secretKeySource,
            String domain,
            String domainSource,
            String baseUrl,
            String baseUrlSource,
            String callbackUrl,
            String callbackUrlSource,
            String returnUrl,
            String returnUrlSource,
            Instant updatedAt,
            String updatedBy) {
    }

    /** All fields optional; a missing or blank field keeps the stored value. */
    public record UpdateSettingsRequest(
            String secretKey,
            String domain,
            String baseUrl,
            String callbackUrl,
            String returnUrl) {
    }
}

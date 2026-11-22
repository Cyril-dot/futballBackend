package com.speedbet.api.superadmin;

import java.time.Instant;

/** Wire shapes for the Reoon settings (super-admin tab). The full key is never returned. */
public final class ReoonSettingsDtos {

    private ReoonSettingsDtos() {
    }

    public record SettingsResponse(
            boolean apiKeySet,
            String apiKeyMasked,
            String apiKeySource,
            boolean enabled,
            Instant updatedAt,
            String updatedBy) {
    }

    /** All fields optional; a missing or blank field keeps the stored value (except enabled, which is set as given when present). */
    public record UpdateSettingsRequest(
            String apiKey,
            Boolean enabled) {
    }
}

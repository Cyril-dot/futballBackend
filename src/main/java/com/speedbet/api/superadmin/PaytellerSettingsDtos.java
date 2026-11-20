package com.speedbet.api.superadmin;

import java.time.Instant;

/** Wire shapes for the Payteller settings (super-admin tab). Full secrets are never returned. */
public final class PaytellerSettingsDtos {

    private PaytellerSettingsDtos() {
    }

    public record SettingsResponse(
            boolean apiKeySet,
            String apiKeyMasked,
            String apiKeySource,
            String merchantId,
            String merchantIdSource,
            String username,
            String usernameSource,
            boolean webhookSecretSet,
            String webhookSecretMasked,
            String webhookSecretSource,
            String baseUrl,
            String baseUrlSource,
            String returnUrl,
            String returnUrlSource,
            boolean enabled,
            Instant updatedAt,
            String updatedBy) {
    }

    /** All fields optional; a missing or blank field keeps the stored value (except enabled, which is set as given when present). */
    public record UpdateSettingsRequest(
            String merchantId,
            String apiKey,
            String username,
            String webhookSecret,
            String baseUrl,
            String returnUrl,
            Boolean enabled) {
    }
}

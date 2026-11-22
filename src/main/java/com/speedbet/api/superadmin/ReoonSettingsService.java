package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Reads and writes the Reoon settings singleton. Every read resolves
 * stored-value-else-env, so behaviour never changes until a super admin
 * deliberately saves a value in the panel. The API key is stored
 * AES-256-GCM encrypted and is never returned in full — only its mask.
 * {@code enabled} is stored when a row exists and falls back to the env
 * value otherwise; it defaults to false, so Reoon checks stay off until
 * a super admin turns them on, even when a key is present.
 */
@Service
@RequiredArgsConstructor
public class ReoonSettingsService {

    private final ReoonSettingsRepository settings;

    /** Env fallbacks — the same defaults as application.properties (app.reoon.*). */
    @Value("${app.reoon.api-key:}")
    private String envApiKey;

    @Value("${app.reoon.enabled:false}")
    private boolean envEnabled;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    /** One resolved snapshot: the key, where it came from, and the switch. */
    public record Resolved(
            String apiKey,
            String apiKeySource,
            boolean enabled,
            Instant updatedAt,
            String updatedBy) {
    }

    /** Resolve once (single row read): stored value wins, env is the fallback. */
    @Transactional(readOnly = true)
    public Resolved resolved() {
        ReoonSettings row = settings.findById(ReoonSettings.SINGLETON_ID).orElse(null);

        String storedApiKey = null;
        if (row != null && row.getApiKeyEncrypted() != null && !row.getApiKeyEncrypted().isBlank()) {
            storedApiKey = ReoonSettingsCrypto.decrypt(jwtSecret, row.getApiKeyEncrypted());
        }
        String apiKey = (storedApiKey != null && !storedApiKey.isBlank())
                ? storedApiKey
                : (envApiKey == null ? "" : envApiKey.trim());
        String apiKeySource = storedApiKey != null ? "stored"
                : (!apiKey.isBlank() ? "env" : "none");

        boolean enabled = row != null && row.getEnabled() != null ? row.getEnabled() : envEnabled;

        return new Resolved(
                apiKey, apiKeySource,
                enabled,
                row == null ? null : row.getUpdatedAt(),
                row == null ? null : row.getUpdatedBy());
    }

    /** Masked view for the panel — the full key is never returned. */
    @Transactional(readOnly = true)
    public ReoonSettingsDtos.SettingsResponse maskedView() {
        Resolved r = resolved();
        return new ReoonSettingsDtos.SettingsResponse(
                !r.apiKey().isBlank(),
                ReoonSettingsCrypto.mask(r.apiKey()),
                r.apiKeySource(),
                r.enabled(),
                r.updatedAt(), r.updatedBy());
    }

    /**
     * Partial update: only non-blank fields are stored, the rest keep
     * their stored value. A blank key field keeps the existing stored
     * key — there is no way to clear it from this endpoint. {@code enabled},
     * when present, is set as given. Throws 400 with a plain message on
     * any validation failure.
     */
    @Transactional
    public ReoonSettingsDtos.SettingsResponse update(
            ReoonSettingsDtos.UpdateSettingsRequest req, String updatedBy) {
        if (req == null) throw ApiException.badRequest("Nothing to save.");

        ReoonSettings row = settings.findById(ReoonSettings.SINGLETON_ID)
                .orElseGet(() -> ReoonSettings.builder().id(ReoonSettings.SINGLETON_ID).build());

        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            if (!ReoonSettingsCrypto.keyUsable(jwtSecret)) {
                throw ApiException.badRequest(
                        "Cannot save the Reoon key on this server — no usable JWT secret is configured. "
                                + "Set JWT_SECRET to a real secret and restart the backend, then save the key again.");
            }
            row.setApiKeyEncrypted(ReoonSettingsCrypto.encrypt(jwtSecret, req.apiKey().trim()));
        }
        if (req.enabled() != null) {
            row.setEnabled(req.enabled());
        }
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy);
        settings.save(row);
        return maskedView();
    }
}

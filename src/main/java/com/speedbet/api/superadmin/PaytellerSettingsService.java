package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Reads and writes the Payteller settings singleton. Every read resolves
 * stored-value-else-env, so behaviour never changes until a super admin
 * deliberately saves a value in the panel. The API key and webhook
 * signing secret are stored AES-256-GCM encrypted and are never
 * returned in full — only their masks. {@code enabled} is stored-only
 * and defaults to false: Payteller stays off until a super admin turns
 * it on, even when credentials are present.
 */
@Service
@RequiredArgsConstructor
public class PaytellerSettingsService {

    private static final Pattern MERCHANT_ID = Pattern.compile("^TTM-\\d{8}$");

    private final PaytellerSettingsRepository settings;

    /** Env fallbacks — the same defaults as application.properties (app.payteller.*). */
    @Value("${app.payteller.merchant-id:}")
    private String envMerchantId;

    @Value("${app.payteller.api-key:}")
    private String envApiKey;

    @Value("${app.payteller.username:}")
    private String envUsername;

    @Value("${app.payteller.webhook-secret:}")
    private String envWebhookSecret;

    @Value("${app.payteller.base-url:https://paytelller.com/api/v1}")
    private String envBaseUrl;

    @Value("${app.payteller.return-url:https://megasportst.com/gh/recharge}")
    private String envReturnUrl;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    /** One resolved snapshot: every field plus where it came from. */
    public record Resolved(
            String apiKey,
            String apiKeySource,
            String merchantId,
            String merchantIdSource,
            String username,
            String usernameSource,
            String webhookSecret,
            String webhookSecretSource,
            String baseUrl,
            String baseUrlSource,
            String returnUrl,
            String returnUrlSource,
            boolean enabled,
            Instant updatedAt,
            String updatedBy) {
    }

    /** Resolve every field once (single row read): stored value wins, env is the fallback. */
    @Transactional(readOnly = true)
    public Resolved resolved() {
        PaytellerSettings row = settings.findById(PaytellerSettings.SINGLETON_ID).orElse(null);

        String storedApiKey = null;
        if (row != null && row.getApiKeyEncrypted() != null && !row.getApiKeyEncrypted().isBlank()) {
            storedApiKey = PaytellerSettingsCrypto.decrypt(jwtSecret, row.getApiKeyEncrypted());
        }
        String apiKey = (storedApiKey != null && !storedApiKey.isBlank())
                ? storedApiKey
                : (envApiKey == null ? "" : envApiKey.trim());
        String apiKeySource = storedApiKey != null ? "stored"
                : (!apiKey.isBlank() ? "env" : "none");

        String storedWebhookSecret = null;
        if (row != null && row.getWebhookSecretEncrypted() != null && !row.getWebhookSecretEncrypted().isBlank()) {
            storedWebhookSecret = PaytellerSettingsCrypto.decrypt(jwtSecret, row.getWebhookSecretEncrypted());
        }
        String webhookSecret = (storedWebhookSecret != null && !storedWebhookSecret.isBlank())
                ? storedWebhookSecret
                : (envWebhookSecret == null ? "" : envWebhookSecret.trim());
        String webhookSecretSource = storedWebhookSecret != null ? "stored"
                : (!webhookSecret.isBlank() ? "env" : "none");

        String merchantId = pick(row == null ? null : row.getMerchantId(), envMerchantId);
        String username = pick(row == null ? null : row.getUsername(), envUsername);
        String baseUrl = pick(row == null ? null : row.getBaseUrl(), envBaseUrl);
        String returnUrl = pick(row == null ? null : row.getReturnUrl(), envReturnUrl);
        boolean enabled = row != null && Boolean.TRUE.equals(row.getEnabled());

        return new Resolved(
                apiKey, apiKeySource,
                merchantId, sourceOrNone(row == null ? null : row.getMerchantId(), merchantId),
                username, sourceOrNone(row == null ? null : row.getUsername(), username),
                webhookSecret, webhookSecretSource,
                baseUrl, source(row == null ? null : row.getBaseUrl()),
                returnUrl, source(row == null ? null : row.getReturnUrl()),
                enabled,
                row == null ? null : row.getUpdatedAt(),
                row == null ? null : row.getUpdatedBy());
    }

    /** Masked view for the panel — full secrets are never returned. Merchant ID and username are identifiers, returned in full. */
    @Transactional(readOnly = true)
    public PaytellerSettingsDtos.SettingsResponse maskedView() {
        Resolved r = resolved();
        return new PaytellerSettingsDtos.SettingsResponse(
                !r.apiKey().isBlank(),
                PaytellerSettingsCrypto.mask(r.apiKey()),
                r.apiKeySource(),
                r.merchantId(), r.merchantIdSource(),
                r.username(), r.usernameSource(),
                !r.webhookSecret().isBlank(),
                PaytellerSettingsCrypto.mask(r.webhookSecret()),
                r.webhookSecretSource(),
                r.baseUrl(), r.baseUrlSource(),
                r.returnUrl(), r.returnUrlSource(),
                r.enabled(),
                r.updatedAt(), r.updatedBy());
    }

    /**
     * Partial update: only non-blank fields are stored, the rest keep
     * their stored value. A blank secret field keeps the existing stored
     * secret — there is no way to clear it from this endpoint. {@code enabled},
     * when present, is set as given. Throws 400 with a plain message on
     * any validation failure.
     */
    @Transactional
    public PaytellerSettingsDtos.SettingsResponse update(
            PaytellerSettingsDtos.UpdateSettingsRequest req, String updatedBy) {
        if (req == null) throw ApiException.badRequest("Nothing to save.");

        PaytellerSettings row = settings.findById(PaytellerSettings.SINGLETON_ID)
                .orElseGet(() -> PaytellerSettings.builder().id(PaytellerSettings.SINGLETON_ID).build());

        if (req.merchantId() != null && !req.merchantId().isBlank()) {
            String merchantId = req.merchantId().trim();
            if (!MERCHANT_ID.matcher(merchantId).matches()) {
                throw ApiException.badRequest(
                        "Merchant ID must look like TTM-00984532 — TTM- followed by eight digits.");
            }
            row.setMerchantId(merchantId);
        }
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            if (!PaytellerSettingsCrypto.keyUsable(jwtSecret)) {
                throw ApiException.badRequest(
                        "Cannot save the Payteller key on this server — no usable JWT secret is configured. "
                                + "Set JWT_SECRET to a real secret and restart the backend, then save the key again.");
            }
            row.setApiKeyEncrypted(PaytellerSettingsCrypto.encrypt(jwtSecret, req.apiKey().trim()));
        }
        if (req.username() != null && !req.username().isBlank()) {
            row.setUsername(req.username().trim());
        }
        if (req.webhookSecret() != null && !req.webhookSecret().isBlank()) {
            if (!PaytellerSettingsCrypto.keyUsable(jwtSecret)) {
                throw ApiException.badRequest(
                        "Cannot save the Payteller webhook secret on this server — no usable JWT secret is configured. "
                                + "Set JWT_SECRET to a real secret and restart the backend, then save the secret again.");
            }
            row.setWebhookSecretEncrypted(PaytellerSettingsCrypto.encrypt(jwtSecret, req.webhookSecret().trim()));
        }
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            row.setBaseUrl(normalizeHttpsUrl(req.baseUrl(), "Base URL"));
        }
        if (req.returnUrl() != null && !req.returnUrl().isBlank()) {
            row.setReturnUrl(normalizeHttpsUrl(req.returnUrl(), "Return URL"));
        }
        if (req.enabled() != null) {
            row.setEnabled(req.enabled());
        }
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy);
        settings.save(row);
        return maskedView();
    }

    private String pick(String stored, String envFallback) {
        return (stored != null && !stored.isBlank()) ? stored : envFallback;
    }

    private String source(String stored) {
        return (stored != null && !stored.isBlank()) ? "stored" : "env";
    }

    /** "stored" when a value was saved in the panel, "env" when the env fallback is in use, "none" when nothing is set. */
    private String sourceOrNone(String stored, String resolved) {
        if (stored != null && !stored.isBlank()) return "stored";
        return (resolved != null && !resolved.isBlank()) ? "env" : "none";
    }

    /** Must be an https URL with a host. Trims whitespace; base URL loses trailing slashes. */
    private String normalizeHttpsUrl(String raw, String field) {
        String url = raw.trim();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest(field + " must be a valid https URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.badRequest(field + " must be a valid https URL.");
        }
        if ("Base URL".equals(field)) {
            url = url.replaceAll("/+$", "");
        }
        return url;
    }
}

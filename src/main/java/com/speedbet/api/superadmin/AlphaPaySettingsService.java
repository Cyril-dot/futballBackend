package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads and writes the AlphaPay settings singleton. Every read resolves
 * stored-value-else-env, so behaviour never changes until a super admin
 * deliberately saves a value in the panel. The secret key is stored
 * AES-256-GCM encrypted and is never returned in full — only its mask.
 */
@Service
@RequiredArgsConstructor
public class AlphaPaySettingsService {

    private static final Pattern HOSTNAME = Pattern.compile(
            "^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$");

    private final AlphaPaySettingsRepository settings;

    /** Env fallbacks — the same defaults as application.properties (app.alphapay.*). */
    @Value("${app.alphapay.secret-key:}")
    private String envSecretKey;

    @Value("${app.alphapay.base-url:https://api.edibytes.online/api}")
    private String envBaseUrl;

    @Value("${app.alphapay.domain:luckysttake.site}")
    private String envDomain;

    @Value("${app.alphapay.callback-url:https://futballbackend-production-b06a.up.railway.app/api/webhooks/alphapay}")
    private String envCallbackUrl;

    @Value("${app.alphapay.return-url:https://www.luckysttake.site/wallet/deposit/complete}")
    private String envReturnUrl;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    /** One resolved snapshot: every field plus where it came from. */
    public record Resolved(
            String secretKey,
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

    /** Resolve every field once (single row read): stored value wins, env is the fallback. */
    @Transactional(readOnly = true)
    public Resolved resolved() {
        AlphaPaySettings row = settings.findById(AlphaPaySettings.SINGLETON_ID).orElse(null);

        String storedSecret = null;
        if (row != null && row.getSecretKeyEncrypted() != null && !row.getSecretKeyEncrypted().isBlank()) {
            storedSecret = AlphaPaySettingsCrypto.decrypt(jwtSecret, row.getSecretKeyEncrypted());
        }
        String secretKey = (storedSecret != null && !storedSecret.isBlank())
                ? storedSecret
                : (envSecretKey == null ? "" : envSecretKey.trim());
        String secretKeySource = storedSecret != null ? "stored"
                : (!secretKey.isBlank() ? "env" : "none");

        String domain = pick(row == null ? null : row.getDomain(), envDomain);
        String baseUrl = pick(row == null ? null : row.getBaseUrl(), envBaseUrl);
        String callbackUrl = pick(row == null ? null : row.getCallbackUrl(), envCallbackUrl);
        String returnUrl = pick(row == null ? null : row.getReturnUrl(), envReturnUrl);

        return new Resolved(
                secretKey, secretKeySource,
                domain, source(row == null ? null : row.getDomain()),
                baseUrl, source(row == null ? null : row.getBaseUrl()),
                callbackUrl, source(row == null ? null : row.getCallbackUrl()),
                returnUrl, source(row == null ? null : row.getReturnUrl()),
                row == null ? null : row.getUpdatedAt(),
                row == null ? null : row.getUpdatedBy());
    }

    /** Masked view for the panel — the full secret key is never returned. */
    @Transactional(readOnly = true)
    public AlphaPaySettingsDtos.SettingsResponse maskedView() {
        Resolved r = resolved();
        return new AlphaPaySettingsDtos.SettingsResponse(
                !r.secretKey().isBlank(),
                AlphaPaySettingsCrypto.mask(r.secretKey()),
                r.secretKeySource(),
                r.domain(), r.domainSource(),
                r.baseUrl(), r.baseUrlSource(),
                r.callbackUrl(), r.callbackUrlSource(),
                r.returnUrl(), r.returnUrlSource(),
                r.updatedAt(), r.updatedBy());
    }

    /**
     * Partial update: only non-blank fields are stored, the rest keep
     * their stored value. A blank secretKey keeps the existing stored
     * key — there is no way to clear it from this endpoint.
     * Throws 400 with a plain message on any validation failure.
     */
    @Transactional
    public AlphaPaySettingsDtos.SettingsResponse update(
            AlphaPaySettingsDtos.UpdateSettingsRequest req, String updatedBy) {
        if (req == null) throw ApiException.badRequest("Nothing to save.");

        AlphaPaySettings row = settings.findById(AlphaPaySettings.SINGLETON_ID)
                .orElseGet(() -> AlphaPaySettings.builder().id(AlphaPaySettings.SINGLETON_ID).build());

        if (req.secretKey() != null && !req.secretKey().isBlank()) {
            if (!AlphaPaySettingsCrypto.keyUsable(jwtSecret)) {
                throw ApiException.badRequest(
                        "Cannot save the AlphaPay key on this server — no usable JWT secret is configured. "
                                + "Set JWT_SECRET to a real secret and restart the backend, then save the key again.");
            }
            row.setSecretKeyEncrypted(AlphaPaySettingsCrypto.encrypt(jwtSecret, req.secretKey().trim()));
        }
        if (req.domain() != null && !req.domain().isBlank()) {
            row.setDomain(normalizeDomain(req.domain()));
        }
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            row.setBaseUrl(normalizeHttpsUrl(req.baseUrl(), "Base URL"));
        }
        if (req.callbackUrl() != null && !req.callbackUrl().isBlank()) {
            row.setCallbackUrl(normalizeHttpsUrl(req.callbackUrl(), "Callback URL"));
        }
        if (req.returnUrl() != null && !req.returnUrl().isBlank()) {
            row.setReturnUrl(normalizeHttpsUrl(req.returnUrl(), "Return URL"));
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

    /** Bare hostname only — no scheme, path, port, or slash. Lowercased. */
    private String normalizeDomain(String raw) {
        String domain = raw.trim().toLowerCase(Locale.ROOT);
        if (domain.contains("://") || domain.contains("/") || domain.contains(":")
                || !HOSTNAME.matcher(domain).matches()) {
            throw ApiException.badRequest(
                    "Domain must be a bare hostname, e.g. playbett.xyz — no scheme, path, or port.");
        }
        return domain;
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

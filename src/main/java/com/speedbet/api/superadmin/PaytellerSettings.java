package com.speedbet.api.superadmin;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton settings row for Payteller (one row, fixed id — mirrors the
 * AlphaPaySettings / PlatformSetting pattern). The API key and the
 * webhook signing secret are stored AES-256-GCM encrypted (see
 * PaytellerSettingsCrypto); merchant ID, username and URLs are plain.
 * A missing row — or a blank field — reads exactly as before: the
 * Railway env values (app.payteller.*) are the fallback. {@code enabled}
 * is stored-only and defaults to false — Payteller stays off until a
 * super admin turns it on in the panel.
 */
@Entity
@Table(name = "payteller_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaytellerSettings {

    /** The one and only row's id. */
    public static final UUID SINGLETON_ID = UUID.fromString("44444444-5555-6666-7777-888888888888");

    @Id
    private UUID id;

    /** Payteller Merchant ID, e.g. TTM-00984532. Plain — it is an identifier, not a secret. */
    @Column(name = "merchant_id")
    private String merchantId;

    /** Base64(iv + ciphertext) of the Payteller API key, AES-256-GCM. Null when never saved. */
    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    /** Payteller username. Plain — it is an identifier, not a secret. */
    @Column(name = "username")
    private String username;

    /** Base64(iv + ciphertext) of the webhook signing secret, AES-256-GCM. Null when never saved. */
    @Column(name = "webhook_secret_encrypted", columnDefinition = "TEXT")
    private String webhookSecretEncrypted;

    /** Payteller API base URL, e.g. https://paytelller.com/api/v1. */
    @Column(name = "base_url")
    private String baseUrl;

    /** Browser return URL after the hosted checkout flow. */
    @Column(name = "return_url")
    private String returnUrl;

    /** Master switch: Payteller deposits are offered only when true. Default false. */
    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Email of the super admin who last saved the settings. */
    @Column(name = "updated_by")
    private String updatedBy;
}

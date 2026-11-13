package com.speedbet.api.superadmin;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton settings row for AlphaPay (one row, fixed id — mirrors the
 * PlatformSetting pattern). The secret key is stored AES-256-GCM
 * encrypted (see AlphaPaySettingsCrypto); the other fields are plain.
 * A missing row — or a blank field — reads exactly as before: the
 * Railway env values (app.alphapay.*) are the fallback.
 */
@Entity
@Table(name = "alphapay_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AlphaPaySettings {

    /** The one and only row's id. */
    public static final UUID SINGLETON_ID = UUID.fromString("33333333-4444-5555-6666-777777777777");

    @Id
    private UUID id;

    /** Base64(iv + ciphertext) of the AlphaPay secret key, AES-256-GCM. Null when never saved. */
    @Column(name = "secret_key_encrypted", columnDefinition = "TEXT")
    private String secretKeyEncrypted;

    /** Bare hostname whitelisted on AlphaPay's Developers page, e.g. playbett.xyz. */
    @Column(name = "domain")
    private String domain;

    /** AlphaPay API base URL, e.g. https://api.edibytes.online/api. */
    @Column(name = "base_url")
    private String baseUrl;

    /** Webhook URL AlphaPay POSTs payment events to. */
    @Column(name = "callback_url")
    private String callbackUrl;

    /** Browser return URL after the hosted checkout flow. */
    @Column(name = "return_url")
    private String returnUrl;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Email of the super admin who last saved the settings. */
    @Column(name = "updated_by")
    private String updatedBy;
}

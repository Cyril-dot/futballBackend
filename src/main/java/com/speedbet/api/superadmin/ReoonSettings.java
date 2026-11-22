package com.speedbet.api.superadmin;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton settings row for Reoon Email Verifier (one row, fixed id —
 * mirrors the AlphaPaySettings / PaytellerSettings pattern). The API key
 * is stored AES-256-GCM encrypted (see ReoonSettingsCrypto). A missing
 * row — or a blank key — reads exactly as before: the Railway env values
 * (app.reoon.*) are the fallback. {@code enabled} defaults to false —
 * Reoon checks stay off until a super admin turns them on in the panel,
 * even when a key is present. MSport only: no other skin calls the
 * Reoon check, so this setting changes nothing for them.
 */
@Entity
@Table(name = "reoon_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReoonSettings {

    /** The one and only row's id. */
    public static final UUID SINGLETON_ID = UUID.fromString("55555555-6666-7777-8888-999999999999");

    @Id
    private UUID id;

    /** Base64(iv + ciphertext) of the Reoon API key, AES-256-GCM. Null when never saved. */
    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    /** Master switch: Reoon checks run only when true. Default false. */
    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Email of the super admin who last saved the settings. */
    @Column(name = "updated_by")
    private String updatedBy;
}

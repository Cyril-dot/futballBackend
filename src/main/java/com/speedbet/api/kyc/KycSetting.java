package com.speedbet.api.kyc;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton settings row for the RoyalBet KYC sub-service (one row, fixed
 * id). Currently a single switch: whether users may submit KYC at all.
 * DEFAULT IS CLOSED — the row is created lazily with submissionsOpen =
 * false, and a missing row also reads as closed, so submissions stay off
 * until a super admin deliberately opens them from the dashboard.
 */
@Entity
@Table(name = "kyc_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KycSetting {

    /** The one and only row's id. */
    public static final UUID SINGLETON_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Id
    private UUID id;

    @Column(name = "submissions_open", nullable = false)
    @Builder.Default
    private boolean submissionsOpen = false;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Email of the super admin who last flipped the switch. */
    @Column(name = "updated_by")
    private String updatedBy;
}

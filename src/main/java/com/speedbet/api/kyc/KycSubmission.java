package com.speedbet.api.kyc;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's identity-verification (KYC) submission for RoyalBet.
 *
 * Backend-based replacement for the old browser-only flow: the submission
 * is stored server-side so the super admin can review submissions from
 * ANY user's device. The ID image itself is NOT stored here — the
 * frontend hosts it on ImgBB first and sends only the link (imageUrl),
 * keeping this table light.
 */
@Entity
@Table(name = "kyc_submissions", indexes = {
        @Index(name = "idx_kyc_submissions_user", columnList = "user_id"),
        @Index(name = "idx_kyc_submissions_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KycSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Snapshot so the admin queue is readable without joining users. */
    @Column(nullable = false)
    private String email;

    @Column(name = "user_name")
    private String userName;

    private String country;

    /** Full legal name as printed on the ID. */
    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** NATIONAL_ID | PASSPORT | DRIVERS_LICENCE | VOTER_ID (free-form string). */
    @Column(name = "id_type", nullable = false, length = 32)
    private String idType;

    @Column(name = "id_number", nullable = false)
    private String idNumber;

    /** ImgBB-hosted ID image URL — a link, never the image bytes. */
    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private KycStatus status = KycStatus.PENDING;

    /** Super admin's rejection reason — shown to the user. */
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "submitted_at", nullable = false)
    @Builder.Default
    private Instant submittedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;
}

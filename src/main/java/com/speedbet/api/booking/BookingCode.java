package com.speedbet.api.booking;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "booking_codes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingCode {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String code;

    @Column(name = "creator_admin_id", nullable = false)
    private UUID creatorAdminId;

    private String label;

    /**
     * Stored as the human label ("1X2", "MIXED", …) via BookingKindConverter.
     * Works with both a plain varchar column and a Postgres named-enum column
     * because the converter operates at the JDBC String level.
     */
    @Column(nullable = false)
    @Builder.Default
    private String kind = "MIXED";

    /**
     * Indicates how this booking code was created:
     *   "STANDARD"   — original create() path, no fixture-source restriction.
     *   "ADMIN_ONLY" — all fixtures are ADMIN_CREATED and owned by the creator admin.
     *   "MIXED"      — admin's own fixtures combined with external-feed fixtures.
     *
     * Defaults to "STANDARD" so existing rows and the original create() path
     * require no migration or code change.
     */
    @Column(name = "booking_type", nullable = false, length = 20)
    @Builder.Default
    private String bookingType = "STANDARD";

    @Column(nullable = false)
    @Builder.Default
    private int version = 1;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "GHS";

    @Column(precision = 19, scale = 4)
    private BigDecimal stake;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> selections;

    @Column(name = "total_odds", precision = 12, scale = 4)
    private BigDecimal totalOdds;

    @Column(name = "potential_payout", precision = 19, scale = 4)
    private BigDecimal potentialPayout;

    @Column(nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "redemption_count", nullable = false)
    @Builder.Default
    private int redemptionCount = 0;

    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
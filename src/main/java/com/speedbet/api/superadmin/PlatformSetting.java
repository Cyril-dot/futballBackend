package com.speedbet.api.superadmin;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Singleton settings row for the platform amounts the super admin sets
 * (one row, fixed id — mirrors the KycSetting pattern). Defaults match
 * today's hardcoded values, so a missing row reads exactly as before:
 * min deposit GH₵300, min stake GH₵1, max win GH₵1,200,000 / ₦120,000,000,
 * withdrawal unlock GH₵600 in completed deposits.
 */
@Entity
@Table(name = "platform_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformSetting {

    /** The one and only row's id. */
    public static final UUID SINGLETON_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    @Id
    private UUID id;

    @Column(name = "min_deposit_ghs", nullable = false)
    @Builder.Default
    private BigDecimal minDepositGhs = new BigDecimal("300");

    @Column(name = "min_stake_ghs", nullable = false)
    @Builder.Default
    private BigDecimal minStakeGhs = BigDecimal.ONE;

    @Column(name = "max_win_ghs", nullable = false)
    @Builder.Default
    private BigDecimal maxWinGhs = new BigDecimal("1200000");

    @Column(name = "max_win_ngn", nullable = false)
    @Builder.Default
    private BigDecimal maxWinNgn = new BigDecimal("120000000");

    @Column(name = "withdrawal_unlock_ghs", nullable = false)
    @Builder.Default
    private BigDecimal withdrawalUnlockGhs = new BigDecimal("600");

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Email of the super admin who last saved the amounts. */
    @Column(name = "updated_by")
    private String updatedBy;
}

package com.speedbet.api.casinoGames;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton settings row for Mines (one row, fixed id — same pattern as
 * KycSetting). A missing row reads as AUTOMATIC, so the game stays random
 * until a super admin deliberately switches it to MANUAL and picks fixed
 * bomb tiles. ddl-auto=update creates this table on deploy.
 */
@Entity
@Table(name = "mines_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MinesSetting {

    /** The one and only row's id. */
    public static final UUID SINGLETON_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    @Builder.Default
    private MinesMode mode = MinesMode.AUTOMATIC;

    /** Comma-separated sorted tile indices, e.g. "3,7,12". Empty when AUTOMATIC. */
    @Column(name = "bomb_positions")
    private String bombPositions;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Email of the super admin who last saved the settings. */
    @Column(name = "updated_by")
    private String updatedBy;
}

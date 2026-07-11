package com.speedbet.api.casinoGames.spindaBottle;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpinBottleHistoryEntry {
    private UUID roundId;
    private SpinBottleChoice choice;
    private SpinBottleOutcome outcome;
    private boolean won;
    private BigDecimal stake;
    private BigDecimal payout;
    private Instant createdAt;
}
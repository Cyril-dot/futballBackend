package com.speedbet.api.casinoGames.spindaBottle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpinBottleFairnessDto {
    private String serverSeed;
    private String serverSeedHash;
    private String clientSeed;
    private long nonce;
    private String resultHash;
}
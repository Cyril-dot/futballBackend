package com.speedbet.api.superadmin;

import java.math.BigDecimal;

/** Wire shapes for the platform amounts (super-admin tab + public read). */
public final class PlatformAmountsDtos {

    private PlatformAmountsDtos() {
    }

    public record AmountsResponse(
            BigDecimal minDepositGhs,
            BigDecimal minStakeGhs,
            BigDecimal maxWinGhs,
            BigDecimal maxWinNgn,
            BigDecimal withdrawalUnlockGhs) {
        public static AmountsResponse from(PlatformSetting s) {
            return new AmountsResponse(
                    s.getMinDepositGhs(),
                    s.getMinStakeGhs(),
                    s.getMaxWinGhs(),
                    s.getMaxWinNgn(),
                    s.getWithdrawalUnlockGhs());
        }
    }

    public record UpdateAmountsRequest(
            BigDecimal minDepositGhs,
            BigDecimal minStakeGhs,
            BigDecimal maxWinGhs,
            BigDecimal maxWinNgn,
            BigDecimal withdrawalUnlockGhs) {
    }
}

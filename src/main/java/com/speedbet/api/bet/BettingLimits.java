package com.speedbet.api.bet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * Platform stake limits — the single source of truth for how much a bet
 * may stake. Values come from application.properties
 * (app.betting.min-stake / app.betting.max-stake, overridable through the
 * BETTING_MIN_STAKE / BETTING_MAX_STAKE environment variables), are
 * enforced by BetService on every placement, and are published to the
 * frontend through GET /api/public/config so the UI never hardcodes them.
 */
@Component
public class BettingLimits {

    private final BigDecimal minStake;
    private final BigDecimal maxStake;

    public BettingLimits(
            @Value("${app.betting.min-stake:100}") BigDecimal minStake,
            @Value("${app.betting.max-stake:10000}") BigDecimal maxStake) {
        this.minStake = minStake;
        this.maxStake = maxStake;
    }

    public BigDecimal minStake() {
        return minStake;
    }

    public BigDecimal maxStake() {
        return maxStake;
    }

    /** Grouped, no trailing decimals ("100", "10,000") — for messages. */
    public String describe(BigDecimal amount) {
        return new DecimalFormat("#,##0.##").format(amount);
    }
}

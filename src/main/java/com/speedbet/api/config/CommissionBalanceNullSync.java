package com.speedbet.api.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Repairs legacy {@code affiliate_commission_balances} rows whose money
 * columns are NULL (created before the entity carried defaults).
 * NULLs there used to NPE the commission analytics endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionBalanceNullSync implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int fixed = 0;
            fixed += jdbc.update("UPDATE affiliate_commission_balances SET balance = 0 WHERE balance IS NULL");
            fixed += jdbc.update("UPDATE affiliate_commission_balances SET total_earned_lifetime = 0 WHERE total_earned_lifetime IS NULL");
            fixed += jdbc.update("UPDATE affiliate_commission_balances SET total_paid_out_lifetime = 0 WHERE total_paid_out_lifetime IS NULL");
            if (fixed > 0) log.info("commission null cleanup — repaired {} value(s)", fixed);
        } catch (Exception e) {
            // Never block startup — log loudly instead.
            log.error("commission null cleanup FAILED: {}", e.getMessage());
        }
    }
}

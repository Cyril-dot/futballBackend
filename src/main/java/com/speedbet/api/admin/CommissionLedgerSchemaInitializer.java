package com.speedbet.api.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps deployments that already have the ledger table compatible with the
 * day-specific settlement feature. Flyway is disabled in this service and
 * some Railway databases do not run Hibernate schema updates reliably.
 */
@Component
@RequiredArgsConstructor
public class CommissionLedgerSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    @jakarta.annotation.PostConstruct
    public void ensurePaidAtColumn() {
        jdbcTemplate.execute("ALTER TABLE commission_ledger_entries " +
                "ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP WITH TIME ZONE");
    }
}

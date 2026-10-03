package com.speedbet.api.config;

import com.speedbet.api.wallet.TxKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Keeps the {@code transactions.kind} CHECK constraint in sync with the
 * {@link TxKind} enum.
 *
 * The constraint was created manually on the database before the cashout
 * kinds existed, so cashout credits were rejected at insert time with:
 *   new row for relation "transactions" violates check constraint
 *   "transactions_kind_check"
 * Rebuilding it from the enum on every boot means newly added kinds can
 * never break inserts again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionKindConstraintSync implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        String allowed = Arrays.stream(TxKind.values())
                .map(k -> "'" + k.name() + "'")
                .collect(Collectors.joining(","));
        try {
            jdbc.execute("ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_kind_check");
            jdbc.execute("ALTER TABLE transactions ADD CONSTRAINT transactions_kind_check "
                    + "CHECK (kind IN (" + allowed + "))");
            log.info("transactions_kind_check synced ({} kinds)", TxKind.values().length);
        } catch (Exception e) {
            // Never block startup — log loudly instead.
            log.error("FAILED to sync transactions_kind_check — inserts may be rejected: {}",
                    e.getMessage());
        }
    }
}

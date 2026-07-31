package com.speedbet.api.superadmin;

import com.speedbet.api.wallet.Transaction;
import com.speedbet.api.wallet.TransactionRepository;
import com.speedbet.api.wallet.TxKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Platform-wide deposit totals by day/week, for the super admin dashboard.
 * Separate from AdminCountryDepositService, which is scoped per-admin to
 * that admin's own referred users and split by country.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminDepositService {

    private static final String CURRENCY = "GHS";

    private final TransactionRepository transactionRepo;

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getDaily(int days) {
        int safeDays = clamp(days, 1, 365, 30);
        log.info("getDaily deposits: days={}", safeDays);
        return bucket(loadSince(safeDays), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getWeekly(int weeks) {
        int safeWeeks = clamp(weeks, 1, 52, 12);
        log.info("getWeekly deposits: weeks={}", safeWeeks);
        return bucket(loadSince(safeWeeks * 7), this::weekLabel);
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    private List<Transaction> loadSince(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        // Derived query method — Hibernate binds the enum correctly, avoiding the
        // PostgreSQL "column is of type tx_kind but expression is of type bytea"
        // cast error that a hand-written @Query with an enum param can trigger.
        List<Transaction> raw = transactionRepo.findAllByKindSince(TxKind.DEPOSIT, since);
        if (raw == null || raw.isEmpty()) return List.of();

        List<Transaction> usable = new ArrayList<>(raw.size());
        int skipped = 0;
        for (Transaction t : raw) {
            if (t == null || t.getCreatedAt() == null) { skipped++; continue; }
            usable.add(t);
        }
        if (skipped > 0) {
            log.warn("loadSince: skipped {} deposit transactions with null createdAt", skipped);
        }
        return usable;
    }

    // ─── Label helpers ───────────────────────────────────────────────────────

    private String dayLabel(Transaction t) {
        return t.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private String weekLabel(Transaction t) {
        var d  = t.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        var wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W"
                + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    // ─── Bucketing ───────────────────────────────────────────────────────────

    private List<SuperAdminDtos.PlatformPeriodTotalDto> bucket(
            List<Transaction> rows, Function<Transaction, String> labelFn) {

        if (rows.isEmpty()) return List.of();

        var grouped = new TreeMap<String, List<Transaction>>();
        for (var t : rows) {
            grouped.computeIfAbsent(labelFn.apply(t), k -> new ArrayList<>()).add(t);
        }

        var result = new ArrayList<SuperAdminDtos.PlatformPeriodTotalDto>(grouped.size());
        for (var entry : grouped.entrySet()) {
            var list = entry.getValue();
            var sum = list.stream()
                    .map(Transaction::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new SuperAdminDtos.PlatformPeriodTotalDto(
                    entry.getKey(), sum, list.size(), CURRENCY));
        }
        return result;
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            log.warn("clamp: value {} outside [{}, {}] — using {}", value, min, max, fallback);
            return fallback;
        }
        return value;
    }
}
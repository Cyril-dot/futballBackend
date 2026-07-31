package com.speedbet.api.superadmin;

import com.speedbet.api.admin.CommissionLedgerEntry;
import com.speedbet.api.admin.CommissionLedgerEntryRepository;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Platform-wide (all-admins) commission breakdowns for the super admin
 * dashboard. Reuses CommissionLedgerEntry/Repository from the admin package
 * but is a separate service — AdminCommissionService stays untouched and
 * remains scoped to a single admin.
 *
 * Hardened against null adminId / createdAt / amount / currency, any of which
 * previously produced a 500 rather than an empty or partial result set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminCommissionService {

    private static final String DEFAULT_CURRENCY = "GHS";
    private static final char KEY_SEP = '\u0000';

    private final CommissionLedgerEntryRepository ledgerRepo;
    private final UserRepository userRepo;

    // ─── Per-admin breakdown ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.AdminCommissionPeriodDto> getDailyCommissionByAdmin(int days) {
        int safeDays = clamp(days, 1, 365, 30);
        log.info("getDailyCommissionByAdmin: days={}", safeDays);
        return bucketByAdmin(loadSince(safeDays), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.AdminCommissionPeriodDto> getWeeklyCommissionByAdmin(int weeks) {
        int safeWeeks = clamp(weeks, 1, 52, 12);
        log.info("getWeeklyCommissionByAdmin: weeks={}", safeWeeks);
        return bucketByAdmin(loadSince(safeWeeks * 7), this::weekLabel);
    }

    // ─── Platform totals (all admins combined) ─────────────────────────────

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getDailyCommissionTotals(int days) {
        int safeDays = clamp(days, 1, 365, 30);
        log.info("getDailyCommissionTotals: days={}", safeDays);
        return bucketTotals(loadSince(safeDays), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getWeeklyCommissionTotals(int weeks) {
        int safeWeeks = clamp(weeks, 1, 52, 12);
        log.info("getWeeklyCommissionTotals: weeks={}", safeWeeks);
        return bucketTotals(loadSince(safeWeeks * 7), this::weekLabel);
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    /**
     * Loads entries newer than {@code days} ago and drops any row that cannot be
     * bucketed (null createdAt). A single bad row must not 500 the whole report.
     */
    private List<CommissionLedgerEntry> loadSince(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<CommissionLedgerEntry> raw = ledgerRepo.findAllSince(since);
        if (raw == null || raw.isEmpty()) return List.of();

        List<CommissionLedgerEntry> usable = new ArrayList<>(raw.size());
        int skipped = 0;
        for (CommissionLedgerEntry e : raw) {
            if (e == null || e.getCreatedAt() == null) { skipped++; continue; }
            usable.add(e);
        }
        if (skipped > 0) {
            log.warn("loadSince: skipped {} commission ledger entries with null createdAt", skipped);
        }
        return usable;
    }

    // ─── Label helpers ───────────────────────────────────────────────────────

    private String dayLabel(CommissionLedgerEntry e) {
        return e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private String weekLabel(CommissionLedgerEntry e) {
        var d  = e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        var wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W"
                + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    // ─── Bucketing ───────────────────────────────────────────────────────────

    private List<SuperAdminDtos.AdminCommissionPeriodDto> bucketByAdmin(
            List<CommissionLedgerEntry> entries,
            Function<CommissionLedgerEntry, String> labelFn) {

        if (entries.isEmpty()) return List.of();

        // key = periodLabel + NUL + adminId, so each (period, admin) pair gets its
        // own bucket. Entries with a null adminId are dropped rather than producing
        // the literal string "null", which used to blow up UUID.fromString().
        var grouped = new TreeMap<String, List<CommissionLedgerEntry>>();
        int orphaned = 0;
        for (var e : entries) {
            UUID adminId = e.getAdminId();
            if (adminId == null) { orphaned++; continue; }
            grouped.computeIfAbsent(labelFn.apply(e) + KEY_SEP + adminId,
                    k -> new ArrayList<>()).add(e);
        }
        if (orphaned > 0) {
            log.warn("bucketByAdmin: skipped {} ledger entries with null adminId", orphaned);
        }
        if (grouped.isEmpty()) return List.of();

        // Batch-fetch emails once rather than one query per row.
        var adminIds = entries.stream()
                .map(CommissionLedgerEntry::getAdminId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, String> emailById = adminIds.isEmpty()
                ? Map.of()
                : userRepo.findAllById(adminIds).stream()
                    .filter(u -> u.getId() != null)
                    .collect(Collectors.toMap(
                            User::getId,
                            u -> u.getEmail() != null ? u.getEmail() : "UNKNOWN",
                            (a, b) -> a));

        var result = new ArrayList<SuperAdminDtos.AdminCommissionPeriodDto>(grouped.size());
        for (var entry : grouped.entrySet()) {
            var parts = entry.getKey().split(String.valueOf(KEY_SEP), 2);
            if (parts.length < 2) continue;

            UUID adminId;
            try {
                adminId = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException ex) {
                log.warn("bucketByAdmin: unparseable adminId in bucket key '{}'", entry.getKey());
                continue;
            }

            var list = entry.getValue();
            result.add(new SuperAdminDtos.AdminCommissionPeriodDto(
                    parts[0],
                    adminId,
                    emailById.getOrDefault(adminId, "UNKNOWN"),
                    sum(list),
                    currencyOf(list)
            ));
        }
        return result;
    }

    private List<SuperAdminDtos.PlatformPeriodTotalDto> bucketTotals(
            List<CommissionLedgerEntry> entries,
            Function<CommissionLedgerEntry, String> labelFn) {

        if (entries.isEmpty()) return List.of();

        var grouped = new TreeMap<String, List<CommissionLedgerEntry>>();
        for (var e : entries) {
            grouped.computeIfAbsent(labelFn.apply(e), k -> new ArrayList<>()).add(e);
        }

        var result = new ArrayList<SuperAdminDtos.PlatformPeriodTotalDto>(grouped.size());
        for (var entry : grouped.entrySet()) {
            var list = entry.getValue();
            result.add(new SuperAdminDtos.PlatformPeriodTotalDto(
                    entry.getKey(), sum(list), list.size(), currencyOf(list)));
        }
        return result;
    }

    // ─── Small helpers ───────────────────────────────────────────────────────

    private BigDecimal sum(List<CommissionLedgerEntry> list) {
        return list.stream()
                .map(CommissionLedgerEntry::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String currencyOf(List<CommissionLedgerEntry> list) {
        return list.stream()
                .map(CommissionLedgerEntry::getCurrency)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(DEFAULT_CURRENCY);
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            log.warn("clamp: value {} outside [{}, {}] — using {}", value, min, max, fallback);
            return fallback;
        }
        return value;
    }
}
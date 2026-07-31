package com.speedbet.api.superadmin;

import com.speedbet.api.admin.CommissionLedgerEntry;
import com.speedbet.api.admin.CommissionLedgerEntryRepository;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminCommissionService {

    private final CommissionLedgerEntryRepository ledgerRepo;
    private final UserRepository userRepo;

    // ─── Per-admin breakdown ────────────────────────────────────────────────

    public List<SuperAdminDtos.AdminCommissionPeriodDto> getDailyCommissionByAdmin(int days) {
        log.info("getDailyCommissionByAdmin: days={}", days);
        var since = Instant.now().minus(days, ChronoUnit.DAYS);
        return bucketByAdmin(ledgerRepo.findAllSince(since), this::dayLabel);
    }

    public List<SuperAdminDtos.AdminCommissionPeriodDto> getWeeklyCommissionByAdmin(int weeks) {
        log.info("getWeeklyCommissionByAdmin: weeks={}", weeks);
        var since = Instant.now().minus((long) weeks * 7, ChronoUnit.DAYS);
        return bucketByAdmin(ledgerRepo.findAllSince(since), this::weekLabel);
    }

    // ─── Platform totals (all admins combined) ─────────────────────────────

    public List<SuperAdminDtos.PlatformPeriodTotalDto> getDailyCommissionTotals(int days) {
        var since = Instant.now().minus(days, ChronoUnit.DAYS);
        return bucketTotals(ledgerRepo.findAllSince(since), this::dayLabel);
    }

    public List<SuperAdminDtos.PlatformPeriodTotalDto> getWeeklyCommissionTotals(int weeks) {
        var since = Instant.now().minus((long) weeks * 7, ChronoUnit.DAYS);
        return bucketTotals(ledgerRepo.findAllSince(since), this::weekLabel);
    }

    // ─── Label helpers ───────────────────────────────────────────────────────

    private String dayLabel(CommissionLedgerEntry e) {
        return e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private String weekLabel(CommissionLedgerEntry e) {
        var d = e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        var wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    // ─── Bucketing ───────────────────────────────────────────────────────────

    private List<SuperAdminDtos.AdminCommissionPeriodDto> bucketByAdmin(
            List<CommissionLedgerEntry> entries, Function<CommissionLedgerEntry, String> labelFn) {

        // key = periodLabel + "\u0000" + adminId, so each (period, admin) pair gets its own bucket
        var grouped = new TreeMap<String, List<CommissionLedgerEntry>>();
        for (var e : entries) {
            grouped.computeIfAbsent(labelFn.apply(e) + "\u0000" + e.getAdminId(),
                    k -> new ArrayList<>()).add(e);
        }

        // Batch-fetch emails once rather than one query per row
        var adminIds = entries.stream().map(CommissionLedgerEntry::getAdminId).distinct().toList();
        var emailById = userRepo.findAllById(adminIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        var result = new ArrayList<SuperAdminDtos.AdminCommissionPeriodDto>();
        for (var entry : grouped.entrySet()) {
            var parts   = entry.getKey().split("\u0000", 2);
            var period  = parts[0];
            var adminId = UUID.fromString(parts[1]);
            var list    = entry.getValue();
            var sum = list.stream().map(CommissionLedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(new SuperAdminDtos.AdminCommissionPeriodDto(
                    period, adminId, emailById.getOrDefault(adminId, "UNKNOWN"),
                    sum, list.get(0).getCurrency()
            ));
        }
        return result;
    }

    private List<SuperAdminDtos.PlatformPeriodTotalDto> bucketTotals(
            List<CommissionLedgerEntry> entries, Function<CommissionLedgerEntry, String> labelFn) {

        var grouped = new TreeMap<String, List<CommissionLedgerEntry>>();
        for (var e : entries) {
            grouped.computeIfAbsent(labelFn.apply(e), k -> new ArrayList<>()).add(e);
        }

        var result = new ArrayList<SuperAdminDtos.PlatformPeriodTotalDto>();
        for (var entry : grouped.entrySet()) {
            var list = entry.getValue();
            var sum = list.stream().map(CommissionLedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new SuperAdminDtos.PlatformPeriodTotalDto(
                    entry.getKey(), sum, list.size(), list.get(0).getCurrency()));
        }
        return result;
    }
}
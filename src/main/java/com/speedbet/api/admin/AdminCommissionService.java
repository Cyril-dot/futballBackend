package com.speedbet.api.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Buckets CommissionLedgerEntry rows into daily / weekly / monthly totals
 * per admin. Deliberately a NEW service, separate from AdminAffiliateService,
 * so that existing file stays untouched.
 *
 * All bucketing is done in UTC, in-memory, after a single date-ranged fetch
 * from the ledger repository. This keeps the SQL simple (one indexed range
 * query) and avoids database-specific date-trunc syntax differences.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCommissionService {

    private final CommissionLedgerEntryRepository ledgerRepo;

    // ─── Daily ──────────────────────────────────────────────────────────────

    public List<AffiliateCommissionPeriodDTO> getDailyCommission(UUID adminId, int days) {
        log.info("getDailyCommission: adminId={} days={}", adminId, days);
        var since   = Instant.now().minus(days, ChronoUnit.DAYS);
        var entries = ledgerRepo.findByAdminIdSince(adminId, since);
        return bucket(entries, e -> e.getCreatedAt()
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString());
    }

    // ─── Weekly (ISO week) ──────────────────────────────────────────────────

    public List<AffiliateCommissionPeriodDTO> getWeeklyCommission(UUID adminId, int weeks) {
        log.info("getWeeklyCommission: adminId={} weeks={}", adminId, weeks);
        var since   = Instant.now().minus((long) weeks * 7, ChronoUnit.DAYS);
        var entries = ledgerRepo.findByAdminIdSince(adminId, since);
        var wf      = WeekFields.ISO;

        return bucket(entries, e -> {
            var d = e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
        });
    }

    // ─── Monthly ────────────────────────────────────────────────────────────

    public List<AffiliateCommissionPeriodDTO> getMonthlyCommission(UUID adminId, int months) {
        log.info("getMonthlyCommission: adminId={} months={}", adminId, months);
        // 31 days/month is a deliberate over-estimate so short months never get cut short
        var since   = Instant.now().minus((long) months * 31, ChronoUnit.DAYS);
        var entries = ledgerRepo.findByAdminIdSince(adminId, since);

        return bucket(entries, e -> {
            var d = e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            return d.getYear() + "-" + String.format("%02d", d.getMonthValue());
        });
    }

    // ─── Shared bucketing helper ────────────────────────────────────────────

    private List<AffiliateCommissionPeriodDTO> bucket(
            List<CommissionLedgerEntry> entries,
            Function<CommissionLedgerEntry, String> keyFn) {

        // TreeMap keeps buckets sorted by label (works for daily/monthly since
        // those labels sort lexicographically the same as chronologically;
        // ISO week labels "YYYY-Www" also sort correctly this way).
        var grouped = new TreeMap<String, List<CommissionLedgerEntry>>();
        for (var e : entries) {
            grouped.computeIfAbsent(keyFn.apply(e), k -> new ArrayList<>()).add(e);
        }

        var result = new ArrayList<AffiliateCommissionPeriodDTO>();
        for (var entry : grouped.entrySet()) {
            var list = entry.getValue();
            var sum  = list.stream()
                    .map(CommissionLedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(new AffiliateCommissionPeriodDTO(
                    entry.getKey(),
                    list.get(0).getCreatedAt(),
                    sum,
                    list.get(0).getCurrency()
            ));
        }
        return result;
    }
}
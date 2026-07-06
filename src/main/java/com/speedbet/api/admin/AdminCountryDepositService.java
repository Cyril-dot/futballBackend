package com.speedbet.api.admin;

import com.speedbet.api.wallet.DepositRow;
import com.speedbet.api.wallet.TransactionRepository;
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

/**
 * Buckets deposit transactions into daily/weekly/monthly totals, split by
 * the depositing user's country, per admin. New service — does not touch
 * AdminCommissionService or AdminAffiliateService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCountryDepositService {

    private final TransactionRepository transactionRepo;

    public List<CountryDepositPeriodDTO> getDaily(UUID adminId, int days) {
        var since = Instant.now().minus(days, ChronoUnit.DAYS);
        var rows  = transactionRepo.findDepositsByAdminSince(adminId, since);
        return bucket(rows, r -> r.createdAt().atZone(ZoneOffset.UTC).toLocalDate().toString());
    }

    public List<CountryDepositPeriodDTO> getWeekly(UUID adminId, int weeks) {
        var since = Instant.now().minus((long) weeks * 7, ChronoUnit.DAYS);
        var rows  = transactionRepo.findDepositsByAdminSince(adminId, since);
        var wf    = WeekFields.ISO;
        return bucket(rows, r -> {
            var d = r.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
            return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
        });
    }

    public List<CountryDepositPeriodDTO> getMonthly(UUID adminId, int months) {
        var since = Instant.now().minus((long) months * 31, ChronoUnit.DAYS);
        var rows  = transactionRepo.findDepositsByAdminSince(adminId, since);
        return bucket(rows, r -> {
            var d = r.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
            return d.getYear() + "-" + String.format("%02d", d.getMonthValue());
        });
    }

    /** Also returns lifetime totals per country (no period), for a summary table. */
    public List<CountryDepositPeriodDTO> getTotalsByCountry(UUID adminId, int days) {
        var since = Instant.now().minus(days, ChronoUnit.DAYS);
        var rows  = transactionRepo.findDepositsByAdminSince(adminId, since);
        return bucket(rows, r -> "ALL");
    }

    private List<CountryDepositPeriodDTO> bucket(List<DepositRow> rows, Function<DepositRow, String> periodKeyFn) {
        // key = periodLabel + "\u0000" + country, so each (period, country) pair gets its own bucket
        var grouped = new TreeMap<String, List<DepositRow>>();
        for (var r : rows) {
            var country = (r.country() == null || r.country().isBlank()) ? "UNKNOWN" : r.country();
            var key = periodKeyFn.apply(r) + "\u0000" + country;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        var result = new ArrayList<CountryDepositPeriodDTO>();
        for (var entry : grouped.entrySet()) {
            var parts   = entry.getKey().split("\u0000", 2);
            var period  = parts[0];
            var country = parts[1];
            var list    = entry.getValue();
            var sum = list.stream().map(DepositRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new CountryDepositPeriodDTO(period, country, sum, list.size()));
        }
        return result;
    }
}
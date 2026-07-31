package com.speedbet.api.superadmin;

import com.speedbet.api.wallet.Transaction;
import com.speedbet.api.wallet.TransactionRepository;
import com.speedbet.api.wallet.TxKind;
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

    private final TransactionRepository transactionRepo;

    public List<SuperAdminDtos.PlatformPeriodTotalDto> getDaily(int days) {
        log.info("getDaily deposits: days={}", days);
        var since = Instant.now().minus(days, ChronoUnit.DAYS);
        return bucket(transactionRepo.findAllByKindSince(TxKind.DEPOSIT, since), this::dayLabel);
    }

    public List<SuperAdminDtos.PlatformPeriodTotalDto> getWeekly(int weeks) {
        log.info("getWeekly deposits: weeks={}", weeks);
        var since = Instant.now().minus((long) weeks * 7, ChronoUnit.DAYS);
        return bucket(transactionRepo.findAllByKindSince(TxKind.DEPOSIT, since), this::weekLabel);
    }

    private String dayLabel(Transaction t) {
        return t.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private String weekLabel(Transaction t) {
        var d = t.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        var wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    private List<SuperAdminDtos.PlatformPeriodTotalDto> bucket(
            List<Transaction> rows, Function<Transaction, String> labelFn) {

        var grouped = new TreeMap<String, List<Transaction>>();
        for (var t : rows) {
            grouped.computeIfAbsent(labelFn.apply(t), k -> new ArrayList<>()).add(t);
        }

        var result = new ArrayList<SuperAdminDtos.PlatformPeriodTotalDto>();
        for (var entry : grouped.entrySet()) {
            var list = entry.getValue();
            var sum = list.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            // currency hardcoded to match RevenueOverviewDto elsewhere in this class
            result.add(new SuperAdminDtos.PlatformPeriodTotalDto(entry.getKey(), sum, list.size(), "GHS"));
        }
        return result;
    }
}
package com.speedbet.api.superadmin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Assembles the single response the analytics page needs: per-country
 * summaries plus the period breakdowns for deposits and commission.
 *
 * One request instead of four means the page can never render a half-loaded
 * state where Ghana totals are present but Nigeria totals are still pending.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminCountryReportService {

    private final SuperAdminDepositService depositService;
    private final SuperAdminCommissionService commissionService;

    @Transactional(readOnly = true)
    public SuperAdminDtos.CountrySplitReportDto buildDaily(int days) {
        log.info("buildDaily country report: days={}", days);
        return build("daily",
                days + (days == 1 ? " day" : " days"),
                depositService.getTotalsByCountry(days),
                commissionService.getTotalsByCountry(days),
                depositService.getDailyByCountry(days),
                commissionService.getDailyByCountry(days),
                commissionService.getDailyByAdminAndCountry(days));
    }

    @Transactional(readOnly = true)
    public SuperAdminDtos.CountrySplitReportDto buildWeekly(int weeks) {
        log.info("buildWeekly country report: weeks={}", weeks);
        int days = weeks * 7;
        return build("weekly",
                weeks + (weeks == 1 ? " week" : " weeks"),
                depositService.getTotalsByCountry(days),
                commissionService.getTotalsByCountry(days),
                depositService.getWeeklyByCountry(weeks),
                commissionService.getWeeklyByCountry(weeks),
                commissionService.getWeeklyByAdminAndCountry(weeks));
    }

    // ─── Assembly ────────────────────────────────────────────────────────────

    private SuperAdminDtos.CountrySplitReportDto build(
            String period,
            String rangeLabel,
            Map<String, SuperAdminDepositService.CountryTotals> deposits,
            Map<String, SuperAdminCommissionService.CountryTotals> commission,
            List<SuperAdminDtos.CountryPeriodTotalDto> depositsByPeriod,
            List<SuperAdminDtos.CountryPeriodTotalDto> commissionByPeriod,
            List<SuperAdminDtos.AdminCommissionCountryDto> commissionByAdmin) {

        // Ghana and Nigeria always appear, even at zero, so the dashboard keeps
        // a stable two-column shape. Tail buckets appear only when non-empty.
        LinkedHashSet<String> countries = new LinkedHashSet<>(List.of(CountryUtils.GH, CountryUtils.NG));
        countries.addAll(deposits.keySet());
        countries.addAll(commission.keySet());

        List<SuperAdminDtos.CountrySummaryDto> summaries = new ArrayList<>();
        for (String country : countries) {
            var dep = deposits.get(country);
            var com = commission.get(country);

            BigDecimal depTotal = dep != null ? dep.amount() : BigDecimal.ZERO;
            long depCount = dep != null ? dep.count() : 0L;
            BigDecimal comTotal = com != null ? com.amount() : BigDecimal.ZERO;
            long comCount = com != null ? com.count() : 0L;

            // Skip empty tail buckets, but never skip GH or NG.
            boolean core = CountryUtils.GH.equals(country) || CountryUtils.NG.equals(country);
            if (!core && depCount == 0 && comCount == 0) continue;

            String currency = dep != null ? dep.currency()
                    : com != null ? com.currency()
                    : CountryUtils.currencyOf(country);

            BigDecimal avgDeposit = depCount > 0
                    ? depTotal.divide(BigDecimal.valueOf(depCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            BigDecimal rate = depTotal.signum() > 0
                    ? comTotal.multiply(BigDecimal.valueOf(100))
                        .divide(depTotal, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            summaries.add(new SuperAdminDtos.CountrySummaryDto(
                    country,
                    CountryUtils.displayName(country),
                    currency,
                    depTotal,
                    depCount,
                    comTotal,
                    comCount,
                    avgDeposit,
                    rate
            ));
        }

        return new SuperAdminDtos.CountrySplitReportDto(
                period, rangeLabel, summaries,
                depositsByPeriod, commissionByPeriod, commissionByAdmin);
    }
}
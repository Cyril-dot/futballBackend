package com.speedbet.api.superadmin;

import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.wallet.Transaction;
import com.speedbet.api.wallet.TransactionRepository;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.Wallet;
import com.speedbet.api.wallet.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Platform-wide deposit totals by day/week, split by the depositing user's
 * country.
 *
 * Ghanaian users fund their wallet by MoMo in cedis; Nigerian users deposit
 * predominantly by bank transfer in naira. Because the two are different
 * currencies, totals are bucketed per country and never added together — a
 * single combined "total deposits" figure across both would be meaningless.
 *
 * Country is resolved transaction → wallet → user in three batched queries
 * rather than per row, so the cost is constant regardless of range size.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminDepositService {

    private final TransactionRepository transactionRepo;
    private final WalletRepository walletRepo;
    private final UserRepository userRepo;

    // ─── Public API ──────────────────────────────────────────────────────────

    /** Legacy shape: one row per period, all countries merged. Kept for callers that still use it. */
    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getDaily(int days) {
        int safe = clamp(days, 1, 365, 30);
        log.info("getDaily deposits: days={}", safe);
        return mergeToPlain(bucketByCountry(loadSince(safe), this::dayLabel));
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getWeekly(int weeks) {
        int safe = clamp(weeks, 1, 52, 12);
        log.info("getWeekly deposits: weeks={}", safe);
        return mergeToPlain(bucketByCountry(loadSince(safe * 7), this::weekLabel));
    }

    /** Period × country breakdown. */
    @Transactional(readOnly = true)
    public List<SuperAdminDtos.CountryPeriodTotalDto> getDailyByCountry(int days) {
        int safe = clamp(days, 1, 365, 30);
        log.info("getDailyByCountry deposits: days={}", safe);
        return bucketByCountry(loadSince(safe), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.CountryPeriodTotalDto> getWeeklyByCountry(int weeks) {
        int safe = clamp(weeks, 1, 52, 12);
        log.info("getWeeklyByCountry deposits: weeks={}", safe);
        return bucketByCountry(loadSince(safe * 7), this::weekLabel);
    }

    /** Whole-range roll-up per country, without the period dimension. */
    @Transactional(readOnly = true)
    public Map<String, CountryTotals> getTotalsByCountry(int days) {
        return rollUp(loadSince(clamp(days, 1, 365, 30)));
    }

    /** Simple carrier used by the report assembler. */
    public record CountryTotals(BigDecimal amount, long count, String currency) {}

    // ─── Loading ─────────────────────────────────────────────────────────────

    /** A deposit transaction paired with the country and currency it belongs to. */
    private record Row(Transaction tx, String country, String currency) {}

    private List<Row> loadSince(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        // Derived query — Hibernate binds the enum correctly here, avoiding the
        // Postgres "expression is of type bytea" cast error that a hand-written
        // @Query with an enum parameter can trigger.
        List<Transaction> raw = transactionRepo.findAllByKindSince(TxKind.DEPOSIT, since);
        if (raw == null || raw.isEmpty()) return List.of();

        List<Transaction> usable = new ArrayList<>(raw.size());
        int skipped = 0;
        for (Transaction t : raw) {
            if (t == null || t.getCreatedAt() == null || t.getWalletId() == null) { skipped++; continue; }
            usable.add(t);
        }
        if (skipped > 0) log.warn("loadSince: skipped {} deposits with null createdAt/walletId", skipped);
        if (usable.isEmpty()) return List.of();

        // Batch: wallets → userIds, users → country. Two queries, not 2N.
        Set<UUID> walletIds = usable.stream()
                .map(Transaction::getWalletId).collect(Collectors.toSet());

        List<Wallet> wallets = walletRepo.findAllById(walletIds);

        Map<UUID, UUID> walletToUser = new HashMap<>();
        Map<UUID, String> walletCurrency = new HashMap<>();
        for (Wallet w : wallets) {
            if (w == null || w.getId() == null) continue;
            if (w.getUserId() != null) walletToUser.put(w.getId(), w.getUserId());
            if (w.getCurrency() != null && !w.getCurrency().isBlank())
                walletCurrency.put(w.getId(), w.getCurrency());
        }

        Set<UUID> userIds = new HashSet<>(walletToUser.values());
        Map<UUID, String> userCountry = userIds.isEmpty() ? Map.of()
                : userRepo.findAllById(userIds).stream()
                    .filter(u -> u != null && u.getId() != null)
                    .collect(Collectors.toMap(
                            User::getId,
                            u -> CountryUtils.normalize(u.getCountry()),
                            (a, b) -> a));

        List<Row> rows = new ArrayList<>(usable.size());
        int orphaned = 0;
        for (Transaction t : usable) {
            UUID userId = walletToUser.get(t.getWalletId());
            String country = userId == null
                    ? CountryUtils.UNKNOWN
                    : userCountry.getOrDefault(userId, CountryUtils.UNKNOWN);
            if (userId == null) orphaned++;

            // The wallet's own currency is authoritative — it is the currency
            // the amount was actually recorded in. Country is only a fallback.
            String currency = walletCurrency.getOrDefault(
                    t.getWalletId(), CountryUtils.currencyOf(country));

            rows.add(new Row(t, country, currency));
        }
        if (orphaned > 0)
            log.warn("loadSince: {} deposits had no resolvable wallet owner — bucketed as UNKNOWN", orphaned);

        return rows;
    }

    // ─── Label helpers ───────────────────────────────────────────────────────

    private String dayLabel(Row r) {
        return r.tx().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private String weekLabel(Row r) {
        var d = r.tx().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        var wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W"
                + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    // ─── Bucketing ───────────────────────────────────────────────────────────

    private List<SuperAdminDtos.CountryPeriodTotalDto> bucketByCountry(
            List<Row> rows, Function<Row, String> labelFn) {

        if (rows.isEmpty()) return List.of();

        // key = periodLabel + NUL + country
        var grouped = new TreeMap<String, List<Row>>();
        for (Row r : rows) {
            grouped.computeIfAbsent(labelFn.apply(r) + '\u0000' + r.country(),
                    k -> new ArrayList<>()).add(r);
        }

        var result = new ArrayList<SuperAdminDtos.CountryPeriodTotalDto>(grouped.size());
        for (var e : grouped.entrySet()) {
            String[] parts = e.getKey().split("\u0000", 2);
            if (parts.length < 2) continue;

            List<Row> list = e.getValue();
            result.add(new SuperAdminDtos.CountryPeriodTotalDto(
                    parts[0],
                    parts[1],
                    CountryUtils.displayName(parts[1]),
                    sum(list),
                    list.size(),
                    currencyOf(list, parts[1])
            ));
        }
        return result;
    }

    private Map<String, CountryTotals> rollUp(List<Row> rows) {
        Map<String, List<Row>> byCountry = rows.stream()
                .collect(Collectors.groupingBy(Row::country));

        Map<String, CountryTotals> out = new LinkedHashMap<>();
        for (String key : List.of(CountryUtils.GH, CountryUtils.NG,
                                  CountryUtils.OTHER, CountryUtils.UNKNOWN)) {
            List<Row> list = byCountry.getOrDefault(key, List.of());
            if (list.isEmpty() && (CountryUtils.OTHER.equals(key) || CountryUtils.UNKNOWN.equals(key)))
                continue; // don't clutter the dashboard with empty tail buckets
            out.put(key, new CountryTotals(sum(list), list.size(), currencyOf(list, key)));
        }
        return out;
    }

    /**
     * Merge country buckets back into one row per period. Only safe when every
     * row in the period shares a currency; when they don't, the merged figure
     * is flagged as MIXED so the caller doesn't present it as spendable money.
     */
    private List<SuperAdminDtos.PlatformPeriodTotalDto> mergeToPlain(
            List<SuperAdminDtos.CountryPeriodTotalDto> rows) {

        Map<String, List<SuperAdminDtos.CountryPeriodTotalDto>> byPeriod = new TreeMap<>();
        for (var r : rows) byPeriod.computeIfAbsent(r.periodLabel(), k -> new ArrayList<>()).add(r);

        var out = new ArrayList<SuperAdminDtos.PlatformPeriodTotalDto>(byPeriod.size());
        for (var e : byPeriod.entrySet()) {
            var list = e.getValue();
            BigDecimal total = list.stream()
                    .map(SuperAdminDtos.CountryPeriodTotalDto::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long count = list.stream()
                    .mapToLong(SuperAdminDtos.CountryPeriodTotalDto::count).sum();

            Set<String> currencies = list.stream()
                    .map(SuperAdminDtos.CountryPeriodTotalDto::currency)
                    .collect(Collectors.toSet());
            String currency = currencies.size() == 1 ? currencies.iterator().next() : "MIXED";

            out.add(new SuperAdminDtos.PlatformPeriodTotalDto(e.getKey(), total, count, currency));
        }
        return out;
    }

    // ─── Small helpers ───────────────────────────────────────────────────────

    private BigDecimal sum(List<Row> list) {
        return list.stream()
                .map(r -> r.tx().getAmount())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String currencyOf(List<Row> list, String countryFallback) {
        return list.stream()
                .map(Row::currency)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(CountryUtils.currencyOf(countryFallback));
    }

    static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            log.warn("clamp: value {} outside [{}, {}] — using {}", value, min, max, fallback);
            return fallback;
        }
        return value;
    }
}
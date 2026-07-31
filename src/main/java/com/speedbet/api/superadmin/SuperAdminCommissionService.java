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
 * dashboard, split by country.
 * <p>
 * <b>Country attribution.</b> {@link CommissionLedgerEntry} records only
 * {@code adminId}, {@code amount} and {@code currency} — there is no referred
 * user on the row. Country is therefore resolved in this order:
 * <ol>
 *   <li><b>Currency</b> — GHS maps to Ghana, NGN to Nigeria. This is the
 *       strongest available signal because it is the currency the commission
 *       was actually credited in, and it stays correct even when a Ghanaian
 *       admin refers Nigerian users.</li>
 *   <li><b>The admin's own country</b>, when the currency code is unrecognised.
 *       A weaker proxy: it misfiles cross-border referrals.</li>
 *   <li>UNKNOWN.</li>
 * </ol>
 * If commission is converted to a single currency before crediting, step one
 * collapses everything into one bucket. In that case add a {@code userId}
 * column to the ledger entry (the referred user is known when
 * CommissionLedgerAspect writes the row) and resolve country from that user
 * instead — the rest of this class needs no change beyond
 * {@link #resolveCountry}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminCommissionService {

    private static final char KEY_SEP = '\u0000';

    private final CommissionLedgerEntryRepository ledgerRepo;
    private final UserRepository userRepo;

    // ─── Per-admin breakdown (legacy shape, country-agnostic) ───────────────

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.AdminCommissionPeriodDto> getDailyCommissionByAdmin(int days) {
        int safe = clampDays(days);
        log.info("getDailyCommissionByAdmin: days={}", safe);
        return bucketByAdmin(load(safe), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.AdminCommissionPeriodDto> getWeeklyCommissionByAdmin(int weeks) {
        int safe = clampWeeks(weeks);
        log.info("getWeeklyCommissionByAdmin: weeks={}", safe);
        return bucketByAdmin(load(safe * 7), this::weekLabel);
    }

    // ─── Platform totals (legacy shape) ─────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getDailyCommissionTotals(int days) {
        int safe = clampDays(days);
        log.info("getDailyCommissionTotals: days={}", safe);
        return bucketTotals(load(safe), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.PlatformPeriodTotalDto> getWeeklyCommissionTotals(int weeks) {
        int safe = clampWeeks(weeks);
        log.info("getWeeklyCommissionTotals: weeks={}", safe);
        return bucketTotals(load(safe * 7), this::weekLabel);
    }

    // ─── Country-split breakdowns ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.CountryPeriodTotalDto> getDailyByCountry(int days) {
        int safe = clampDays(days);
        log.info("getDailyByCountry commission: days={}", safe);
        return bucketByCountry(load(safe), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.CountryPeriodTotalDto> getWeeklyByCountry(int weeks) {
        int safe = clampWeeks(weeks);
        log.info("getWeeklyByCountry commission: weeks={}", safe);
        return bucketByCountry(load(safe * 7), this::weekLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.AdminCommissionCountryDto> getDailyByAdminAndCountry(int days) {
        int safe = clampDays(days);
        log.info("getDailyByAdminAndCountry: days={}", safe);
        return bucketByAdminAndCountry(load(safe), this::dayLabel);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminDtos.AdminCommissionCountryDto> getWeeklyByAdminAndCountry(int weeks) {
        int safe = clampWeeks(weeks);
        log.info("getWeeklyByAdminAndCountry: weeks={}", safe);
        return bucketByAdminAndCountry(load(safe * 7), this::weekLabel);
    }

    /** Whole-range roll-up per country, without the period dimension. */
    @Transactional(readOnly = true)
    public Map<String, CountryTotals> getTotalsByCountry(int days) {
        Map<String, List<Row>> byCountry = load(clampDays(days)).stream()
                .collect(Collectors.groupingBy(Row::country));

        Map<String, CountryTotals> out = new LinkedHashMap<>();
        for (var e : byCountry.entrySet()) {
            out.put(e.getKey(), new CountryTotals(
                    sum(e.getValue()),
                    e.getValue().size(),
                    currencyOf(e.getValue(), e.getKey())));
        }
        return out;
    }

    /** Carrier consumed by SuperAdminCountryReportService. */
    public record CountryTotals(BigDecimal amount, long count, String currency) {}

    // ─── Loading ─────────────────────────────────────────────────────────────

    /** A ledger entry paired with the country bucket it belongs to. */
    private record Row(CommissionLedgerEntry entry, String country) {}

    private List<Row> load(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<CommissionLedgerEntry> raw = ledgerRepo.findAllSince(since);
        if (raw == null || raw.isEmpty()) return List.of();

        List<CommissionLedgerEntry> usable = new ArrayList<>(raw.size());
        int skipped = 0;
        for (CommissionLedgerEntry e : raw) {
            if (e == null || e.getCreatedAt() == null) { skipped++; continue; }
            usable.add(e);
        }
        if (skipped > 0) log.warn("load: skipped {} ledger entries with null createdAt", skipped);
        if (usable.isEmpty()) return List.of();

        Map<UUID, User> adminsById = fetchAdmins(usable);

        List<Row> rows = new ArrayList<>(usable.size());
        for (CommissionLedgerEntry e : usable) {
            rows.add(new Row(e, resolveCountry(e, adminsById)));
        }
        return rows;
    }

    /**
     * Currency first, admin country second. See the class javadoc for why, and
     * for the migration path if commission is credited in a single currency.
     */
    private String resolveCountry(CommissionLedgerEntry e, Map<UUID, User> adminsById) {
        String fromCurrency = CountryUtils.fromCurrency(e.getCurrency());
        if (fromCurrency != null) return fromCurrency;

        User admin = e.getAdminId() != null ? adminsById.get(e.getAdminId()) : null;
        if (admin != null) return CountryUtils.normalize(admin.getCountry());

        return CountryUtils.UNKNOWN;
    }

    /** One batched fetch of every admin referenced in the range. */
    private Map<UUID, User> fetchAdmins(List<CommissionLedgerEntry> entries) {
        var ids = entries.stream()
                .map(CommissionLedgerEntry::getAdminId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        return userRepo.findAllById(ids).stream()
                .filter(u -> u != null && u.getId() != null)
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    private Map<UUID, String> adminEmails(List<Row> rows) {
        var ids = rows.stream()
                .map(r -> r.entry().getAdminId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        return userRepo.findAllById(ids).stream()
                .filter(u -> u != null && u.getId() != null)
                .collect(Collectors.toMap(
                        User::getId,
                        u -> u.getEmail() != null ? u.getEmail() : "UNKNOWN",
                        (a, b) -> a));
    }

    // ─── Label helpers ───────────────────────────────────────────────────────

    private String dayLabel(Row r) {
        return r.entry().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private String weekLabel(Row r) {
        var d = r.entry().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        var wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W"
                + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    // ─── Bucketing ───────────────────────────────────────────────────────────

    private List<SuperAdminDtos.AdminCommissionPeriodDto> bucketByAdmin(
            List<Row> rows, Function<Row, String> labelFn) {

        if (rows.isEmpty()) return List.of();

        var grouped = new TreeMap<String, List<Row>>();
        int orphaned = 0;
        for (Row r : rows) {
            UUID adminId = r.entry().getAdminId();
            if (adminId == null) { orphaned++; continue; }
            grouped.computeIfAbsent(labelFn.apply(r) + KEY_SEP + adminId,
                    k -> new ArrayList<>()).add(r);
        }
        if (orphaned > 0) log.warn("bucketByAdmin: skipped {} entries with null adminId", orphaned);
        if (grouped.isEmpty()) return List.of();

        var emailById = adminEmails(rows);
        var result = new ArrayList<SuperAdminDtos.AdminCommissionPeriodDto>(grouped.size());

        for (var e : grouped.entrySet()) {
            String[] parts = e.getKey().split(String.valueOf(KEY_SEP), 2);
            if (parts.length < 2) continue;
            UUID adminId = parseUuid(parts[1], e.getKey());
            if (adminId == null) continue;

            var list = e.getValue();
            result.add(new SuperAdminDtos.AdminCommissionPeriodDto(
                    parts[0], adminId, emailById.getOrDefault(adminId, "UNKNOWN"),
                    sum(list), currencyOf(list, list.get(0).country())));
        }
        return result;
    }

    private List<SuperAdminDtos.PlatformPeriodTotalDto> bucketTotals(
            List<Row> rows, Function<Row, String> labelFn) {

        if (rows.isEmpty()) return List.of();

        var grouped = new TreeMap<String, List<Row>>();
        for (Row r : rows) grouped.computeIfAbsent(labelFn.apply(r), k -> new ArrayList<>()).add(r);

        var result = new ArrayList<SuperAdminDtos.PlatformPeriodTotalDto>(grouped.size());
        for (var e : grouped.entrySet()) {
            var list = e.getValue();

            // A period spanning both currencies has no meaningful single total,
            // so it is labelled MIXED rather than presented as spendable money.
            Set<String> currencies = list.stream()
                    .map(r -> currencyOf(List.of(r), r.country()))
                    .collect(Collectors.toSet());
            String currency = currencies.size() == 1 ? currencies.iterator().next() : "MIXED";

            result.add(new SuperAdminDtos.PlatformPeriodTotalDto(
                    e.getKey(), sum(list), list.size(), currency));
        }
        return result;
    }

    private List<SuperAdminDtos.CountryPeriodTotalDto> bucketByCountry(
            List<Row> rows, Function<Row, String> labelFn) {

        if (rows.isEmpty()) return List.of();

        var grouped = new TreeMap<String, List<Row>>();
        for (Row r : rows) {
            grouped.computeIfAbsent(labelFn.apply(r) + KEY_SEP + r.country(),
                    k -> new ArrayList<>()).add(r);
        }

        var result = new ArrayList<SuperAdminDtos.CountryPeriodTotalDto>(grouped.size());
        for (var e : grouped.entrySet()) {
            String[] parts = e.getKey().split(String.valueOf(KEY_SEP), 2);
            if (parts.length < 2) continue;
            var list = e.getValue();
            result.add(new SuperAdminDtos.CountryPeriodTotalDto(
                    parts[0], parts[1], CountryUtils.displayName(parts[1]),
                    sum(list), list.size(), currencyOf(list, parts[1])));
        }
        return result;
    }

    private List<SuperAdminDtos.AdminCommissionCountryDto> bucketByAdminAndCountry(
            List<Row> rows, Function<Row, String> labelFn) {

        if (rows.isEmpty()) return List.of();

        var grouped = new TreeMap<String, List<Row>>();
        for (Row r : rows) {
            UUID adminId = r.entry().getAdminId();
            if (adminId == null) continue;
            grouped.computeIfAbsent(
                    labelFn.apply(r) + KEY_SEP + adminId + KEY_SEP + r.country(),
                    k -> new ArrayList<>()).add(r);
        }
        if (grouped.isEmpty()) return List.of();

        var emailById = adminEmails(rows);
        var result = new ArrayList<SuperAdminDtos.AdminCommissionCountryDto>(grouped.size());

        for (var e : grouped.entrySet()) {
            String[] parts = e.getKey().split(String.valueOf(KEY_SEP), 3);
            if (parts.length < 3) continue;
            UUID adminId = parseUuid(parts[1], e.getKey());
            if (adminId == null) continue;

            var list = e.getValue();
            result.add(new SuperAdminDtos.AdminCommissionCountryDto(
                    parts[0], adminId, emailById.getOrDefault(adminId, "UNKNOWN"),
                    parts[2], CountryUtils.displayName(parts[2]),
                    sum(list), list.size(), currencyOf(list, parts[2])));
        }
        return result;
    }

    // ─── Small helpers ───────────────────────────────────────────────────────

    private BigDecimal sum(List<Row> list) {
        return list.stream()
                .map(r -> r.entry().getAmount())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String currencyOf(List<Row> list, String countryFallback) {
        return list.stream()
                .map(r -> r.entry().getCurrency())
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .findFirst()
                .orElse(CountryUtils.currencyOf(countryFallback));
    }

    private UUID parseUuid(String raw, String contextKey) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            log.warn("Unparseable adminId in bucket key '{}'", contextKey);
            return null;
        }
    }

    private int clampDays(int days) {
        if (days < 1 || days > 365) {
            log.warn("clampDays: {} outside 1–365, using 30", days);
            return 30;
        }
        return days;
    }

    private int clampWeeks(int weeks) {
        if (weeks < 1 || weeks > 52) {
            log.warn("clampWeeks: {} outside 1–52, using 12", weeks);
            return 12;
        }
        return weeks;
    }
}
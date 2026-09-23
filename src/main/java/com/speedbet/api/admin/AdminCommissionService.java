package com.speedbet.api.admin;

import com.speedbet.api.affiliate.AffiliateCommissionBalance;
import com.speedbet.api.affiliate.AffiliateCommissionBalanceRepository;
import com.speedbet.api.affiliate.AffiliateWithdrawalRepository;
import com.speedbet.api.affiliate.AffiliateWithdrawalRequest;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.wallet.AdminDepositRow;
import com.speedbet.api.wallet.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCommissionService {
    private static final ZoneOffset REPORT_ZONE = ZoneOffset.UTC;

    private final CommissionLedgerEntryRepository ledgerRepo;
    private final TransactionRepository transactionRepo;
    private final AffiliateCommissionBalanceRepository balanceRepo;
    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public AdminCommissionDtos.DailyCommissionDto getDailyCommission(UUID adminId, LocalDate date) {
        LocalDate reportDate = date == null ? LocalDate.now(REPORT_ZONE) : date;
        Instant from = reportDate.atStartOfDay(REPORT_ZONE).toInstant();
        Instant until = reportDate.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();

        var ledger = ledgerRepo.findByAdminIdSince(adminId, from).stream()
                .filter(e -> e.getCreatedAt() != null && e.getCreatedAt().isBefore(until)).toList();
        BigDecimal commission = ledger.stream().map(CommissionLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = ledger.stream().map(CommissionLedgerEntry::getCurrency)
                .filter(c -> c != null && !c.isBlank()).findFirst().orElse("GHS");

        List<AdminDepositRow> rows = transactionRepo.findAdminDepositsBetween(adminId, from, until);
        Map<UUID, UserDepositAccumulator> grouped = new TreeMap<>(Comparator.comparing(UUID::toString));
        BigDecimal totalDeposits = BigDecimal.ZERO;
        for (AdminDepositRow row : rows) {
            BigDecimal amount = row.amount() == null ? BigDecimal.ZERO : row.amount();
            totalDeposits = totalDeposits.add(amount);
            grouped.computeIfAbsent(row.userId(), ignored -> new UserDepositAccumulator(row))
                    .add(amount);
        }
        List<AdminCommissionDtos.UserDailyDepositDto> byUser = grouped.values().stream()
                .map(UserDepositAccumulator::toDto).toList();
        return new AdminCommissionDtos.DailyCommissionDto(
                reportDate, commission, currency, totalDeposits, rows.size(), byUser);
    }

    /** Returns the most recent processed payout for this admin, for notification polling. */
    @Transactional(readOnly = true)
    public AdminCommissionDtos.CommissionPayoutNotificationDto latestPaidPayout(UUID adminId) {
        return withdrawalRepo.findByUserIdOrderByRequestedAtDesc(adminId,
                        org.springframework.data.domain.PageRequest.of(0, 1)).getContent().stream()
                .filter(p -> p.getProcessedAt() != null && p.getStatus().name().equals("PROCESSED"))
                .findFirst()
                .map(this::toNotification)
                .orElse(null);
    }

    private AdminCommissionDtos.CommissionPayoutNotificationDto toNotification(AffiliateWithdrawalRequest payout) {
        return new AdminCommissionDtos.CommissionPayoutNotificationDto(
                payout.getId(), payout.getAmount(), payout.getCurrency(), payout.getReference(),
                payout.getStatus().name(), payout.getProcessedAt(),
                "Your commission payout has been paid and your commission balance was cleared.");
    }

    private static final class UserDepositAccumulator {
        private final AdminDepositRow first;
        private BigDecimal total = BigDecimal.ZERO;
        private long count;
        private UserDepositAccumulator(AdminDepositRow first) { this.first = first; }
        private void add(BigDecimal amount) { total = total.add(amount); count++; }
        private AdminCommissionDtos.UserDailyDepositDto toDto() {
            return new AdminCommissionDtos.UserDailyDepositDto(first.userId(), first.email(), first.firstName(),
                    first.lastName(), first.country(), total, count);
        }
    }

    // Existing period methods retained for backwards compatibility.
    public List<AffiliateCommissionPeriodDTO> getDailyCommission(UUID adminId, int days) {
        var since = Instant.now().minus(days, ChronoUnit.DAYS);
        return bucket(ledgerRepo.findByAdminIdSince(adminId, since), e -> e.getCreatedAt().atZone(REPORT_ZONE).toLocalDate().toString());
    }
    public List<AffiliateCommissionPeriodDTO> getWeeklyCommission(UUID adminId, int weeks) {
        var since = Instant.now().minus((long) weeks * 7, ChronoUnit.DAYS);
        var wf = WeekFields.ISO;
        return bucket(ledgerRepo.findByAdminIdSince(adminId, since), e -> {
            var d = e.getCreatedAt().atZone(REPORT_ZONE).toLocalDate();
            return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
        });
    }
    public List<AffiliateCommissionPeriodDTO> getMonthlyCommission(UUID adminId, int months) {
        var since = Instant.now().minus((long) months * 31, ChronoUnit.DAYS);
        return bucket(ledgerRepo.findByAdminIdSince(adminId, since), e -> {
            var d = e.getCreatedAt().atZone(REPORT_ZONE).toLocalDate();
            return d.getYear() + "-" + String.format("%02d", d.getMonthValue());
        });
    }
    private List<AffiliateCommissionPeriodDTO> bucket(List<CommissionLedgerEntry> entries, Function<CommissionLedgerEntry, String> keyFn) {
        var grouped = new TreeMap<String, List<CommissionLedgerEntry>>();
        for (var e : entries) grouped.computeIfAbsent(keyFn.apply(e), k -> new ArrayList<>()).add(e);
        var result = new ArrayList<AffiliateCommissionPeriodDTO>();
        for (var entry : grouped.entrySet()) {
            var list = entry.getValue();
            result.add(new AffiliateCommissionPeriodDTO(entry.getKey(), list.get(0).getCreatedAt(),
                    list.stream().map(CommissionLedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                    list.get(0).getCurrency()));
        }
        return result;
    }
}

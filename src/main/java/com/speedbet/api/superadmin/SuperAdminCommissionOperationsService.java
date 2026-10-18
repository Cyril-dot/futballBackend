package com.speedbet.api.superadmin;

import com.speedbet.api.affiliate.AffiliateCommissionBalance;
import com.speedbet.api.affiliate.AffiliateCommissionBalanceRepository;
import com.speedbet.api.affiliate.AffiliateCommissionService;
import com.speedbet.api.affiliate.AffiliateWithdrawalRequest;
import com.speedbet.api.affiliate.AffiliateWithdrawalRepository;
import com.speedbet.api.affiliate.AffiliateWithdrawalStatus;
import com.speedbet.api.admin.CommissionLedgerEntry;
import com.speedbet.api.admin.CommissionLedgerEntryRepository;
import com.speedbet.api.referral.ReferralLink;
import com.speedbet.api.referral.ReferralLinkRepository;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.wallet.DepositRow;
import com.speedbet.api.wallet.TransactionRepository;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletRepository;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminCommissionOperationsService {
    private static final ZoneOffset REPORT_ZONE = ZoneOffset.UTC;

    private final UserRepository userRepo;
    private final ReferralLinkRepository referralLinkRepo;
    private final AffiliateCommissionBalanceRepository balanceRepo;
    private final AffiliateCommissionService commissionService;
    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final TransactionRepository transactionRepo;
    private final WalletRepository walletRepo;
    private final WalletService walletService;
    private final CommissionLedgerEntryRepository ledgerRepo;

    @Transactional(readOnly = true)
    public List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto> daily(LocalDate date, UUID adminId) {
        try {
            return dailyInternal(date, adminId);
        } catch (RuntimeException ex) {
            log.error("commission.daily.failed date={} adminId={} type={} message={}", date, adminId,
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto> dailyInternal(LocalDate date, UUID adminId) {
        LocalDate reportDate = date == null ? LocalDate.now(REPORT_ZONE) : date;
        Instant from = reportDate.atStartOfDay(REPORT_ZONE).toInstant();
        Instant to = reportDate.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();

        List<User> admins = adminId == null
                ? userRepo.findAllByRole(UserRole.ADMIN)
                : List.of(userRepo.findById(adminId)
                    .filter(u -> u.getRole() == UserRole.ADMIN)
                    .orElseThrow(() -> new IllegalArgumentException("Admin not found: " + adminId)));
        Map<UUID, AffiliateCommissionBalance> balances = balanceRepo.findAll().stream()
                .collect(Collectors.toMap(AffiliateCommissionBalance::getUserId, b -> b, (a, b) -> a));

        List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto> result = new ArrayList<>();
        for (User admin : admins) {
            List<ReferralLink> links = referralLinkRepo.findByAdminId(admin.getId());
            BigDecimal rate = links.stream().map(ReferralLink::getCommissionPercent)
                    .filter(Objects::nonNull).findFirst().orElse(BigDecimal.ZERO);
            List<DepositRow> deposits = transactionRepo.findDepositsByAdminSince(admin.getId(), from).stream()
                    .filter(d -> d.createdAt() != null && !d.createdAt().isBefore(from) && d.createdAt().isBefore(to))
                    .toList();
            List<CommissionLedgerEntry> ledger = ledgerRepo.findByAdminIdSince(admin.getId(), from).stream()
                    .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isBefore(from)
                            && e.getCreatedAt().isBefore(to) && e.getPaidAt() == null)
                    .toList();
            AffiliateCommissionBalance balance = balances.get(admin.getId());
            BigDecimal balanceAmount = balance == null ? BigDecimal.ZERO : balance.getBalance();

            // -- Per-currency split -------------------------------------------------
            // Deposits carry the depositor's country, so they group cleanly by
            // currency. The commission ledger records every credit as GHS (see
            // ReferralService.attributeCommission), so per-currency commission
            // is derived with the same formula used at credit time:
            // deposit x rate / 100. One row is emitted per (admin, currency);
            // cedis and naira are never added together.
            Map<String, BigDecimal> depositsByCurrency = new LinkedHashMap<>();
            Map<String, Long> countsByCurrency = new LinkedHashMap<>();
            for (DepositRow d : deposits) {
                String currency = CountryUtils.currencyOf(CountryUtils.normalize(d.country()));
                depositsByCurrency.merge(currency, d.amount() == null ? BigDecimal.ZERO : d.amount(), BigDecimal::add);
                countsByCurrency.merge(currency, 1L, Long::sum);
            }
            boolean hasUnpaid = !ledger.isEmpty();
            if (depositsByCurrency.isEmpty()) {
                // No deposits today - preserve the previous single-row shape so
                // the admin still appears (the frontend hides zero-activity rows).
                String currency = ledger.stream().map(CommissionLedgerEntry::getCurrency).filter(Objects::nonNull)
                        .findFirst().orElse(balance != null ? balance.getCurrency()
                                : CountryUtils.currencyOf(CountryUtils.normalize(admin.getCountry())));
                BigDecimal earned = ledger.stream().map(CommissionLedgerEntry::getAmount).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                result.add(new SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto(
                        reportDate, admin.getId(), admin.getEmail(), displayName(admin), rate, earned,
                        currency, balanceAmount, BigDecimal.ZERO, 0));
            } else {
                for (Map.Entry<String, BigDecimal> entry : depositsByCurrency.entrySet()) {
                    String currency = entry.getKey();
                    BigDecimal depositTotal = entry.getValue();
                    long depositCount = countsByCurrency.getOrDefault(currency, 0L);
                    BigDecimal earned = hasUnpaid
                            ? depositTotal.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    result.add(new SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto(
                            reportDate, admin.getId(), admin.getEmail(), displayName(admin), rate, earned,
                            currency, balanceAmount, depositTotal, depositCount));
                }
            }
        }
        result.sort(Comparator.comparing(SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto::adminEmail,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    /**
     * Tops up one admin's commission for a UTC day so the amount credited for
     * that day equals referred deposits × the admin's current link rate.
     *
     * Why this does not simply call AffiliateCommissionService.creditCommission:
     * the CommissionLedgerAspect stamps every entry it writes with the instant
     * the credit happens, so a top-up for a past day would land in today's
     * reports — and a re-run for that past day would compute the same
     * shortfall again and double-pay. Instead this mirrors
     * creditCommission's balance update directly and writes the ledger entry
     * dated inside the target day, which keeps reconcile idempotent for any
     * date: a second run sees the top-up in the day's entries and adds zero.
     */
    @Transactional
    public SuperAdminCommissionOperationsDtos.ReconcileCommissionDto reconcile(UUID adminId, LocalDate date) {
        LocalDate reportDate = date == null ? LocalDate.now(REPORT_ZONE) : date;
        User admin = getAdmin(adminId);
        Instant from = reportDate.atStartOfDay(REPORT_ZONE).toInstant();
        Instant to = reportDate.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();

        BigDecimal rate = referralLinkRepo.findByAdminId(adminId).stream()
                .map(ReferralLink::getCommissionPercent)
                .filter(Objects::nonNull)
                .findFirst().orElse(BigDecimal.ZERO);

        BigDecimal totalDeposits = transactionRepo.findAdminDepositsBetween(adminId, from, to).stream()
                .map(row -> row.amount() == null ? BigDecimal.ZERO : row.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expected = totalDeposits.multiply(rate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        List<CommissionLedgerEntry> unpaid = unpaidEntries(adminId, reportDate);
        BigDecimal credited = sum(unpaid);
        BigDecimal shortfall = expected.subtract(credited);

        AffiliateCommissionBalance balance = balanceRepo.findByUserId(adminId).orElse(null);
        String currency = unpaid.stream().map(CommissionLedgerEntry::getCurrency)
                .filter(Objects::nonNull).findFirst()
                .orElse(balance != null ? balance.getCurrency()
                        : CountryUtils.currencyOf(admin.getCountry()));

        BigDecimal toppedUp = BigDecimal.ZERO;
        if (shortfall.compareTo(BigDecimal.ZERO) > 0) {
            AffiliateCommissionBalance bal = balanceRepo.findByUserIdForUpdate(adminId)
                    .orElseGet(() -> balanceRepo.save(AffiliateCommissionBalance.builder()
                            .userId(adminId)
                            .currency(currency)
                            .build()));
            if (!bal.getCurrency().equalsIgnoreCase(currency))
                throw new IllegalArgumentException("Commission currency mismatch: expected "
                        + bal.getCurrency() + ", got " + currency);
            bal.setBalance(bal.getBalance().add(shortfall));
            bal.setTotalEarnedLifetime(bal.getTotalEarnedLifetime().add(shortfall));
            balanceRepo.save(bal);
            balance = bal;

            ledgerRepo.save(CommissionLedgerEntry.builder()
                    .adminId(adminId)
                    .amount(shortfall)
                    .currency(currency)
                    .createdAt(reportDate.atTime(12, 0).toInstant(REPORT_ZONE))
                    .build());
            toppedUp = shortfall;
        }

        log.info("commission.reconcile adminId={} date={} rate={} deposits={} expected={} credited={} toppedUp={}",
                adminId, reportDate, rate, totalDeposits, expected, credited, toppedUp);
        return new SuperAdminCommissionOperationsDtos.ReconcileCommissionDto(
                reportDate, adminId, admin.getEmail(), rate, totalDeposits, expected,
                credited, toppedUp, currency,
                balance == null ? BigDecimal.ZERO : balance.getBalance());
    }

    @Transactional
    public SuperAdminCommissionOperationsDtos.CommissionPayoutDto markPaid(UUID adminId, LocalDate date) {
        LocalDate reportDate = date == null ? LocalDate.now(REPORT_ZONE) : date;
        String operationId = UUID.randomUUID().toString();
        log.info("commission.pay.start operationId={} adminId={} date={}", operationId, adminId, reportDate);
        try {
            var result = settleOne(getAdmin(adminId), reportDate, operationId);
            log.info("commission.pay.success operationId={} adminId={} date={} amount={}", operationId, adminId, reportDate, result.amount());
            return result;
        } catch (RuntimeException ex) {
            log.error("commission.pay.failed operationId={} adminId={} date={} type={} message={}", operationId, adminId, reportDate, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            throw ex;
        }
    }

    @Transactional
    public SuperAdminCommissionOperationsDtos.ClearCommissionResult clearAll(LocalDate date) {
        LocalDate reportDate = date == null ? LocalDate.now(REPORT_ZONE) : date;
        Instant clearedAt = Instant.now();
        String operationId = UUID.randomUUID().toString();
        log.info("commission.clear.start operationId={} date={}", operationId, reportDate);
        BigDecimal total = BigDecimal.ZERO;
        List<UUID> ids = new ArrayList<>();
        for (User admin : userRepo.findAllByRole(UserRole.ADMIN)) {
            List<CommissionLedgerEntry> unpaid = unpaidEntries(admin.getId(), reportDate);
            BigDecimal amount = sum(unpaid);
            BigDecimal available = balanceRepo.findByUserId(admin.getId()).map(AffiliateCommissionBalance::getBalance).orElse(BigDecimal.ZERO);
            if (amount.compareTo(BigDecimal.ZERO) <= 0 || available.compareTo(BigDecimal.ZERO) <= 0) continue;
            var payout = settleOne(admin, reportDate, operationId);
            log.info("commission.clear.admin-success operationId={} adminId={} date={} amount={}", operationId, admin.getId(), reportDate, payout.amount());
            total = total.add(payout.amount());
            ids.add(admin.getId());
        }
        log.info("commission.clear.success operationId={} date={} admins={} amount={}", operationId, reportDate, ids.size(), total);
        return new SuperAdminCommissionOperationsDtos.ClearCommissionResult(ids.size(), total, ids, clearedAt);
    }

    private SuperAdminCommissionOperationsDtos.CommissionPayoutDto settleOne(User admin, LocalDate reportDate, String operationId) {
        List<CommissionLedgerEntry> entries = unpaidEntries(admin.getId(), reportDate);
        BigDecimal amount = sum(entries);
        log.info("commission.settle.inspect operationId={} adminId={} date={} entries={} amount={}", operationId, admin.getId(), reportDate, entries.size(), amount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Admin has no unpaid commission for " + reportDate);

        AffiliateCommissionBalance balance = balanceRepo.findByUserIdForUpdate(admin.getId())
                .orElseThrow(() -> new IllegalArgumentException("Commission balance not found for admin: " + admin.getId()));
        log.info("commission.settle.balance operationId={} adminId={} balance={} selectedAmount={} walletPresent={}", operationId, admin.getId(), balance.getBalance(), amount, walletRepo.findByUserId(admin.getId()).isPresent());
        BigDecimal payable = balance.getBalance().min(amount);
        if (balance.getBalance().compareTo(amount) < 0) {
            log.warn("commission.settle.balance-mismatch operationId={} adminId={} date={} ledgerAmount={} balance={} payable={}",
                    operationId, admin.getId(), reportDate, amount, balance.getBalance(), payable);
        }

        Instant paidAt = Instant.now();
        entries.forEach(entry -> entry.setPaidAt(paidAt));
        ledgerRepo.saveAll(entries);
        balance.setBalance(balance.getBalance().subtract(payable));
        balance.setTotalPaidOutLifetime(balance.getTotalPaidOutLifetime().add(payable));
        balance.setLastPayoutAt(paidAt);
        balanceRepo.save(balance);

        String reference = "MANUAL-COMM-PAYOUT-" + admin.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                + "-" + reportDate + "-" + paidAt.toEpochMilli();
        AffiliateWithdrawalRequest payout = withdrawalRepo.save(AffiliateWithdrawalRequest.builder()
                .userId(admin.getId()).amount(payable).currency(balance.getCurrency())
                .status(AffiliateWithdrawalStatus.PROCESSED).reference(reference)
                .requestedAt(paidAt).processedAt(paidAt).build());
        if (walletRepo.findByUserId(admin.getId()).isPresent()) {
            walletService.recordExternalDebit(admin.getId(), payable, TxKind.AFFILIATE_COMMISSION_PAYOUT, reference,
                    Map.of("type", "manual_affiliate_commission_payout", "paidBy", "SUPER_ADMIN",
                            "commissionDate", reportDate.toString(), "withdrawalRequestId", payout.getId().toString()));
        }
        return new SuperAdminCommissionOperationsDtos.CommissionPayoutDto(
                payout.getId(), admin.getId(), admin.getEmail(), payable, balance.getCurrency(), reference,
                payout.getStatus().name(), payout.getProcessedAt());
    }

    private List<CommissionLedgerEntry> unpaidEntries(UUID adminId, LocalDate date) {
        Instant from = date.atStartOfDay(REPORT_ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
        return ledgerRepo.findUnpaidByAdminIdBetween(adminId, from, to);
    }

    private BigDecimal sum(List<CommissionLedgerEntry> entries) {
        return entries.stream().map(CommissionLedgerEntry::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private User getAdmin(UUID adminId) {
        return userRepo.findById(adminId)
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .orElseThrow(() -> new IllegalArgumentException("Admin not found: " + adminId));
    }

    private String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String name = (first + " " + last).trim();
        return name.isBlank() ? user.getEmail() : name;
    }
}

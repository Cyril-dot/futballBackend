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
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SuperAdminCommissionOperationsService {
    private static final ZoneOffset REPORT_ZONE = ZoneOffset.UTC;

    private final UserRepository userRepo;
    private final ReferralLinkRepository referralLinkRepo;
    private final AffiliateCommissionBalanceRepository balanceRepo;
    private final AffiliateCommissionService commissionService;
    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final TransactionRepository transactionRepo;
    private final WalletService walletService;
    private final CommissionLedgerEntryRepository ledgerRepo;

    @Transactional(readOnly = true)
    public List<SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto> daily(LocalDate date, UUID adminId) {
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
                    .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isBefore(from) && e.getCreatedAt().isBefore(to))
                    .toList();
            AffiliateCommissionBalance balance = balances.get(admin.getId());
            String currency = ledger.stream().map(CommissionLedgerEntry::getCurrency).filter(Objects::nonNull)
                    .findFirst().orElse(balance != null ? balance.getCurrency() : CountryUtils.currencyOf(admin.getCountry()));
            BigDecimal depositTotal = deposits.stream().map(DepositRow::amount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal earned = ledger.stream().map(CommissionLedgerEntry::getAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto(
                    reportDate, admin.getId(), admin.getEmail(), displayName(admin), rate, earned,
                    currency, balance == null ? BigDecimal.ZERO : balance.getBalance(), depositTotal, deposits.size()));
        }
        result.sort(Comparator.comparing(SuperAdminCommissionOperationsDtos.DailyAdminCommissionDto::adminEmail,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    @Transactional
    public SuperAdminCommissionOperationsDtos.CommissionPayoutDto markPaid(UUID adminId) {
        User admin = getAdmin(adminId);
        AffiliateCommissionBalance balance = balanceRepo.findByUserIdForUpdate(adminId)
                .orElseThrow(() -> new IllegalArgumentException("Commission balance not found for admin: " + adminId));
        BigDecimal amount = balance.getBalance();
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Admin has no unpaid commission balance");

        BigDecimal paid = commissionService.sweepCommissionBalance(adminId);
        String reference = "MANUAL-COMM-PAYOUT-" + adminId.toString().substring(0, 8).toUpperCase(Locale.ROOT)
                + "-" + Instant.now().toEpochMilli();
        AffiliateWithdrawalRequest payout = withdrawalRepo.save(AffiliateWithdrawalRequest.builder()
                .userId(adminId).amount(paid).currency(balance.getCurrency())
                .status(AffiliateWithdrawalStatus.PROCESSED).reference(reference)
                .requestedAt(Instant.now()).processedAt(Instant.now()).build());
        walletService.recordExternalDebit(adminId, paid, TxKind.AFFILIATE_COMMISSION_PAYOUT, reference,
                Map.of("type", "manual_affiliate_commission_payout", "paidBy", "SUPER_ADMIN",
                        "withdrawalRequestId", payout.getId().toString()));
        return new SuperAdminCommissionOperationsDtos.CommissionPayoutDto(
                payout.getId(), adminId, admin.getEmail(), paid, balance.getCurrency(), reference,
                payout.getStatus().name(), payout.getProcessedAt());
    }

    @Transactional
    public SuperAdminCommissionOperationsDtos.ClearCommissionResult clearAll() {
        Instant clearedAt = Instant.now();
        List<AffiliateCommissionBalance> balances = balanceRepo.findAllWithPositiveBalance();
        BigDecimal total = BigDecimal.ZERO;
        List<UUID> ids = new ArrayList<>();
        for (AffiliateCommissionBalance balance : balances) {
            BigDecimal paid = balance.getBalance();
            total = total.add(paid);
            ids.add(balance.getUserId());
            commissionService.sweepCommissionBalance(balance.getUserId());
            String reference = "MANUAL-COMM-CLEAR-" + balance.getUserId().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                    + "-" + clearedAt.toEpochMilli();
            AffiliateWithdrawalRequest payout = withdrawalRepo.save(AffiliateWithdrawalRequest.builder()
                    .userId(balance.getUserId()).amount(paid).currency(balance.getCurrency())
                    .status(AffiliateWithdrawalStatus.PROCESSED).reference(reference)
                    .requestedAt(clearedAt).processedAt(clearedAt).build());
            walletService.recordExternalDebit(balance.getUserId(), paid, TxKind.AFFILIATE_COMMISSION_PAYOUT,
                    reference, Map.of("type", "manual_affiliate_commission_clear", "paidBy", "SUPER_ADMIN",
                            "withdrawalRequestId", payout.getId().toString()));
        }
        return new SuperAdminCommissionOperationsDtos.ClearCommissionResult(ids.size(), total, ids, clearedAt);
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

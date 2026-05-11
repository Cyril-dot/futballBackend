package com.speedbet.api.superadmin;

import com.speedbet.api.affiliate.AffiliateWithdrawalRepository;
import com.speedbet.api.affiliate.AffiliateWithdrawalStatus;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralLink;
import com.speedbet.api.referral.ReferralLinkRepository;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.wallet.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminQueryService {

    private final UserRepository userRepo;
    private final WalletRepository walletRepo;
    private final TransactionRepository txRepo;
    private final AffiliateWithdrawalRepository withdrawalRepo;
    private final ReferralLinkRepository referralLinkRepo;

    // ─── Revenue / Deposit Overview ───────────────────────────────────────────

    public SuperAdminDtos.RevenueOverviewDto getRevenueOverview() {
        log.info("getRevenueOverview: computing platform-wide stats");

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfMonth = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfMonth() - 1L, ChronoUnit.DAYS);

        BigDecimal depositsAllTime    = txRepo.sumAllByKind(TxKind.DEPOSIT);
        BigDecimal depositsThisMonth  = txRepo.sumAllByKindSince(TxKind.DEPOSIT, startOfMonth);
        BigDecimal depositsToday      = txRepo.sumAllByKindSince(TxKind.DEPOSIT, startOfToday);
        BigDecimal withdrawalsAllTime = txRepo.sumAllByKind(TxKind.WITHDRAW);
        BigDecimal withdrawalsMonth   = txRepo.sumAllByKindSince(TxKind.WITHDRAW, startOfMonth);
        long depositCount             = txRepo.countByKind(TxKind.DEPOSIT);
        long withdrawalCount          = txRepo.countByKind(TxKind.WITHDRAW);

        return new SuperAdminDtos.RevenueOverviewDto(
                depositsAllTime,
                depositsThisMonth,
                depositsToday,
                withdrawalsAllTime,
                withdrawalsMonth,
                depositCount,
                withdrawalCount,
                "GHS"
        );
    }

    // ─── All Users (paginated + search) ──────────────────────────────────────

    public Page<SuperAdminDtos.UserSummaryDto> listUsers(
            String search, UserRole role, Pageable pageable) {
        log.info("listUsers: search='{}' role={}", search, role);
        return userRepo.findAllFiltered(role, search, pageable)
                .map(this::toUserSummary);
    }

    // ─── Single User Detail ───────────────────────────────────────────────────

    public SuperAdminDtos.UserDetailDto getUserDetail(UUID userId) {
        log.info("getUserDetail: userId='{}'", userId);
        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        Wallet wallet = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        long txCount             = txRepo.countByWalletId(wallet.getId());
        BigDecimal walletDeposits    = txRepo.sumByKindSince(wallet.getId(), TxKind.DEPOSIT,  Instant.EPOCH);
        BigDecimal walletWithdrawals = txRepo.sumByKindSince(wallet.getId(), TxKind.WITHDRAW, Instant.EPOCH);

        SuperAdminDtos.WalletSummaryDto walletDto = new SuperAdminDtos.WalletSummaryDto(
                wallet.getId(),
                wallet.getBalance(),
                wallet.getCurrency(),
                txCount,
                walletDeposits,
                walletWithdrawals
        );

        Instant createdAt = user.getCreatedAt() != null
                ? user.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null;

        return new SuperAdminDtos.UserDetailDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getCountry(),
                user.getRole().name(),
                user.isEmailVerified(),
                createdAt,
                walletDto
        );
    }

    // ─── Single Admin Detail ──────────────────────────────────────────────────

    public SuperAdminDtos.AdminDetailDto getAdminDetail(UUID adminId) {
        log.info("getAdminDetail: adminId='{}'", adminId);

        User admin = userRepo.findById(adminId)
                .orElseThrow(() -> ApiException.notFound("Admin not found"));

        if (admin.getRole() != UserRole.ADMIN) {
            throw ApiException.badRequest("User is not an ADMIN");
        }

        Wallet wallet = walletRepo.findByUserId(adminId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        long txCount         = txRepo.countByWalletId(wallet.getId());
        BigDecimal deposited = txRepo.sumByKindSince(wallet.getId(), TxKind.DEPOSIT,  Instant.EPOCH);
        BigDecimal withdrawn = txRepo.sumByKindSince(wallet.getId(), TxKind.WITHDRAW, Instant.EPOCH);

        SuperAdminDtos.WalletSummaryDto walletDto = new SuperAdminDtos.WalletSummaryDto(
                wallet.getId(), wallet.getBalance(), wallet.getCurrency(),
                txCount, deposited, withdrawn
        );

        List<ReferralLink> links = referralLinkRepo.findByAdminId(adminId);
        SuperAdminDtos.ReferralSummaryDto referralDto = links.stream()
                .filter(ReferralLink::isActive)
                .findFirst()
                .or(() -> links.stream().findFirst())
                .map(link -> new SuperAdminDtos.ReferralSummaryDto(
                        link.getId(),
                        link.getCode(),
                        link.getCommissionPercent(),
                        null,
                        null
                ))
                .orElse(null);

        Instant adminCreatedAt = admin.getCreatedAt() != null
                ? admin.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null;

        return new SuperAdminDtos.AdminDetailDto(
                admin.getId(),
                admin.getEmail(),
                admin.getFirstName(),
                admin.getLastName(),
                admin.getPhone(),
                admin.getCountry(),
                admin.isEmailVerified(),
                adminCreatedAt,
                walletDto,
                referralDto
        );
    }

    // ─── Platform-wide Transactions (paginated + filtered) ───────────────────

    public Page<SuperAdminDtos.TransactionDto> listTransactions(
            TxKind kind, String status, UUID walletId,
            Instant from, Instant to, Pageable pageable) {
        log.info("listTransactions: kind={} status={} walletId={}", kind, status, walletId);

        // Convert enum to String (or null) — required because findAllFiltered is a
        // native query and cannot bind Java enums directly.
        String kindStr = kind != null ? kind.name() : null;

        return txRepo.findAllFiltered(kindStr, status, walletId, from, to, pageable)
                .map(tx -> {
                    UUID userId = walletRepo.findById(tx.getWalletId())
                            .map(Wallet::getUserId).orElse(null);
                    String email = userId != null
                            ? userRepo.findById(userId).map(User::getEmail).orElse(null)
                            : null;
                    return toTransactionDto(tx, userId, email);
                });
    }

    // ─── Affiliate Withdrawal History (all statuses) ──────────────────────────

    public Page<com.speedbet.api.affiliate.AffiliateWithdrawalRequest> listWithdrawals(
            AffiliateWithdrawalStatus status, Pageable pageable) {
        log.info("listWithdrawals: status={}", status);
        if (status != null) {
            return withdrawalRepo.findByStatusOrderByRequestedAtDesc(status, pageable);
        }
        return withdrawalRepo.findAll(pageable);
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private SuperAdminDtos.UserSummaryDto toUserSummary(User u) {
        return new SuperAdminDtos.UserSummaryDto(
                u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(),
                u.getPhone(), u.getCountry(), u.getRole().name(),
                u.isEmailVerified(),
                u.getCreatedAt() != null ? u.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null
        );
    }

    private SuperAdminDtos.TransactionDto toTransactionDto(
            Transaction tx, UUID userId, String email) {
        return new SuperAdminDtos.TransactionDto(
                tx.getId(), tx.getWalletId(), userId, email,
                tx.getKind(), tx.getAmount(), tx.getBalanceAfter(),
                tx.getProviderRef(), tx.getStatus(), tx.getMetadata(),
                tx.getCreatedAt()
        );
    }
}
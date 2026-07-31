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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * NOTE: this sums every deposit into one figure and labels it GHS. If
     * Nigerian deposits are recorded in naira, that total mixes currencies.
     * The country-split endpoints under /commission/country-report are the
     * correct source for per-currency figures; this overview is kept for the
     * legacy dashboard tiles.
     */
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
                depositsAllTime, depositsThisMonth, depositsToday,
                withdrawalsAllTime, withdrawalsMonth,
                depositCount, withdrawalCount, "GHS");
    }

    // ─── All Users (paginated + search) ──────────────────────────────────────

    /**
     * Uses JPA Specifications so null params are simply omitted from the WHERE
     * clause — avoids the PostgreSQL enum-cast error that plagued the old
     * @Query("... :role IS NULL OR u.role = :role ...") approach with Hibernate.
     */
    public Page<SuperAdminDtos.UserSummaryDto> listUsers(
            String search, UserRole role, Pageable pageable) {
        log.info("listUsers: search='{}' role={}", search, role);

        Specification<User> spec = Specification.where(null);

        if (role != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("role"), role));
        }

        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")),     pattern),
                    cb.like(cb.lower(root.get("firstName")), pattern),
                    cb.like(cb.lower(root.get("lastName")),  pattern)
            ));
        }

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return userRepo.findAll(spec, p).map(this::toUserSummary);
    }

    // ─── Single User Detail ───────────────────────────────────────────────────

    public SuperAdminDtos.UserDetailDto getUserDetail(UUID userId) {
        log.info("getUserDetail: userId='{}'", userId);
        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        Wallet wallet = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        long txCount                 = txRepo.countByWalletId(wallet.getId());
        BigDecimal walletDeposits    = txRepo.sumByKindSince(wallet.getId(), TxKind.DEPOSIT,  Instant.EPOCH);
        BigDecimal walletWithdrawals = txRepo.sumByKindSince(wallet.getId(), TxKind.WITHDRAW, Instant.EPOCH);

        var walletDto = new SuperAdminDtos.WalletSummaryDto(
                wallet.getId(), wallet.getBalance(), wallet.getCurrency(),
                txCount, walletDeposits, walletWithdrawals);

        Instant createdAt = user.getCreatedAt() != null
                ? user.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null;

        return new SuperAdminDtos.UserDetailDto(
                user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getPhone(), user.getCountry(),
                user.getRole().name(), user.getStatus().name(),
                user.isEmailVerified(), createdAt, walletDto);
    }

    // ─── User Deposit History ─────────────────────────────────────────────────

    /**
     * Paginated DEPOSIT transactions for one user, newest first. Each row
     * carries the user's country and the wallet currency so the client can
     * render ₵ or ₦ without a second lookup.
     */
    public Page<SuperAdminDtos.UserDepositDto> listUserDeposits(UUID userId, Pageable pageable) {
        log.info("listUserDeposits: userId='{}'", userId);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        Wallet wallet = walletRepo.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found"));

        String country = CountryUtils.normalize(user.getCountry());
        String currency = (wallet.getCurrency() != null && !wallet.getCurrency().isBlank())
                ? wallet.getCurrency() : CountryUtils.currencyOf(country);

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return txRepo
                .findByWalletIdAndKindOrderByCreatedAtDesc(wallet.getId(), TxKind.DEPOSIT, p)
                .map(tx -> new SuperAdminDtos.UserDepositDto(
                        tx.getId(), tx.getWalletId(),
                        user.getId(), user.getEmail(),
                        user.getFirstName(), user.getLastName(),
                        country, currency,
                        tx.getAmount(), tx.getBalanceAfter(),
                        tx.getProviderRef(), tx.getStatus(), tx.getCreatedAt()));
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

        var walletDto = new SuperAdminDtos.WalletSummaryDto(
                wallet.getId(), wallet.getBalance(), wallet.getCurrency(),
                txCount, deposited, withdrawn);

        List<ReferralLink> links = referralLinkRepo.findByAdminId(adminId);
        var referralDto = links.stream()
                .filter(ReferralLink::isActive)
                .findFirst()
                .or(() -> links.stream().findFirst())
                .map(link -> new SuperAdminDtos.ReferralSummaryDto(
                        link.getId(), link.getCode(), link.getCommissionPercent(), null, null))
                .orElse(null);

        Instant adminCreatedAt = admin.getCreatedAt() != null
                ? admin.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null;

        return new SuperAdminDtos.AdminDetailDto(
                admin.getId(), admin.getEmail(), admin.getFirstName(), admin.getLastName(),
                admin.getPhone(), admin.getCountry(), admin.isEmailVerified(),
                adminCreatedAt, walletDto, referralDto);
    }

    // ─── Platform-wide Transactions (paginated + filtered) ───────────────────

    /**
     * Wallet and user lookups are batched per page rather than performed inside
     * the map. The previous version issued two extra queries per row, which at
     * size=500 during CSV export meant roughly a thousand round-trips per page.
     */
    public Page<SuperAdminDtos.TransactionDto> listTransactions(
            TxKind kind, String status, UUID walletId,
            Instant from, Instant to, Pageable pageable) {
        log.info("listTransactions: kind={} status={} walletId={}", kind, status, walletId);

        Pageable p = pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Transaction> page =
                txRepo.findAll(TransactionSpecs.filtered(kind, status, walletId, from, to), p);

        List<Transaction> rows = page.getContent();
        if (rows.isEmpty()) return page.map(tx -> toTransactionDto(tx, null, null, null));

        Set<UUID> walletIds = rows.stream()
                .map(Transaction::getWalletId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, UUID> walletToUser = walletRepo.findAllById(walletIds).stream()
                .filter(w -> w != null && w.getId() != null && w.getUserId() != null)
                .collect(Collectors.toMap(Wallet::getId, Wallet::getUserId, (a, b) -> a));

        Set<UUID> userIds = new HashSet<>(walletToUser.values());
        Map<UUID, User> usersById = userIds.isEmpty() ? Map.of()
                : userRepo.findAllById(userIds).stream()
                    .filter(u -> u != null && u.getId() != null)
                    .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        return page.map(tx -> {
            UUID userId = walletToUser.get(tx.getWalletId());
            User u = userId != null ? usersById.get(userId) : null;
            return toTransactionDto(tx, userId,
                    u != null ? u.getEmail() : null,
                    u != null ? CountryUtils.normalize(u.getCountry()) : CountryUtils.UNKNOWN);
        });
    }

    // ─── Affiliate Withdrawal History (all statuses) ──────────────────────────

    public Page<com.speedbet.api.affiliate.AffiliateWithdrawalRequest> listWithdrawals(
            AffiliateWithdrawalStatus status, Pageable pageable) {
        log.info("listWithdrawals: status={}", status);

        Pageable p = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "requestedAt"));

        if (status != null) {
            return withdrawalRepo.findByStatusOrderByRequestedAtDesc(status, p);
        }
        return withdrawalRepo.findAll(p);
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private SuperAdminDtos.UserSummaryDto toUserSummary(User u) {
        return new SuperAdminDtos.UserSummaryDto(
                u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(),
                u.getPhone(), u.getCountry(), u.getRole().name(), u.getStatus().name(),
                u.isEmailVerified(),
                u.getCreatedAt() != null ? u.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null);
    }

    private SuperAdminDtos.TransactionDto toTransactionDto(
            Transaction tx, UUID userId, String email, String country) {
        return new SuperAdminDtos.TransactionDto(
                tx.getId(), tx.getWalletId(), userId, email, country,
                tx.getKind(), tx.getAmount(), tx.getBalanceAfter(),
                tx.getProviderRef(), tx.getStatus(), tx.getMetadata(),
                tx.getCreatedAt());
    }
}
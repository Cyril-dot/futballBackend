package com.speedbet.api.superadmin;

import com.speedbet.api.affiliate.AffiliateWithdrawalRequest;
import com.speedbet.api.affiliate.AffiliateWithdrawalStatus;
import com.speedbet.api.audit.AuditLog;
import com.speedbet.api.audit.AuditService;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import com.speedbet.api.user.UserService;
import com.speedbet.api.wallet.TxKind;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminController {

    private final UserRepository userRepo;
    private final UserService userService;
    private final AuditService auditService;
    private final SuperAdminQueryService queryService;

    // ══════════════════════════════════════════════════════════════════════════
    // EXISTING ENDPOINTS (unchanged)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/admins")
    public ResponseEntity<ApiResponse<List<User>>> admins() {
        var admins = userRepo.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(admins));
    }

    @PostMapping("/admins")
    public ResponseEntity<ApiResponse<User>> createAdmin(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, String> req) {
        var admin = userService.createAdmin(
                req.get("email"), req.get("password"),
                req.get("firstName"), req.get("lastName"),
                actor.getId());
        auditService.log(actor.getId(), "CREATE_ADMIN", "users", admin.getId(),
                null, Map.of("email", admin.getEmail()), null);
        return ResponseEntity.ok(ApiResponse.ok(admin, "Admin created"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NEW: CREATE ADMIN WITH COMMISSION RATE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/super-admin/admins/with-commission
     *
     * Creates a new admin AND sets their commission rate in one step, at
     * creation time — calls UserService.createAdminWithCommissionRate(...),
     * a separate method from the one the existing POST /admins endpoint
     * above calls. That endpoint (and UserService.createAdmin) are left
     * completely untouched; admins created through it still get no referral
     * link / commission rate until updateAdminCommissionRate (below) is
     * called on them separately.
     *
     * Required body fields: email, password, firstName, lastName, commissionRate
     *   commissionRate is sent as a plain numeric string, e.g. "60" means 60%.
     *   UserService.createAdminWithCommissionRate validates it's between 0 and 100.
     */
    @PostMapping("/admins/with-commission")
    public ResponseEntity<ApiResponse<User>> createAdminWithCommission(
            @AuthenticationPrincipal User actor,
            @RequestBody Map<String, String> req) {

        var rate = new BigDecimal(req.get("commissionRate"));

        var admin = userService.createAdminWithCommissionRate(
                req.get("email"), req.get("password"),
                req.get("firstName"), req.get("lastName"),
                actor.getId(), rate);

        auditService.log(actor.getId(), "CREATE_ADMIN_WITH_COMMISSION", "users", admin.getId(),
                null, Map.of("email", admin.getEmail(), "commissionRate", rate.toPlainString()), null);

        return ResponseEntity.ok(ApiResponse.ok(admin, "Admin created with " + rate + "% commission rate"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NEW: UPDATE AN EXISTING ADMIN'S COMMISSION RATE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * PATCH /api/super-admin/admins/{adminId}/commission-rate
     *
     * Updates the commission rate on an existing admin's active referral
     * link(s) — calls UserService.updateAdminCommissionRate(...), which
     * confirms the target is actually an ADMIN before delegating to
     * ReferralService.updateCommissionRate(). Takes effect immediately on
     * the admin's next referred deposit; nothing already earned at the old
     * rate is recalculated retroactively.
     *
     * Required body field: commissionRate — plain numeric string, e.g. "45" means 45%.
     */
    @PatchMapping("/admins/{adminId}/commission-rate")
    public ResponseEntity<ApiResponse<User>> updateAdminCommissionRate(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID adminId,
            @RequestBody Map<String, String> req) {

        var rate = new BigDecimal(req.get("commissionRate"));

        var admin = userService.updateAdminCommissionRate(adminId, rate);

        auditService.log(actor.getId(), "UPDATE_ADMIN_COMMISSION_RATE", "users", admin.getId(),
                null, Map.of("commissionRate", rate.toPlainString()), null);

        return ResponseEntity.ok(ApiResponse.ok(admin, "Commission rate updated to " + rate + "%"));
    }

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> metrics() {
        long totalUsers  = userRepo.count();
        long totalAdmins = userRepo.countByRole(UserRole.ADMIN);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "totalUsers",  totalUsers,
                "totalAdmins", totalAdmins,
                "platform",    "SpeedBet"
        )));
    }

    @GetMapping("/audit-log")
    public ResponseEntity<ApiResponse<PageResponse<AuditLog>>> auditLog(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(auditService.getAll(PageRequest.of(page, size)))));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REVENUE / DEPOSIT OVERVIEW
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/super-admin/metrics/deposits
     *
     * Returns platform-wide deposit and withdrawal totals broken down by
     * all-time, this month, and today.
     *
     * Response:
     * {
     *   "totalDepositsAllTime":    12500000.00,
     *   "totalDepositsThisMonth":    850000.00,
     *   "totalDepositsToday":          3200.00,
     *   "totalWithdrawalsAllTime":   4200000.00,
     *   "totalWithdrawalsThisMonth":  320000.00,
     *   "totalDepositCount":              1240,
     *   "totalWithdrawalCount":            380,
     *   "currency": "GHS"
     * }
     */
    @GetMapping("/metrics/deposits")
    public ResponseEntity<ApiResponse<SuperAdminDtos.RevenueOverviewDto>> depositMetrics() {
        return ResponseEntity.ok(ApiResponse.ok(queryService.getRevenueOverview()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ALL USERS (paginated + search)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/super-admin/users
     *
     * Query params:
     *   page   (int,    default 0)
     *   size   (int,    default 20)
     *   search (string, optional) — matches email, firstName, lastName
     *   role   (string, optional) — USER | ADMIN | SUPER_ADMIN
     *
     * Response: paginated list of UserSummaryDto
     */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<PageResponse<SuperAdminDtos.UserSummaryDto>>> listUsers(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String search,
            @RequestParam(required = false)    UserRole role) {

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(queryService.listUsers(search, role, pageable))));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SINGLE USER DETAIL
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/super-admin/users/{userId}
     *
     * Returns full user profile + wallet summary (balance, total deposited,
     * total withdrawn, transaction count).
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<SuperAdminDtos.UserDetailDto>> getUserDetail(
            @PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(queryService.getUserDetail(userId)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // USER DEPOSIT HISTORY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/super-admin/users/{userId}/deposits
     *
     * Returns a paginated list of every deposit made by a specific user,
     * ordered newest-first. Each entry shows who deposited, when, and how much.
     *
     * Query params:
     *   page  (int, default 0)
     *   size  (int, default 20)
     *
     * Example response:
     * {
     *   "content": [
     *     {
     *       "transactionId": "a1b2c3d4-...",
     *       "walletId":      "e5f6a7b8-...",
     *       "userId":        "c9d0e1f2-...",
     *       "userEmail":     "kwame@example.com",
     *       "firstName":     "Kwame",
     *       "lastName":      "Mensah",
     *       "amount":        500.00,
     *       "balanceAfter":  1200.00,
     *       "providerRef":   "PAY-XYZ123",
     *       "status":        "COMPLETED",
     *       "createdAt":     "2025-06-01T10:30:00Z"
     *     }
     *   ],
     *   "page": 0,
     *   "size": 20,
     *   "totalElements": 14,
     *   "totalPages": 1
     * }
     */
    @GetMapping("/users/{userId}/deposits")
    public ResponseEntity<ApiResponse<PageResponse<SuperAdminDtos.UserDepositDto>>> getUserDeposits(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(queryService.listUserDeposits(userId, pageable))));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SINGLE ADMIN DETAIL
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/super-admin/admins/{adminId}
     *
     * Returns admin profile + wallet summary + referral link details
     * (code, commission rate, total referrals, total earnings).
     */
    @GetMapping("/admins/{adminId}")
    public ResponseEntity<ApiResponse<SuperAdminDtos.AdminDetailDto>> getAdminDetail(
            @PathVariable UUID adminId) {
        return ResponseEntity.ok(ApiResponse.ok(queryService.getAdminDetail(adminId)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLATFORM-WIDE TRANSACTIONS (paginated + filtered)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/super-admin/transactions
     *
     * Query params:
     *   page     (int,     default 0)
     *   size     (int,     default 50)
     *   kind     (TxKind,  optional) — DEPOSIT | WITHDRAWAL | WITHDRAWAL_REFUND | etc.
     *   status   (string,  optional) — COMPLETED | PENDING | FAILED
     *   walletId (UUID,    optional) — filter to a specific wallet
     *   from     (Instant, optional) — ISO-8601 e.g. 2024-01-01T00:00:00Z
     *   to       (Instant, optional) — ISO-8601
     *
     * Response: paginated list of TransactionDto (includes userId + userEmail resolved)
     */
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<PageResponse<SuperAdminDtos.TransactionDto>>> listTransactions(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    TxKind kind,
            @RequestParam(required = false)    String status,
            @RequestParam(required = false)    UUID walletId,
            @RequestParam(required = false)    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        var pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(queryService.listTransactions(kind, status, walletId, from, to, pageable))));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AFFILIATE WITHDRAWAL HISTORY (all statuses)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/super-admin/affiliate-withdrawals
     *
     * Query params:
     *   page   (int,                       default 0)
     *   size   (int,                       default 20)
     *   status (AffiliateWithdrawalStatus, optional) — PENDING | PROCESSED | REJECTED
     *          Omit to retrieve all statuses.
     *
     * Response: paginated list of AffiliateWithdrawalRequest
     */
    @GetMapping("/affiliate-withdrawals")
    public ResponseEntity<ApiResponse<PageResponse<AffiliateWithdrawalRequest>>> listWithdrawals(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    AffiliateWithdrawalStatus status) {

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(queryService.listWithdrawals(status, pageable))));
    }
}
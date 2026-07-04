package com.speedbet.api.user;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.Wallet;
import com.speedbet.api.wallet.WalletRepository;
import com.speedbet.api.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // ─── Dependencies ─────────────────────────────────────────────────────────

    private final UserRepository userRepo;
    private final WalletRepository walletRepo;
    private final WalletService walletService;
    private final PasswordEncoder passwordEncoder;
    private final ReferralService referralService;

    public UserService(UserRepository userRepo,
                       WalletRepository walletRepo,
                       WalletService walletService,
                       PasswordEncoder passwordEncoder,
                       ReferralService referralService) {
        this.userRepo = userRepo;
        this.walletRepo = walletRepo;
        this.walletService = walletService;
        this.passwordEncoder = passwordEncoder;
        this.referralService = referralService;
    }

    // ─── Auth ─────────────────────────────────────────────────────────────────

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.debug("loadUserByUsername: looking up '{}'", email);
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    @Transactional
    public User register(String email, String password, String firstName, String lastName,
                         String phone, String country, UUID referredViaLinkId) {
        log.info("register: attempt for email='{}'", email);

        if (userRepo.existsByEmail(email)) {
            log.warn("register: email '{}' already registered", email);
            throw ApiException.conflict("Email already registered");
        }

        var now = LocalDateTime.now();

        var user = User.builder()
                .email(email.toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .phone(phone)
                .country(country)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .referredViaLinkId(referredViaLinkId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        log.debug("register: createdAt={} updatedAt={}", user.getCreatedAt(), user.getUpdatedAt());
        log.debug("register: saving user entity for '{}'", email);
        user = userRepo.save(user);
        log.info("register: user saved id='{}' email='{}'", user.getId(), email);

        if (referredViaLinkId != null) {
            referralService.attributeUser(referredViaLinkId, user.getId());
            log.info("register: referral attributed linkId='{}' userId='{}'",
                    referredViaLinkId, user.getId());
        }

        walletRepo.save(Wallet.builder()
                .userId(user.getId())
                .currency("GHS")
                .balance(BigDecimal.ZERO)
                .build());
        log.info("register: wallet created for userId='{}'", user.getId());

        return user;
    }

    // ─── Admin Creation (internal / super-admin) ──────────────────────────────

    @Transactional
    public User createAdmin(String email, String password, String firstName, String lastName,
                            UUID createdByAdminId) {
        log.info("createAdmin: attempt for email='{}'", email);

        if (userRepo.existsByEmail(email)) {
            log.warn("createAdmin: email '{}' already registered", email);
            throw ApiException.conflict("Email already registered");
        }

        var now = LocalDateTime.now();

        var user = User.builder()
                .email(email.toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .createdByAdminId(createdByAdminId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        log.debug("createAdmin: createdAt={} updatedAt={}", user.getCreatedAt(), user.getUpdatedAt());
        log.debug("createAdmin: saving admin entity for '{}'", email);
        user = userRepo.save(user);
        log.info("createAdmin: admin saved id='{}' email='{}'", user.getId(), email);

        walletRepo.save(Wallet.builder()
                .userId(user.getId())
                .currency("GHS")
                .balance(BigDecimal.ZERO)
                .build());
        log.info("createAdmin: wallet created for adminId='{}'", user.getId());

        return user;
    }

    // ─── Admin Creation with Commission Rate (Super Admin, new separate method) ─

    /**
     * Creates a new ADMIN and immediately gives them a referral link at a
     * Super-Admin-specified commission rate — in one step, at creation time.
     *
     * This is a NEW, SEPARATE method from {@link #createAdmin}. That method
     * is left completely untouched and still creates an admin with NO
     * referral link at all (a gap noted separately — admins created via
     * createAdmin() currently can't earn commission until a link exists).
     * Use THIS method instead whenever Super Admin wants to set the rate
     * at creation time.
     *
     * Steps:
     *  1. Same guard + User/Wallet creation as createAdmin()
     *  2. Validate commissionRate is between 0 and 100 inclusive
     *  3. Create a referral link for the new admin via
     *     ReferralService.createLink(...) using the supplied rate — this is
     *     the same low-level factory createAdminUpgradeLink() uses
     *     internally, just with a caller-supplied rate instead of the
     *     hardcoded 70% default.
     *
     * @param email             new admin's email
     * @param password          raw password (will be hashed)
     * @param firstName         new admin's first name
     * @param lastName          new admin's last name
     * @param createdByAdminId  the Super Admin performing this action
     * @param commissionRate    commission percentage for this admin's referral link, e.g. 60 means 60%
     */
    @Transactional
    public User createAdminWithCommissionRate(String email, String password, String firstName, String lastName,
                                              UUID createdByAdminId, BigDecimal commissionRate) {
        log.info("createAdminWithCommissionRate: attempt for email='{}' rate={}%", email, commissionRate);

        if (commissionRate == null
                || commissionRate.compareTo(BigDecimal.ZERO) < 0
                || commissionRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("Commission rate must be between 0 and 100.");
        }

        if (userRepo.existsByEmail(email)) {
            log.warn("createAdminWithCommissionRate: email '{}' already registered", email);
            throw ApiException.conflict("Email already registered");
        }

        var now = LocalDateTime.now();

        var user = User.builder()
                .email(email.toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .createdByAdminId(createdByAdminId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        user = userRepo.save(user);
        log.info("createAdminWithCommissionRate: admin saved id='{}' email='{}'", user.getId(), email);

        walletRepo.save(Wallet.builder()
                .userId(user.getId())
                .currency("GHS")
                .balance(BigDecimal.ZERO)
                .build());
        log.info("createAdminWithCommissionRate: wallet created for adminId='{}'", user.getId());

        referralService.createLink(user.getId(), "Admin referral link", commissionRate, null);
        log.info("createAdminWithCommissionRate: referral link created for adminId='{}' at rate={}%",
                user.getId(), commissionRate);

        return user;
    }

    // ─── Admin Upgrade (Paystack-triggered) ───────────────────────────────────

    /**
     * Promote a USER to ADMIN after their GHS 200 Paystack payment is confirmed.
     *
     * This method is called ONLY by the Paystack webhook (charge.success +
     * upgradeIntent=admin). It must never be called before payment is verified.
     *
     * No wallet debit happens here — Paystack collected the money externally.
     * The upgrade fee is recorded as an ADMIN_UPGRADE_FEE audit transaction via
     * WalletService.recordExternalDebit() — no balance is mutated.
     *
     * Idempotent: if the user is already ADMIN (e.g. webhook retry), logs and
     * returns the existing user without error.
     *
     * Steps:
     *  1. Guard — must be USER role
     *  2. Promote role to ADMIN
     *  3. Record ADMIN_UPGRADE_FEE transaction for audit (no balance change)
     *  4. Create 60% referral link + migrate existing 2% referrals
     */
    @Transactional
    public User upgradeToAdmin(UUID userId, String paystackRef) {
        log.info("upgradeToAdmin: userId='{}' paystackRef='{}'", userId, paystackRef);

        var user = getById(userId);

        // Idempotency — safe for webhook retries
        if (user.getRole() == UserRole.ADMIN) {
            log.warn("upgradeToAdmin: userId='{}' is already ADMIN — skipping (ref='{}')",
                    userId, paystackRef);
            return user;
        }

        if (user.getRole() != UserRole.USER) {
            throw ApiException.badRequest(
                    "Only regular users can upgrade to Admin. Current role: " + user.getRole());
        }

        // Promote role
        user.setRole(UserRole.ADMIN);
        user.setUpdatedAt(LocalDateTime.now());
        user = userRepo.save(user);
        log.info("upgradeToAdmin: userId='{}' promoted to ADMIN", userId);

        // Audit trail — record the upgrade fee (no balance mutation;
        // Paystack collected the money externally)
        walletService.recordExternalDebit(
                userId,
                BigDecimal.valueOf(200),
                TxKind.ADMIN_UPGRADE_FEE,
                paystackRef,
                Map.of("reason", "admin_upgrade_fee", "provider", "paystack",
                        "paystackRef", paystackRef)
        );
        log.info("upgradeToAdmin: ADMIN_UPGRADE_FEE audit record saved for userId='{}' ref='{}'",
                userId, paystackRef);

        @SuppressWarnings("unused")
        var ignored = referralService.createAdminUpgradeLink(userId);
        log.info("upgradeToAdmin: referral upgrade complete for userId='{}'", userId);

        return user;
    }

    // ─── Commission Rate (Super Admin action) ─────────────────────────────────

    /**
     * Updates the commission rate on an admin's active referral link(s).
     *
     * This is a thin guard-and-delegate wrapper around
     * {@link ReferralService#updateCommissionRate(UUID, BigDecimal)}, which
     * does the actual work: validating 0 <= rate <= 100, and updating every
     * ACTIVE ReferralLink row belonging to that admin. This method's only
     * added responsibility is confirming the target user is actually an
     * ADMIN before the rate change is allowed to proceed — you can't set a
     * commission rate for a plain USER or it would silently fail deeper in
     * ReferralService with a confusing "no active link" error instead of a
     * clear one here.
     *
     * The new rate takes effect immediately — the next deposit made by any
     * of this admin's referred users will use it. Nothing already earned at
     * the old rate is recalculated.
     *
     * AUTHORIZATION NOTE: this method does NOT itself verify that the
     * CALLER is a Super Admin — that check belongs on the controller
     * endpoint via @PreAuthorize, the same pattern used everywhere else in
     * this codebase (e.g. hasRole('ADMIN') on AdminAffiliateController).
     * No SUPER_ADMIN value was visible in the UserRole enum shared so far —
     * confirm the exact role name that should gate this before wiring the
     * @PreAuthorize annotation on whichever controller calls this method.
     *
     * @param adminUserId the admin whose commission rate is being changed
     * @param rate        new rate as a percentage value, e.g. 60 means 60%
     * @return the admin User row (unchanged — included for convenient chaining/logging by the caller)
     */
    @Transactional
    public User updateAdminCommissionRate(UUID adminUserId, BigDecimal rate) {
        log.info("updateAdminCommissionRate: adminUserId='{}' requestedRate={}%", adminUserId, rate);

        var admin = getById(adminUserId);

        if (admin.getRole() != UserRole.ADMIN) {
            log.warn("updateAdminCommissionRate: userId='{}' is not an ADMIN (role={}) — rejecting",
                    adminUserId, admin.getRole());
            throw ApiException.badRequest(
                    "Commission rate can only be set for ADMIN users. Current role: " + admin.getRole());
        }

        referralService.updateCommissionRate(adminUserId, rate);

        log.info("updateAdminCommissionRate: adminUserId='{}' rate updated to {}%", adminUserId, rate);
        return admin;
    }

    // ─── Profile ──────────────────────────────────────────────────────────────

    @Transactional
    public User updateProfile(UUID userId, String firstName, String lastName,
                              String phone, String country, String themePreference) {
        log.info("updateProfile: userId='{}'", userId);
        var user = getById(userId);

        if (firstName != null)       user.setFirstName(firstName);
        if (lastName != null)        user.setLastName(lastName);
        if (phone != null)           user.setPhone(phone);
        if (country != null)         user.setCountry(country);
        if (themePreference != null) user.setThemePreference(themePreference);

        user.setUpdatedAt(LocalDateTime.now());

        var saved = userRepo.save(user);
        log.info("updateProfile: saved userId='{}'", userId);
        return saved;
    }

    // ─── Password ─────────────────────────────────────────────────────────────

    @Transactional
    public void updatePassword(User user, String newPassword) {
        log.info("updatePassword: userId='{}'", user.getId());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.save(user);
        log.info("updatePassword: done for userId='{}'", user.getId());
    }

    /**
     * Verify that the supplied raw password matches the stored hash.
     *
     * Used by: AuthController (login), ChangePasswordController (current-password check).
     * FIX: IDE flagged "method is never used" — this is a false positive because the
     * method is called from controllers in other packages. Keeping it public and
     * documented silences the warning without suppression annotations.
     */
    public boolean checkPassword(User user, String password) {
        return passwordEncoder.matches(password, user.getPasswordHash());
    }

    // ─── Finders ──────────────────────────────────────────────────────────────

    public User getById(UUID id) {
        log.debug("getById: id='{}'", id);
        return userRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    public User getByEmail(String email) {
        log.debug("getByEmail: email='{}'", email);
        return userRepo.findByEmail(email)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    public User save(User user) {
        log.debug("save: userId='{}'", user.getId());
        return userRepo.save(user);
    }
}
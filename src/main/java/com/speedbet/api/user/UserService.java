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

    // ─── Welcome bonus config ─────────────────────────────────────────────────

    private record WelcomeBonus(String currency, BigDecimal amount) {}

    private static final Map<String, WelcomeBonus> WELCOME_BONUSES = Map.of(
            "GH", new WelcomeBonus("GHS", BigDecimal.valueOf(100)),
            "NG", new WelcomeBonus("NGN", BigDecimal.valueOf(9_000)),
            "US", new WelcomeBonus("USD", BigDecimal.valueOf(50))
    );

    private static final WelcomeBonus DEFAULT_BONUS = new WelcomeBonus("GHS", BigDecimal.ZERO);

    private WelcomeBonus resolveWelcomeBonus(String countryCode) {
        if (countryCode == null) return DEFAULT_BONUS;
        return WELCOME_BONUSES.getOrDefault(countryCode.toUpperCase(), DEFAULT_BONUS);
    }

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

        var bonus = resolveWelcomeBonus(country);
        log.info("register: welcome bonus for country='{}' → {} {}",
                country, bonus.amount(), bonus.currency());

        walletRepo.save(Wallet.builder()
                .userId(user.getId())
                .currency(bonus.currency())
                .balance(BigDecimal.ZERO)
                .build());
        log.info("register: wallet created for userId='{}'", user.getId());

        if (bonus.amount().compareTo(BigDecimal.ZERO) > 0) {
            walletService.credit(
                    user.getId(),
                    bonus.amount(),
                    TxKind.WELCOME_BONUS,
                    "WELCOME_BONUS_" + user.getId(),
                    Map.of("reason", "welcome_bonus", "country", country)
            );
            log.info("register: welcome bonus of {} {} credited to userId='{}'",
                    bonus.amount(), bonus.currency(), user.getId());
        }

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

        // Create 60% referral link + migrate existing referrals.
        // FIX: return value was previously discarded (IDE warning). We don't need the
        // ReferralLink object here — the work happens inside createAdminUpgradeLink and
        // the result is intentionally unused at this call site.
        @SuppressWarnings("unused")
        var ignored = referralService.createAdminUpgradeLink(userId);
        log.info("upgradeToAdmin: referral upgrade complete for userId='{}'", userId);

        return user;
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
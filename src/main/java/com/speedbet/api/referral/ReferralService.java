package com.speedbet.api.referral;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    private static final String     CHARSET                    = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM                   = new SecureRandom();

    // ── Commission defaults ───────────────────────────────────────────────────
    /** Commission rate for regular (non-admin) affiliate links. */
    private static final BigDecimal DEFAULT_USER_COMMISSION    = BigDecimal.valueOf(2);

    /** Commission rate applied immediately after an admin upgrade, before the
     *  Super Admin finalises it via the onboarding chat. */
    private static final BigDecimal DEFAULT_ADMIN_COMMISSION   = BigDecimal.valueOf(65);

    private final ReferralLinkRepository linkRepo;
    private final ReferralRepository     referralRepo;
    private final WalletService          walletService;

    // ─── Link Resolution ──────────────────────────────────────────────────────

    public Optional<UUID> findLinkIdByCode(String code) {
        Optional<UUID> result = linkRepo.findValidByCode(code, Instant.now())
                .map(ReferralLink::getId);
        log.info("findLinkIdByCode: code={} found={}", code, result.isPresent());
        return result;
    }

    // ─── Referral Attribution ─────────────────────────────────────────────────

    @Transactional
    public void attributeUser(UUID linkId, UUID userId) {
        if (referralRepo.findByUserId(userId).isPresent()) {
            log.info("attributeUser: userId={} already attributed — skipping", userId);
            return;
        }
        referralRepo.save(Referral.builder()
                .linkId(linkId)
                .userId(userId)
                .build());
        log.info("attributeUser: userId={} attributed to linkId={}", userId, linkId);
    }

    @Transactional
    public void attributeCommission(UUID userId, BigDecimal stake) {
        log.info("attributeCommission: userId={} stake={}", userId, stake);

        Referral referral = referralRepo.findByUserId(userId).orElse(null);
        if (referral == null) {
            log.warn("attributeCommission: no referral found for userId={} — skipping", userId);
            return;
        }

        ReferralLink link = linkRepo.findById(referral.getLinkId()).orElse(null);
        if (link == null) {
            log.warn("attributeCommission: linkId={} not found for userId={} — skipping",
                    referral.getLinkId(), userId);
            return;
        }

        var commission = stake.multiply(
                link.getCommissionPercent().divide(BigDecimal.valueOf(100), MathContext.DECIMAL64));

        referral.setLifetimeStake(referral.getLifetimeStake().add(stake));
        referral.setLifetimeCommission(referral.getLifetimeCommission().add(commission));
        referralRepo.save(referral);

        walletService.credit(
                link.getAdminId(),
                commission,
                TxKind.REFERRAL_COMMISSION,
                "REF-" + userId + "-" + System.currentTimeMillis(),
                Map.of("userId", userId.toString(), "stake", stake.toString()));

        log.info("attributeCommission: GHS {} credited to adminId={} for userId={}",
                commission, link.getAdminId(), userId);
    }

    // ─── Link Management ──────────────────────────────────────────────────────

    /**
     * Creates a referral link for a regular (non-admin) user.
     * Always uses DEFAULT_USER_COMMISSION (2%) — never falls back to an
     * ambiguous default.
     */
    @Transactional
    public ReferralLink createUserLink(UUID userId, String label, Instant expiresAt) {
        log.info("createUserLink: userId={} label='{}' expiresAt={}", userId, label, expiresAt);
        return createLink(userId, label, DEFAULT_USER_COMMISSION, expiresAt);
    }

    /**
     * Low-level link factory. commissionPercent is required — callers must
     * always pass an explicit rate. This prevents accidental use of the wrong
     * default.
     *
     * @throws IllegalArgumentException if commissionPercent is null
     */
    @Transactional
    public ReferralLink createLink(UUID adminId, String label,
                                   BigDecimal commissionPercent, Instant expiresAt) {

        if (commissionPercent == null) {
            throw new IllegalArgumentException(
                    "commissionPercent must be provided explicitly. " +
                            "Use createUserLink() for regular users (2%) or " +
                            "createAdminUpgradeLink() for admins (60%).");
        }

        log.info("createLink: adminId={} label='{}' commission={}% expiresAt={}",
                adminId, label, commissionPercent, expiresAt);

        ReferralLink link = linkRepo.save(ReferralLink.builder()
                .adminId(adminId)
                .code(generateUniqueCode())
                .label(label)
                .commissionPercent(commissionPercent)
                .expiresAt(expiresAt)
                .active(true)
                .build());

        log.info("createLink: success — code={} id={} commission={}%",
                link.getCode(), link.getId(), link.getCommissionPercent());
        return link;
    }

    /**
     * Called by UserService.upgradeToAdmin() after role promotion.
     *
     * 1. Creates a new 60% referral link for this admin.
     * 2. Migrates ALL existing referrals (from their old user links) to the
     *    new link so their referred users immediately earn them 60%.
     * 3. Deactivates all old links.
     *
     * The Super Admin may later adjust the 60% rate via the onboarding chat
     * (AdminUpgradeChatService.setCommission → updateCommissionRate).
     */
    @Transactional
    public ReferralLink createAdminUpgradeLink(UUID adminId) {
        log.info("createAdminUpgradeLink: adminId={}", adminId);

        ReferralLink newLink = createLink(
                adminId,
                "Admin upgrade link",
                DEFAULT_ADMIN_COMMISSION,   // ← 60%, explicit constant
                null
        );
        log.info("createAdminUpgradeLink: new {}% linkId={} created for adminId={}",
                DEFAULT_ADMIN_COMMISSION, newLink.getId(), adminId);

        // Migrate existing referrals so commission rate takes effect immediately
        List<Referral> existingReferrals = referralRepo.findByAdminId(adminId);
        for (Referral referral : existingReferrals) {
            referral.setLinkId(newLink.getId());
            referralRepo.save(referral);
        }
        log.info("createAdminUpgradeLink: migrated {} referral(s) to linkId={} for adminId={}",
                existingReferrals.size(), newLink.getId(), adminId);

        // Deactivate old links
        List<ReferralLink> oldLinks = linkRepo.findByAdminIdAndIdNot(adminId, newLink.getId());
        for (ReferralLink old : oldLinks) {
            old.setActive(false);
            linkRepo.save(old);
        }
        log.info("createAdminUpgradeLink: deactivated {} old link(s) for adminId={}",
                oldLinks.size(), adminId);

        return newLink;
    }

    // ─── Commission Update ────────────────────────────────────────────────────

    /**
     * Updates the commission rate on the admin's active referral link.
     *
     * Called by AdminUpgradeChatService.setCommission() when the Super Admin
     * finalises the onboarding rate via the upgrade chat. Finds all active links
     * for this admin (normally exactly one after the upgrade flow) and updates
     * their commissionPercent.
     *
     * Throws 404 if no active link exists — which should never happen in the
     * normal flow since upgradeToAdmin always calls createAdminUpgradeLink first.
     */
    @Transactional
    public void updateCommissionRate(UUID adminUserId, BigDecimal rate) {
        log.info("updateCommissionRate: adminUserId={} rate={}", adminUserId, rate);

        List<ReferralLink> activeLinks = linkRepo.findByAdminId(adminUserId)
                .stream()
                .filter(ReferralLink::isActive)
                .toList();

        if (activeLinks.isEmpty()) {
            log.error("updateCommissionRate: no active referral link found for adminUserId={}",
                    adminUserId);
            throw ApiException.notFound(
                    "No active referral link found for this admin. Cannot set commission.");
        }

        for (ReferralLink link : activeLinks) {
            link.setCommissionPercent(rate);
            linkRepo.save(link);
            log.info("updateCommissionRate: linkId={} commission updated to {}% for adminUserId={}",
                    link.getId(), rate, adminUserId);
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    public List<ReferralLink> getLinksForAdmin(UUID adminId) {
        List<ReferralLink> links = linkRepo.findByAdminId(adminId);
        log.info("getLinksForAdmin: adminId={} found={}", adminId, links.size());
        return links;
    }

    public List<Referral> getReferralsForAdmin(UUID adminId) {
        List<Referral> referrals = referralRepo.findByAdminId(adminId);
        log.info("getReferralsForAdmin: adminId={} found={}", adminId, referrals.size());
        return referrals;
    }

    public List<ReferredUserDTO> getReferredUserDTOs(UUID adminId) {
        List<ReferredUserDTO> users = referralRepo.findReferredUserDTOsByAdminId(adminId);
        log.info("getReferredUserDTOs: adminId={} found={}", adminId, users.size());
        return users;
    }

    // ─── Code Generation ──────────────────────────────────────────────────────

    private String generateUniqueCode() {
        int maxAttempts = 10;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            var sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
            }
            String code = sb.toString();
            if (linkRepo.findByCode(code).isEmpty()) {
                log.debug("generateUniqueCode: code={} attempt={}", code, attempt);
                return code;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique referral code after " + maxAttempts + " attempts");
    }
}
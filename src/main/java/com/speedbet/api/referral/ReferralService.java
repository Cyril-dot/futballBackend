package com.speedbet.api.referral;

import com.speedbet.api.affiliate.AffiliateCommissionService;
import com.speedbet.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    private static final String       CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM  = new SecureRandom();

    // ── Commission defaults ───────────────────────────────────────────────────

    /** Commission rate for regular (non-admin) referral links. */
    private static final BigDecimal DEFAULT_USER_COMMISSION = BigDecimal.valueOf(2);

    /**
     * Default commission rate (%) applied immediately after an admin upgrade,
     * before Super Admin finalises it via the onboarding chat.
     *
     * Rate: 60% — the admin earns 60% of every deposit made by their referred users.
     * This is credited to the admin's COMMISSION BALANCE (not their main wallet).
     * Super Admin can adjust this per-admin at any time via
     * AdminUpgradeChatService.setCommission → updateCommissionRate().
     *
     * Examples of custom rates:
     *   60% (default) — standard admin rate
     *   45%           — negotiated lower rate
     *   75%           — negotiated higher rate for top performers
     */
    private static final BigDecimal DEFAULT_ADMIN_COMMISSION = BigDecimal.valueOf(70);

    private final ReferralLinkRepository     linkRepo;
    private final ReferralRepository         referralRepo;
    private final AffiliateCommissionService commissionService; // ← commission balance, NOT wallet

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

    /**
     * Attributes commission to the referring admin when a referred user deposits.
     *
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │  deposit  ×  (commissionPercent / 100)  =  commission earned        │
     * │                                                                      │
     * │  Credited to:  admin's COMMISSION BALANCE (AffiliateCommissionService)│
     * │  NOT credited: admin's main wallet (separate ledger, never touched) │
     * └──────────────────────────────────────────────────────────────────────┘
     *
     * The rate is stored on the ReferralLink (commissionPercent). It defaults
     * to 60% at upgrade time. Super Admin can change it per-admin at any point
     * via updateCommissionRate() — the new rate applies on the very next deposit.
     *
     * Example (default 60% rate):
     *   User deposits GHS 100
     *   Commission = GHS 100 × 60% = GHS 60
     *   → GHS 60 added to admin's commission balance
     *   → Admin's main wallet: unchanged
     *   → Commission balance paid out daily by AffiliateDailyPayoutScheduler
     *
     * @param userId  the depositing referred user
     * @param deposit gross deposit amount in GHS
     */
    @Transactional
    public void attributeCommission(UUID userId, BigDecimal deposit) {
        log.info("attributeCommission: userId={} deposit={}", userId, deposit);

        Referral referral = referralRepo.findByUserId(userId).orElse(null);
        if (referral == null) {
            log.info("attributeCommission: userId={} has no referral — no commission to attribute", userId);
            return;
        }

        ReferralLink link = linkRepo.findById(referral.getLinkId()).orElse(null);
        if (link == null) {
            log.warn("attributeCommission: linkId={} not found for userId={} — skipping",
                    referral.getLinkId(), userId);
            return;
        }

        BigDecimal rate       = link.getCommissionPercent();   // e.g. 60, 45, 75 — set per admin
        BigDecimal commission = deposit.multiply(
                rate.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64));

        // Update referral lifetime stats
        referral.setLifetimeStake(referral.getLifetimeStake().add(deposit));
        referral.setLifetimeCommission(referral.getLifetimeCommission().add(commission));
        referralRepo.save(referral);

        // ── Credit commission balance, NOT the main wallet ────────────────────
        // The old code used walletService.credit() here which was wrong — it
        // would have added commission money directly into the admin's spendable
        // wallet balance. Commission earned from referrals lives in a separate
        // ledger and is paid out daily via AffiliateDailyPayoutScheduler.
        commissionService.creditCommission(link.getAdminId(), commission, "GHS");

        log.info("attributeCommission: GHS {} → commission balance of adminId={} | "
                        + "userId={} deposited GHS {} at {}%",
                commission, link.getAdminId(), userId, deposit, rate);
    }

    // ─── Link Management ──────────────────────────────────────────────────────

    /**
     * Creates a referral link for a regular (non-admin) user at 2%.
     */
    @Transactional
    public ReferralLink createUserLink(UUID userId, String label, Instant expiresAt) {
        log.info("createUserLink: userId={} label='{}' expiresAt={}", userId, label, expiresAt);
        return createLink(userId, label, DEFAULT_USER_COMMISSION, expiresAt);
    }

    /**
     * Low-level link factory. commissionPercent is always required explicitly.
     */
    @Transactional
    public ReferralLink createLink(UUID adminId, String label,
                                   BigDecimal commissionPercent, Instant expiresAt) {
        if (commissionPercent == null)
            throw new IllegalArgumentException(
                    "commissionPercent must be provided explicitly. " +
                            "Use createUserLink() for regular users (2%) or " +
                            "createAdminUpgradeLink() for admins (" + DEFAULT_ADMIN_COMMISSION + "%).");

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

        log.info("createLink: created code={} id={} commission={}%",
                link.getCode(), link.getId(), link.getCommissionPercent());
        return link;
    }

    /**
     * Called by UserService.upgradeToAdmin() after role promotion.
     *
     * 1. Creates a new referral link at DEFAULT_ADMIN_COMMISSION (60%).
     * 2. Migrates ALL existing referrals to the new link so their referred
     *    users immediately earn at the new rate.
     * 3. Deactivates all old links.
     *
     * Super Admin can adjust the 60% default at any time via the onboarding
     * chat → updateCommissionRate(). The change takes effect immediately.
     */
    @Transactional
    public ReferralLink createAdminUpgradeLink(UUID adminId) {
        log.info("createAdminUpgradeLink: adminId={} defaultRate={}%",
                adminId, DEFAULT_ADMIN_COMMISSION);

        ReferralLink newLink = createLink(
                adminId,
                "Admin upgrade link",
                DEFAULT_ADMIN_COMMISSION,   // 60% default, adjustable per admin
                null
        );

        // Migrate existing referrals to the new link
        List<Referral> existing = referralRepo.findByAdminId(adminId);
        for (Referral r : existing) {
            r.setLinkId(newLink.getId());
            referralRepo.save(r);
        }
        log.info("createAdminUpgradeLink: migrated {} referral(s) to linkId={} at {}%",
                existing.size(), newLink.getId(), DEFAULT_ADMIN_COMMISSION);

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

    // ─── Commission Rate Update ───────────────────────────────────────────────

    /**
     * Updates the commission rate on ALL active referral links for this admin.
     *
     * Called by AdminUpgradeChatService when Super Admin sets a custom rate
     * for a specific admin, either during onboarding or at any later time.
     *
     * The rate takes effect immediately — the very next deposit by any of
     * this admin's referred users will use the new rate.
     *
     * There is no system-wide cap enforced here — Super Admin decides the rate.
     * The only constraint is 0 ≤ rate ≤ 100.
     *
     * @param adminUserId the admin whose rate is being changed
     * @param rate        new rate as a percentage value, e.g. 60 means 60%
     */
    @Transactional
    public void updateCommissionRate(UUID adminUserId, BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0
                || rate.compareTo(BigDecimal.valueOf(100)) > 0)
            throw ApiException.badRequest("Commission rate must be between 0 and 100.");

        log.info("updateCommissionRate: adminUserId={} newRate={}%", adminUserId, rate);

        List<ReferralLink> activeLinks = linkRepo.findByAdminId(adminUserId)
                .stream()
                .filter(ReferralLink::isActive)
                .toList();

        if (activeLinks.isEmpty())
            throw ApiException.notFound(
                    "No active referral link found for this admin. Cannot update commission rate.");

        for (ReferralLink link : activeLinks) {
            BigDecimal oldRate = link.getCommissionPercent();
            link.setCommissionPercent(rate);
            linkRepo.save(link);
            log.info("updateCommissionRate: linkId={} {}% → {}% for adminUserId={}",
                    link.getId(), oldRate, rate, adminUserId);
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    public List<ReferralLink> getLinksForAdmin(UUID adminId) {
        return linkRepo.findByAdminId(adminId);
    }

    public List<Referral> getReferralsForAdmin(UUID adminId) {
        return referralRepo.findByAdminId(adminId);
    }

    public List<ReferredUserDTO> getReferredUserDTOs(UUID adminId) {
        return referralRepo.findReferredUserDTOsByAdminId(adminId);
    }

    // ─── Code Generation ──────────────────────────────────────────────────────

    private String generateUniqueCode() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            var sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++)
                sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
            String code = sb.toString();
            if (linkRepo.findByCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("Could not generate a unique referral code after 10 attempts");
    }
}
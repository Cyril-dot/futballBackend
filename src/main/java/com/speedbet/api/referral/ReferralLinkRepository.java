package com.speedbet.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralLinkRepository extends JpaRepository<ReferralLink, UUID> {
    /**
     * All links for one admin, oldest first. The ordering is load-bearing:
     * several call sites take the first ACTIVE link as "the" link (the
     * auto-generated one from admin creation), so an unordered list could
     * surface a newer manual link instead. created_at ASC makes the
     * original link win deterministically everywhere.
     */
    @Query("SELECT l FROM ReferralLink l WHERE l.adminId = :adminId ORDER BY l.createdAt ASC")
    List<ReferralLink> findByAdminId(@Param("adminId") UUID adminId);
    Optional<ReferralLink> findByCode(String code);
    Optional<ReferralLink> findByCodeAndActiveTrue(String code);



    // Add to ReferralLinkRepository
    List<ReferralLink> findByAdminIdAndIdNot(UUID adminId, UUID excludeLinkId);
    @Query("""
    SELECT l FROM ReferralLink l
    WHERE l.code = :code
      AND l.active = true
      AND (l.expiresAt IS NULL OR l.expiresAt > :now)
    """)
    Optional<ReferralLink> findValidByCode(@Param("code") String code, @Param("now") Instant now);
}

package com.speedbet.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralLinkRepository extends JpaRepository<ReferralLink, UUID> {
    List<ReferralLink> findByAdminId(UUID adminId);
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

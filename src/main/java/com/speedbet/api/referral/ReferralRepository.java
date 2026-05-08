package com.speedbet.api.referral;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    Optional<Referral> findByUserId(UUID userId);

    List<Referral> findByLinkId(UUID linkId);

    @Query("SELECT r FROM Referral r WHERE r.linkId IN " +
            "(SELECT l.id FROM ReferralLink l WHERE l.adminId = :adminId)")
    List<Referral> findByAdminId(@Param("adminId") UUID adminId);

    @Query("SELECT COALESCE(SUM(r.lifetimeCommission),0) FROM Referral r WHERE r.linkId IN " +
            "(SELECT l.id FROM ReferralLink l WHERE l.adminId = :adminId)")
    BigDecimal totalCommissionByAdmin(@Param("adminId") UUID adminId);

    // Joins referrals → users so the admin dashboard gets full user info in one query
    @Query("""
        SELECT new com.speedbet.api.referral.ReferredUserDTO(
            u.id,
            u.firstName,
            u.lastName,
            u.email,
            u.createdAt,
            r.lifetimeStake,
            r.lifetimeCommission
        )
        FROM Referral r
        JOIN com.speedbet.api.user.User u ON u.id = r.userId
        WHERE r.linkId IN (
            SELECT l.id FROM ReferralLink l WHERE l.adminId = :adminId
        )
        ORDER BY u.createdAt DESC
    """)
    List<ReferredUserDTO> findReferredUserDTOsByAdminId(@Param("adminId") UUID adminId);
}
package com.speedbet.api.affiliate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AffiliateWithdrawalRepository extends JpaRepository<AffiliateWithdrawalRequest, UUID> {

    /** MSport expiry job: a commission payout request is money history (funded). */
    boolean existsByUserId(UUID userId);

    /** MSport expiry job: remove a deleted account's commission payout requests. */
    @Modifying
    @Query("delete from AffiliateWithdrawalRequest a where a.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    Page<AffiliateWithdrawalRequest> findByUserIdOrderByRequestedAtDesc(UUID userId, Pageable pageable);

    List<AffiliateWithdrawalRequest> findByStatus(AffiliateWithdrawalStatus status);

    boolean existsByUserIdAndStatus(UUID userId, AffiliateWithdrawalStatus status);

    Page<AffiliateWithdrawalRequest> findByStatusOrderByRequestedAtDesc(
            AffiliateWithdrawalStatus status, Pageable pageable);

    Optional<AffiliateWithdrawalRequest> findByReference(String reference);
}
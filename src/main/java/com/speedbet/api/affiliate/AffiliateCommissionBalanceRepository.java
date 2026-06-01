package com.speedbet.api.affiliate;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AffiliateCommissionBalanceRepository extends JpaRepository<AffiliateCommissionBalance, UUID> {

    Optional<AffiliateCommissionBalance> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AffiliateCommissionBalance b WHERE b.userId = :userId")
    Optional<AffiliateCommissionBalance> findByUserIdForUpdate(@Param("userId") UUID userId);

    /**
     * All affiliates who currently have a positive commission balance ready for payout.
     * Used by the daily payout scheduler.
     */
    @Query("SELECT b FROM AffiliateCommissionBalance b WHERE b.balance > 0")
    List<AffiliateCommissionBalance> findAllWithPositiveBalance();

    /**
     * Minimum payout threshold — configurable per query so the scheduler
     * can pass in the configured minimum (e.g. GHS 10.00).
     */
    @Query("SELECT b FROM AffiliateCommissionBalance b WHERE b.balance >= :minAmount")
    List<AffiliateCommissionBalance> findAllEligibleForPayout(@Param("minAmount") BigDecimal minAmount);
}
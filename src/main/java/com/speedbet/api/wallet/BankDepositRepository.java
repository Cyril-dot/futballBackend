package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface BankDepositRepository extends JpaRepository<BankDeposit, UUID> {

    /** MSport expiry job: a pending manual deposit is money in flight (funded). */
    boolean existsByUserId(UUID userId);

    /** MSport expiry job: remove a deleted account's manual deposits. */
    @Modifying
    @Query("delete from BankDeposit d where d.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    /** All deposits for a specific user, newest first */
    Page<BankDeposit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** All pending deposits, oldest first (FIFO review queue) */
    Page<BankDeposit> findByStatusOrderByCreatedAtAsc(BankDepositStatus status, Pageable pageable);

    /** Duplicate reference guard */
    boolean existsByTransferReference(String transferReference);
    /** MoMo queue: manual proofs below GHS 30,000. */
    Page<BankDeposit> findByNgnAmountSentLessThanOrderByCreatedAtDesc(java.math.BigDecimal maxAmount, Pageable pageable);
    Page<BankDeposit> findByStatusAndNgnAmountSentLessThanOrderByCreatedAtAsc(BankDepositStatus status, java.math.BigDecimal maxAmount, Pageable pageable);
}
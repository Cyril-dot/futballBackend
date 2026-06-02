package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BankDepositRepository extends JpaRepository<BankDeposit, UUID> {

    /** All deposits for a specific user, newest first */
    Page<BankDeposit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** All pending deposits, oldest first (FIFO review queue) */
    Page<BankDeposit> findByStatusOrderByCreatedAtAsc(BankDepositStatus status, Pageable pageable);

    /** Duplicate reference guard */
    boolean existsByTransferReference(String transferReference);
}
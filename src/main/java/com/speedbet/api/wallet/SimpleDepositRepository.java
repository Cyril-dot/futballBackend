package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface SimpleDepositRepository extends JpaRepository<SimpleDeposit, UUID> {

    /** MSport expiry job: a pending manual deposit is money in flight (funded). */
    boolean existsByUserId(UUID userId);

    /** MSport expiry job: remove a deleted account's manual deposits. */
    @Modifying
    @Query("delete from SimpleDeposit d where d.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    Page<SimpleDeposit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<SimpleDeposit> findByStatusOrderByCreatedAtAsc(SimpleDepositStatus status, Pageable pageable);
}
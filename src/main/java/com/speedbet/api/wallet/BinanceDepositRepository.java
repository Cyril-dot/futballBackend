package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BinanceDepositRepository extends JpaRepository<BinanceDeposit, UUID> {

    /** MSport expiry job: a pending manual deposit is money in flight (funded). */
    boolean existsByUserId(UUID userId);

    /** MSport expiry job: remove a deleted account's manual deposits. */
    @Modifying
    @Query("delete from BinanceDeposit d where d.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    /** User's own deposit history */
    Page<BinanceDeposit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Admin queue — all pending deposits */
    Page<BinanceDeposit> findByStatusOrderByCreatedAtAsc(BinanceDepositStatus status, Pageable pageable);

    /** Admin queue — all deposits regardless of status */
    Page<BinanceDeposit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Idempotency: reject duplicate TXIDs */
    boolean existsByTxid(String txid);

    Optional<BinanceDeposit> findByTxid(String txid);
}
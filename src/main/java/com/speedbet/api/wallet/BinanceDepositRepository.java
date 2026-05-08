package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BinanceDepositRepository extends JpaRepository<BinanceDeposit, UUID> {

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
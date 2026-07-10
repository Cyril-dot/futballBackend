package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SimpleDepositRepository extends JpaRepository<SimpleDeposit, UUID> {

    Page<SimpleDeposit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<SimpleDeposit> findByStatusOrderByCreatedAtAsc(SimpleDepositStatus status, Pageable pageable);
}
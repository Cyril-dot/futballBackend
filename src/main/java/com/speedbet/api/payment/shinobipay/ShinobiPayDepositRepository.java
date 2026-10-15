package com.speedbet.api.payment.shinobipay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShinobiPayDepositRepository extends JpaRepository<ShinobiPayDeposit, UUID> {
    Optional<ShinobiPayDeposit> findByReference(String reference);
}

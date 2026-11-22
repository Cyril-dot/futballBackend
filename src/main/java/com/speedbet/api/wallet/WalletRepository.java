package com.speedbet.api.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    Optional<Wallet> findByUserId(UUID userId);

    /** MSport expiry job: remove the (zero-balance) wallet of a deleted account. */
    @Modifying
    @Query("delete from Wallet w where w.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}

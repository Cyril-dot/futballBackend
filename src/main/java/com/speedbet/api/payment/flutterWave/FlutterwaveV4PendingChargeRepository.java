package com.speedbet.api.payment.flutterWave;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FlutterwaveV4PendingChargeRepository
        extends JpaRepository<FlutterwaveV4PendingCharge, UUID> {

    Optional<FlutterwaveV4PendingCharge> findByReference(String reference);

    /**
     * Work queue for the reconciler: PENDING rows for this provider whose
     * backoff window has elapsed, oldest-due first.
     *
     * No SKIP LOCKED / pessimistic lock deliberately — two instances picking
     * up the same row is harmless because crediting is idempotent on the
     * reference, and optimistic locking (@Version) stops the double write.
     * Keeping it lock-free also keeps this portable across Postgres/MySQL/H2.
     */
    @Query("""
           select c from FlutterwaveV4PendingCharge c
            where c.providerTag = :providerTag
              and c.status = com.speedbet.api.payment.flutterWave.FlutterwaveV4ChargeStatus.PENDING
              and c.nextPollAt <= :now
            order by c.nextPollAt asc
           """)
    List<FlutterwaveV4PendingCharge> findDue(@Param("providerTag") String providerTag,
                                             @Param("now") Instant now,
                                             Pageable pageable);

    @Query("""
           select count(c) from FlutterwaveV4PendingCharge c
            where c.providerTag = :providerTag
              and c.status = com.speedbet.api.payment.flutterWave.FlutterwaveV4ChargeStatus.PENDING
           """)
    long countPending(@Param("providerTag") String providerTag);

    List<FlutterwaveV4PendingCharge> findByUserIdAndStatusOrderByCreatedAtDesc(
            UUID userId, FlutterwaveV4ChargeStatus status);

    /** Housekeeping — drop long-settled rows so the table doesn't grow forever. */
    @Modifying
    @Query("""
           delete from FlutterwaveV4PendingCharge c
            where c.status <> com.speedbet.api.payment.flutterWave.FlutterwaveV4ChargeStatus.PENDING
              and c.settledAt < :cutoff
           """)
    int deleteSettledBefore(@Param("cutoff") Instant cutoff);
}
package com.speedbet.api.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CommissionLedgerEntryRepository extends JpaRepository<CommissionLedgerEntry, UUID> {

    /**
     * All ledger entries for one admin since a given instant, oldest first.
     * Used as the raw input for daily/weekly/monthly bucketing in
     * AdminCommissionService — bucketing itself happens in Java, not SQL,
     * since week/month boundaries are easier to get right with java.time
     * than with database-specific date-trunc functions.
     */
    @Query("SELECT c FROM CommissionLedgerEntry c " +
           "WHERE c.adminId = :adminId AND c.createdAt >= :since " +
           "ORDER BY c.createdAt ASC")
    List<CommissionLedgerEntry> findByAdminIdSince(
            @Param("adminId") UUID adminId,
            @Param("since") Instant since);

    @Query("SELECT c FROM CommissionLedgerEntry c " +
            "WHERE c.createdAt >= :since ORDER BY c.createdAt ASC")
    List<CommissionLedgerEntry> findAllSince(@Param("since") Instant since);
}


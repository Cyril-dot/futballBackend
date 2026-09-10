package com.speedbet.api.payment.akwapay;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PhoneBlockRepository extends JpaRepository<PhoneBlockEntry, String> {

    /** Exact-number match. */
    boolean existsByFullNumber(String fullNumber);

    /** Prefix-pattern match — catches suffix-swappers. */
    boolean existsByPrefixPattern(String prefixPattern);

    /** Find the block entry that owns this pattern (for logging). */
    Optional<PhoneBlockEntry> findByPrefixPattern(String prefixPattern);

    /**
     * Combined check: is this number blocked either directly or by pattern?
     * Returns true if any row matches.
     */
    @Query("SELECT COUNT(b) > 0 FROM PhoneBlockEntry b " +
           "WHERE b.fullNumber = :fullNumber OR b.prefixPattern = :prefixPattern")
    boolean isBlockedByFullNumberOrPattern(@Param("fullNumber") String fullNumber,
                                           @Param("prefixPattern") String prefixPattern);
}
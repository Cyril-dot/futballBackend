package com.speedbet.api.odds;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OddsRepository extends JpaRepository<Odds, UUID> {

    List<Odds> findByMatchId(UUID matchId);

    List<Odds> findByMatchIdAndMarket(UUID matchId, String market);

    // findFirst instead of findBy — prevents NonUniqueResultException if duplicates
    // ever exist. The unique constraint on (match_id, market, selection) ensures
    // there is always at most one row, but findFirst is defensive insurance.
    Optional<Odds> findFirstByMatchIdAndMarketAndSelection(UUID matchId, String market, String selection);

    // Alias kept for BookingService compatibility — delegates to findFirst internally
    default Optional<Odds> findByMatchIdAndMarketAndSelection(UUID matchId, String market, String selection) {
        return findFirstByMatchIdAndMarketAndSelection(matchId, market, selection);
    }

    @Modifying
    @Query("DELETE FROM Odds o WHERE o.matchId = :matchId")
    void deleteByMatchId(@Param("matchId") UUID matchId);

    @Modifying
    @Query("DELETE FROM Odds o WHERE o.matchId = :matchId AND o.market IN :markets")
    void deleteByMatchIdAndMarketIn(@Param("matchId") UUID matchId,
                                    @Param("markets") List<String> markets);
}
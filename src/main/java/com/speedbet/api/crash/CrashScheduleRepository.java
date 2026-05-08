package com.speedbet.api.crash;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrashScheduleRepository extends JpaRepository<GameCrashSchedule, UUID> {

    @Query("SELECT g FROM GameCrashSchedule g WHERE g.gameSlug = :slug AND g.playedAt IS NULL ORDER BY g.roundNumber")
    List<GameCrashSchedule> findUnplayedBySlug(String slug);

    @Query("SELECT COUNT(g) FROM GameCrashSchedule g WHERE g.gameSlug = :slug AND g.playedAt IS NULL")
    long countUnplayed(String slug);

    @Query("SELECT MAX(g.roundNumber) FROM GameCrashSchedule g WHERE g.gameSlug = :slug")
    Optional<Long> findMaxRoundNumber(String slug);

    @Query("SELECT g FROM GameCrashSchedule g WHERE g.gameSlug = :slug AND g.playedAt IS NULL ORDER BY g.roundNumber LIMIT :n")
    List<GameCrashSchedule> findNextN(String slug, int n);

    Page<GameCrashSchedule> findByGameSlugOrderByRoundNumberDesc(String slug, Pageable pageable);

    @Query("SELECT g FROM GameCrashSchedule g WHERE g.gameSlug = :slug AND g.playedAt IS NULL AND (g.highCrash = true OR g.extremeCrash = true) AND g.adminNotified = false ORDER BY g.roundNumber")
    List<GameCrashSchedule> findUnnofifiedHighRounds(String slug);
}

package com.speedbet.api.crash;

import com.speedbet.api.ai.MistralClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrashService {

    private static final List<String> GAME_SLUGS = List.of("aviator", "crash", "superhero");

    @Value("${app.crash.schedule-buffer:100}") private int scheduleBuffer;
    @Value("${app.crash.batch-size:50}")        private int batchSize;
    @Value("${app.crash.high-threshold:10.0}")  private double highThreshold;
    @Value("${app.crash.extreme-threshold:20.0}") private double extremeThreshold;
    @Value("${app.crash.alert-lookahead-rounds:3}") private int lookaheadRounds;

    private final CrashScheduleRepository scheduleRepo;
    private final MistralClient           mistral;
    private final SimpMessagingTemplate   messaging;

    // ── Public API ────────────────────────────────────────────────────────

    public GameCrashSchedule getNextRound(String slug) {
        var upcoming = scheduleRepo.findUnplayedBySlug(slug);
        if (upcoming.isEmpty()) {
            log.warn("No upcoming rounds for {}, generating emergency batch", slug);
            generateBatch(slug);
            upcoming = scheduleRepo.findUnplayedBySlug(slug);
        }
        return upcoming.get(0);
    }

    @Transactional
    public void markPlayed(String slug, long roundNumber) {
        scheduleRepo.findUnplayedBySlug(slug).stream()
                .filter(r -> r.getRoundNumber() == roundNumber)
                .findFirst()
                .ifPresent(r -> {
                    r.setPlayedAt(java.time.Instant.now());
                    scheduleRepo.save(r);
                });
    }

    public List<GameCrashSchedule> getUpcoming(String slug, int limit) {
        return scheduleRepo.findNextN(slug, limit);
    }

    public Page<GameCrashSchedule> getHistory(String slug, Pageable pageable) {
        return scheduleRepo.findByGameSlugOrderByRoundNumberDesc(slug, pageable);
    }

    @Transactional
    public GameCrashSchedule overrideRound(String roundId, BigDecimal newValue, String reason) {
        var round = scheduleRepo.findById(java.util.UUID.fromString(roundId))
                .orElseThrow(() -> new RuntimeException("Round not found: " + roundId));
        if (round.getPlayedAt() != null)
            throw new RuntimeException("Cannot override already-played round: " + roundId);

        round.setCrashAt(newValue);
        round.setTier(classifyTier(newValue.doubleValue()));
        round.setHighCrash(newValue.doubleValue() >= highThreshold);
        round.setExtremeCrash(newValue.doubleValue() >= extremeThreshold);
        round.setGeneratedBy("ADMIN_OVERRIDE");
        round.setOverrideReason(reason);
        return scheduleRepo.save(round);
    }

    // ── Batch generation ──────────────────────────────────────────────────

    @Transactional
    public void generateBatch(String slug) {
        long maxRound = scheduleRepo.findMaxRoundNumber(slug).orElse(0L);

        Map<String, Object> distribution = new HashMap<>();
        distribution.put("low_pct",     0.40);
        distribution.put("med_pct",     0.35);
        distribution.put("high_pct",    0.20);
        distribution.put("extreme_pct", 0.05);

        // ── Null-safe point acquisition ───────────────────────────────────
        // generateCrashPoints already falls back to PRNG internally, but
        // if it somehow returns null (e.g. thread interrupted mid-reactive
        // chain), we guard here so a NPE never escapes into the scheduler.
        List<Double> points;
        try {
            points = mistral.generateCrashPoints(slug, batchSize, distribution);
        } catch (Exception e) {
            log.error("Unexpected error calling generateCrashPoints for {}: {}", slug, e.getMessage(), e);
            points = null;
        }

        if (points == null || points.isEmpty()) {
            log.warn("generateCrashPoints returned null/empty for {}, falling back to PRNG", slug);
            points = mistral.generateFallbackCrashPoints(batchSize, distribution);
        }

        // Safety: ensure we always have exactly batchSize points
        if (points.size() < batchSize) {
            log.warn("Only {}/{} points for {}, padding with PRNG", points.size(), batchSize, slug);
            var extra = mistral.generateFallbackCrashPoints(batchSize - points.size(), distribution);
            points = new ArrayList<>(points);
            points.addAll(extra);
        }

        var rounds = new ArrayList<GameCrashSchedule>(points.size());
        for (int i = 0; i < points.size(); i++) {
            double val = points.get(i);
            // Clamp to sensible range — never below 1.00 or NaN/Inf
            if (!Double.isFinite(val) || val < 1.00) {
                log.warn("Invalid crash point {} at index {} for {}, replacing with 1.00", val, i, slug);
                val = 1.00;
            }
            val = BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();

            rounds.add(GameCrashSchedule.builder()
                    .gameSlug(slug)
                    .roundNumber(maxRound + i + 1)
                    .crashAt(BigDecimal.valueOf(val))
                    .tier(classifyTier(val))
                    .highCrash(val >= highThreshold)
                    .extremeCrash(val >= extremeThreshold)
                    .generatedBy("AI")
                    .build());
        }
        scheduleRepo.saveAll(rounds);
        log.info("Generated {} crash points for {}", rounds.size(), slug);
    }

    // ── Scheduled tasks ───────────────────────────────────────────────────

    @Scheduled(fixedDelay = 3_600_000)
    public void replenishSchedule() {
        for (var slug : GAME_SLUGS) {
            try {
                long unplayed = scheduleRepo.countUnplayed(slug);
                if (unplayed < scheduleBuffer) {
                    log.info("Replenishing crash schedule for {} ({} unplayed)", slug, unplayed);
                    generateBatch(slug);
                }
            } catch (Exception e) {
                // Isolated per-slug: one slug failing must never abort the others
                log.error("Failed to replenish schedule for {}: {}", slug, e.getMessage(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 5_000)
    public void detectHighRounds() {
        for (var slug : GAME_SLUGS) {
            try {
                var upcoming = scheduleRepo.findNextN(slug, lookaheadRounds);
                for (var round : upcoming) {
                    if ((round.isHighCrash() || round.isExtremeCrash()) && !round.isAdminNotified()) {
                        Map<String, Object> alert = new HashMap<>();
                        alert.put("gameSlug",    slug);
                        alert.put("roundNumber", round.getRoundNumber());
                        alert.put("tier",        round.getTier());
                        alert.put("crashAt",     round.getCrashAt());
                        alert.put("type",        "HIGH_CRASH_ALERT");
                        messaging.convertAndSend("/topic/admin/crash-alerts", alert);
                        round.setAdminNotified(true);
                        scheduleRepo.save(round);
                        log.info("HIGH crash alert sent for {} round {}", slug, round.getRoundNumber());
                    }
                }
            } catch (Exception e) {
                log.error("detectHighRounds failed for {}: {}", slug, e.getMessage(), e);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String classifyTier(double val) {
        if (val >= extremeThreshold) return "EXTREME";
        if (val >= highThreshold)    return "HIGH";
        if (val >= 2.01)             return "MEDIUM";
        return "LOW";
    }
}
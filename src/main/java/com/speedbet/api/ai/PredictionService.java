package com.speedbet.api.ai;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    private final AiPredictionRepository predictionRepository;
    private final MistralClient mistral;
    private final MatchService matchService;

    @Transactional
    public AiPrediction runPrediction(String matchId, UUID adminId) {
        var match = matchService.getById(matchId);
        var ctx = buildContext(match);
        var predictionData = mistral.predictMatch(ctx);
        return predictionRepository.save(AiPrediction.builder()
                .matchId(UUID.fromString(matchId))  // ← convert here
                .prediction(predictionData)
                .model("mistral-large-latest")
                .sharedByAdminId(adminId)
                .build());
    }

    @Transactional
    public AiPrediction publish(UUID predictionId, UUID adminId, String note) {
        var prediction = predictionRepository.findById(predictionId)
                .orElseThrow(() -> ApiException.notFound("Prediction not found"));

        prediction.setPublishedToUsers(true);
        prediction.setSharedAt(Instant.now());
        prediction.setSharedByAdminId(adminId);

        if (note != null) {
            prediction.setAdminNote(note);
        }
        return predictionRepository.save(prediction);
    }

    @Transactional
    public AiPrediction unpublish(UUID predictionId, UUID adminId) {
        var prediction = predictionRepository.findById(predictionId)
                .orElseThrow(() -> ApiException.notFound("Prediction not found"));
        prediction.setPublishedToUsers(false);
        return predictionRepository.save(prediction);
    }

    public Page<AiPrediction> getAdminPredictions(UUID adminId, Pageable pageable) {
        return predictionRepository.findBySharedByAdminIdOrderByGeneratedAtDesc(adminId, pageable);
    }

    @Cacheable("predictions")
    public Page<AiPrediction> getPublishedForUsers(Pageable pageable) {
        return predictionRepository.findByPublishedToUsersTrueOrderByGeneratedAtDesc(pageable);
    }

    public AiPrediction getById(UUID id) {
        return predictionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Prediction not found"));
    }

    public Page<AiPrediction> getAllForSuperAdmin(Pageable pageable) {
        return predictionRepository.findAllOrderByGeneratedAtDesc(pageable);
    }

    // Hourly auto-prediction for matches kicking off in next 24h
    @Scheduled(fixedDelay = 3600000)
    public void refreshPredictions() {
        var upcoming = matchService.getUpcomingMatches().stream()
                .filter(m -> m.getKickoffAt() != null &&
                        m.getKickoffAt().isBefore(Instant.now().plus(24, ChronoUnit.HOURS)))
                .toList();

        for (var match : upcoming) {
            try {
                var existing = predictionRepository.findTopByMatchIdOrderByGeneratedAtDesc(match.getId());
                if (existing.isPresent() &&
                        existing.get().getGeneratedAt().isAfter(Instant.now().minus(1, ChronoUnit.HOURS))) {
                    continue; // Fresh enough
                }
                var ctx = buildContext(match);
                var predictionData = mistral.predictMatch(ctx);
                predictionRepository.save(AiPrediction.builder()
                        .matchId(match.getId())
                        .prediction(predictionData)
                        .build());
            } catch (Exception e) {
                log.warn("Auto-prediction failed for match {}: {}", match.getId(), e.getMessage());
            }
        }
    }

    private Map<String, Object> buildContext(Match match) {
        // Note: H2H data removed as SportSrcClient H2H endpoint is unavailable on Free tier
        return Map.of(
                "home", match.getHomeTeam(),
                "away", match.getAwayTeam(),
                "league", match.getLeague() != null ? match.getLeague() : "Unknown",
                "homeForm", List.of("W", "W", "D", "L", "W"),
                "awayForm", List.of("W", "L", "D", "W", "W"),
                "h2h", List.of("2-1H", "1-1", "0-2A"), // Static fallback data
                "leagueAvgGoals", 2.7,
                "kickoff", match.getKickoffAt() != null ? match.getKickoffAt().toString() : ""
        );
    }
}

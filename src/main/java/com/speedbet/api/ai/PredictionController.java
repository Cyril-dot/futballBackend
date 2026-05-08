package com.speedbet.api.ai;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predService;

    // ── Public ────────────────────────────────────────────────────────────

    @GetMapping("/api/tip/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicTip(@PathVariable UUID id) {
        var pred = predService.getById(id);
        var data = pred.getPrediction();
        // Return stripped version — no reasoning, no generated_at
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "id", pred.getId(),
                "matchId", pred.getMatchId(),
                "confidence", data.getOrDefault("confidence", 0),
                "predictedScore", data.getOrDefault("predicted_score", Map.of()),
                "winProbability", data.getOrDefault("win_probability", Map.of()),
                "adminNote", pred.getAdminNote() != null ? pred.getAdminNote() : ""
        )));
    }

    // ── User feed ─────────────────────────────────────────────────────────

    @GetMapping("/api/predictions/public")
    public ResponseEntity<ApiResponse<PageResponse<AiPrediction>>> userFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(predService.getPublishedForUsers(PageRequest.of(page, size)))));
    }

    // ── Admin ─────────────────────────────────────────────────────────────

    @PostMapping("/api/admin/predictions/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AiPrediction>> run(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> req) {
        var pred = predService.runPrediction(req.get("matchId"), user.getId()); // ← no UUID.fromString, correct order
        return ResponseEntity.ok(ApiResponse.ok(pred, "Prediction generated"));
    }

    @GetMapping("/api/admin/predictions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<AiPrediction>>> adminList(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(predService.getAdminPredictions(user.getId(), PageRequest.of(page, size)))));
    }

    @PostMapping("/api/admin/predictions/{id}/share")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AiPrediction>> share(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> req) {
        String note = req != null ? req.get("note") : null;
        return ResponseEntity.ok(ApiResponse.ok(predService.publish(id, user.getId(), note)));
    }

    @PostMapping("/api/admin/predictions/{id}/unpublish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AiPrediction>> unpublish(
            @AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(predService.unpublish(id, user.getId())));
    }

    // ── Super Admin ───────────────────────────────────────────────────────

    @GetMapping("/api/super-admin/predictions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<AiPrediction>>> superAdminList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(predService.getAllForSuperAdmin(PageRequest.of(page, size)))));
    }
}

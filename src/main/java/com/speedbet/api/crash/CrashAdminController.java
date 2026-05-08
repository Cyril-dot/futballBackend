package com.speedbet.api.crash;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/crash")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class CrashAdminController {

    private final CrashService crashService;

    @GetMapping("/schedule/{game}")
    public ResponseEntity<ApiResponse<List<GameCrashSchedule>>> schedule(
            @PathVariable String game,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(crashService.getUpcoming(game, limit)));
    }

    @GetMapping("/history/{game}")
    public ResponseEntity<ApiResponse<PageResponse<GameCrashSchedule>>> history(
            @PathVariable String game,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            new PageResponse<>(crashService.getHistory(game, PageRequest.of(page, size)))));
    }

    @PostMapping("/schedule/{game}/generate")
    public ResponseEntity<ApiResponse<Void>> generate(@PathVariable String game) {
        crashService.generateBatch(game);
        return ResponseEntity.ok(ApiResponse.ok(null, "Crash schedule generated"));
    }

    @PatchMapping("/schedule/{id}/override")
    public ResponseEntity<ApiResponse<GameCrashSchedule>> override(
            @PathVariable String id,
            @RequestBody Map<String, Object> req) {
        var newValue = new BigDecimal(req.get("crashAt").toString());
        var reason = req.getOrDefault("reason", "Admin override").toString();
        return ResponseEntity.ok(ApiResponse.ok(crashService.overrideRound(id, newValue, reason)));
    }
}

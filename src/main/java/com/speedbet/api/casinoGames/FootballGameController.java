package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games/football")
@RequiredArgsConstructor
public class FootballGameController {

    private final FootballGameService gameService;

    @PostMapping("/play")
    public ResponseEntity<ApiResponse<PlayResponse>> play(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody PlayRequest request) {
        var result = gameService.play(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/settle")
    public ResponseEntity<ApiResponse<SettleResponse>> settle(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody SettleRequest request) {
        var result = gameService.settle(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** Kept for backward compatibility — only the most recent open round. */
    @GetMapping("/current-round")
    public ResponseEntity<ApiResponse<RoundView>> currentRound(@AuthenticationPrincipal User user) {
        RoundView view = gameService.currentRound(user.getId());
        return ResponseEntity.ok(ApiResponse.ok(view));
    }

    /** ALL open rounds — use this on page load to resume every live bet. */
    @GetMapping("/open-rounds")
    public ResponseEntity<ApiResponse<List<RoundView>>> openRounds(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.openRounds(user.getId())));
    }

    @GetMapping("/odds")
    public ResponseEntity<ApiResponse<OddsQuote>> odds() {
        return ResponseEntity.ok(ApiResponse.ok(gameService.previewOdds()));
    }

    /** Full roster, e.g. for a "pick your matchup" picker. */
    @GetMapping("/teams")
    public ResponseEntity<ApiResponse<List<Team>>> teams() {
        return ResponseEntity.ok(ApiResponse.ok(gameService.teams()));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<RoundStore.HistoryEntry>>> history(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "20") int limit) {
        var result = gameService.history(user.getId(), Math.min(limit, 100));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> balance(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("balance", gameService.balance(user.getId()))));
    }
}
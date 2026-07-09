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

/**
 * POST /api/games/football/play
 * POST /api/games/football/settle
 * GET  /api/games/football/current-round
 * GET  /api/games/football/history
 * GET  /api/games/football/balance
 *
 * userId always comes from @AuthenticationPrincipal — never from the
 * request body — so a client can never place or settle a bet on someone
 * else's wallet.
 */
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

    @GetMapping("/current-round")
    public ResponseEntity<ApiResponse<RoundView>> currentRound(@AuthenticationPrincipal User user) {
        RoundView view = gameService.currentRound(user.getId());
        return ResponseEntity.ok(ApiResponse.ok(view));
    }

    @GetMapping("/odds")
    public ResponseEntity<ApiResponse<OddsQuote>> odds() {
        return ResponseEntity.ok(ApiResponse.ok(gameService.previewOdds()));
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
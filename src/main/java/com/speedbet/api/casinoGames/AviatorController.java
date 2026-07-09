package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/games/aviator")
@RequiredArgsConstructor
public class AviatorController {

    private static final Logger log = LoggerFactory.getLogger(AviatorController.class);

    private final AviatorRoundService roundService;

    public record PlayRequest(@NotNull @Positive BigDecimal stake) {}
    public record CashoutRequest(@NotNull UUID roundId, @NotNull @Positive Double cashoutAt) {}

    @PostMapping("/play")
    public ResponseEntity<ApiResponse<Map<String, UUID>>> play(
            @Valid @RequestBody PlayRequest req,
            @AuthenticationPrincipal User user) {
        log.info("aviator.play: userId='{}' stake='{}'", user.getId(), req.stake());

        UUID roundId = roundService.placeBet(user.getId(), req.stake());

        log.info("aviator.play: bet placed userId='{}' roundId='{}'", user.getId(), roundId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", roundId)));
    }

    @PostMapping("/cashout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cashout(
            @Valid @RequestBody CashoutRequest req,
            @AuthenticationPrincipal User user) {
        log.info("aviator.cashout: userId='{}' roundId='{}' requestedAt='{}'", user.getId(), req.roundId(), req.cashoutAt());

        AviatorRoundService.CashoutResult result = roundService.cashout(user.getId(), req.roundId(), req.cashoutAt());

        log.info("aviator.cashout: confirmed userId='{}' roundId='{}' multiplier='{}' payout='{}'",
                user.getId(), req.roundId(), result.multiplier(), result.payout());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "payout", result.payout(),
                "multiplier", result.multiplier(),
                "walletBalance", result.walletBalance()
        )));
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<AviatorRoundService.RoundView>> current() {
        return ResponseEntity.ok(ApiResponse.ok(roundService.getCurrentRoundView()));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> history(
            @RequestParam(defaultValue = "15") int limit) {
        if (limit < 1 || limit > 100) {
            throw ApiException.badRequest("limit must be between 1 and 100");
        }

        List<Map<String, Object>> rounds = roundService.getHistory(limit).stream()
                .map(cp -> Map.<String, Object>of("result", Map.of("crashPoint", cp)))
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(rounds));
    }
}
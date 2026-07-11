package com.speedbet.api.casinoGames.spindaBottle;

import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for Spin Da' Bottle — matches the {slug} convention
 * game.js already assumes (CONFIG.GAME_SLUG = 'spin-da-bottle',
 * SpeedBetAPI.games.play/result at /api/games/{slug}/*).
 *
 * Unlike Mines (start → reveal → cashout, several player decisions per
 * round), a bottle spin is fully determined the instant it's placed, so
 * there's a single /play call rather than separate play/result steps —
 * the response already carries the settled outcome, payout, and the
 * post-round wallet balance. The front end uses the returned outcome
 * purely to pick which way the bottle animates; it never decides the
 * result itself.
 *
 * Resolves the authenticated user via @AuthenticationPrincipal User, same
 * as MinesController / FootballGameController — not by parsing the JWT
 * subject as a UUID.
 */
@RestController
@RequestMapping("/api/games/spin-da-bottle")
@RequiredArgsConstructor
public class SpinBottleController {

    private final SpinBottleService spinBottleService;

    @PostMapping("/play")
    public SpinBottlePlayResponse play(@AuthenticationPrincipal User user,
                                        @Valid @RequestBody SpinBottlePlayRequest request) {
        return spinBottleService.play(user.getId(), request);
    }

    @GetMapping("/history")
    public List<SpinBottleHistoryEntry> history(@AuthenticationPrincipal User user,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return spinBottleService.history(user.getId(), limit);
    }
}
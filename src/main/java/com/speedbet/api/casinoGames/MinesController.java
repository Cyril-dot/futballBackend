package com.speedbet.api.casinoGames;

import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST surface for the mines game — matches the endpoints game.js
 * already calls (SpeedBetAPI.games.start/reveal/cashout) at
 * /api/games/mines/*, and is also what the React lobby widget should
 * call instead of generating a local layout.
 *
 * Resolves the authenticated user the same way FootballGameController
 * does — via @AuthenticationPrincipal User, not by parsing the JWT
 * subject as a UUID (the subject here is the user's email, not their id).
 */
@RestController
@RequestMapping("/api/games/mines")
@RequiredArgsConstructor
public class MinesController {

    private final MinesGameService minesGameService;

    @PostMapping("/start")
    public MinesStartResponse start(@AuthenticationPrincipal User user, @RequestBody MinesStartRequest request) {
        return minesGameService.start(user.getId(), request);
    }

    @PostMapping("/reveal")
    public MinesRevealResponse reveal(@AuthenticationPrincipal User user, @RequestBody MinesRevealRequest request) {
        return minesGameService.reveal(user.getId(), request);
    }

    @PostMapping("/cashout")
    public MinesCashoutResponse cashout(@AuthenticationPrincipal User user, @RequestBody MinesCashoutRequest request) {
        return minesGameService.cashout(user.getId(), request);
    }

    @GetMapping("/history")
    public List<MinesHistoryEntry> history(@AuthenticationPrincipal User user,
                                           @RequestParam(defaultValue = "20") int limit) {
        return minesGameService.history(user.getId(), limit);
    }
}
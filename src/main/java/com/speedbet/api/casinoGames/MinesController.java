package com.speedbet.api.casinoGames;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the mines game — matches the endpoints game.js
 * already calls (SpeedBetAPI.games.start/reveal/cashout) at
 * /api/games/mines/*, and is also what the React lobby widget should
 * call instead of generating a local layout.
 *
 * resolveUserId() below is a placeholder assuming the JWT subject IS
 * the user's UUID. Swap it for whatever the rest of this codebase
 * actually uses to pull the authenticated user id (e.g. a custom
 * UserPrincipal cast, an @AuthenticationPrincipal type used by
 * FootballController, etc) — this is the one seam that needs
 * confirming against your real security config.
 */
@RestController
@RequestMapping("/api/games/mines")
@RequiredArgsConstructor
public class MinesController {

    private final MinesGameService minesGameService;

    @PostMapping("/start")
    public MinesStartResponse start(Authentication auth, @RequestBody MinesStartRequest request) {
        return minesGameService.start(resolveUserId(auth), request);
    }

    @PostMapping("/reveal")
    public MinesRevealResponse reveal(Authentication auth, @RequestBody MinesRevealRequest request) {
        return minesGameService.reveal(resolveUserId(auth), request);
    }

    @PostMapping("/cashout")
    public MinesCashoutResponse cashout(Authentication auth, @RequestBody MinesCashoutRequest request) {
        return minesGameService.cashout(resolveUserId(auth), request);
    }

    @GetMapping("/history")
    public List<MinesHistoryEntry> history(Authentication auth,
                                            @RequestParam(defaultValue = "20") int limit) {
        return minesGameService.history(resolveUserId(auth), limit);
    }

    private UUID resolveUserId(Authentication auth) {
        // TODO: align with however FootballController (or wherever else
        // userId is resolved elsewhere in this API) actually does this.
        return UUID.fromString(auth.getName());
    }
}
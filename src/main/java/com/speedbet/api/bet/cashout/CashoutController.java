package com.speedbet.api.bet.cashout;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for bet cashout.
 *
 * All routes live under /api/bets/{betId}/cashout so they are
 * grouped naturally with the existing bet resource.
 *
 * ── Endpoints ────────────────────────────────────────────────────────────
 *
 *   GET  /api/bets/{betId}/cashout/preview?pct=100
 *        Returns estimated cashout value — no state change.
 *        Safe to call on every bet-slip render.
 *
 *   POST /api/bets/{betId}/cashout/full
 *        Closes the bet and credits the full payout immediately.
 *
 *   POST /api/bets/{betId}/cashout/partial?pct=50
 *        Cashes out `pct` percent; bet stays PENDING at reduced stake.
 *        pct must be 10–90 inclusive.
 */
@RestController
@RequestMapping("/api/bets/{betId}/cashout")
@RequiredArgsConstructor
public class CashoutController {

    private final CashoutService cashoutService;

    /**
     * Preview cashout value — no state change.
     *
     * @param pct 100 for full preview, 10–90 for partial (default 100)
     */
    @GetMapping("/preview")
    public ResponseEntity<CashoutService.CashoutPreview> preview(
            @PathVariable UUID betId,
            @RequestParam(defaultValue = "100") int pct,
            @AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok(cashoutService.preview(betId, userId, pct));
    }

    /**
     * Execute full cashout — closes the bet entirely.
     */
    @PostMapping("/full")
    public ResponseEntity<CashoutService.CashoutResult> cashoutFull(
            @PathVariable UUID betId,
            @AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok(cashoutService.cashoutFull(betId, userId));
    }

    /**
     * Execute partial cashout — bet continues at a reduced stake.
     *
     * @param pct percentage to cash out (10–90)
     */
    @PostMapping("/partial")
    public ResponseEntity<CashoutService.CashoutResult> cashoutPartial(
            @PathVariable UUID betId,
            @RequestParam int pct,
            @AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok(cashoutService.cashoutPartial(betId, userId, pct));
    }
}
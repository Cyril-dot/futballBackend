package com.speedbet.api.bet;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/bets")
@RequiredArgsConstructor
public class BetController {

    private final BetService betService;

    @PostMapping
    public ResponseEntity<ApiResponse<Bet>> place(
            @AuthenticationPrincipal User user,
            @RequestBody PlaceBetRequest req) {

        log.info("═══ POST /api/bets ═══");
        log.info("  user        = {}", user != null ? user.getId() : "NULL ← NOT AUTHENTICATED");
        log.info("  stake       = {}", req != null ? req.stake() : "NULL");
        log.info("  currency    = {}", req != null ? req.currency() : "NULL");
        log.info("  selections  = {}", req != null && req.selections() != null ? req.selections().size() : "NULL");
        log.info("  bookingCode = {}", req != null ? req.bookingCodeUsedId() : "NULL");

        if (req == null) {
            log.error("  REQUEST BODY IS NULL — check Content-Type header is application/json");
            throw new IllegalArgumentException("Request body is null");
        }

        if (req.selections() == null || req.selections().isEmpty()) {
            log.error("  SELECTIONS ARE EMPTY — nothing to place");
            throw new IllegalArgumentException("No selections provided");
        }

        req.selections().forEach(s ->
                log.info("  selection — matchId={} market={} selection={} submittedOdds={}",
                        s.matchId(), s.market(), s.selection(), s.submittedOdds())
        );

        var selections = req.selections().stream()
                .map(s -> new BetService.SelectionRequest(
                        s.matchId(), s.market(), s.selection(), s.submittedOdds()))
                .toList();

        try {
            var bet = betService.placeBet(new BetService.PlaceRequest(
                    user.getId(), req.stake(), req.currency(), selections, req.bookingCodeUsedId()));
            log.info("  ✅ SUCCESS — betId={} status={}", bet.getId(), bet.getStatus());
            return ResponseEntity.ok(ApiResponse.ok(bet, "Bet placed successfully"));
        } catch (Exception e) {
            log.error("  ❌ FAILED — {}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<Bet>>> myBets(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/bets — userId={} page={} size={}", user.getId(), page, size);
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(betService.getUserBets(user.getId(), PageRequest.of(page, size)))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Bet>> getOne(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        log.info("GET /api/bets/{} — userId={}", id, user.getId());
        return ResponseEntity.ok(ApiResponse.ok(betService.getById(id, user.getId())));
    }

    @GetMapping("/unseen-wins")
    public ResponseEntity<ApiResponse<List<Bet>>> unseenWins(@AuthenticationPrincipal User user) {
        log.info("GET /api/bets/unseen-wins — userId={}", user.getId());
        return ResponseEntity.ok(ApiResponse.ok(betService.getUnseenWins(user.getId())));
    }

    @PostMapping("/{id}/dismiss-win")
    public ResponseEntity<ApiResponse<Void>> dismissWin(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        log.info("POST /api/bets/{}/dismiss-win — userId={}", id, user.getId());
        betService.markWinSeen(user.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Win dismissed"));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    public record PlaceBetRequest(
            BigDecimal stake,
            String currency,
            List<SelectionDto> selections,
            UUID bookingCodeUsedId
    ) {}

    public record SelectionDto(
            UUID matchId, String market, String selection, BigDecimal submittedOdds
    ) {}
}
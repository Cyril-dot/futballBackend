package com.speedbet.api.game;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.crash.CrashService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

// ── Entity ────────────────────────────────────────────────────────────────────

@Entity
@Table(name = "game_rounds")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class GameRound {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String game;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal stake;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> result;

    @Column(precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal payout = BigDecimal.ZERO;

    @Column(name = "played_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant playedAt = Instant.now();
}

// ── Repository ───────────────────────────────────────────────────────────────

interface GameRoundRepository extends JpaRepository<GameRound, UUID> {
    List<GameRound> findByUserIdOrderByPlayedAtDesc(UUID userId, org.springframework.data.domain.Pageable pageable);
}

// ── Service ───────────────────────────────────────────────────────────────────

@Slf4j
@Service
@RequiredArgsConstructor
class GameService {
    private final GameRoundRepository roundRepo;
    private final WalletService walletService;
    private final CrashService crashService;
    private static final Random RAND = new Random();

    @Transactional
    public GameRound play(UUID userId, String gameSlug, BigDecimal stake) {
        log.info("play: userId={} game={} stake={}", userId, gameSlug, stake);

        walletService.debit(userId, stake, TxKind.BET_STAKE, null,
                Map.of("game", gameSlug, "type", "game_stake"));
        log.debug("play: stake debited userId={} amount={}", userId, stake);

        var result = resolveGame(gameSlug, stake);
        var payout = (BigDecimal) result.get("_payout");
        result.remove("_payout");

        var round = roundRepo.save(GameRound.builder()
                .userId(userId).game(gameSlug).stake(stake).result(result).payout(payout).build());
        log.info("play: round saved roundId={} game={} payout={}", round.getId(), gameSlug, payout);

        if (payout.compareTo(BigDecimal.ZERO) > 0) {
            walletService.credit(userId, payout, TxKind.BET_WIN,
                    "GAME-WIN-" + round.getId(),
                    Map.of("game", gameSlug, "roundId", round.getId().toString()));
            log.info("play: win credited userId={} payout={} roundId={}", userId, payout, round.getId());
        } else {
            log.debug("play: no payout for userId={} game={} roundId={}", userId, gameSlug, round.getId());
        }

        return round;
    }

    @Transactional
    public Map<String, Object> crashCashout(UUID userId, UUID roundId, BigDecimal cashoutAt) {
        log.info("crashCashout: userId={} roundId={} cashoutAt={}", userId, roundId, cashoutAt);

        GameRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> {
                    log.warn("crashCashout: round not found roundId={}", roundId);
                    return ApiException.notFound("Round not found");
                });

        if (!round.getUserId().equals(userId)) {
            log.warn("crashCashout: userId={} does not own roundId={}", userId, roundId);
            throw ApiException.unprocessable("Round does not belong to user");
        }

        if (round.getPayout().compareTo(BigDecimal.ZERO) > 0) {
            log.warn("crashCashout: duplicate cashout attempt userId={} roundId={}", userId, roundId);
            throw ApiException.conflict("Already cashed out");
        }

        BigDecimal payout = round.getStake()
                .multiply(cashoutAt, MathContext.DECIMAL64)
                .setScale(4, RoundingMode.HALF_UP);
        log.debug("crashCashout: computed payout={} stake={} multiplier={}", payout, round.getStake(), cashoutAt);

        round.setPayout(payout);
        round.setResult(new HashMap<>(Map.of("outcome", "CASHED_OUT", "cashoutAt", cashoutAt)));
        roundRepo.save(round);

        walletService.credit(userId, payout, TxKind.BET_WIN,
                "CRASH-WIN-" + roundId,
                Map.of("game", "aviator", "roundId", roundId.toString(), "multiplier", cashoutAt));
        log.info("crashCashout: payout credited userId={} payout={} roundId={}", userId, payout, roundId);

        var wallet = walletService.getWallet(userId);
        log.debug("crashCashout: wallet balance after cashout userId={} balance={}", userId, wallet.getBalance());

        return Map.of(
                "status", "CASHED_OUT",
                "multiplier", cashoutAt,
                "payout", payout,
                "walletBalance", wallet.getBalance()
        );
    }

    public Map<String, Object> getCurrentCrashRound(String slug) {
        log.debug("getCurrentCrashRound: slug={}", slug);
        var next = crashService.getNextRound(slug);
        log.debug("getCurrentCrashRound: roundNumber={} tier={}", next.getRoundNumber(), next.getTier());
        return Map.of(
                "roundNumber", next.getRoundNumber(),
                "tier", next.getTier(),
                "gameSlug", slug
        );
    }

    public List<GameRound> getHistory(UUID userId, int limit) {
        log.debug("getHistory: userId={} limit={}", userId, limit);
        var history = roundRepo.findByUserIdOrderByPlayedAtDesc(userId, PageRequest.of(0, limit));
        log.debug("getHistory: returned {} rounds for userId={}", history.size(), userId);
        return history;
    }

    @SuppressWarnings("SpellCheckingInspection")
    private Map<String, Object> resolveGame(String gameSlug, BigDecimal stake) {
        log.debug("resolveGame: game={} stake={}", gameSlug, stake);
        return switch (gameSlug.toLowerCase()) {
            case "flip", "coin" -> {
                boolean win = RAND.nextBoolean();
                var payout = win ? stake.multiply(new BigDecimal("1.95"), MathContext.DECIMAL64) : BigDecimal.ZERO;
                log.debug("resolveGame: flip result={} payout={}", win ? "HEADS" : "TAILS", payout);
                yield new HashMap<>(Map.of("outcome", win ? "HEADS" : "TAILS",
                        "won", win, "_payout", payout));
            }
            case "dice" -> {
                int roll = RAND.nextInt(99) + 1;
                int threshold = 50;
                boolean over = roll > threshold;
                var payout = over ? stake.multiply(new BigDecimal("1.95"), MathContext.DECIMAL64) : BigDecimal.ZERO;
                log.debug("resolveGame: dice roll={} over={} payout={}", roll, over, payout);
                yield new HashMap<>(Map.of("roll", roll, "threshold", threshold,
                        "won", over, "_payout", payout));
            }
            case "spin" -> {
                double[] weights = {2.0, 0, 5.0, 0.5, 10.0, 0, 3.0, 0};
                int segment = RAND.nextInt(8);
                var multiplier = new BigDecimal(String.valueOf(weights[segment]));
                var payout = stake.multiply(multiplier, MathContext.DECIMAL64);
                log.debug("resolveGame: spin segment={} multiplier={} payout={}", segment, multiplier, payout);
                yield new HashMap<>(Map.of("segment", segment, "multiplier", multiplier,
                        "won", multiplier.compareTo(BigDecimal.ZERO) > 0, "_payout", payout));
            }
            case "magic-ball", "magicball" -> {
                int pick = RAND.nextInt(3);
                int ball = RAND.nextInt(3);
                boolean win = pick == ball;
                var payout = win ? stake.multiply(new BigDecimal("2.9"), MathContext.DECIMAL64) : BigDecimal.ZERO;
                log.debug("resolveGame: magic-ball pick={} ball={} win={} payout={}", pick, ball, win, payout);
                yield new HashMap<>(Map.of("pick", pick, "ballUnder", ball,
                        "won", win, "_payout", payout));
            }
            default -> {
                log.warn("resolveGame: unknown game='{}' — returning PLAYED with zero payout", gameSlug);
                yield new HashMap<>(Map.of("outcome", "PLAYED", "_payout", BigDecimal.ZERO));
            }
        };
    }
}

// ── Controller ───────────────────────────────────────────────────────────────

@Slf4j
@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;

    @PostMapping("/{game}/play")
    public ResponseEntity<ApiResponse<GameRound>> play(
            @AuthenticationPrincipal User user,
            @PathVariable String game,
            @RequestBody Map<String, Object> req) {
        log.info("POST /api/games/{}/play userId={}", game, user.getId());
        var stake = new BigDecimal(req.get("stake").toString());
        return ResponseEntity.ok(ApiResponse.ok(gameService.play(user.getId(), game, stake)));
    }

    @PostMapping("/{game}/cashout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cashout(
            @AuthenticationPrincipal User user,
            @PathVariable String game,
            @RequestBody Map<String, Object> req) {
        log.info("POST /api/games/{}/cashout userId={}", game, user.getId());
        var roundId = UUID.fromString(req.get("roundId").toString());
        var cashoutAt = new BigDecimal(req.get("cashoutAt").toString());
        return ResponseEntity.ok(ApiResponse.ok(gameService.crashCashout(user.getId(), roundId, cashoutAt)));
    }

    @GetMapping("/{game}/current-round")
    public ResponseEntity<ApiResponse<Map<String, Object>>> currentRound(@PathVariable String game) {
        log.debug("GET /api/games/{}/current-round", game);
        return ResponseEntity.ok(ApiResponse.ok(gameService.getCurrentCrashRound(game)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<GameRound>>> history(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "20") int limit) {
        log.debug("GET /api/games/history userId={} limit={}", user.getId(), limit);
        return ResponseEntity.ok(ApiResponse.ok(gameService.getHistory(user.getId(), limit)));
    }
}
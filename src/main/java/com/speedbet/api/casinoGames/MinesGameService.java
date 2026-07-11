package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates mines rounds end to end. Mirrors FootballGameService:
 * the ENTIRE bomb layout is decided and frozen at start() time (same
 * "simulate now, freeze the outcome" idea as football's match sim) —
 * reveal() and cashout() only ever uncover what was already decided,
 * never re-roll anything.
 *
 *   start()    → validate bet, debit stake, freeze a bomb layout. If
 *                firstTileIdx is supplied it's guaranteed safe and
 *                revealed immediately; otherwise the round just opens.
 *   reveal()   → uncover one more tile for an in-progress round. A
 *                bomb ends the round as a loss; revealing every safe
 *                tile is an auto win.
 *   cashout()  → bank the current multiplier for an in-progress round.
 *   autoSettleAbandonedRounds() → force-settles (as a loss — nothing
 *                was ever banked) any round left open past the grace
 *                period, so a closed tab never leaves a debited stake
 *                in limbo forever.
 */
@Service
@RequiredArgsConstructor
public class MinesGameService {

    public static final int GRID_SIZE = 25;
    public static final int MIN_BOMBS = 1;
    public static final int MAX_BOMBS = 24;
    private static final BigDecimal MIN_STAKE = BigDecimal.ONE;
    private static final BigDecimal RTP = new BigDecimal("0.97");
    // Generous relative to a typical round — this only exists to catch
    // rounds nobody ever calls reveal()/cashout() for again.
    private static final Duration AUTO_SETTLE_GRACE = Duration.ofMinutes(10);

    private final WalletService walletService;
    private final MinesRoundStore roundStore;
    private final SecureRandom secureRandom = new SecureRandom();

    public MinesStartResponse start(UUID userId, MinesStartRequest request) {
        if (request.stake() == null || request.stake().compareTo(MIN_STAKE) < 0) {
            throw ApiException.unprocessable("Minimum stake is " + MIN_STAKE);
        }
        int bombCount = request.bombCount() == null ? 0 : request.bombCount();
        if (bombCount < MIN_BOMBS || bombCount > MAX_BOMBS) {
            throw ApiException.unprocessable("bombCount must be between " + MIN_BOMBS + " and " + MAX_BOMBS);
        }
        Integer firstTileIdx = request.firstTileIdx();
        if (firstTileIdx != null && (firstTileIdx < 0 || firstTileIdx >= GRID_SIZE)) {
            throw ApiException.unprocessable("firstTileIdx must be between 0 and " + (GRID_SIZE - 1));
        }

        String roundId = UUID.randomUUID().toString();

        // Debit first — throws ApiException.unprocessable("Insufficient balance")
        // via WalletService if the stake can't be covered. Nothing below runs
        // if this throws, so no round/layout is ever created for an unfunded bet.
        walletService.debit(
                userId, request.stake(), TxKind.GAME_STAKE,
                "mines:stake:" + roundId,
                Map.of(
                        "game", "mines",
                        "roundId", roundId,
                        "bombCount", bombCount
                )
        );

        String serverSeed = randomHex(32);
        String commitHash = sha256(serverSeed + userId + roundId);

        // If a first tile was supplied it's excluded from the bomb draw
        // (guaranteed safe, standard mines-game convention). If not,
        // bombs are drawn from the full 25-tile grid.
        Set<Integer> bombPositions = drawBombPositions(serverSeed, roundId, bombCount, firstTileIdx);

        MinesRound round = new MinesRound(roundId, userId, request.stake(), bombCount,
                bombPositions, serverSeed, commitHash);

        int diamondsFound = 0;
        if (firstTileIdx != null) {
            round.addRevealed(firstTileIdx);
            diamondsFound = 1;
        }
        roundStore.save(round);

        BigDecimal multiplier = calcMultiplier(diamondsFound, bombCount);
        BigDecimal potentialPayout = request.stake().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        BigDecimal walletBalance = walletService.getBalance(userId);

        return new MinesStartResponse(
                roundId, commitHash, walletBalance, request.stake(), bombCount,
                diamondsFound, multiplier, potentialPayout
        );
    }

    public MinesRevealResponse reveal(UUID userId, MinesRevealRequest request) {
        MinesRound round = findOwnedOpenRound(userId, request.roundId());

        int tileIdx = request.tileIdx() == null ? -1 : request.tileIdx();
        if (tileIdx < 0 || tileIdx >= GRID_SIZE) {
            throw ApiException.unprocessable("tileIdx must be between 0 and " + (GRID_SIZE - 1));
        }
        if (round.isRevealed(tileIdx)) {
            throw ApiException.unprocessable("Tile " + tileIdx + " has already been revealed");
        }

        if (round.isBomb(tileIdx)) {
            return settleAsLoss(round);
        }

        round.addRevealed(tileIdx);
        int diamonds = round.diamondsFound();
        BigDecimal multiplier = calcMultiplier(diamonds, round.bombCount);
        BigDecimal potentialPayout = round.stake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

        int safeTiles = GRID_SIZE - round.bombCount;
        if (diamonds >= safeTiles) {
            // Every safe tile is revealed — auto cash-out at the max multiplier.
            return settleAsWin(round, multiplier, potentialPayout, RevealResult.AUTO_WIN);
        }

        return new MinesRevealResponse(
                RevealResult.SAFE, diamonds, multiplier, potentialPayout,
                null, null, null, walletService.getBalance(userId)
        );
    }

    public MinesCashoutResponse cashout(UUID userId, MinesCashoutRequest request) {
        MinesRound round = findOwnedOpenRound(userId, request.roundId());

        int diamonds = round.diamondsFound();
        if (diamonds == 0) {
            throw ApiException.unprocessable("Reveal at least one tile before cashing out");
        }

        BigDecimal multiplier = calcMultiplier(diamonds, round.bombCount);
        BigDecimal payout = round.stake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        MinesRevealResponse settled = settleAsWin(round, multiplier, payout, RevealResult.SAFE);

        return new MinesCashoutResponse(
                settled.payout(), settled.multiplier(), settled.bombPositions(),
                settled.serverSeed(), settled.newBalance()
        );
    }

    /**
     * Force-settles (as a loss — the stake is already at risk and
     * nothing was ever banked) any round still open past
     * AUTO_SETTLE_GRACE. Same idea as football's abandoned-round sweep.
     */
    @Scheduled(fixedDelay = 60 * 1000)
    void autoSettleAbandonedRounds() {
        for (MinesRound round : roundStore.findStaleOpenRounds(AUTO_SETTLE_GRACE)) {
            if (round.isSettled()) continue; // already handled by a real call in the meantime
            try {
                settleAsLoss(round);
            } catch (ApiException e) {
                // Lost the race to a concurrent reveal/cashout — fine, skip it.
            }
        }
    }

    public List<MinesHistoryEntry> history(UUID userId, int limit) {
        return roundStore.history(userId, limit);
    }

    public BigDecimal balance(UUID userId) {
        return walletService.getBalance(userId);
    }

    // ── internal ─────────────────────────────────────────────────────

    private MinesRound findOwnedOpenRound(UUID userId, String roundId) {
        MinesRound round = roundStore.find(roundId)
                .orElseThrow(() -> ApiException.notFound("No round found with id " + roundId));

        // Don't distinguish "not found" from "not yours" — same as football.
        if (!round.userId.equals(userId)) {
            throw ApiException.notFound("No round found with id " + roundId);
        }
        if (round.isSettled()) {
            throw ApiException.conflict("Round " + round.id + " has already been settled");
        }
        return round;
    }

    private MinesRevealResponse settleAsLoss(MinesRound round) {
        if (!round.markSettled()) {
            throw ApiException.conflict("Round " + round.id + " has already been settled");
        }
        BigDecimal balance = walletService.getBalance(round.userId);
        roundStore.addHistory(round.userId, new MinesHistoryEntry(
                round.bombCount, round.diamondsFound(), false,
                round.stake.negate(), Instant.now()
        ));
        return new MinesRevealResponse(
                RevealResult.BOMB, round.diamondsFound(),
                calcMultiplier(round.diamondsFound(), round.bombCount),
                BigDecimal.ZERO, List.copyOf(round.bombPositions), round.serverSeed,
                BigDecimal.ZERO, balance
        );
    }

    private MinesRevealResponse settleAsWin(MinesRound round, BigDecimal multiplier,
                                             BigDecimal payout, RevealResult result) {
        if (!round.markSettled()) {
            throw ApiException.conflict("Round " + round.id + " has already been settled");
        }
        var tx = walletService.credit(
                round.userId, payout, TxKind.GAME_PAYOUT,
                "mines:payout:" + round.id,
                Map.of(
                        "game", "mines",
                        "roundId", round.id,
                        "diamondsFound", round.diamondsFound()
                )
        );
        roundStore.addHistory(round.userId, new MinesHistoryEntry(
                round.bombCount, round.diamondsFound(), true,
                payout.subtract(round.stake), Instant.now()
        ));
        return new MinesRevealResponse(
                result, round.diamondsFound(), multiplier, payout,
                List.copyOf(round.bombPositions), round.serverSeed, payout, tx.getBalanceAfter()
        );
    }

    private Set<Integer> drawBombPositions(String serverSeed, String roundId, int bombCount, Integer excludeIdx) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < GRID_SIZE; i++) {
            if (excludeIdx == null || i != excludeIdx) candidates.add(i);
        }
        Random rnd = new Random(seedFrom(serverSeed + roundId));
        Collections.shuffle(candidates, rnd);
        return new HashSet<>(candidates.subList(0, bombCount));
    }

    private long seedFrom(String s) {
        String hash = sha256(s);
        return Long.parseUnsignedLong(hash.substring(0, 16), 16);
    }

    private String randomHex(int numBytes) {
        byte[] bytes = new byte[numBytes];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Must match the frontend's getMultiplier() exactly. */
    static BigDecimal calcMultiplier(int diamonds, int bombs) {
        if (diamonds == 0) return BigDecimal.ONE;
        double safe = GRID_SIZE - bombs;
        double prob = 1.0;
        for (int i = 0; i < diamonds; i++) {
            prob *= (safe - i) / (GRID_SIZE - i);
        }
        double mult = Math.round((1.0 / prob) * RTP.doubleValue() * 100) / 100.0;
        return BigDecimal.valueOf(mult).setScale(2, RoundingMode.HALF_UP);
    }
}
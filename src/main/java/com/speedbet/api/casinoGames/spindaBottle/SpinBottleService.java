package com.speedbet.api.casinoGames.spindaBottle;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Server-authoritative game logic for Spin Da' Bottle.
 *
 * This replaces the old client-side RNG.generateOutcome() in game.js /
 * SpinDaBottleGame.tsx. In the old build the outcome, the "server seed",
 * and the fairness hash were all generated in the browser — a player
 * could trivially patch their own client to always win. Here the wallet
 * debit, the RNG roll, and the wallet credit all happen in one backend
 * transaction; the client only receives the result afterward and uses it
 * to drive the spin animation.
 *
 * Distribution is unchanged from the original client-side version:
 *   UP     0.000–0.485 (48.5%)
 *   DOWN   0.485–0.970 (48.5%)
 *   MIDDLE 0.970–1.000 (3.0%)  — house edge, ~97% RTP
 *
 * NOTE: assumes TxKind has GAME_STAKE / GAME_PAYOUT constants (or your
 * existing equivalents) — swap these for whatever this codebase already
 * uses for other games such as Mines.
 */
@Service
@RequiredArgsConstructor
public class SpinBottleService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final BigDecimal PAYOUT_MULTIPLIER = new BigDecimal("2");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final WalletService walletService;
    private final SpinBottleRoundRepository roundRepo;

    @Transactional
    public SpinBottlePlayResponse play(UUID userId, SpinBottlePlayRequest request) {
        BigDecimal stake = request.getStake();
        if (stake == null || stake.signum() <= 0)
            throw ApiException.unprocessable("Stake must be greater than zero");
        if (request.getChoice() == null)
            throw ApiException.unprocessable("choice is required");

        // Debit first. WalletService.debit() runs SERIALIZABLE with a
        // pessimistic row lock and throws if the balance is insufficient,
        // so there's no way to spin without the stake actually being taken.
        walletService.debit(userId, stake, TxKind.GAME_STAKE);

        String serverSeed = randomHex(16);
        String serverSeedHash = sha256Hex(serverSeed);
        String clientSeed = (request.getClientSeed() == null || request.getClientSeed().isBlank())
                ? randomHex(8)
                : request.getClientSeed();
        long nonce = Math.abs(SECURE_RANDOM.nextInt(100_000));

        String resultHash = hmacSha256Hex(serverSeed, clientSeed + nonce);
        SpinBottleOutcome outcome = resolveOutcome(resultHash);

        boolean won = (outcome == SpinBottleOutcome.UP && request.getChoice() == SpinBottleChoice.UP)
                || (outcome == SpinBottleOutcome.DOWN && request.getChoice() == SpinBottleChoice.DOWN);

        BigDecimal payout = won ? stake.multiply(PAYOUT_MULTIPLIER, MathContext.DECIMAL64) : ZERO;

        if (won) {
            walletService.credit(userId, payout, TxKind.GAME_PAYOUT);
        }

        SpinBottleRound round = roundRepo.save(SpinBottleRound.builder()
                .userId(userId)
                .choice(request.getChoice())
                .outcome(outcome)
                .won(won)
                .stake(stake)
                .payout(payout)
                .serverSeed(serverSeed)
                .serverSeedHash(serverSeedHash)
                .clientSeed(clientSeed)
                .nonce(nonce)
                .resultHash(resultHash)
                .build());

        return SpinBottlePlayResponse.builder()
                .roundId(round.getId())
                .choice(round.getChoice())
                .outcome(round.getOutcome())
                .won(won)
                .stake(stake)
                .payout(payout)
                .balanceAfter(walletService.getBalance(userId))
                .fairness(SpinBottleFairnessDto.builder()
                        .serverSeed(serverSeed)   // safe to reveal now — the round is already settled
                        .serverSeedHash(serverSeedHash)
                        .clientSeed(clientSeed)
                        .nonce(nonce)
                        .resultHash(resultHash)
                        .build())
                .createdAt(round.getCreatedAt())
                .build();
    }

    public List<SpinBottleHistoryEntry> history(UUID userId, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        Page<SpinBottleRound> page = roundRepo.findByUserIdOrderByCreatedAtDesc(userId, Pageable.ofSize(size));
        return page.map(r -> SpinBottleHistoryEntry.builder()
                        .roundId(r.getId())
                        .choice(r.getChoice())
                        .outcome(r.getOutcome())
                        .won(r.isWon())
                        .stake(r.getStake())
                        .payout(r.getPayout())
                        .createdAt(r.getCreatedAt())
                        .build())
                .getContent();
    }

    // ── RNG / provably-fair helpers ─────────────────────────────────

    private SpinBottleOutcome resolveOutcome(String hash) {
        // Same technique the old client used: first 8 hex chars as a
        // uint32, normalized to [0, 1).
        long num = Long.parseLong(hash.substring(0, 8), 16);
        double r = num / 4294967295.0; // 0xffffffff

        if (r < 0.485) return SpinBottleOutcome.UP;
        if (r < 0.970) return SpinBottleOutcome.DOWN;
        return SpinBottleOutcome.MIDDLE;
    }

    private static String randomHex(int len) {
        byte[] bytes = new byte[len];
        SECURE_RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
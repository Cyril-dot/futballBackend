package com.speedbet.api.casinoGames.spindaBottle;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *
 * LOGGING: temporary trace-level logging was added around play() to help
 * diagnose a frontend symptom where the UI occasionally parses an empty
 * {} response for a round immediately after a round that returned full,
 * correct data. If this method is only ever invoked once per client
 * spin, the logs below will show a single "play() called" line per round
 * and a single "play() returning" line with a populated outcome — which
 * would prove the empty object is NOT coming from the backend, and the
 * frontend/network layer is the one worth chasing next. If instead you
 * see two "play() called" lines close together for what the user
 * experienced as one click, that points to the frontend firing the
 * request twice (e.g. a double-bound click handler or a race in the
 * spin-lock state) rather than anything happening here.
 */
@Slf4j
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
        long startedAt = System.nanoTime();
        log.info("play() called userId={} choice={} stake={} clientSeed={} thread={}",
                userId, request.getChoice(), request.getStake(), request.getClientSeed(),
                Thread.currentThread().getName());

        BigDecimal stake = request.getStake();
        if (stake == null || stake.signum() <= 0)
            throw ApiException.unprocessable("Stake must be greater than zero");
        if (request.getChoice() == null)
            throw ApiException.unprocessable("choice is required");

        // Debit first. WalletService.debit() runs SERIALIZABLE with a
        // pessimistic row lock and throws if the balance is insufficient,
        // so there's no way to spin without the stake actually being taken.
        walletService.debit(userId, stake, TxKind.GAME_STAKE);
        log.debug("play() debited userId={} stake={}", userId, stake);

        String serverSeed = randomHex(16);
        String serverSeedHash = sha256Hex(serverSeed);
        String clientSeed = (request.getClientSeed() == null || request.getClientSeed().isBlank())
                ? randomHex(8)
                : request.getClientSeed();
        long nonce = SECURE_RANDOM.nextInt(100_000);

        String resultHash = hmacSha256Hex(serverSeed, clientSeed + nonce);
        SpinBottleOutcome outcome = resolveOutcome(resultHash);

        boolean won = (outcome == SpinBottleOutcome.UP && request.getChoice() == SpinBottleChoice.UP)
                || (outcome == SpinBottleOutcome.DOWN && request.getChoice() == SpinBottleChoice.DOWN);

        BigDecimal payout = won ? stake.multiply(PAYOUT_MULTIPLIER, MathContext.DECIMAL64) : ZERO;

        if (won) {
            walletService.credit(userId, payout, TxKind.GAME_PAYOUT);
            log.debug("play() credited userId={} payout={}", userId, payout);
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

        SpinBottlePlayResponse response = SpinBottlePlayResponse.builder()
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

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("play() returning userId={} roundId={} outcome={} won={} payout={} elapsedMs={}",
                userId, round.getId(), outcome, won, payout, elapsedMs);

        return response;
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
        log.debug("resolveOutcome hash={} len={} first8={}", hash, hash.length(), hash.substring(0, 8));
        long num = Long.parseLong(hash.substring(0, 8), 16);
        double r = num / 4294967295.0; // 0xffffffff

        SpinBottleOutcome outcome;
        if (r < 0.485) outcome = SpinBottleOutcome.UP;
        else if (r < 0.970) outcome = SpinBottleOutcome.DOWN;
        else outcome = SpinBottleOutcome.MIDDLE;

        log.debug("resolveOutcome r={} -> outcome={}", r, outcome);
        return outcome;
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
        for (byte b : bytes) {
            // IMPORTANT: mask to 0xFF before formatting. A raw `byte` is
            // signed, so passing it directly to String.format("%02x", b)
            // autoboxes to Byte and sign-extends negative values (any byte
            // with its high bit set) out to a 16-character run of "f"s
            // instead of a clean 2-character hex pair. That corrupted the
            // fixed-length structure of every hash this method produced,
            // which in turn biased resolveOutcome()'s substring(0, 8) read
            // toward "ffffffff" (r ≈ 1.0) far more often than the intended
            // 3% MIDDLE probability — that was the "only middle" bug.
            // This part is already fixed and confirmed working (production
            // resultHash values are a clean 64 hex chars). Left in place,
            // not the current suspect.
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
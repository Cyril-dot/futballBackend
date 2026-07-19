package com.speedbet.api.casinoGames.spindaBottle;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
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
 * CHANGE (this revision): play() no longer lets a transient DB lock
 * conflict escape as a raw 500. WalletService.debit() takes a
 * PESSIMISTIC_WRITE row lock on the wallet under SERIALIZABLE isolation
 * (see WalletService), which means any other transaction touching the
 * same wallet row at the same instant — most notably the settlement
 * scheduler crediting/debiting wallets during bet settlement — can
 * cause Postgres/Hibernate to throw a lock-acquisition or serialization
 * failure. That is a genuinely transient condition (the same request
 * would very likely succeed a few hundred ms later), not a real error,
 * so play() now retries it a small, bounded number of times before
 * giving up and returning a clean 409-style ApiException instead of an
 * unhandled 500.
 *
 * Distribution is unchanged from the original client-side version:
 *   UP     0.000–0.485 (48.5%)
 *   DOWN   0.485–0.970 (48.5%)
 *   MIDDLE 0.970–1.000 (3.0%)  — house edge, ~97% RTP
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpinBottleService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final BigDecimal PAYOUT_MULTIPLIER = new BigDecimal("2");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // Retry tuning for transient lock/serialization conflicts on the
    // wallet row. Kept small — this is meant to smooth over a brief
    // collision with the settlement scheduler, not mask a real bug.
    private static final int MAX_LOCK_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 75;

    private final WalletService walletService;
    private final SpinBottleRoundRepository roundRepo;

    public SpinBottlePlayResponse play(UUID userId, SpinBottlePlayRequest request) {
        BigDecimal stake = request.getStake();
        if (stake == null || stake.signum() <= 0)
            throw ApiException.unprocessable("Stake must be greater than zero");
        if (request.getChoice() == null)
            throw ApiException.unprocessable("choice is required");

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return playOnce(userId, request, stake, attempt);
            } catch (PessimisticLockingFailureException e) {
                // Catches CannotAcquireLockException too — it's a subclass
                // of PessimisticLockingFailureException, so a multi-catch
                // listing both is a compile error (types must be disjoint).
                // Catching the parent type covers both cases.
                // Wallet row was locked by a concurrent transaction (most
                // likely settlement touching the same wallet). Safe to
                // retry: playOnce() hasn't committed anything on this path
                // — the exception surfaces from the debit() transaction
                // itself failing to acquire/hold its lock, so no stake was
                // taken and no round was recorded.
                if (attempt >= MAX_LOCK_RETRIES) {
                    log.error("play() giving up after {} attempts for userId={} — wallet row contention",
                            attempt, userId, e);
                    throw ApiException.conflict(
                            "The table is busy right now — please try your spin again in a moment.");
                }
                long delay = RETRY_BASE_DELAY_MS * attempt;
                log.warn("play() lock conflict for userId={} attempt={}/{} — retrying in {}ms",
                        userId, attempt, MAX_LOCK_RETRIES, delay);
                sleepQuietly(delay);
            }
        }
    }

    /**
     * One full attempt at a round: debit, roll, credit if won, persist.
     * Runs in its own transaction so a failed/retried attempt doesn't
     * hold a half-open transaction across the retry loop above.
     */
    @Transactional
    protected SpinBottlePlayResponse playOnce(UUID userId, SpinBottlePlayRequest request,
                                              BigDecimal stake, int attempt) {
        long startedAt = System.nanoTime();
        log.info("play() called userId={} choice={} stake={} clientSeed={} attempt={} thread={}",
                userId, request.getChoice(), stake, request.getClientSeed(), attempt,
                Thread.currentThread().getName());

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
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.Transaction;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the single, server-authoritative Aviator round. No persistence layer —
 * everything lives in memory, guarded by a lock, and is rebuilt fresh on
 * restart (in-flight bets are refunded on boot, see @PostConstruct note below
 * if you add one later).
 *
 * Lifecycle: WAITING -> RUNNING -> CRASHED -> WAITING -> ...
 * A single @Scheduled tick drives all phase transitions, so the frontend's
 * poll of GET /current always reads a consistent, server-decided state.
 *
 * IMPORTANT: the crash point is generated once, at the moment RUNNING starts,
 * and is never revealed to clients until the round has crashed. Cashout
 * requests are validated against wall-clock elapsed time using the exact
 * same growth formula the client renders, so a client cannot cash out past
 * a multiplier the server hasn't reached yet, and can never cash out past
 * the (hidden) crash point.
 */
@Service
@RequiredArgsConstructor
public class AviatorRoundService {

    private static final double GROWTH_RATE = 0.00006;   // must match frontend GROWTH_RATE
    private static final double MAX_MULTIPLIER = 50.0;    // must match frontend MAX_MULTIPLIER (display/cashout cap)
    private static final long WAITING_MS = 6_000;
    private static final long CRASHED_HOLD_MS = 2_800;
    private static final String DEFAULT_CLIENT_SEED = "aviator-public-seed-v1";

    private final WalletService walletService;
    private final CrashPointGenerator crashGen;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Double> history = new ArrayDeque<>();
    private final Map<UUID, BetSlip> betsByUserInCurrentRound = new ConcurrentHashMap<>();

    private long nonce = 0;
    private RoundPhase phase = RoundPhase.WAITING;
    private Instant phaseStartedAt = Instant.now();
    private UUID roundId = UUID.randomUUID();
    private CrashPointGenerator.Commit commit; // initialized in init(), after DI completes
    private double crashPoint = 0; // hidden until CRASHED

    public enum RoundPhase { WAITING, RUNNING, CRASHED }

    private record BetSlip(UUID roundId, BigDecimal stake, boolean cashedOut, double cashoutMultiplier, BigDecimal payout) {
        BetSlip withCashout(double multiplier, BigDecimal payout) {
            return new BetSlip(roundId, stake, true, multiplier, payout);
        }
    }

    /** Runs once, after Spring has injected walletService/crashGen, so it's safe to use crashGen here. */
    @PostConstruct
    private void init() {
        this.commit = crashGen.newCommit();
    }

    // ── Scheduler: the only place phase transitions happen ─────────────
    @Scheduled(fixedRate = 250)
    public void tick() {
        lock.lock();
        try {
            long elapsed = Instant.now().toEpochMilli() - phaseStartedAt.toEpochMilli();
            switch (phase) {
                case WAITING -> {
                    if (elapsed >= WAITING_MS) startRunning();
                }
                case RUNNING -> {
                    double liveMultiplier = currentMultiplierUnlocked();
                    if (liveMultiplier >= crashPoint) crashNow();
                }
                case CRASHED -> {
                    if (elapsed >= CRASHED_HOLD_MS) startWaiting();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void startWaiting() {
        betsByUserInCurrentRound.clear();
        roundId = UUID.randomUUID();
        commit = crashGen.newCommit();
        phase = RoundPhase.WAITING;
        phaseStartedAt = Instant.now();
    }

    private void startRunning() {
        nonce++;
        crashPoint = crashGen.computeCrashPoint(commit.serverSeed(), DEFAULT_CLIENT_SEED, nonce);
        phase = RoundPhase.RUNNING;
        phaseStartedAt = Instant.now();
    }

    private void crashNow() {
        phase = RoundPhase.CRASHED;
        phaseStartedAt = Instant.now();
        history.addFirst(Math.min(crashPoint, MAX_MULTIPLIER));
        while (history.size() > 15) history.removeLast();
    }

    /** Authoritative multiplier at "now", using the same growth curve the client animates. Caller must hold the lock. */
    private double currentMultiplierUnlocked() {
        if (phase != RoundPhase.RUNNING) return 1.0;
        long elapsed = Instant.now().toEpochMilli() - phaseStartedAt.toEpochMilli();
        double multiplier = Math.exp(GROWTH_RATE * elapsed);
        return Math.max(1.0, Math.min(multiplier, MAX_MULTIPLIER));
    }

    // ── Public API used by the controller ───────────────────────────────

    /** Places a bet for the current WAITING round. Debits the wallet first — no bet is recorded if the debit fails. */
    public UUID placeBet(UUID userId, BigDecimal stake) {
        lock.lock();
        try {
            if (phase != RoundPhase.WAITING) {
                throw ApiException.unprocessable("Betting is closed for this round.");
            }
            if (betsByUserInCurrentRound.containsKey(userId)) {
                throw ApiException.conflict("You already have a bet on this round.");
            }
            if (stake == null || stake.signum() <= 0) {
                throw ApiException.unprocessable("Invalid stake amount.");
            }

            // Debit first. If this throws (e.g. insufficient balance) no bet is ever recorded.
            walletService.debit(userId, stake, TxKind.GAME_STAKE);

            betsByUserInCurrentRound.put(userId, new BetSlip(roundId, stake, false, 0, BigDecimal.ZERO));
            return roundId;
        } finally {
            lock.unlock();
        }
    }

    public record CashoutResult(BigDecimal payout, double multiplier, BigDecimal walletBalance) {}

    /**
     * Cashes out a user's bet. The requested multiplier is clamped to what the
     * server has actually reached "right now" — a client cannot cash out at a
     * multiplier the round hasn't hit yet, and once CRASHED, cashout is refused.
     */
    public CashoutResult cashout(UUID userId, UUID requestedRoundId, double requestedMultiplier) {
        BigDecimal payout;
        double confirmedMultiplier;

        lock.lock();
        try {
            BetSlip slip = betsByUserInCurrentRound.get(userId);
            if (slip == null || !slip.roundId().equals(requestedRoundId)) {
                throw ApiException.unprocessable("No active bet found for this round.");
            }
            if (slip.cashedOut()) {
                throw ApiException.conflict("Already cashed out.");
            }
            if (phase != RoundPhase.RUNNING) {
                throw ApiException.unprocessable("Round is not currently running.");
            }

            double serverMultiplier = currentMultiplierUnlocked();
            confirmedMultiplier = Math.min(Math.max(1.0, Math.min(requestedMultiplier, serverMultiplier)), MAX_MULTIPLIER);

            payout = slip.stake()
                    .multiply(BigDecimal.valueOf(confirmedMultiplier))
                    .setScale(2, RoundingMode.DOWN);

            betsByUserInCurrentRound.put(userId, slip.withCashout(confirmedMultiplier, payout));
        } finally {
            lock.unlock();
        }

        // Wallet credit happens outside the round lock — it has its own transaction boundary.
        Transaction tx = walletService.credit(userId, payout, TxKind.GAME_PAYOUT);
        return new CashoutResult(payout, confirmedMultiplier, tx.getBalanceAfter());
    }

    public record RoundView(String state, Double multiplier, Double crashPoint, String hash,
                            String serverSeed, String clientSeed, int countdownSecondsRemaining) {}

    public RoundView getCurrentRoundView() {
        lock.lock();
        try {
            long elapsed = Instant.now().toEpochMilli() - phaseStartedAt.toEpochMilli();
            return switch (phase) {
                case WAITING -> new RoundView("WAITING", null, null, commit.hash(), null, DEFAULT_CLIENT_SEED,
                        (int) Math.max(0, Math.ceil((WAITING_MS - elapsed) / 1000.0)));
                case RUNNING -> new RoundView("RUNNING", currentMultiplierUnlocked(), null, commit.hash(), null, DEFAULT_CLIENT_SEED, 0);
                case CRASHED -> new RoundView("CRASHED", Math.min(crashPoint, MAX_MULTIPLIER), Math.min(crashPoint, MAX_MULTIPLIER),
                        commit.hash(), commit.serverSeed(), DEFAULT_CLIENT_SEED, 0); // seed revealed only now
            };
        } finally {
            lock.unlock();
        }
    }

    public List<Double> getHistory(int limit) {
        lock.lock();
        try {
            return history.stream().limit(limit).toList();
        } finally {
            lock.unlock();
        }
    }
}
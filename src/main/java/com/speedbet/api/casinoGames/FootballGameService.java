package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates football rounds end to end. A user may have several rounds
 * open at once — every operation is keyed by roundId, so placing bet #2
 * while bet #1 is still unsettled is safe.
 *
 *   previewOdds() → fresh, throwaway odds quote for the bet slip. Does NOT
 *                   create a round or touch the wallet.
 *   play()        → validate bet, debit stake, pick/select teams, simulate
 *                    the ENTIRE match now, freeze the outcome, store the
 *                    round. Safe to call again immediately for another bet.
 *   settle()      → reveal the frozen outcome for one specific roundId,
 *                    credit payout if it won. Never trusts a client score.
 *   autoSettleAbandonedRounds() → same settlement logic as settle(), run
 *                    on a schedule for any round nobody ever came back to
 *                    settle (closed tab, crashed app, etc). Without this,
 *                    an abandoned round leaves its stake debited forever
 *                    with no win/loss ever resolved.
 */
@Service
@RequiredArgsConstructor
public class FootballGameService {

    private static final int MATCH_DURATION_SECONDS = 30;
    private static final BigDecimal MIN_STAKE = BigDecimal.ONE;
    // Grace period before an unsettled round is force-settled by the
    // server itself. Generous relative to MATCH_DURATION_SECONDS (30s)
    // since the outcome is already frozen at play() time either way —
    // this only exists to catch rounds nobody ever calls settle() for.
    private static final Duration AUTO_SETTLE_GRACE = Duration.ofMinutes(2);

    private final WalletService walletService;
    private final RoundStore roundStore;

    public OddsQuote previewOdds() {
        Odds odds = Odds.generate();
        return new OddsQuote(
                odds.forBetType(BetType.HOME),
                odds.forBetType(BetType.DRAW),
                odds.forBetType(BetType.AWAY),
                odds.forBetType(BetType.OVER),
                odds.forBetType(BetType.UNDER)
        );
    }

    /** Full roster, for a "pick your matchup" screen. */
    public List<Team> teams() {
        return Arrays.asList(Team.ROSTER);
    }

    public PlayResponse play(UUID userId, PlayRequest request) {
        if (request.stake().compareTo(MIN_STAKE) < 0) {
            throw ApiException.unprocessable("Minimum stake is " + MIN_STAKE);
        }

        Team home;
        Team away;
        if (request.homeTeam() != null || request.awayTeam() != null) {
            if (request.homeTeam() == null || request.awayTeam() == null) {
                throw ApiException.unprocessable("Provide both homeTeam and awayTeam, or neither for a random matchup");
            }
            home = findTeam(request.homeTeam());
            away = findTeam(request.awayTeam());
            if (home.name().equals(away.name())) {
                throw ApiException.unprocessable("homeTeam and awayTeam must be different");
            }
        } else {
            Team[] pair = pickTwoDistinctTeams();
            home = pair[0];
            away = pair[1];
        }

        // serverOdds is always what actually gets used/settled with — any
        // client-supplied odds value is display-only and never checked
        // against a second random draw.
        Odds freshOdds = Odds.generate();
        BigDecimal serverOdds = freshOdds.forBetType(request.betType());

        String roundId = UUID.randomUUID().toString();

        // Debit first — throws ApiException.unprocessable("Insufficient balance")
        // via WalletService if the stake can't be covered. Nothing below runs
        // if this throws, so no round/outcome is ever created for an unfunded bet.
        walletService.debit(
                userId, request.stake(), TxKind.GAME_STAKE,
                "football:stake:" + roundId,
                Map.of(
                        "game", "football",
                        "roundId", roundId,
                        "betType", request.betType().toJson(),
                        "homeTeam", home.name(),
                        "awayTeam", away.name()
                )
        );

        MatchSimulator.MatchOutcome outcome = MatchSimulator.simulate(home, away);

        FootballRound round = new FootballRound(
                roundId, userId, request.stake(), request.betType(), serverOdds, home, away, outcome
        );
        roundStore.save(round);

        BigDecimal walletBalance = walletService.getBalance(userId);

        return new PlayResponse(
                roundId, walletBalance, request.stake(), request.betType(), serverOdds,
                home.name(), away.name(), MATCH_DURATION_SECONDS
        );
    }

    public SettleResponse settle(UUID userId, SettleRequest request) {
        FootballRound round = roundStore.find(request.roundId())
                .orElseThrow(() -> ApiException.notFound("No round found with id " + request.roundId()));

        // Don't distinguish "not found" from "not yours" — avoids leaking
        // round existence to a user who doesn't own it.
        if (!round.userId.equals(userId)) {
            throw ApiException.notFound("No round found with id " + request.roundId());
        }
        return doSettle(round);
    }

    /**
     * Shared settlement path for both the user-initiated settle() above
     * and the scheduled auto-settle job below. markSettled() is what
     * actually guards against double-settling — whichever caller wins the
     * race does the crediting, the other gets ApiException.conflict (for
     * the user path) or is simply skipped (for the scheduled path, which
     * checks isSettled() again right before calling this).
     */
    private SettleResponse doSettle(FootballRound round) {
        if (!round.markSettled()) {
            throw ApiException.conflict("Round " + round.id + " has already been settled");
        }

        var outcome = round.outcome;
        boolean won = outcome.wins(round.betType);

        BigDecimal payout = BigDecimal.ZERO;
        BigDecimal newBalance = walletService.getBalance(round.userId);

        if (won) {
            payout = round.stake.multiply(round.oddsAtBet).setScale(2, RoundingMode.HALF_UP);
            var tx = walletService.credit(
                    round.userId, payout, TxKind.GAME_PAYOUT,
                    "football:payout:" + round.id,
                    Map.of(
                            "game", "football",
                            "roundId", round.id,
                            "betType", round.betType.toJson(),
                            "homeScore", outcome.homeScore(),
                            "awayScore", outcome.awayScore()
                    )
            );
            newBalance = tx.getBalanceAfter();
        }

        roundStore.addHistory(round.userId, new RoundStore.HistoryEntry(
                round.home.name(), round.away.name(),
                outcome.homeScore() + "-" + outcome.awayScore(),
                won, java.time.Instant.now()
        ));

        return new SettleResponse(won, outcome.homeScore(), outcome.awayScore(), payout, newBalance);
    }

    /**
     * Force-settles any round still open past AUTO_SETTLE_GRACE. Runs
     * independently of any user request — this is what prevents a closed
     * tab or crashed client from leaving a debited stake in limbo forever.
     * Uses the exact same doSettle() path as a normal settle() call, so
     * there is only one place money actually moves for a round.
     */
    @Scheduled(fixedDelay = 60 * 1000)
    void autoSettleAbandonedRounds() {
        for (FootballRound round : roundStore.findStaleOpenRounds(AUTO_SETTLE_GRACE)) {
            if (round.isSettled()) continue; // already handled by a real settle() call in the meantime
            try {
                doSettle(round);
            } catch (ApiException e) {
                // Lost the race to a concurrent settle() — fine, skip it.
            }
        }
    }

    /** Kept for backward compatibility — only the most recent open round. */
    public RoundView currentRound(UUID userId) {
        return roundStore.findLatestOpenForUser(userId).map(RoundView::of).orElse(null);
    }

    /** ALL open rounds for a user, so the frontend can resume every live bet. */
    public List<RoundView> openRounds(UUID userId) {
        return roundStore.findAllOpenForUser(userId).stream().map(RoundView::of).toList();
    }

    public List<RoundStore.HistoryEntry> history(UUID userId, int limit) {
        return roundStore.history(userId, limit);
    }

    public BigDecimal balance(UUID userId) {
        return walletService.getBalance(userId);
    }

    private Team findTeam(String name) {
        return Arrays.stream(Team.ROSTER)
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("Unknown team: " + name));
    }

    private Team[] pickTwoDistinctTeams() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int i1 = r.nextInt(Team.ROSTER.length);
        int i2;
        do {
            i2 = r.nextInt(Team.ROSTER.length);
        } while (i2 == i1);
        return new Team[]{Team.ROSTER[i1], Team.ROSTER[i2]};
    }
}
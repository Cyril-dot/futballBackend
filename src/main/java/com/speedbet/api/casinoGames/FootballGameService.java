package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates a football round end to end:
 *   previewOdds() → generate a fresh, throwaway odds quote for display in
 *                   the bet slip before the user commits. Does NOT create
 *                   a round or touch the wallet — purely informational.
 *                   The odds actually locked in at play() are generated
 *                   again independently at that point (see play() below),
 *                   so this quote is a preview only and can drift slightly
 *                   between preview and commit — play() tolerates that via
 *                   ODDS_DRIFT_TOLERANCE.
 *   play()         → validate bet, debit stake via WalletService, pick teams,
 *                    simulate the ENTIRE match right now, freeze the outcome,
 *                    store the round.
 *   settle()       → reveal the frozen outcome, credit payout via WalletService
 *                    if it won. Never trusts a client-supplied score.
 *
 * TxKind.GAME_STAKE / TxKind.GAME_PAYOUT are assumed to already exist on
 * your TxKind enum (shared across Aviator/other games). If they don't yet,
 * add them — everything else here is generic and doesn't care which game
 * a ledger row came from; that's what the metadata map is for.
 */
@Service
@RequiredArgsConstructor
public class FootballGameService {

    private static final int MATCH_DURATION_SECONDS = 30;
    private static final BigDecimal MIN_STAKE = BigDecimal.ONE;
    // How far a client's displayed odds may drift from a freshly generated
    // quote before play() rejects it as stale.
    private static final BigDecimal ODDS_DRIFT_TOLERANCE = BigDecimal.valueOf(0.05);

    private final WalletService walletService;
    private final RoundStore roundStore;

    /**
     * Generates a fresh odds quote for all five markets, purely for display
     * in the bet slip before the user has committed to anything. No wallet
     * access, no round created, no user context needed — this is why the
     * corresponding controller endpoint has no @AuthenticationPrincipal
     * parameter.
     */
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

    public PlayResponse play(UUID userId, PlayRequest request) {
        if (request.stake().compareTo(MIN_STAKE) < 0) {
            throw ApiException.unprocessable("Minimum stake is " + MIN_STAKE);
        }

        Team[] pair = pickTwoDistinctTeams();
        Team home = pair[0];
        Team away = pair[1];

        Odds freshOdds = Odds.generate();
        BigDecimal serverOdds = freshOdds.forBetType(request.betType());

        if (request.odds() != null
                && request.odds().subtract(serverOdds).abs().compareTo(ODDS_DRIFT_TOLERANCE) > 0) {
            throw ApiException.unprocessable("Odds have moved, please refresh and try again");
        }

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

        // Don't distinguish "not found" from "not yours" in the message —
        // avoids leaking round existence to a user who doesn't own it.
        if (!round.userId.equals(userId)) {
            throw ApiException.notFound("No round found with id " + request.roundId());
        }
        if (!round.markSettled()) {
            throw ApiException.conflict("Round " + request.roundId() + " has already been settled");
        }

        var outcome = round.outcome;
        boolean won = outcome.wins(round.betType);

        BigDecimal payout = BigDecimal.ZERO;
        BigDecimal newBalance = walletService.getBalance(userId);

        if (won) {
            payout = round.stake.multiply(round.oddsAtBet).setScale(2, RoundingMode.HALF_UP);
            var tx = walletService.credit(
                    userId, payout, TxKind.GAME_PAYOUT,
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

        roundStore.addHistory(userId, new RoundStore.HistoryEntry(
                round.home.name(), round.away.name(),
                outcome.homeScore() + "-" + outcome.awayScore(),
                won, java.time.Instant.now()
        ));

        return new SettleResponse(won, outcome.homeScore(), outcome.awayScore(), payout, newBalance);
    }

    public RoundView currentRound(UUID userId) {
        return roundStore.findLatestOpenForUser(userId).map(RoundView::of).orElse(null);
    }

    public List<RoundStore.HistoryEntry> history(UUID userId, int limit) {
        return roundStore.history(userId, limit);
    }

    public BigDecimal balance(UUID userId) {
        return walletService.getBalance(userId);
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
package com.speedbet.api.bet;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MmaMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class MmaSettlementEngine {

    private final MmaMatchService mmaMatchService;
    private final BetService      betService;

    // ── Winner codes stored in Match.scoreHome ────────────────────────────
    private static final int WINNER_FIGHTER1 = 1;   // HOME
    private static final int WINNER_FIGHTER2 = 2;   // AWAY
    private static final int WINNER_NONE     = 0;   // no-contest / pending

    // ── Result methods that force a VOID regardless of winner ─────────────
    private static final String METHOD_NO_CONTEST = "no contest";
    private static final String METHOD_DQ         = "dq";

    // ── Scheduled runner ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000L, initialDelay = 15_000L)
    public void run() {
        var unsettled = mmaMatchService.getUnsettledFinished();
        log.info("MMA settlement run: {} unsettled match(es) to process", unsettled.size());

        for (var match : unsettled) {
            try {
                log.info("MMA settlement run: processing match {} ({} vs {})",
                        match.getId(), match.getHomeTeam(), match.getAwayTeam());
                settleMatch(match);
                mmaMatchService.markSettled(match.getId().toString());
                log.info("MMA settlement run: match {} marked settled", match.getId());
            } catch (Exception e) {
                log.error("MMA settlement run: FAILED for match {} — {}", match.getId(), e.getMessage(), e);
            }
        }

        log.info("MMA settlement run: complete");
    }

    // ── Match-level settlement ────────────────────────────────────────────

    @Transactional
    public void settleMatch(Match match) {
        // scoreHome encodes the winner (1 = fighter1/HOME, 2 = fighter2/AWAY, 0 = void)
        if (match.getScoreHome() == null) {
            log.warn("MMA settleMatch: match {} has null scoreHome (winner indicator) — skipping",
                    match.getId());
            return;
        }

        int winnerCode = match.getScoreHome();
        String resultMethod = extractMetaString(match, "resultMethod").toLowerCase();
        String winner       = extractMetaString(match, "winner");
        String resultRound  = extractMetaString(match, "resultRound");
        String resultClock  = extractMetaString(match, "resultClock");

        log.info("MMA settleMatch: match {} {} vs {} | winnerCode={} winner='{}' method='{}' round={} clock={}",
                match.getId(), match.getHomeTeam(), match.getAwayTeam(),
                winnerCode, winner, resultMethod, resultRound, resultClock);

        // Detect void result up-front so all bets on this bout can be voided together
        boolean isVoidResult = isVoidMethod(resultMethod)
                || winnerCode == WINNER_NONE;

        if (isVoidResult) {
            log.info("MMA settleMatch: match {} result is void (method='{}' winnerCode={}) — voiding all bets",
                    match.getId(), resultMethod, winnerCode);
        }

        var pendingBets = betService.getPendingBetsForMatch(match.getId());
        log.info("MMA settleMatch: {} pending bet(s) for match {}", pendingBets.size(), match.getId());

        int won = 0, lost = 0, voided = 0;

        for (var bet : pendingBets) {
            BetStatus before = bet.getStatus();
            settleMmaBet(bet, match, winnerCode, isVoidResult);
            BetStatus after = bet.getStatus();

            if      (BetStatus.WON.equals(after))  won++;
            else if (BetStatus.LOST.equals(after)) lost++;
            else if (BetStatus.VOID.equals(after)) voided++;

            if (!before.equals(after)) {
                log.debug("MMA settleMatch: bet {} {} → {}", bet.getId(), before, after);
            }
        }

        log.info("MMA settleMatch: match {} done — WON={} LOST={} VOID={}",
                match.getId(), won, lost, voided);
    }

    // ── Bet-level settlement ──────────────────────────────────────────────

    private void settleMmaBet(Bet bet, Match match, int winnerCode, boolean boutIsVoid) {
        log.debug("MMA settleMmaBet: bet {} stake={} totalOdds={} selections={}",
                bet.getId(), bet.getStake(), bet.getTotalOdds(), bet.getSelections().size());

        boolean hasFullLoss    = false;
        BigDecimal oddsAdjustment = BigDecimal.ONE;

        for (var sel : bet.getSelections()) {
            if (!sel.getMatchId().equals(match.getId())) continue;

            String result = boutIsVoid
                    ? "VOID"
                    : evaluateMmaSelection(sel, match, winnerCode);

            sel.setResult(result);

            log.info("MMA settleMmaBet: bet {} sel {} market='{}' selection='{}' oddsLocked={} → {}",
                    bet.getId(), sel.getId(), sel.getMarket(), sel.getSelection(),
                    sel.getOddsLocked(), result);

            switch (result) {
                case "LOST" -> {
                    hasFullLoss = true;
                    log.debug("MMA settleMmaBet: bet {} — full loss on sel {}", bet.getId(), sel.getId());
                }
                case "VOID" -> {
                    // Divide the locked odds out of the accumulator so the leg is neutral
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64);
                    log.debug("MMA settleMmaBet: bet {} sel {} VOID — divided out odds {}, adjustment now {}",
                            bet.getId(), sel.getId(), sel.getOddsLocked(), oddsAdjustment);
                }
                case "WON" ->
                        log.debug("MMA settleMmaBet: bet {} sel {} WON — no odds adjustment", bet.getId(), sel.getId());

                default ->
                        log.warn("MMA settleMmaBet: unknown result '{}' for sel {} — treating as VOID", result, sel.getId());
            }
        }

        // One outright losing leg kills the whole accumulator
        if (hasFullLoss) {
            log.info("MMA settleMmaBet: bet {} LOST (at least one losing leg)", bet.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return;
        }

        // Defer if not all legs are settled yet (multi-sport accumulator)
        boolean allSettled = bet.getSelections().stream()
                .noneMatch(s -> "PENDING".equals(s.getResult()));
        if (!allSettled) {
            log.debug("MMA settleMmaBet: bet {} — not all legs settled yet, deferring payout", bet.getId());
            return;
        }

        // Effective odds = declared total adjusted for voided legs
        BigDecimal effectiveOdds = bet.getTotalOdds()
                .multiply(oddsAdjustment, MathContext.DECIMAL64);

        log.debug("MMA settleMmaBet: bet {} totalOdds={} × adjustment={} = effectiveOdds={}",
                bet.getId(), bet.getTotalOdds(), oddsAdjustment, effectiveOdds);

        // All legs voided → stake return (odds 1.0)
        if (effectiveOdds.compareTo(BigDecimal.ONE) < 0) {
            log.warn("MMA settleMmaBet: bet {} effectiveOdds {} < 1.0 — clamping to 1.0 (stake return)",
                    bet.getId(), effectiveOdds);
            effectiveOdds = BigDecimal.ONE;
        }

        BigDecimal payout = bet.getStake()
                .multiply(effectiveOdds, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);

        // All legs were voided → VOID status (stake returned, not a "win")
        BetStatus finalStatus = effectiveOdds.compareTo(BigDecimal.ONE) == 0
                ? BetStatus.VOID
                : BetStatus.WON;

        log.info("MMA settleMmaBet: bet {} → {} effectiveOdds={} payout={}",
                bet.getId(), finalStatus, effectiveOdds, payout);
        betService.settleBet(bet, finalStatus, payout);
    }

    // ── Market router ─────────────────────────────────────────────────────

    /**
     * Routes to the correct evaluator for the selection's market.
     * Returns one of: WON | LOST | VOID
     *
     * Unknown markets are voided with a WARN log — matches SettlementEngine
     * behaviour for safety.
     */
    private String evaluateMmaSelection(BetSelection sel, Match match, int winnerCode) {
        String market = sel.getMarket() == null ? "" : sel.getMarket().toLowerCase();

        log.debug("MMA evaluateMmaSelection: sel {} market='{}' selection='{}' winnerCode={}",
                sel.getId(), market, sel.getSelection(), winnerCode);

        String result = switch (market) {
            case "mma_moneyline",
                 "mma_live_moneyline" -> evaluateMoneyline(sel, match, winnerCode);
            default -> {
                log.warn("MMA evaluateMmaSelection: unknown market '{}' for sel {} — VOID",
                        sel.getMarket(), sel.getId());
                yield "VOID";
            }
        };

        log.debug("MMA evaluateMmaSelection: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── Moneyline evaluator ───────────────────────────────────────────────

    /**
     * Evaluates a "mma_moneyline" or "mma_live_moneyline" selection.
     *
     * Selection values (as normalised by MmaOddsPersistenceService):
     *   "HOME" → fighter1 (competitors[0], ESPN "home" side)
     *   "AWAY" → fighter2 (competitors[1], ESPN "away" side)
     *
     * Winner mapping:
     *   winnerCode == WINNER_FIGHTER1 (1) → HOME won
     *   winnerCode == WINNER_FIGHTER2 (2) → AWAY won
     *   winnerCode == WINNER_NONE    (0)  → bout was void (handled above)
     *
     * Cross-check: if the winnerCode is inconsistent with metadata "winner"
     * display name, a WARN is logged but the scoreHome value is authoritative.
     *
     * Returns: WON | LOST | VOID
     */
    private String evaluateMoneyline(BetSelection sel, Match match, int winnerCode) {
        String selection = sel.getSelection() == null ? "" : sel.getSelection().trim().toUpperCase();

        // Sanity cross-check against metadata winner name
        String metaWinner = extractMetaString(match, "winner");
        if (!metaWinner.isBlank()) {
            boolean metaSaysHome = metaWinner.equalsIgnoreCase(match.getHomeTeam());
            boolean metaSaysAway = metaWinner.equalsIgnoreCase(match.getAwayTeam());
            boolean codeSaysHome = winnerCode == WINNER_FIGHTER1;

            if ((codeSaysHome && metaSaysAway) || (!codeSaysHome && metaSaysHome)) {
                log.warn("MMA evaluateMoneyline: sel {} — winnerCode={} conflicts with " +
                                "metadata winner='{}' home='{}' away='{}'. " +
                                "scoreHome is authoritative.",
                        sel.getId(), winnerCode,
                        metaWinner, match.getHomeTeam(), match.getAwayTeam());
            }
        }

        log.debug("MMA evaluateMoneyline: sel {} selection='{}' winnerCode={} metaWinner='{}'",
                sel.getId(), selection, winnerCode, metaWinner);

        return switch (selection) {
            case "HOME" -> {
                String r = winnerCode == WINNER_FIGHTER1 ? "WON" : "LOST";
                log.debug("MMA evaluateMoneyline: sel {} HOME → {}", sel.getId(), r);
                yield r;
            }
            case "AWAY" -> {
                String r = winnerCode == WINNER_FIGHTER2 ? "WON" : "LOST";
                log.debug("MMA evaluateMoneyline: sel {} AWAY → {}", sel.getId(), r);
                yield r;
            }
            default -> {
                log.warn("MMA evaluateMoneyline: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), sel.getSelection());
                yield "VOID";
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Returns true if the result method forces a void regardless of who "won".
     * Industry standard: no-contest and DQ fights are refunded.
     */
    private boolean isVoidMethod(String resultMethod) {
        if (resultMethod == null || resultMethod.isBlank()) return false;
        String m = resultMethod.toLowerCase();
        return m.contains(METHOD_NO_CONTEST) || m.contains(METHOD_DQ);
    }

    /**
     * Safely reads a String value from match.metadata.
     * Returns "" if the key is absent or the metadata map is null.
     */
    private String extractMetaString(Match match, String key) {
        if (match.getMetadata() == null) return "";
        Object val = match.getMetadata().get(key);
        return val != null ? val.toString() : "";
    }

    /**
     * Public entry-point used by integration tests or admin tooling to
     * re-settle a single match without waiting for the scheduler.
     */
    @Transactional
    public void settleMatchById(String matchId) {
        Match match = mmaMatchService.getById(matchId);
        if (!"FINISHED".equals(match.getStatus())) {
            log.warn("MMA settleMatchById: match {} is not FINISHED (status='{}') — skipping",
                    matchId, match.getStatus());
            return;
        }
        settleMatch(match);
        mmaMatchService.markSettled(matchId);
    }
}
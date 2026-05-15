package com.speedbet.api.bet;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * NflSettlementEngine — settles bets across all SpeedBet NFL markets.
 *
 * ── NFL vs. Football (soccer) differences ───────────────────────────────
 *
 *   NFL uses POINTS not goals. A "score" of 7-3 is a close game.
 *   This completely changes Over/Under lines (totals of 40–55 are typical),
 *   handicap lines (spreads of -3, -6.5, -13.5 are common), and
 *   the meaning of a "draw" (extremely rare — OT tie in regular season).
 *
 *   Key NFL scoring unit sizes:
 *     Touchdown + XP conversion  = 7 points   (most common TD)
 *     Field Goal                 = 3 points
 *     Safety                     = 2 points
 *     2-Point conversion         = 2 points (instead of XP)
 *
 *   These unit sizes mean NFL handicap lines are always much larger than
 *   football handicap lines, and exact score markets use point totals, not
 *   goal counts.
 *
 * ── Markets handled ──────────────────────────────────────────────────────
 *
 *   ┌──────────────────────┬────────────────────────────────────────────────────┐
 *   │ nfl_moneyline        │ Home Win / Draw (OT tie) / Away Win                │
 *   │ nfl_live_moneyline   │ Same as above — live in-play variant               │
 *   │ NFL_SPREAD           │ Point-spread handicap: home or away -/+ points     │
 *   │ NFL_TOTAL            │ Over/Under total points: "Over 48.5", "Under 47"   │
 *   │ NFL_FIRST_TD         │ Which team scores the first TD of the game         │
 *   │ NFL_WINNING_MARGIN   │ Exact winning margin band: "Home 1-6", "Away 7-12" │
 *   │ NFL_HALF_TIME        │ Half-time result: HOME / DRAW / AWAY               │
 *   │ NFL_HALF_TIME_TOTAL  │ Over/Under total points at half-time               │
 *   │ NFL_BTTS             │ Both teams score at least 1 point — Yes / No       │
 *   │ NFL_CORRECT_SCORE    │ Exact final score "h-a" (rare, high odds)          │
 *   └──────────────────────┴────────────────────────────────────────────────────┘
 *
 * ── Moneyline / Draw settlement ──────────────────────────────────────────
 *
 *   Selections: HOME | DRAW | AWAY  (persistence-normalised uppercase)
 *   Also accepts aliases: HOME WIN | AWAY WIN  for client-facing display names.
 *   Draw applies only if the final score is tied after overtime (regular season).
 *
 * ── NFL Spread settlement ────────────────────────────────────────────────
 *
 *   The selection string encodes side and line: "Home -6.5", "Away +3", "Home -3"
 *   NFL spread lines are typically half-point (eliminating push), but whole-point
 *   lines DO exist and produce a push on exact cover.
 *
 *   gd = home_score - away_score when backing home team
 *   gd = away_score - home_score when backing away team
 *   adjustedGD = gd + line    (line is negative for favourites)
 *
 *   Win  : adjustedGD > 0
 *   Push : adjustedGD == 0    (whole-line only; half-line push is impossible)
 *   Lose : adjustedGD < 0
 *
 *   Quarter-point lines are rare in NFL but handled for completeness.
 *
 * ── NFL Total (Over/Under) settlement ────────────────────────────────────
 *
 *   Total = homeScore + awayScore
 *   Selection format: "Over 48.5" | "Under 47" | "over48.5" | "UNDER 47"
 *   Half-point lines: no push possible.
 *   Whole-point lines: push when total == line exactly.
 *
 * ── NFL Winning Margin settlement ────────────────────────────────────────
 *
 *   Selection format: "Home 1-6" | "Away 7-12" | "Home 13+" | "Draw"
 *   The margin is the absolute difference: Math.abs(homeScore - awayScore)
 *   "Home 1-6"  → home wins by 1–6 points inclusive
 *   "Away 7-12" → away wins by 7–12 points inclusive
 *   "Home 13+"  → home wins by 13 or more points
 *   "Draw"      → tied game (margin == 0)
 *
 * ── NFL First TD settlement ──────────────────────────────────────────────
 *
 *   Selections: HOME | AWAY | NO_TD
 *   Stored in match.metadata["first_td_scorer"]: "HOME", "AWAY", or "NONE"
 *   Voids if metadata key is absent (data not yet received).
 *
 * ── Half-time data ────────────────────────────────────────────────────────
 *
 *   Stored in match.metadata:
 *     "score_home_ht" → integer home score at half-time
 *     "score_away_ht" → integer away score at half-time
 *   Voids when absent — same pattern as the main SettlementEngine.
 *
 * ── Spread accumulator handling ───────────────────────────────────────────
 *
 *   Push legs: locked odds divided OUT of the running total (stake returned
 *   for that leg). Same approach as SettlementEngine's VOID / PUSH handling.
 *
 *   Quarter-spread half results use HALF_WON / HALF_LOST, combined the same
 *   way as Asian Handicap in the main SettlementEngine.
 *
 * ── Selection normalisation ──────────────────────────────────────────────
 *
 *   OddsPersistenceService writes HOME / DRAW / AWAY in uppercase.
 *   All selection comparisons normalise to uppercase before matching.
 *
 * ── Scheduled cadence ────────────────────────────────────────────────────
 *
 *   Runs every 60 seconds (same as SettlementEngine).
 *   NFL games are post-state once the final whistle fires in AmericanFootballDataService.
 *   MatchService.getUnsettledFinished() returns matches whose state == "post"
 *   and whose settled flag is false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NflSettlementEngine {

    private final MatchService matchService;
    private final BetService   betService;

    // ── Metadata keys ─────────────────────────────────────────────────────
    private static final String META_HT_HOME      = "score_home_ht";
    private static final String META_HT_AWAY      = "score_away_ht";
    private static final String META_FIRST_TD      = "first_td_scorer"; // "HOME" | "AWAY" | "NONE"

    // ── Scheduled runner ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        var finishedMatches = matchService.getUnsettledFinished();
        log.info("NFL Settlement run: {} finished match(es) to process", finishedMatches.size());

        for (var match : finishedMatches) {
            // Only process NFL matches — other leagues handled by SettlementEngine
            if (!isNflMatch(match)) continue;

            try {
                log.info("NFL Settlement run: processing match {} ({} vs {})",
                        match.getId(), match.getHomeTeam(), match.getAwayTeam());
                settleMatch(match);
                matchService.markSettled(match.getId().toString());
                log.info("NFL Settlement run: match {} marked settled", match.getId());
            } catch (Exception e) {
                log.error("NFL Settlement run: FAILED for match {} — {}", match.getId(), e.getMessage(), e);
            }
        }

        log.info("NFL Settlement run: complete");
    }

    // ── Match-level settlement ────────────────────────────────────────────

    @Transactional
    public void settleMatch(Match match) {
        if (match.getScoreHome() == null || match.getScoreAway() == null) {
            log.warn("NFL settleMatch: match {} has null score(s) — skipping", match.getId());
            return;
        }

        int h = match.getScoreHome();
        int a = match.getScoreAway();
        log.info("NFL settleMatch: match {} final score {}-{}", match.getId(), h, a);

        // Log half-time metadata
        Integer htHome = extractIntFromMetadata(match, META_HT_HOME);
        Integer htAway = extractIntFromMetadata(match, META_HT_AWAY);
        if (htHome != null && htAway != null) {
            log.info("NFL settleMatch: match {} half-time score {}-{}", match.getId(), htHome, htAway);
        } else {
            log.warn("NFL settleMatch: match {} has no half-time metadata — NFL_HALF_TIME bets will VOID", match.getId());
        }

        // Log first TD metadata
        String firstTd = extractStringFromMetadata(match, META_FIRST_TD);
        if (firstTd != null) {
            log.info("NFL settleMatch: match {} first TD scorer = '{}'", match.getId(), firstTd);
        } else {
            log.warn("NFL settleMatch: match {} has no first_td_scorer metadata — NFL_FIRST_TD bets will VOID", match.getId());
        }

        var pendingBets = betService.getPendingBetsForMatch(match.getId());
        log.info("NFL settleMatch: {} pending bet(s) found for match {}", pendingBets.size(), match.getId());

        int won = 0, lost = 0, voided = 0, partial = 0;
        for (var bet : pendingBets) {
            BetStatus before = bet.getStatus();
            settleOneBet(bet, match);
            BetStatus after = bet.getStatus();

            if      (BetStatus.WON.equals(after))  won++;
            else if (BetStatus.LOST.equals(after)) lost++;
            else if (BetStatus.VOID.equals(after)) voided++;
            else                                   partial++;

            if (!before.equals(after)) {
                log.debug("NFL settleMatch: bet {} {} → {}", bet.getId(), before, after);
            }
        }
        log.info("NFL settleMatch: match {} done — WON={} LOST={} VOID={} other={}",
                match.getId(), won, lost, voided, partial);
    }

    // ── Bet-level settlement ──────────────────────────────────────────────

    private void settleOneBet(Bet bet, Match match) {
        log.debug("NFL settleOneBet: bet {} stake={} totalOdds={} selections={}",
                bet.getId(), bet.getStake(), bet.getTotalOdds(), bet.getSelections().size());

        boolean hasFullLoss = false;

        // Accumulated odds adjustment for voided / partial legs (mirrors SettlementEngine logic):
        //   VOID / PUSH  → divide out the full locked odds
        //   HALF_WON     → divide out locked odds, multiply in (odds+1)/2
        //   HALF_LOST    → divide out locked odds, multiply in 0.5
        BigDecimal oddsAdjustment = BigDecimal.ONE;

        for (var sel : bet.getSelections()) {
            if (!sel.getMatchId().equals(match.getId())) continue;

            String result = evaluateSelection(sel, match);
            sel.setResult(result);

            log.info("NFL settleOneBet: bet {} sel {} market={} selection='{}' oddsLocked={} → {}",
                    bet.getId(), sel.getId(), sel.getMarket(), sel.getSelection(),
                    sel.getOddsLocked(), result);

            switch (result) {
                case "LOST" -> {
                    hasFullLoss = true;
                    log.debug("NFL settleOneBet: bet {} — full loss flagged on sel {}", bet.getId(), sel.getId());
                }

                case "VOID", "PUSH" -> {
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64);
                    log.debug("NFL settleOneBet: bet {} sel {} {} — divided out odds {}, adjustment now {}",
                            bet.getId(), sel.getId(), result, sel.getOddsLocked(), oddsAdjustment);
                }

                case "HALF_WON" -> {
                    BigDecimal halfWinMultiplier = sel.getOddsLocked()
                            .add(BigDecimal.ONE)
                            .divide(new BigDecimal("2"), MathContext.DECIMAL64);
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                            .multiply(halfWinMultiplier, MathContext.DECIMAL64);
                    log.debug("NFL settleOneBet: bet {} sel {} HALF_WON — halfWinMultiplier={} adjustment now {}",
                            bet.getId(), sel.getId(), halfWinMultiplier, oddsAdjustment);
                }

                case "HALF_LOST" -> {
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                            .multiply(new BigDecimal("0.5"), MathContext.DECIMAL64);
                    log.debug("NFL settleOneBet: bet {} sel {} HALF_LOST — adjustment now {}",
                            bet.getId(), sel.getId(), oddsAdjustment);
                }

                case "WON" -> log.debug("NFL settleOneBet: bet {} sel {} WON — no odds adjustment",
                        bet.getId(), sel.getId());

                default -> log.warn("NFL settleOneBet: unknown result '{}' for sel {} — treating as VOID",
                        result, sel.getId());
            }
        }

        // A single outright LOST leg kills the whole bet
        if (hasFullLoss) {
            log.info("NFL settleOneBet: bet {} LOST (at least one outright losing leg)", bet.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return;
        }

        // Wait for all legs to be settled before paying out
        boolean allSettled = bet.getSelections().stream()
                .noneMatch(s -> "PENDING".equals(s.getResult()));
        if (!allSettled) {
            log.debug("NFL settleOneBet: bet {} — not all legs settled yet, deferring payout", bet.getId());
            return;
        }

        // Effective odds = declared total ÷ voided-odds product × partial adjustments
        BigDecimal effectiveOdds = bet.getTotalOdds()
                .multiply(oddsAdjustment, MathContext.DECIMAL64);

        log.debug("NFL settleOneBet: bet {} totalOdds={} × oddsAdjustment={} = effectiveOdds={}",
                bet.getId(), bet.getTotalOdds(), oddsAdjustment, effectiveOdds);

        // A bet where all legs ended as PUSH/VOID collapses to odds 1.0 (stake returned)
        if (effectiveOdds.compareTo(BigDecimal.ONE) < 0) {
            log.warn("NFL settleOneBet: bet {} effectiveOdds {} < 1.0 — clamping to 1.0 (stake return)",
                    bet.getId(), effectiveOdds);
            effectiveOdds = BigDecimal.ONE;
        }

        BigDecimal payout = bet.getStake()
                .multiply(effectiveOdds, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);

        BetStatus finalStatus = effectiveOdds.compareTo(BigDecimal.ONE) == 0
                ? BetStatus.VOID    // all legs pushed → full stake return
                : BetStatus.WON;

        log.info("NFL settleOneBet: bet {} → {} effectiveOdds={} payout={}",
                bet.getId(), finalStatus, effectiveOdds, payout);
        betService.settleBet(bet, finalStatus, payout);
    }

    // ── Market router ─────────────────────────────────────────────────────

    /**
     * Routes a BetSelection to the correct NFL market evaluator.
     * Returns one of: WON | LOST | VOID | PUSH | HALF_WON | HALF_LOST
     */
    private String evaluateSelection(BetSelection sel, Match match) {
        int h = match.getScoreHome();
        int a = match.getScoreAway();
        String market = sel.getMarket() == null ? "" : sel.getMarket().toUpperCase();

        log.debug("NFL evaluateSelection: sel {} market={} selection='{}' score={}-{}",
                sel.getId(), market, sel.getSelection(), h, a);

        String result = switch (market) {

            // ── Moneyline (pre-match and live) ─────────────────────────────
            case "NFL_MONEYLINE", "NFL_LIVE_MONEYLINE" ->
                    evaluateMoneyline(sel.getSelection(), h, a, sel.getId().toString());

            // ── Point spread ────────────────────────────────────────────────
            case "NFL_SPREAD" ->
                    evaluateSpread(sel, h, a);

            // ── Total points (Over/Under) ────────────────────────────────────
            case "NFL_TOTAL" ->
                    evaluateTotal(sel, h + a);

            // ── First touchdown scorer ───────────────────────────────────────
            case "NFL_FIRST_TD" ->
                    evaluateFirstTd(sel, match);

            // ── Winning margin band ──────────────────────────────────────────
            case "NFL_WINNING_MARGIN" ->
                    evaluateWinningMargin(sel.getSelection(), h, a, sel.getId().toString());

            // ── Half-time result ─────────────────────────────────────────────
            case "NFL_HALF_TIME" ->
                    evaluateHalfTimeResult(sel, match);

            // ── Half-time total (Over/Under) ─────────────────────────────────
            case "NFL_HALF_TIME_TOTAL" ->
                    evaluateHalfTimeTotal(sel, match);

            // ── Both teams to score (at least 1 point) ───────────────────────
            case "NFL_BTTS" -> {
                boolean bothScored = h > 0 && a > 0;
                boolean wantsBoth  = "Yes".equalsIgnoreCase(sel.getSelection());
                String r = wantsBoth == bothScored ? "WON" : "LOST";
                log.debug("NFL evaluateSelection: NFL_BTTS sel {} wantsBoth={} bothScored={} → {}",
                        sel.getId(), wantsBoth, bothScored, r);
                yield r;
            }

            // ── Exact correct score ───────────────────────────────────────────
            case "NFL_CORRECT_SCORE" -> {
                String expected = h + "-" + a;
                String r = expected.equals(sel.getSelection()) ? "WON" : "LOST";
                log.debug("NFL evaluateSelection: NFL_CORRECT_SCORE sel {} expected='{}' got='{}' → {}",
                        sel.getId(), sel.getSelection(), expected, r);
                yield r;
            }

            default -> {
                log.warn("NFL evaluateSelection: unknown market '{}' for sel {} — voiding",
                        sel.getMarket(), sel.getId());
                yield "VOID";
            }
        };

        log.debug("NFL evaluateSelection: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── Moneyline (Home Win / Draw / Away Win) ────────────────────────────

    /**
     * Evaluates NFL_MONEYLINE and NFL_LIVE_MONEYLINE markets.
     *
     * Accepted selections (any case):
     *   HOME | HOME WIN  — home team wins at full time (including OT)
     *   DRAW             — tied score at end of regulation + OT
     *   AWAY | AWAY WIN  — away team wins at full time (including OT)
     *
     * In NFL a Draw only occurs if the regular-season OT ends with no
     * score. This is rare (~1–2% of games) but the market is offered.
     * In the playoffs, OT continues until a team scores — no ties possible.
     */
    private String evaluateMoneyline(String selection, int h, int a, String selId) {
        String s = selection == null ? "" : selection.trim().toUpperCase();
        log.debug("NFL evaluateMoneyline: sel {} normalised='{}' score={}-{}", selId, s, h, a);

        return switch (s) {
            case "HOME", "HOME WIN" -> {
                String r = h > a ? "WON" : "LOST";
                log.debug("NFL evaluateMoneyline: sel {} HOME → {}", selId, r);
                yield r;
            }
            case "DRAW" -> {
                String r = h == a ? "WON" : "LOST";
                log.debug("NFL evaluateMoneyline: sel {} DRAW → {}", selId, r);
                yield r;
            }
            case "AWAY", "AWAY WIN" -> {
                String r = a > h ? "WON" : "LOST";
                log.debug("NFL evaluateMoneyline: sel {} AWAY → {}", selId, r);
                yield r;
            }
            default -> {
                log.warn("NFL evaluateMoneyline: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
    }

    // ── Point spread ──────────────────────────────────────────────────────

    /**
     * Evaluates NFL_SPREAD.
     *
     * Selection format: "{Home|Away} {+|-}{line}"
     * Examples: "Home -6.5", "Away +3", "Home -3", "Away +0.5"
     *
     * A missing sign is treated as positive ("Home 3" → +3.0).
     *
     * Line classification uses epsilon comparisons (< 0.01) to guard
     * against floating-point parse drift — mirrors SettlementEngine's AH fix.
     *
     *   Half lines  (x.5)        : WON or LOST only — no push possible
     *   Whole lines (x.0)        : WON, PUSH, or LOST
     *   Quarter lines (x.25/x.75): HALF_WON, HALF_LOST, PUSH, WON, or LOST
     *                              (stake split 50/50 across two sub-lines)
     *
     * Returns: WON | LOST | PUSH | HALF_WON | HALF_LOST | VOID
     */
    private String evaluateSpread(BetSelection sel, int h, int a) {
        String raw = sel.getSelection() == null ? "" : sel.getSelection().trim();

        // ── Parse side ────────────────────────────────────────────────────
        boolean bettingHome;
        String rest;
        if (raw.toUpperCase().startsWith("HOME")) {
            bettingHome = true;
            rest = raw.substring(4).trim();
        } else if (raw.toUpperCase().startsWith("AWAY")) {
            bettingHome = false;
            rest = raw.substring(4).trim();
        } else {
            log.warn("NFL evaluateSpread: sel {} cannot parse side from '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        // ── Parse line ────────────────────────────────────────────────────
        double line;
        try {
            line = Double.parseDouble(rest);
        } catch (NumberFormatException e) {
            log.warn("NFL evaluateSpread: sel {} cannot parse line from '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        // ── Normalise to bettor's goal-difference perspective ─────────────
        // gd           = net points in favour of the side being backed
        // effectiveLine = spread applied from that side's perspective
        int    gd           = bettingHome ? (h - a) : (a - h);
        double effectiveLine = bettingHome ? line : -line;

        log.debug("NFL evaluateSpread: sel {} side={} line={} score={}-{} gd={} effectiveLine={}",
                sel.getId(), bettingHome ? "HOME" : "AWAY", line, h, a, gd, effectiveLine);

        // ── Epsilon-based line classification ────────────────────────────
        double frac = Math.abs(effectiveLine % 1);

        String result;
        if (frac < 0.01) {
            // Whole line — push possible
            result = evaluateWholeSpread(gd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.5) < 0.01) {
            // Half line — no push possible
            result = evaluateHalfSpread(gd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.25) < 0.01 || Math.abs(frac - 0.75) < 0.01) {
            // Quarter line — stake split into two sub-lines
            result = evaluateQuarterSpread(effectiveLine, gd, sel.getId().toString());
        } else {
            log.warn("NFL evaluateSpread: sel {} unrecognised frac={} for line={} — VOID",
                    sel.getId(), frac, line);
            result = "VOID";
        }

        log.debug("NFL evaluateSpread: sel {} → {}", sel.getId(), result);
        return result;
    }

    /**
     * Whole spread line (e.g. Home -3, Away +7).
     *   Win  : adjustedPoints > 0
     *   Push : adjustedPoints == 0
     *   Lose : adjustedPoints < 0
     */
    private String evaluateWholeSpread(double adjustedPoints, String selId) {
        String r;
        if      (adjustedPoints > 0)  r = "WON";
        else if (adjustedPoints == 0) r = "PUSH";
        else                          r = "LOST";
        log.debug("NFL evaluateWholeSpread: sel {} adjustedPoints={} → {}", selId, adjustedPoints, r);
        return r;
    }

    /**
     * Half spread line (e.g. Home -6.5, Away +3.5).
     * No push possible — win if adjustedPoints > 0, lose otherwise.
     */
    private String evaluateHalfSpread(double adjustedPoints, String selId) {
        String r = adjustedPoints > 0 ? "WON" : "LOST";
        log.debug("NFL evaluateHalfSpread: sel {} adjustedPoints={} → {}", selId, adjustedPoints, r);
        return r;
    }

    /**
     * Quarter spread line (e.g. Home -6.25, Away +3.75).
     * Stake split 50/50 across two adjacent sub-lines.
     *
     * x.25 → sub-lines: x.0 (whole) and x.5 (half)
     * x.75 → sub-lines: x.5 (half) and x+0.5 (next half)
     *
     * Combined outcomes:
     *   WON+WON    → FULL WIN
     *   WON+PUSH   → HALF_WON
     *   PUSH+WON   → HALF_WON
     *   PUSH+PUSH  → PUSH
     *   LOST+PUSH  → HALF_LOST
     *   PUSH+LOST  → HALF_LOST
     *   WON+LOST   → PUSH (wash)
     *   LOST+LOST  → FULL LOST
     */
    private String evaluateQuarterSpread(double effectiveLine, int gd, String selId) {
        double lower = effectiveLine - 0.25;
        double upper = effectiveLine + 0.25;

        log.debug("NFL evaluateQuarterSpread: sel {} effectiveLine={} gd={} sub-lines=[{}, {}]",
                selId, effectiveLine, gd, lower, upper);

        String lowerResult = settleSingleSpreadLine(lower, gd, selId);
        String upperResult = settleSingleSpreadLine(upper, gd, selId);

        log.debug("NFL evaluateQuarterSpread: sel {} lower={} upper={}", selId, lowerResult, upperResult);

        String combined = combineQuarterSubResults(lowerResult, upperResult, selId);
        log.debug("NFL evaluateQuarterSpread: sel {} combined → {}", selId, combined);
        return combined;
    }

    /** Settles a single sub-line (half or whole) for quarter-spread evaluation. */
    private String settleSingleSpreadLine(double subLine, int gd, String selId) {
        double adjPoints = gd + subLine;
        double frac      = Math.abs(subLine % 1);

        if (frac < 0.01)                  return evaluateWholeSpread(adjPoints, selId + "-whole");
        if (Math.abs(frac - 0.5) < 0.01) return evaluateHalfSpread(adjPoints,  selId + "-half");

        log.warn("NFL settleSingleSpreadLine: sel {} unexpected frac={} for subLine={} — VOID", selId, frac, subLine);
        return "VOID";
    }

    // ── Total points (Over/Under) ─────────────────────────────────────────

    /**
     * Evaluates NFL_TOTAL market.
     *
     * Total = homeScore + awayScore.
     * Selection format: "Over 48.5" | "Under 47" | "over48.5" | "UNDER 47"
     *
     * Half-point lines: no push possible.
     * Whole-point lines: push when total == line exactly (stake refunded).
     */
    private String evaluateTotal(BetSelection sel, int totalPoints) {
        String raw    = sel.getSelection() == null ? "" : sel.getSelection().trim().toLowerCase();
        boolean isOver = raw.startsWith("over");

        double line;
        try {
            line = Double.parseDouble(raw.replaceAll("(?i)^(over|under)\\s*", ""));
        } catch (NumberFormatException e) {
            log.warn("NFL evaluateTotal: cannot parse line from '{}' for sel {} — VOID",
                    sel.getSelection(), sel.getId());
            return "VOID";
        }

        log.debug("NFL evaluateTotal: sel {} isOver={} line={} totalPoints={}", sel.getId(), isOver, line, totalPoints);

        // Whole-number line can push on exact total
        if (line == Math.floor(line) && totalPoints == (int) line) {
            log.debug("NFL evaluateTotal: sel {} exact total on whole line — PUSH", sel.getId());
            return "PUSH";
        }

        String r = isOver == (totalPoints > line) ? "WON" : "LOST";
        log.debug("NFL evaluateTotal: sel {} → {}", sel.getId(), r);
        return r;
    }

    // ── First TD scorer ───────────────────────────────────────────────────

    /**
     * Evaluates NFL_FIRST_TD market.
     *
     * Reads match.metadata["first_td_scorer"]:
     *   "HOME" → home team scored first touchdown
     *   "AWAY" → away team scored first touchdown
     *   "NONE" → no touchdown was scored in the game (e.g. FG-only game)
     *
     * Accepted selections (any case): HOME | AWAY | NO_TD
     *
     * Voids if metadata key is absent — live data not yet populated.
     */
    private String evaluateFirstTd(BetSelection sel, Match match) {
        String actual = extractStringFromMetadata(match, META_FIRST_TD);

        if (actual == null) {
            log.warn("NFL evaluateFirstTd: missing '{}' metadata for match {} sel {} — VOID",
                    META_FIRST_TD, match.getId(), sel.getId());
            return "VOID";
        }

        String actualNorm = actual.trim().toUpperCase();
        String selected   = sel.getSelection() == null ? "" : sel.getSelection().trim().toUpperCase();

        log.debug("NFL evaluateFirstTd: sel {} match {} actual='{}' selected='{}'",
                sel.getId(), match.getId(), actualNorm, selected);

        // Map "NO_TD" selection against "NONE" metadata value
        boolean wantsNoTd = "NO_TD".equals(selected) || "NONE".equals(selected);
        boolean actualNone = "NONE".equals(actualNorm);

        String r;
        if (wantsNoTd) {
            r = actualNone ? "WON" : "LOST";
        } else {
            r = actualNorm.equals(selected) ? "WON" : "LOST";
        }

        log.debug("NFL evaluateFirstTd: sel {} → {}", sel.getId(), r);
        return r;
    }

    // ── Winning margin band ───────────────────────────────────────────────

    /**
     * Evaluates NFL_WINNING_MARGIN market.
     *
     * Selection formats:
     *   "Home 1-6"    → home wins by 1 to 6 points inclusive
     *   "Away 7-12"   → away wins by 7 to 12 points inclusive
     *   "Home 13+"    → home wins by 13 or more points
     *   "Away 1-6"    → away wins by 1 to 6 points inclusive
     *   "Draw"        → tied score (margin == 0)
     *
     * margin = Math.abs(homeScore - awayScore)
     *
     * The winning side must match — "Home 1-6" loses if away wins by 4.
     */
    private String evaluateWinningMargin(String selection, int h, int a, String selId) {
        if (selection == null || selection.isBlank()) {
            log.warn("NFL evaluateWinningMargin: sel {} null/blank selection — VOID", selId);
            return "VOID";
        }

        String s = selection.trim();

        // Draw case
        if ("Draw".equalsIgnoreCase(s) || "DRAW".equalsIgnoreCase(s)) {
            String r = h == a ? "WON" : "LOST";
            log.debug("NFL evaluateWinningMargin: sel {} DRAW score={}-{} → {}", selId, h, a, r);
            return r;
        }

        // Parse side (Home / Away)
        boolean pickHome;
        String rest;
        String upper = s.toUpperCase();
        if (upper.startsWith("HOME")) {
            pickHome = true;
            rest = s.substring(4).trim();
        } else if (upper.startsWith("AWAY")) {
            pickHome = false;
            rest = s.substring(4).trim();
        } else {
            log.warn("NFL evaluateWinningMargin: sel {} cannot parse side from '{}' — VOID", selId, s);
            return "VOID";
        }

        // Verify the right team actually won
        boolean homeWon = h > a;
        boolean awayWon = a > h;
        if (pickHome && !homeWon) {
            log.debug("NFL evaluateWinningMargin: sel {} picked HOME to win but home did not win — LOST", selId);
            return "LOST";
        }
        if (!pickHome && !awayWon) {
            log.debug("NFL evaluateWinningMargin: sel {} picked AWAY to win but away did not win — LOST", selId);
            return "LOST";
        }
        if (h == a) {
            // Game is tied — only Draw selection can win
            log.debug("NFL evaluateWinningMargin: sel {} game is tied but selection is not Draw — LOST", selId);
            return "LOST";
        }

        int margin = Math.abs(h - a);

        // Parse margin band: "1-6" or "13+"
        String r;
        if (rest.contains("-")) {
            // Band format "low-high"
            String[] parts = rest.split("-");
            if (parts.length != 2) {
                log.warn("NFL evaluateWinningMargin: sel {} cannot parse band from '{}' — VOID", selId, rest);
                return "VOID";
            }
            try {
                int low  = Integer.parseInt(parts[0].trim());
                int high = Integer.parseInt(parts[1].trim());
                r = (margin >= low && margin <= high) ? "WON" : "LOST";
            } catch (NumberFormatException e) {
                log.warn("NFL evaluateWinningMargin: sel {} non-integer band '{}' — VOID", selId, rest);
                return "VOID";
            }
        } else if (rest.endsWith("+")) {
            // Open-ended format "13+"
            try {
                int threshold = Integer.parseInt(rest.substring(0, rest.length() - 1).trim());
                r = margin >= threshold ? "WON" : "LOST";
            } catch (NumberFormatException e) {
                log.warn("NFL evaluateWinningMargin: sel {} cannot parse threshold from '{}' — VOID", selId, rest);
                return "VOID";
            }
        } else {
            log.warn("NFL evaluateWinningMargin: sel {} unrecognised band format '{}' — VOID", selId, rest);
            return "VOID";
        }

        log.debug("NFL evaluateWinningMargin: sel {} margin={} band='{}' → {}", selId, margin, rest, r);
        return r;
    }

    // ── Half-time result ─────────────────────────────────────────────────

    /**
     * Evaluates NFL_HALF_TIME market.
     *
     * Half-time score is sourced from match.metadata:
     *   "score_home_ht" → integer home score at the end of Q2
     *   "score_away_ht" → integer away score at the end of Q2
     *
     * Accepted selections: HOME | HOME WIN | DRAW | AWAY | AWAY WIN  (any case).
     * Voids if either metadata key is absent.
     */
    private String evaluateHalfTimeResult(BetSelection sel, Match match) {
        Integer htHome = extractIntFromMetadata(match, META_HT_HOME);
        Integer htAway = extractIntFromMetadata(match, META_HT_AWAY);

        if (htHome == null || htAway == null) {
            log.warn("NFL evaluateHalfTimeResult: missing ht metadata for match {} sel {} — VOID",
                    match.getId(), sel.getId());
            return "VOID";
        }

        log.debug("NFL evaluateHalfTimeResult: sel {} match {} ht={}-{} selection='{}'",
                sel.getId(), match.getId(), htHome, htAway, sel.getSelection());

        String s = sel.getSelection() == null ? "" : sel.getSelection().trim().toUpperCase();
        String r = switch (s) {
            case "HOME", "HOME WIN" -> htHome > htAway  ? "WON" : "LOST";
            case "DRAW"             -> htHome.equals(htAway) ? "WON" : "LOST";
            case "AWAY", "AWAY WIN" -> htAway > htHome  ? "WON" : "LOST";
            default -> {
                log.warn("NFL evaluateHalfTimeResult: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), sel.getSelection());
                yield "VOID";
            }
        };

        log.debug("NFL evaluateHalfTimeResult: sel {} → {}", sel.getId(), r);
        return r;
    }

    // ── Half-time total (Over/Under) ──────────────────────────────────────

    /**
     * Evaluates NFL_HALF_TIME_TOTAL market.
     *
     * halfTimeTotal = htHome + htAway  (from match.metadata).
     * Selection format identical to NFL_TOTAL: "Over 23.5" | "Under 21"
     *
     * Voids if half-time score metadata is absent.
     */
    private String evaluateHalfTimeTotal(BetSelection sel, Match match) {
        Integer htHome = extractIntFromMetadata(match, META_HT_HOME);
        Integer htAway = extractIntFromMetadata(match, META_HT_AWAY);

        if (htHome == null || htAway == null) {
            log.warn("NFL evaluateHalfTimeTotal: missing ht metadata for match {} sel {} — VOID",
                    match.getId(), sel.getId());
            return "VOID";
        }

        int halfTimeTotal = htHome + htAway;
        log.debug("NFL evaluateHalfTimeTotal: sel {} match {} htTotal={} selection='{}'",
                sel.getId(), match.getId(), halfTimeTotal, sel.getSelection());

        // Re-use the NFL_TOTAL evaluator logic against the half-time total
        return evaluateTotalPoints(sel, halfTimeTotal);
    }

    /**
     * Shared Over/Under evaluation logic used by both NFL_TOTAL and NFL_HALF_TIME_TOTAL.
     * Parses "Over X" or "Under X" from sel.getSelection(), evaluates against actualTotal.
     */
    private String evaluateTotalPoints(BetSelection sel, int actualTotal) {
        String raw    = sel.getSelection() == null ? "" : sel.getSelection().trim().toLowerCase();
        boolean isOver = raw.startsWith("over");

        double line;
        try {
            line = Double.parseDouble(raw.replaceAll("(?i)^(over|under)\\s*", ""));
        } catch (NumberFormatException e) {
            log.warn("NFL evaluateTotalPoints: cannot parse line from '{}' for sel {} — VOID",
                    sel.getSelection(), sel.getId());
            return "VOID";
        }

        log.debug("NFL evaluateTotalPoints: sel {} isOver={} line={} actualTotal={}",
                sel.getId(), isOver, line, actualTotal);

        // Whole-number line can push on exact total
        if (line == Math.floor(line) && actualTotal == (int) line) {
            log.debug("NFL evaluateTotalPoints: sel {} exact total on whole line — PUSH", sel.getId());
            return "PUSH";
        }

        String r = isOver == (actualTotal > line) ? "WON" : "LOST";
        log.debug("NFL evaluateTotalPoints: sel {} → {}", sel.getId(), r);
        return r;
    }

    // ── Quarter sub-result combiner ───────────────────────────────────────

    /**
     * Combines two sub-line results for a quarter-spread into a single outcome.
     * Identical logic to SettlementEngine's combineQuarterSubResults.
     */
    private String combineQuarterSubResults(String lower, String upper, String selId) {
        if (lower.equals(upper)) return lower;  // WON+WON, LOST+LOST, PUSH+PUSH

        if (isWon(lower)  && isPush(upper)) return "HALF_WON";
        if (isPush(lower) && isWon(upper))  return "HALF_WON";

        if (isLost(lower) && isPush(upper)) return "HALF_LOST";
        if (isPush(lower) && isLost(upper)) return "HALF_LOST";

        // Win + Loss = net wash
        if ((isWon(lower) && isLost(upper)) || (isLost(lower) && isWon(upper))) return "PUSH";

        log.warn("NFL combineQuarterSubResults: sel {} unexpected combination lower={} upper={} — VOID",
                selId, lower, upper);
        return "VOID";
    }

    // ── League guard ──────────────────────────────────────────────────────

    /**
     * Returns true if this Match belongs to an NFL league.
     * Prevents the NFL engine from settling soccer or MMA bets.
     * Checks match.getLeague() for "nfl" (case-insensitive).
     */
    private boolean isNflMatch(Match match) {
        String league = match.getLeague();
        return league != null && league.toLowerCase().contains("nfl");
    }

    // ── Metadata helpers ──────────────────────────────────────────────────

    /**
     * Safely reads an integer from match.metadata.
     * Handles values stored as Integer, Long, or String.
     * Returns null if the key is absent or the value is non-numeric.
     */
    private Integer extractIntFromMetadata(Match match, String key) {
        if (match.getMetadata() == null) return null;
        Object val = match.getMetadata().get(key);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            log.warn("NFL extractIntFromMetadata: match {} key '{}' value '{}' is not an int",
                    match.getId(), key, val);
            return null;
        }
    }

    /**
     * Safely reads a String from match.metadata.
     * Returns null if the key is absent.
     */
    private String extractStringFromMetadata(Match match, String key) {
        if (match.getMetadata() == null) return null;
        Object val = match.getMetadata().get(key);
        return val != null ? val.toString() : null;
    }

    // ── Result predicates ─────────────────────────────────────────────────

    private boolean isWon(String r)  { return "WON".equals(r); }
    private boolean isLost(String r) { return "LOST".equals(r); }
    private boolean isPush(String r) { return "PUSH".equals(r); }
}
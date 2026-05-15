package com.speedbet.api.bet;

import com.speedbet.api.match.BaseballMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * MlbSettlementService — settles bets across all SpeedBet MLB markets.
 *
 *   Markets handled:
 *   ┌──────────────────────┬──────────────────────────────────────────────────────┐
 *   │ mlb_moneyline        │ Pre-match: HOME / AWAY (no draw — extra innings rule) │
 *   │ mlb_live_moneyline   │ In-play:   HOME / AWAY (settled on final result)      │
 *   │ MLB_RUN_LINE         │ Run-line handicap: "Home -1.5", "Away +1.5" etc.      │
 *   │ MLB_OVER_UNDER       │ Total runs: "Over 8.5", "Under 7" etc.               │
 *   │ MLB_FIRST_5_INNINGS  │ 1st-5-innings result: HOME / AWAY / DRAW (F5 draw    │
 *   │                      │ is possible — game may be tied after 5 full innings)  │
 *   └──────────────────────┴──────────────────────────────────────────────────────┘
 *
 *  Baseball settlement rules:
 *
 *   mlb_moneyline / mlb_live_moneyline:
 *     No draw is possible — someone always wins (extra innings if tied after 9).
 *     HOME wins if homeScore > awayScore at game end; AWAY otherwise.
 *     Any "DRAW" selection is VOID (should never appear — see MlbOddsPersistenceService).
 *
 *   MLB_RUN_LINE:
 *     Standard run-line is ±1.5 (half-line, no push possible).
 *     Whole-line run-lines (e.g. ±1, ±2) push on exact run difference.
 *     Quarter-line run-lines (e.g. ±1.25, ±1.75) split 50/50 across two sub-lines.
 *     Selection string format: "{Home|Away} {+|-}{line}"  e.g. "Home -1.5", "Away +1"
 *     Epsilon comparisons used for all fractional checks (FP drift guard).
 *
 *   MLB_OVER_UNDER:
 *     Settled on total runs (homeScore + awayScore) at game end.
 *     Whole-line total (e.g. "Over 8") pushes if exact; half-lines never push.
 *     Selection format: "Over 8.5" | "Under 7" | "over9" etc.
 *
 *   MLB_FIRST_5_INNINGS:
 *     Settled on the score after exactly 5 complete innings (both top and bottom).
 *     HOME / AWAY / DRAW all valid — a tie after 5 is a DRAW win.
 *     Scores stored in match.metadata under "score_home_f5" and "score_away_f5".
 *     If metadata is absent the selection is VOID.
 *
 *  Void / partial / accumulator logic:
 *
 *   Mirrors SettlementEngine exactly:
 *     VOID / PUSH   → locked odds divided out of parlay total.
 *     HALF_WON      → (oddsLocked + 1) / 2 multiplier applied.
 *     HALF_LOST     → 0.5 multiplier applied; does NOT kill the accumulator.
 *     LOST          → full accumulator loss.
 *   A bet where all legs push/void collapses to effectiveOdds = 1.0 (stake returned).
 *
 *  Scheduling:
 *   Runs every 60 seconds (same cadence as SettlementEngine).
 *   Match sourcing uses BaseballMatchService.getUnsettledFinished() which is
 *   already scoped to sport="baseball" — no league filter needed here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MlbSettlementService {

    private final BaseballMatchService baseballMatchService;
    private final BetService           betService;

    // ── Scheduled runner ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        var finishedMatches = baseballMatchService.getUnsettledFinished();

        log.info("MlbSettlement run: {} finished MLB match(es) to process", finishedMatches.size());

        for (var match : finishedMatches) {
            try {
                log.info("MlbSettlement run: processing match {} ({} vs {})",
                        match.getId(), match.getHomeTeam(), match.getAwayTeam());
                settleMatch(match);
                baseballMatchService.markSettled(match.getId().toString());
                log.info("MlbSettlement run: match {} marked settled", match.getId());
            } catch (Exception e) {
                log.error("MlbSettlement run: FAILED for match {} — {}", match.getId(), e.getMessage(), e);
            }
        }

        log.info("MlbSettlement run: complete");
    }

    // ── Match-level settlement ────────────────────────────────────────────

    @Transactional
    public void settleMatch(com.speedbet.api.match.Match match) {
        if (match.getScoreHome() == null || match.getScoreAway() == null) {
            log.warn("MlbSettlement settleMatch: match {} has null score(s) — skipping", match.getId());
            return;
        }

        int h = match.getScoreHome();
        int a = match.getScoreAway();
        log.info("MlbSettlement settleMatch: match {} final score {}-{}", match.getId(), h, a);

        // Log F5 metadata so we can verify it arrived before settling MLB_FIRST_5_INNINGS bets
        Integer f5Home = extractIntFromMetadata(match, "score_home_f5");
        Integer f5Away = extractIntFromMetadata(match, "score_away_f5");
        if (f5Home != null && f5Away != null) {
            log.info("MlbSettlement settleMatch: match {} first-5-innings score {}-{}", match.getId(), f5Home, f5Away);
        } else {
            log.warn("MlbSettlement settleMatch: match {} has no F5 metadata — MLB_FIRST_5_INNINGS bets will VOID",
                    match.getId());
        }

        var pendingBets = betService.getPendingBetsForMatch(match.getId());
        log.info("MlbSettlement settleMatch: {} pending bet(s) found for match {}", pendingBets.size(), match.getId());

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
                log.debug("MlbSettlement settleMatch: bet {} {} → {}", bet.getId(), before, after);
            }
        }
        log.info("MlbSettlement settleMatch: match {} done — WON={} LOST={} VOID={} other={}",
                match.getId(), won, lost, voided, partial);
    }

    // ── Bet-level settlement ──────────────────────────────────────────────

    private void settleOneBet(Bet bet, com.speedbet.api.match.Match match) {
        log.debug("MlbSettlement settleOneBet: bet {} stake={} totalOdds={} selections={}",
                bet.getId(), bet.getStake(), bet.getTotalOdds(), bet.getSelections().size());

        boolean hasFullLoss = false;
        BigDecimal oddsAdjustment = BigDecimal.ONE;

        for (var sel : bet.getSelections()) {
            if (!sel.getMatchId().equals(match.getId())) continue;

            String result = evaluateSelection(sel, match);
            sel.setResult(result);

            log.info("MlbSettlement settleOneBet: bet {} sel {} market={} selection='{}' oddsLocked={} → {}",
                    bet.getId(), sel.getId(), sel.getMarket(), sel.getSelection(),
                    sel.getOddsLocked(), result);

            switch (result) {
                case "LOST" -> {
                    hasFullLoss = true;
                    log.debug("MlbSettlement settleOneBet: bet {} — full loss on sel {}", bet.getId(), sel.getId());
                }

                case "VOID", "PUSH" -> {
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64);
                    log.debug("MlbSettlement settleOneBet: bet {} sel {} {} — divided out odds {}, adjustment now {}",
                            bet.getId(), sel.getId(), result, sel.getOddsLocked(), oddsAdjustment);
                }

                case "HALF_WON" -> {
                    BigDecimal halfWinMultiplier = sel.getOddsLocked()
                            .add(BigDecimal.ONE)
                            .divide(new BigDecimal("2"), MathContext.DECIMAL64);
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                            .multiply(halfWinMultiplier, MathContext.DECIMAL64);
                    log.debug("MlbSettlement settleOneBet: bet {} sel {} HALF_WON — halfWinMultiplier={} adjustment now {}",
                            bet.getId(), sel.getId(), halfWinMultiplier, oddsAdjustment);
                }

                case "HALF_LOST" -> {
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                            .multiply(new BigDecimal("0.5"), MathContext.DECIMAL64);
                    log.debug("MlbSettlement settleOneBet: bet {} sel {} HALF_LOST — adjustment now {}",
                            bet.getId(), sel.getId(), oddsAdjustment);
                }

                case "WON" -> log.debug("MlbSettlement settleOneBet: bet {} sel {} WON — no adjustment",
                        bet.getId(), sel.getId());

                default -> log.warn("MlbSettlement settleOneBet: unknown result '{}' for sel {} — treating as VOID",
                        result, sel.getId());
            }
        }

        // A single outright LOST leg kills the whole bet
        if (hasFullLoss) {
            log.info("MlbSettlement settleOneBet: bet {} LOST (at least one outright losing leg)", bet.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return;
        }

        // Defer if any leg is still PENDING (multi-match accumulators)
        boolean allSettled = bet.getSelections().stream()
                .noneMatch(s -> "PENDING".equals(s.getResult()));
        if (!allSettled) {
            log.debug("MlbSettlement settleOneBet: bet {} — not all legs settled yet, deferring", bet.getId());
            return;
        }

        BigDecimal effectiveOdds = bet.getTotalOdds()
                .multiply(oddsAdjustment, MathContext.DECIMAL64);

        log.debug("MlbSettlement settleOneBet: bet {} totalOdds={} × oddsAdjustment={} = effectiveOdds={}",
                bet.getId(), bet.getTotalOdds(), oddsAdjustment, effectiveOdds);

        // All legs PUSH/VOID → stake returned at 1.0
        if (effectiveOdds.compareTo(BigDecimal.ONE) < 0) {
            log.warn("MlbSettlement settleOneBet: bet {} effectiveOdds {} < 1.0 — clamping to 1.0", bet.getId(), effectiveOdds);
            effectiveOdds = BigDecimal.ONE;
        }

        BigDecimal payout = bet.getStake()
                .multiply(effectiveOdds, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);

        BetStatus finalStatus = effectiveOdds.compareTo(BigDecimal.ONE) == 0
                ? BetStatus.VOID   // all legs pushed → full stake return
                : BetStatus.WON;

        log.info("MlbSettlement settleOneBet: bet {} → {} effectiveOdds={} payout={}",
                bet.getId(), finalStatus, effectiveOdds, payout);
        betService.settleBet(bet, finalStatus, payout);
    }

    // ── Market router ─────────────────────────────────────────────────────

    /**
     * Returns one of: WON | LOST | VOID | PUSH | HALF_WON | HALF_LOST
     */
    private String evaluateSelection(BetSelection sel, com.speedbet.api.match.Match match) {
        int h = match.getScoreHome();
        int a = match.getScoreAway();
        String market = sel.getMarket() == null ? "" : sel.getMarket().toUpperCase();

        log.debug("MlbSettlement evaluateSelection: sel {} market={} selection='{}' score={}-{}",
                sel.getId(), market, sel.getSelection(), h, a);

        String result = switch (market) {

            case "MLB_MONEYLINE", "MLB_LIVE_MONEYLINE",
                 "MLM_LIVE_MONEYLINE_CORRECTED"           // guard for any persistence alias variants
                    -> evaluateMlbMoneyline(sel.getSelection(), h, a, sel.getId().toString());

            // Normalised persistence labels — same logic, different stored market string
            case "MLM_MONEYLINE" -> evaluateMlbMoneyline(sel.getSelection(), h, a, sel.getId().toString());

            case "MLB_RUN_LINE"  -> evaluateRunLine(sel, h, a);

            case "MLB_OVER_UNDER" -> evaluateOverUnder(sel, h + a);

            case "MLB_FIRST_5_INNINGS" -> evaluateFirst5Innings(sel, match);

            default -> {
                log.warn("MlbSettlement evaluateSelection: unknown market '{}' for sel {} — voiding",
                        sel.getMarket(), sel.getId());
                yield "VOID";
            }
        };

        log.debug("MlbSettlement evaluateSelection: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── Moneyline (pre-match and live) ────────────────────────────────────

    /**
     * Two-way market: HOME wins if homeRuns > awayRuns; AWAY otherwise.
     * Baseball has no draw — extra innings ensure a winner.
     *
     * Normalises selection to uppercase before matching so persistence-stored
     * values ("HOME", "AWAY") and human-readable aliases settle correctly.
     * "DRAW" is VOID — should never appear in MLB moneyline, but handled defensively.
     */
    private String evaluateMlbMoneyline(String selection, int h, int a, String selId) {
        if (h == a) {
            // Final score should never be tied in MLB — game incomplete or data error
            log.warn("MlbSettlement evaluateMlbMoneyline: sel {} tied score {}-{} on moneyline — VOID",
                    selId, h, a);
            return "VOID";
        }

        String s = selection == null ? "" : selection.trim().toUpperCase();
        log.debug("MlbSettlement evaluateMlbMoneyline: sel {} normalised='{}' score={}-{}", selId, s, h, a);

        return switch (s) {
            case "HOME", "HOME WIN" -> {
                String r = h > a ? "WON" : "LOST";
                log.debug("MlbSettlement evaluateMlbMoneyline: sel {} HOME → {}", selId, r);
                yield r;
            }
            case "AWAY", "AWAY WIN" -> {
                String r = a > h ? "WON" : "LOST";
                log.debug("MlbSettlement evaluateMlbMoneyline: sel {} AWAY → {}", selId, r);
                yield r;
            }
            case "DRAW" -> {
                // Baseball has no draw — should never be stored, but guard it
                log.warn("MlbSettlement evaluateMlbMoneyline: sel {} DRAW selection on MLB moneyline — VOID", selId);
                yield "VOID";
            }
            default -> {
                log.warn("MlbSettlement evaluateMlbMoneyline: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
    }

    // ── Run Line ─────────────────────────────────────────────────────────

    /**
     * Run-line handicap — the baseball equivalent of the Asian Handicap.
     *
     * Selection string format: "{Home|Away} {+|-}{line}"
     * Examples: "Home -1.5", "Away +1.5", "Home -2", "Away +0.25"
     *
     * Line classification (epsilon guarded against FP drift):
     *   x.0          whole line  — push possible when run difference exactly equals line
     *   x.5          half line   — no push; win or lose only
     *   x.25 / x.75  quarter line — stake split 50/50 across two adjacent sub-lines
     *
     * Returns: WON | LOST | PUSH | HALF_WON | HALF_LOST | VOID
     */
    private String evaluateRunLine(BetSelection sel, int h, int a) {
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
            log.warn("MlbSettlement evaluateRunLine: sel {} cannot parse side from '{}' — VOID",
                    sel.getId(), raw);
            return "VOID";
        }

        // ── Parse line ────────────────────────────────────────────────────
        double line;
        try {
            line = Double.parseDouble(rest);
        } catch (NumberFormatException e) {
            log.warn("MlbSettlement evaluateRunLine: sel {} cannot parse line from '{}' — VOID",
                    sel.getId(), raw);
            return "VOID";
        }

        // ── Goal difference from bettor's perspective ─────────────────────
        int    rd           = bettingHome ? (h - a) : (a - h);
        double effectiveLine = bettingHome ? line : -line;

        log.debug("MlbSettlement evaluateRunLine: sel {} side={} line={} score={}-{} rd={} effectiveLine={}",
                sel.getId(), bettingHome ? "HOME" : "AWAY", line, h, a, rd, effectiveLine);

        // ── Line classification (epsilon guarded) ─────────────────────────
        double frac = Math.abs(effectiveLine % 1);

        String result;
        if (frac < 0.01) {
            result = evaluateWholeRunLine(rd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.5) < 0.01) {
            result = evaluateHalfRunLine(rd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.25) < 0.01 || Math.abs(frac - 0.75) < 0.01) {
            result = evaluateQuarterRunLine(effectiveLine, rd, sel.getId().toString());
        } else {
            log.warn("MlbSettlement evaluateRunLine: sel {} unrecognised frac={} for line={} — VOID",
                    sel.getId(), frac, line);
            result = "VOID";
        }

        log.debug("MlbSettlement evaluateRunLine: sel {} → {}", sel.getId(), result);
        return result;
    }

    /**
     * Whole run-line: x.0
     * WON  if adjustedRD > 0
     * PUSH if adjustedRD == 0   (stake refunded)
     * LOST if adjustedRD < 0
     */
    private String evaluateWholeRunLine(double adjustedRD, String selId) {
        String r;
        if      (adjustedRD > 0.001)  r = "WON";
        else if (adjustedRD > -0.001) r = "PUSH";  // effectively == 0 with epsilon
        else                          r = "LOST";
        log.debug("MlbSettlement evaluateWholeRunLine: sel {} adjustedRD={} → {}", selId, adjustedRD, r);
        return r;
    }

    /**
     * Half run-line: x.5 — no push possible.
     * WON if adjustedRD > 0, LOST if ≤ 0.
     */
    private String evaluateHalfRunLine(double adjustedRD, String selId) {
        String r = adjustedRD > 0 ? "WON" : "LOST";
        log.debug("MlbSettlement evaluateHalfRunLine: sel {} adjustedRD={} → {}", selId, adjustedRD, r);
        return r;
    }

    /**
     * Quarter run-line: x.25 or x.75 — stake split 50/50 across two adjacent sub-lines.
     *
     * Combined outcomes:
     *   Both win               → FULL WIN
     *   One win,   one push    → HALF_WON
     *   Both push              → PUSH
     *   One lose,  one push    → HALF_LOST
     *   One win,   one lose    → PUSH  (wash)
     *   Both lose              → FULL LOST
     */
    private String evaluateQuarterRunLine(double effectiveLine, int rd, String selId) {
        double lower = effectiveLine - 0.25;
        double upper = effectiveLine + 0.25;

        log.debug("MlbSettlement evaluateQuarterRunLine: sel {} effectiveLine={} rd={} sub-lines=[{}, {}]",
                selId, effectiveLine, rd, lower, upper);

        String lowerResult = settleSingleRunLine(lower, rd, selId);
        String upperResult = settleSingleRunLine(upper, rd, selId);

        log.debug("MlbSettlement evaluateQuarterRunLine: sel {} lower={} upper={}", selId, lowerResult, upperResult);

        String combined = combineQuarterSubResults(lowerResult, upperResult, selId);
        log.debug("MlbSettlement evaluateQuarterRunLine: sel {} combined → {}", selId, combined);
        return combined;
    }

    /** Evaluates a single sub-line (half or whole) for a given run difference. */
    private String settleSingleRunLine(double subLine, int rd, String selId) {
        double adjRD = rd + subLine;
        double frac  = Math.abs(subLine % 1);

        if (frac < 0.01)                  return evaluateWholeRunLine(adjRD, selId + "-whole");
        if (Math.abs(frac - 0.5) < 0.01) return evaluateHalfRunLine(adjRD,  selId + "-half");

        log.warn("MlbSettlement settleSingleRunLine: sel {} unexpected frac={} for subLine={} — VOID",
                selId, frac, subLine);
        return "VOID";
    }

    private String combineQuarterSubResults(String lower, String upper, String selId) {
        if (lower.equals(upper)) return lower;  // WON+WON, LOST+LOST, PUSH+PUSH

        if (isWon(lower)  && isPush(upper)) return "HALF_WON";
        if (isPush(lower) && isWon(upper))  return "HALF_WON";

        if (isLost(lower) && isPush(upper)) return "HALF_LOST";
        if (isPush(lower) && isLost(upper)) return "HALF_LOST";

        // Win + Loss = net wash
        if ((isWon(lower) && isLost(upper)) || (isLost(lower) && isWon(upper))) return "PUSH";

        log.warn("MlbSettlement combineQuarterSubResults: unexpected combination lower={} upper={} — VOID",
                lower, upper);
        return "VOID";
    }

    // ── Over / Under (runs) ──────────────────────────────────────────────

    /**
     * Settled on total runs at game end.
     * Accepted formats: "Over 8.5", "Under 7", "over9.5", "UNDER 8"
     * Whole-number lines push on exact total (e.g. "Over 8" with exactly 8 total runs → PUSH).
     */
    private String evaluateOverUnder(BetSelection sel, int totalRuns) {
        String raw    = sel.getSelection() == null ? "" : sel.getSelection().trim().toLowerCase();
        boolean isOver = raw.startsWith("over");

        double line;
        try {
            line = Double.parseDouble(raw.replaceAll("(?i)^(over|under)\\s*", ""));
        } catch (NumberFormatException e) {
            log.warn("MlbSettlement evaluateOverUnder: cannot parse line from '{}' for sel {} — VOID",
                    sel.getSelection(), sel.getId());
            return "VOID";
        }

        log.debug("MlbSettlement evaluateOverUnder: sel {} isOver={} line={} totalRuns={}",
                sel.getId(), isOver, line, totalRuns);

        // Whole-number lines push on exact total
        double frac = Math.abs(line % 1);
        if (frac < 0.01 && totalRuns == (int) Math.round(line)) {
            log.debug("MlbSettlement evaluateOverUnder: sel {} exact total on whole line — PUSH", sel.getId());
            return "PUSH";
        }

        String r = isOver == (totalRuns > line) ? "WON" : "LOST";
        log.debug("MlbSettlement evaluateOverUnder: sel {} → {}", sel.getId(), r);
        return r;
    }

    // ── First 5 Innings ──────────────────────────────────────────────────

    /**
     * Settled on the score after exactly 5 complete innings (top and bottom of 5th both played).
     *
     * Unlike the full-game moneyline, a DRAW is valid here — if the teams are tied
     * after 5 innings, the DRAW selection wins.
     *
     * Scores are read from match.metadata:
     *   "score_home_f5" — home runs through end of 5th inning
     *   "score_away_f5" — away runs through end of 5th inning
     *
     * If either value is absent, the selection is VOID (data not yet received or unavailable).
     *
     * Selections: "HOME" | "DRAW" | "AWAY"
     * Also accepts human aliases: "HOME WIN" | "AWAY WIN"
     */
    private String evaluateFirst5Innings(BetSelection sel, com.speedbet.api.match.Match match) {
        Integer f5Home = extractIntFromMetadata(match, "score_home_f5");
        Integer f5Away = extractIntFromMetadata(match, "score_away_f5");

        if (f5Home == null || f5Away == null) {
            log.warn("MlbSettlement evaluateFirst5Innings: missing F5 metadata for match {} sel {} — VOID",
                    match.getId(), sel.getId());
            return "VOID";
        }

        log.debug("MlbSettlement evaluateFirst5Innings: sel {} match {} f5={}-{} selection='{}'",
                sel.getId(), match.getId(), f5Home, f5Away, sel.getSelection());

        String s = sel.getSelection() == null ? "" : sel.getSelection().trim().toUpperCase();
        String r = switch (s) {
            case "HOME", "HOME WIN" -> f5Home > f5Away  ? "WON" : "LOST";
            case "DRAW"             -> f5Home.equals(f5Away) ? "WON" : "LOST";
            case "AWAY", "AWAY WIN" -> f5Away > f5Home  ? "WON" : "LOST";
            default -> {
                log.warn("MlbSettlement evaluateFirst5Innings: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), sel.getSelection());
                yield "VOID";
            }
        };

        log.debug("MlbSettlement evaluateFirst5Innings: sel {} → {}", sel.getId(), r);
        return r;
    }

    // ── Metadata helpers ──────────────────────────────────────────────────

    /**
     * Safely reads an integer from match.metadata.
     * Handles values stored as Integer, Long, or String (all three can appear
     * depending on how the LiveScorePoller deserialized the JSON).
     */
    private Integer extractIntFromMetadata(com.speedbet.api.match.Match match, String key) {
        if (match.getMetadata() == null) return null;
        Object val = match.getMetadata().get(key);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            log.warn("MlbSettlement extractIntFromMetadata: match {} key '{}' value '{}' is not an int",
                    match.getId(), key, val);
            return null;
        }
    }

    // ── Sub-result predicates ─────────────────────────────────────────────

    private boolean isWon(String r)  { return "WON".equals(r); }
    private boolean isLost(String r) { return "LOST".equals(r); }
    private boolean isPush(String r) { return "PUSH".equals(r); }
}
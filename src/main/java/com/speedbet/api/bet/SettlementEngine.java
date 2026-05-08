package com.speedbet.api.bet;

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
 * SettlementEngine — settles bets across all SpeedBet markets:
 *
 *   Markets handled:
 *   ┌─────────────────┬───────────────────────────────────────────────────────┐
 *   │ 1X2 / ONE_X_TWO │ Full-time: Home Win / Draw / Away Win                 │
 *   │ HOME_WIN        │ Alias — home wins at full time                         │
 *   │ AWAY_WIN        │ Alias — away wins at full time                         │
 *   │ BTTS            │ Both Teams To Score — Yes / No                         │
 *   │ OVER_UNDER      │ Total goals — line parsed from selection string         │
 *   │ CORRECT_SCORE   │ Exact full-time scoreline "h-a"                        │
 *   │ DOUBLE_CHANCE   │ 1X / X2 / 12                                           │
 *   │ HALF_TIME       │ Half-time result: Home / Draw / Away                   │
 *   │ ASIAN_HANDICAP  │ Half / Whole / Quarter lines — push & partial support  │
 *   └─────────────────┴───────────────────────────────────────────────────────┘
 *
 *  Asian Handicap settlement:
 *   • Half lines  (e.g. ±0.5, ±1.5, ±2.5)  — FULL WIN or FULL LOSS only
 *   • Whole lines (e.g. ±1,  ±2)            — WIN, PUSH (refund), or LOSS
 *   • Quarter lines (e.g. ±0.25, ±0.75)    — stake split 50/50 across two sub-lines;
 *                                             results: FULL WIN, HALF WIN, PUSH,
 *                                             HALF LOSS, or FULL LOSS
 *
 *  The selection string for ASIAN_HANDICAP encodes side and line together:
 *   e.g. "Home -1.5", "Away +0.25", "Home -1"
 *
 *  Void odds product:
 *   When a selection is fully voided (VOID or PUSH on a whole-line handicap),
 *   its locked odds are divided OUT of the parlay total so the remaining legs
 *   are settled at their true combined odds.
 *
 *  Partial payouts (quarter handicap half results):
 *   When a selection is HALF_WON the effective payout multiplier is:
 *       (oddsLocked + 1) / 2      — half at odds, half returned at 1.0
 *   When a selection is HALF_LOST the multiplier is 0.5 (half stake lost).
 *   A HALF_LOST leg does NOT fail the entire accumulator — a partial
 *   odds adjustment is applied instead.
 *
 *  FIX 1 — evaluate1X2: normalise selection to uppercase before matching so
 *   that persistence-normalised values ("HOME", "AWAY", "DRAW") settle
 *   correctly alongside human-readable aliases ("Home Win", "Away Win").
 *
 *  FIX 2 — evaluateAsianHandicap: use epsilon comparisons (< 0.01) for all
 *   fractional checks instead of exact == equality, preventing floating-point
 *   parse drift from routing a valid line into the VOID branch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEngine {

    private final MatchService matchService;
    private final BetService   betService;

    // ── Scheduled runner ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        var finishedMatches = matchService.getUnsettledFinished();
        log.info("Settlement run: {} finished match(es) to process", finishedMatches.size());

        for (var match : finishedMatches) {
            try {
                log.info("Settlement run: processing match {} ({} vs {})",
                        match.getId(), match.getHomeTeam(), match.getAwayTeam());
                settleMatch(match);
                matchService.markSettled(match.getId().toString());
                log.info("Settlement run: match {} marked settled", match.getId());
            } catch (Exception e) {
                log.error("Settlement run: FAILED for match {} — {}", match.getId(), e.getMessage(), e);
            }
        }

        log.info("Settlement run: complete");
    }

    // ── Match-level settlement ────────────────────────────────────────────

    @Transactional
    public void settleMatch(com.speedbet.api.match.Match match) {
        if (match.getScoreHome() == null || match.getScoreAway() == null) {
            log.warn("settleMatch: match {} has null score(s) — skipping", match.getId());
            return;
        }

        int h = match.getScoreHome();
        int a = match.getScoreAway();
        log.info("settleMatch: match {} final score {}-{}", match.getId(), h, a);

        // Log half-time metadata so we can verify it arrived before settling HALF_TIME bets
        Integer htHome = extractIntFromMetadata(match, "score_home_ht");
        Integer htAway = extractIntFromMetadata(match, "score_away_ht");
        if (htHome != null && htAway != null) {
            log.info("settleMatch: match {} half-time score {}-{}", match.getId(), htHome, htAway);
        } else {
            log.warn("settleMatch: match {} has no half-time metadata — HALF_TIME bets will VOID", match.getId());
        }

        var pendingBets = betService.getPendingBetsForMatch(match.getId());
        log.info("settleMatch: {} pending bet(s) found for match {}", pendingBets.size(), match.getId());

        int won = 0, lost = 0, voided = 0, partial = 0;
        for (var bet : pendingBets) {
            BetStatus before = bet.getStatus();
            settleOneBet(bet, match);
            BetStatus after = bet.getStatus();
            // tally for summary log
            if      (BetStatus.WON.equals(after))  won++;
            else if (BetStatus.LOST.equals(after)) lost++;
            else if (BetStatus.VOID.equals(after)) voided++;
            else                                   partial++;
            if (!before.equals(after)) {
                log.debug("settleMatch: bet {} {} → {}", bet.getId(), before, after);
            }
        }
        log.info("settleMatch: match {} done — WON={} LOST={} VOID={} other={}",
                match.getId(), won, lost, voided, partial);
    }

    // ── Bet-level settlement ──────────────────────────────────────────────

    private void settleOneBet(Bet bet, com.speedbet.api.match.Match match) {
        log.debug("settleOneBet: bet {} stake={} totalOdds={} selections={}",
                bet.getId(), bet.getStake(), bet.getTotalOdds(), bet.getSelections().size());

        boolean hasFullLoss = false;
        // Accumulated adjustments to totalOdds for voided / partial legs:
        //   • VOID / PUSH      → divide out the full locked odds
        //   • HALF_WON         → divide out locked odds, multiply in (odds+1)/2
        //   • HALF_LOST        → divide out locked odds, multiply in 0.5
        BigDecimal oddsAdjustment = BigDecimal.ONE;

        for (var sel : bet.getSelections()) {
            if (!sel.getMatchId().equals(match.getId())) continue;

            String result = evaluateSelection(sel, match);
            sel.setResult(result);

            log.info("settleOneBet: bet {} sel {} market={} selection='{}' oddsLocked={} → {}",
                    bet.getId(), sel.getId(), sel.getMarket(), sel.getSelection(),
                    sel.getOddsLocked(), result);

            switch (result) {
                case "LOST" -> {
                    hasFullLoss = true;
                    log.debug("settleOneBet: bet {} — full loss flagged on sel {}", bet.getId(), sel.getId());
                }

                case "VOID", "PUSH" -> {
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64);
                    log.debug("settleOneBet: bet {} sel {} {} — divided out odds {}, adjustment now {}",
                            bet.getId(), sel.getId(), result, sel.getOddsLocked(), oddsAdjustment);
                }

                case "HALF_WON" -> {
                    BigDecimal halfWinMultiplier = sel.getOddsLocked()
                            .add(BigDecimal.ONE)
                            .divide(new BigDecimal("2"), MathContext.DECIMAL64);
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                            .multiply(halfWinMultiplier, MathContext.DECIMAL64);
                    log.debug("settleOneBet: bet {} sel {} HALF_WON — halfWinMultiplier={} adjustment now {}",
                            bet.getId(), sel.getId(), halfWinMultiplier, oddsAdjustment);
                }

                case "HALF_LOST" -> {
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                            .multiply(new BigDecimal("0.5"), MathContext.DECIMAL64);
                    log.debug("settleOneBet: bet {} sel {} HALF_LOST — adjustment now {}",
                            bet.getId(), sel.getId(), oddsAdjustment);
                }

                case "WON" -> log.debug("settleOneBet: bet {} sel {} WON — no odds adjustment", bet.getId(), sel.getId());

                default -> log.warn("settleOneBet: unknown result '{}' for sel {} — treating as VOID", result, sel.getId());
            }
        }

        // A single outright LOST leg kills the whole bet
        if (hasFullLoss) {
            log.info("settleOneBet: bet {} LOST (at least one outright losing leg)", bet.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return;
        }

        // Wait for all legs to be settled before paying out
        boolean allSettled = bet.getSelections().stream()
                .noneMatch(s -> "PENDING".equals(s.getResult()));
        if (!allSettled) {
            log.debug("settleOneBet: bet {} — not all legs settled yet, deferring payout", bet.getId());
            return;
        }

        // Effective odds = declared total ÷ voided-odds product × partial adjustments
        BigDecimal effectiveOdds = bet.getTotalOdds()
                .multiply(oddsAdjustment, MathContext.DECIMAL64);

        log.debug("settleOneBet: bet {} totalOdds={} × oddsAdjustment={} = effectiveOdds={}",
                bet.getId(), bet.getTotalOdds(), oddsAdjustment, effectiveOdds);

        // A bet where all legs ended as PUSH/VOID collapses to odds 1.0 (stake returned)
        if (effectiveOdds.compareTo(BigDecimal.ONE) < 0) {
            log.warn("settleOneBet: bet {} effectiveOdds {} < 1.0 — clamping to 1.0 (stake return)", bet.getId(), effectiveOdds);
            effectiveOdds = BigDecimal.ONE;
        }

        BigDecimal payout = bet.getStake()
                .multiply(effectiveOdds, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);

        BetStatus finalStatus = effectiveOdds.compareTo(BigDecimal.ONE) == 0
                ? BetStatus.VOID    // all legs pushed → full stake return
                : BetStatus.WON;

        log.info("settleOneBet: bet {} → {} effectiveOdds={} payout={}", bet.getId(), finalStatus, effectiveOdds, payout);
        betService.settleBet(bet, finalStatus, payout);
    }

    // ── Market router ─────────────────────────────────────────────────────

    /**
     * Returns one of: WON | LOST | VOID | PUSH | HALF_WON | HALF_LOST
     */
    private String evaluateSelection(BetSelection sel, com.speedbet.api.match.Match match) {
        int h = match.getScoreHome();
        int a = match.getScoreAway();
        String market = sel.getMarket().toUpperCase();

        log.debug("evaluateSelection: sel {} market={} selection='{}' score={}-{}",
                sel.getId(), market, sel.getSelection(), h, a);

        String result = switch (market) {

            case "1X2", "ONE_X_TWO"  -> evaluate1X2(sel.getSelection(), h, a, sel.getId().toString());
            case "HOME_WIN"          -> h > a ? "WON" : "LOST";
            case "AWAY_WIN"          -> a > h ? "WON" : "LOST";

            case "BTTS" -> {
                boolean bothScored = h > 0 && a > 0;
                boolean wantsBoth  = "Yes".equalsIgnoreCase(sel.getSelection());
                String r = wantsBoth == bothScored ? "WON" : "LOST";
                log.debug("evaluateSelection: BTTS sel {} wantsBoth={} bothScored={} → {}", sel.getId(), wantsBoth, bothScored, r);
                yield r;
            }

            case "OVER_UNDER" -> evaluateOverUnder(sel, h + a);

            case "CORRECT_SCORE" -> {
                String expected = h + "-" + a;
                String r = expected.equals(sel.getSelection()) ? "WON" : "LOST";
                log.debug("evaluateSelection: CORRECT_SCORE sel {} expected='{}' got='{}' → {}",
                        sel.getId(), sel.getSelection(), expected, r);
                yield r;
            }

            case "DOUBLE_CHANCE" -> evaluateDoubleChance(sel.getSelection(), h, a, sel.getId().toString());

            case "HALF_TIME"     -> evaluateHalfTime(sel, match);

            case "ASIAN_HANDICAP" -> evaluateAsianHandicap(sel, h, a);

            default -> {
                log.warn("evaluateSelection: unknown market '{}' for sel {} — voiding", sel.getMarket(), sel.getId());
                yield "VOID";
            }
        };

        log.debug("evaluateSelection: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── 1X2 ──────────────────────────────────────────────────────────────

    /**
     * FIX: normalise selection to uppercase before matching.
     *
     * OddsPersistenceService.normalizeSelection() writes "HOME", "DRAW", "AWAY".
     * The original code only matched "Home Win" / "Home" / "Draw" / "Away Win" / "Away"
     * (case-insensitively) — which missed the uppercase persistence-normalised forms,
     * causing every normally placed 1X2 bet to VOID instead of settling.
     *
     * Now accepts: HOME | HOME WIN | DRAW | AWAY | AWAY WIN (any case).
     */
    private String evaluate1X2(String selection, int h, int a, String selId) {
        String s = selection == null ? "" : selection.trim().toUpperCase();
        log.debug("evaluate1X2: sel {} normalised='{}' score={}-{}", selId, s, h, a);

        return switch (s) {
            case "HOME", "HOME WIN" -> {
                String r = h > a ? "WON" : "LOST";
                log.debug("evaluate1X2: sel {} HOME → {}", selId, r);
                yield r;
            }
            case "DRAW" -> {
                String r = h == a ? "WON" : "LOST";
                log.debug("evaluate1X2: sel {} DRAW → {}", selId, r);
                yield r;
            }
            case "AWAY", "AWAY WIN" -> {
                String r = a > h ? "WON" : "LOST";
                log.debug("evaluate1X2: sel {} AWAY → {}", selId, r);
                yield r;
            }
            default -> {
                log.warn("evaluate1X2: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
    }

    // ── Over / Under ─────────────────────────────────────────────────────

    /**
     * Parses the line from the selection string.
     * Accepted formats: "Over 2.5", "Under 1.5", "over2.5", "UNDER 3"
     * Whole-number lines push on exact total (e.g. Over 2 with exactly 2 goals → PUSH).
     */
    private String evaluateOverUnder(BetSelection sel, int totalGoals) {
        String raw    = sel.getSelection().trim().toLowerCase();
        boolean isOver = raw.startsWith("over");

        double line;
        try {
            line = Double.parseDouble(raw.replaceAll("(?i)^(over|under)\\s*", ""));
        } catch (NumberFormatException e) {
            log.warn("evaluateOverUnder: cannot parse line from '{}' for sel {} — VOID",
                    sel.getSelection(), sel.getId());
            return "VOID";
        }

        log.debug("evaluateOverUnder: sel {} isOver={} line={} totalGoals={}", sel.getId(), isOver, line, totalGoals);

        // Whole-number lines can push
        if (line == Math.floor(line) && totalGoals == (int) line) {
            log.debug("evaluateOverUnder: sel {} exact total on whole line — PUSH", sel.getId());
            return "PUSH";
        }

        String r = isOver == (totalGoals > line) ? "WON" : "LOST";
        log.debug("evaluateOverUnder: sel {} → {}", sel.getId(), r);
        return r;
    }

    // ── Double Chance ─────────────────────────────────────────────────────

    private String evaluateDoubleChance(String selection, int h, int a, String selId) {
        String s = selection == null ? "" : selection.trim().toUpperCase();
        String r = switch (s) {
            case "1X" -> h >= a ? "WON" : "LOST";   // home win or draw
            case "X2" -> a >= h ? "WON" : "LOST";   // draw or away win
            case "12" -> h != a ? "WON" : "LOST";   // home win or away win
            default   -> {
                log.warn("evaluateDoubleChance: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
        log.debug("evaluateDoubleChance: sel {} selection='{}' score={}-{} → {}", selId, s, h, a, r);
        return r;
    }

    // ── Half-Time ────────────────────────────────────────────────────────

    /**
     * Half-time scores are stored in match.metadata under keys
     * "score_home_ht" and "score_away_ht" (populated by LiveScorePoller
     * via extractHalfTimeScore() → scores.ht_score from the LiveScore API).
     *
     * If either key is absent the selection is VOID — data not yet received.
     *
     * Selections: "HOME" | "DRAW" | "AWAY"  (same normalised labels as 1X2)
     * Also accepts human aliases: "HOME WIN" | "AWAY WIN"
     */
    private String evaluateHalfTime(BetSelection sel, com.speedbet.api.match.Match match) {
        Integer htHome = extractIntFromMetadata(match, "score_home_ht");
        Integer htAway = extractIntFromMetadata(match, "score_away_ht");

        if (htHome == null || htAway == null) {
            log.warn("evaluateHalfTime: missing ht metadata for match {} sel {} — VOID",
                    match.getId(), sel.getId());
            return "VOID";
        }

        log.debug("evaluateHalfTime: sel {} match {} ht={}-{} selection='{}'",
                sel.getId(), match.getId(), htHome, htAway, sel.getSelection());

        String s = sel.getSelection() == null ? "" : sel.getSelection().trim().toUpperCase();
        String r = switch (s) {
            case "HOME", "HOME WIN" -> htHome > htAway  ? "WON" : "LOST";
            case "DRAW"             -> htHome.equals(htAway) ? "WON" : "LOST";
            case "AWAY", "AWAY WIN" -> htAway > htHome  ? "WON" : "LOST";
            default -> {
                log.warn("evaluateHalfTime: sel {} unrecognised selection '{}' — VOID", sel.getId(), sel.getSelection());
                yield "VOID";
            }
        };

        log.debug("evaluateHalfTime: sel {} → {}", sel.getId(), r);
        return r;
    }

    /**
     * Safely reads an integer value from match.metadata.
     * The poller may store values as Integer, Long, or String — all handled.
     */
    private Integer extractIntFromMetadata(com.speedbet.api.match.Match match, String key) {
        if (match.getMetadata() == null) return null;
        Object val = match.getMetadata().get(key);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            log.warn("extractIntFromMetadata: match {} key '{}' value '{}' is not an int",
                    match.getId(), key, val);
            return null;
        }
    }

    // ── Asian Handicap ────────────────────────────────────────────────────

    /**
     * The selection string encodes both the side and the line, e.g.:
     *   "Home -1.5"   "Away +0.25"   "Home -1"   "Away +0.75"
     *
     * Format: "{Home|Away} {+|-}{line}"
     * A missing sign is treated as positive (e.g. "Home 1.5" → +1.5).
     *
     * Returns: WON | LOST | PUSH | HALF_WON | HALF_LOST
     *
     * Line classification:
     *   x.0          whole line  — push possible on exact result
     *   x.5          half line   — no push; strictly win or lose
     *   x.25 / x.75  quarter line — stake split 50/50 across two adjacent sub-lines
     *
     * FIX: all fractional comparisons now use epsilon (< 0.01) instead of
     * exact == equality to guard against floating-point parse drift.
     */
    private String evaluateAsianHandicap(BetSelection sel, int h, int a) {
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
            log.warn("evaluateAsianHandicap: sel {} cannot parse side from '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        // ── Parse line ────────────────────────────────────────────────────
        double line;
        try {
            line = Double.parseDouble(rest);
        } catch (NumberFormatException e) {
            log.warn("evaluateAsianHandicap: sel {} cannot parse line from '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        // ── Normalise to bettor's perspective ─────────────────────────────
        // gd           = net goals in favour of the side being backed
        // effectiveLine = handicap applied from that same side's perspective
        int    gd           = bettingHome ? (h - a) : (a - h);
        double effectiveLine = bettingHome ? line : -line;

        log.debug("evaluateAsianHandicap: sel {} side={} line={} score={}-{} gd={} effectiveLine={}",
                sel.getId(), bettingHome ? "HOME" : "AWAY", line, h, a, gd, effectiveLine);

        // ── FIX: use epsilon comparisons for line classification ──────────
        double frac = Math.abs(effectiveLine % 1);

        String result;
        if (frac < 0.01) {
            // Whole line: 0, ±1, ±2 …
            result = evaluateWholeHandicap(gd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.5) < 0.01) {
            // Half line: ±0.5, ±1.5, ±2.5 …
            result = evaluateHalfHandicap(gd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.25) < 0.01 || Math.abs(frac - 0.75) < 0.01) {
            // Quarter line: ±0.25, ±0.75, ±1.25, ±1.75 …
            result = evaluateQuarterHandicap(effectiveLine, gd, sel.getId().toString());
        } else {
            log.warn("evaluateAsianHandicap: sel {} unrecognised frac={} for line={} — VOID",
                    sel.getId(), frac, line);
            result = "VOID";
        }

        log.debug("evaluateAsianHandicap: sel {} → {}", sel.getId(), result);
        return result;
    }

    /**
     * Half line: x.5 — no push possible.
     * Win if adjusted GD > 0, Lose if ≤ 0.
     */
    private String evaluateHalfHandicap(double adjustedGD, String selId) {
        String r = adjustedGD > 0 ? "WON" : "LOST";
        log.debug("evaluateHalfHandicap: sel {} adjustedGD={} → {}", selId, adjustedGD, r);
        return r;
    }

    /**
     * Whole line: x.0
     * Win  if adjustedGD > 0
     * Push if adjustedGD == 0   (stake refunded)
     * Lose if adjustedGD < 0
     */
    private String evaluateWholeHandicap(double adjustedGD, String selId) {
        String r;
        if      (adjustedGD > 0)  r = "WON";
        else if (adjustedGD == 0) r = "PUSH";
        else                      r = "LOST";
        log.debug("evaluateWholeHandicap: sel {} adjustedGD={} → {}", selId, adjustedGD, r);
        return r;
    }

    /**
     * Quarter line: x.25 or x.75 — stake split 50/50 across two adjacent sub-lines.
     *
     * x.25 splits into: x.0 (whole) + x.5 (half)  — lower and upper
     * x.75 splits into: x.5 (half)  + x.0+0.5 (next half) — lower and upper
     *
     * Combined outcomes:
     *   Both win               → FULL WIN
     *   One win,   one push    → HALF_WON
     *   Both push              → PUSH
     *   One lose,  one push    → HALF_LOST
     *   One win,   one lose    → PUSH  (wash — net zero)
     *   Both lose              → FULL LOST
     */
    private String evaluateQuarterHandicap(double effectiveLine, int gd, String selId) {
        double lower = effectiveLine - 0.25;
        double upper = effectiveLine + 0.25;

        log.debug("evaluateQuarterHandicap: sel {} effectiveLine={} gd={} sub-lines=[{}, {}]",
                selId, effectiveLine, gd, lower, upper);

        String lowerResult = settleSingleLine(lower, gd, selId);
        String upperResult = settleSingleLine(upper, gd, selId);

        log.debug("evaluateQuarterHandicap: sel {} lower={} upper={}", selId, lowerResult, upperResult);

        String combined = combineQuarterSubResults(lowerResult, upperResult);
        log.debug("evaluateQuarterHandicap: sel {} combined → {}", selId, combined);
        return combined;
    }

    /** Evaluates a single sub-line (half or whole) for a given goal difference. */
    private String settleSingleLine(double subLine, int gd, String selId) {
        double adjGD = gd + subLine;
        double frac  = Math.abs(subLine % 1);

        if (frac < 0.01)                   return evaluateWholeHandicap(adjGD, selId + "-whole");
        if (Math.abs(frac - 0.5) < 0.01)  return evaluateHalfHandicap(adjGD,  selId + "-half");

        log.warn("settleSingleLine: sel {} unexpected frac={} for subLine={} — VOID", selId, frac, subLine);
        return "VOID";
    }

    private String combineQuarterSubResults(String lower, String upper) {
        if (lower.equals(upper)) return lower;  // WON+WON, LOST+LOST, PUSH+PUSH

        if (isWon(lower)  && isPush(upper)) return "HALF_WON";
        if (isPush(lower) && isWon(upper))  return "HALF_WON";

        if (isLost(lower) && isPush(upper)) return "HALF_LOST";
        if (isPush(lower) && isLost(upper)) return "HALF_LOST";

        // Win + Loss = net wash
        if ((isWon(lower) && isLost(upper)) || (isLost(lower) && isWon(upper))) return "PUSH";

        log.warn("combineQuarterSubResults: unexpected combination lower={} upper={} — VOID", lower, upper);
        return "VOID";
    }

    private boolean isWon(String r)  { return "WON".equals(r); }
    private boolean isLost(String r) { return "LOST".equals(r); }
    private boolean isPush(String r) { return "PUSH".equals(r); }
}
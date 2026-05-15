package com.speedbet.api.bet;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.NbaMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

/**
 * NBASettlementEngine — settles bets for all NBA-specific markets:
 *
 *   Markets handled:
 *   ┌─────────────────┬───────────────────────────────────────────────────────────┐
 *   │ moneyline       │ Full-game: Home Win / Away Win (no draw in NBA)           │
 *   │ point_spread    │ Handicap lines — half/whole; push on whole-number lines   │
 *   │ game_total      │ Over/Under total points — half/whole lines                │
 *   │ winning_margin  │ 10 buckets: team + points-range (1-5, 6-10, … 21+)       │
 *   │ overtime        │ Yes / No — did the game go to OT?                         │
 *   │ q1_leader       │ Which team leads after Q1                                 │
 *   │ halftime_leader │ Which team leads at half time                             │
 *   │ q3_leader       │ Which team leads after Q3                                 │
 *   │ q1_total        │ Over/Under for Q1 points total                            │
 *   │ q2_total        │ Over/Under for Q2 points total                            │
 *   │ q3_total        │ Over/Under for Q3 points total                            │
 *   │ q4_total        │ Over/Under for Q4 points total                            │
 *   └─────────────────┴───────────────────────────────────────────────────────────┘
 *
 *  Selection key conventions (from nba_odds_display_guide):
 *   moneyline      : "HOME" | "AWAY"
 *   point_spread   : "HOME:-5.5" | "AWAY:+5.5" | "HOME:-6" | "PUSH:-6/+6" | "AWAY:+6"
 *   game_total     : "Over:224.5" | "Under:224.5" | "Over:224" | "Push/Refund:224" | "Under:224"
 *   winning_margin : "{TeamName} by 1-5" | "… by 6-10" | "… by 11-15" | "… by 16-20" | "… by 21+"
 *   overtime       : "Yes" | "No"
 *   q1/ht/q3_leader: "HOME" | "AWAY"
 *   q*_total       : same format as game_total — "Over:{line}" | "Under:{line}" | "Push/Refund:{line}"
 *
 *  Metadata keys expected on the Match object:
 *   score_home_q1, score_away_q1  — Q1 points
 *   score_home_q2, score_away_q2  — Q2 points
 *   score_home_q3, score_away_q3  — Q3 points
 *   score_home_q4, score_away_q4  — Q4 points (reg)
 *   score_home_ht, score_away_ht  — halftime cumulative points (Q1+Q2)
 *   went_to_overtime               — "true"/"false" string or boolean
 *
 *  Partial / push settlement:
 *   Whole-number spread and total lines push on exact result.
 *   When a leg is PUSH or VOID its locked odds are divided out of the
 *   parlay total; the remaining legs settle at their true combined odds.
 *   A single outright LOST leg kills the entire accumulator.
 *
 *  Odds format: always decimal to 2 d.p. as per display guide (e.g. 1.91, 2.05, 14.00).
 *
 *  Match sourcing: uses NbaMatchService.getUnsettledFinished() and
 *  NbaMatchService.markSettled() — scoped to sport="basketball" so
 *  football/NFL/MMA rows are never touched by this engine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NBASettlementEngine {

    private final NbaMatchService nbaMatchService;
    private final BetService      betService;

    // ── Scheduled runner ──────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        var finishedMatches = nbaMatchService.getUnsettledFinished();
        log.info("NBA settlement run: {} finished match(es) to process", finishedMatches.size());

        for (var match : finishedMatches) {
            try {
                log.info("NBA settlement run: processing match {} ({} vs {})",
                        match.getId(), match.getHomeTeam(), match.getAwayTeam());
                settleMatch(match);
                nbaMatchService.markSettled(match.getId().toString());
                log.info("NBA settlement run: match {} marked settled", match.getId());
            } catch (Exception e) {
                log.error("NBA settlement run: FAILED for match {} — {}", match.getId(), e.getMessage(), e);
            }
        }

        log.info("NBA settlement run: complete");
    }

    // ── Match-level settlement ─────────────────────────────────────────────────

    @Transactional
    public void settleMatch(Match match) {
        if (match.getScoreHome() == null || match.getScoreAway() == null) {
            log.warn("settleMatch: match {} has null final score(s) — skipping", match.getId());
            return;
        }

        int h = match.getScoreHome();
        int a = match.getScoreAway();
        log.info("settleMatch: match {} final score {}-{}", match.getId(), h, a);

        // Log quarter metadata availability upfront
        logMetadataAvailability(match);

        var pendingBets = betService.getPendingBetsForMatch(match.getId());
        log.info("settleMatch: {} pending bet(s) for match {}", pendingBets.size(), match.getId());

        int won = 0, lost = 0, voided = 0, other = 0;
        for (var bet : pendingBets) {
            BetStatus before = bet.getStatus();
            settleOneBet(bet, match);
            BetStatus after = bet.getStatus();
            if      (BetStatus.WON.equals(after))  won++;
            else if (BetStatus.LOST.equals(after)) lost++;
            else if (BetStatus.VOID.equals(after)) voided++;
            else                                   other++;
            if (!before.equals(after)) {
                log.debug("settleMatch: bet {} {} → {}", bet.getId(), before, after);
            }
        }
        log.info("settleMatch: match {} done — WON={} LOST={} VOID={} other={}",
                match.getId(), won, lost, voided, other);
    }

    private void logMetadataAvailability(Match match) {
        List<String> quarterKeys = Arrays.asList(
                "score_home_q1", "score_away_q1",
                "score_home_q2", "score_away_q2",
                "score_home_q3", "score_away_q3",
                "score_home_q4", "score_away_q4",
                "score_home_ht", "score_away_ht",
                "went_to_overtime"
        );
        for (String key : quarterKeys) {
            Object val = extractFromMetadata(match, key);
            if (val == null) {
                log.warn("settleMatch: match {} — metadata key '{}' is MISSING; dependent bets will VOID",
                        match.getId(), key);
            } else {
                log.debug("settleMatch: match {} metadata '{}' = {}", match.getId(), key, val);
            }
        }
    }

    // ── Bet-level settlement ───────────────────────────────────────────────────

    private void settleOneBet(Bet bet, Match match) {
        log.debug("settleOneBet: bet {} stake={} totalOdds={} selections={}",
                bet.getId(), bet.getStake(), bet.getTotalOdds(), bet.getSelections().size());

        boolean hasFullLoss = false;
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
                case "WON" ->
                    log.debug("settleOneBet: bet {} sel {} WON — no odds adjustment", bet.getId(), sel.getId());

                default ->
                    log.warn("settleOneBet: unknown result '{}' for sel {} — treating as VOID", result, sel.getId());
            }
        }

        if (hasFullLoss) {
            log.info("settleOneBet: bet {} LOST (at least one outright losing leg)", bet.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return;
        }

        boolean allSettled = bet.getSelections().stream()
                .noneMatch(s -> "PENDING".equals(s.getResult()));
        if (!allSettled) {
            log.debug("settleOneBet: bet {} — not all legs settled yet, deferring payout", bet.getId());
            return;
        }

        BigDecimal effectiveOdds = bet.getTotalOdds()
                .multiply(oddsAdjustment, MathContext.DECIMAL64);

        log.debug("settleOneBet: bet {} totalOdds={} × oddsAdjustment={} = effectiveOdds={}",
                bet.getId(), bet.getTotalOdds(), oddsAdjustment, effectiveOdds);

        if (effectiveOdds.compareTo(BigDecimal.ONE) < 0) {
            log.warn("settleOneBet: bet {} effectiveOdds {} < 1.0 — clamping to 1.0 (stake return)",
                    bet.getId(), effectiveOdds);
            effectiveOdds = BigDecimal.ONE;
        }

        BigDecimal payout = bet.getStake()
                .multiply(effectiveOdds, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);

        BetStatus finalStatus = effectiveOdds.compareTo(BigDecimal.ONE) == 0
                ? BetStatus.VOID   // all legs pushed → full stake return
                : BetStatus.WON;

        log.info("settleOneBet: bet {} → {} effectiveOdds={} payout={}",
                bet.getId(), finalStatus, effectiveOdds, payout);
        betService.settleBet(bet, finalStatus, payout);
    }

    // ── Market router ──────────────────────────────────────────────────────────

    /**
     * Returns one of: WON | LOST | VOID | PUSH
     */
    private String evaluateSelection(BetSelection sel, Match match) {
        int h = match.getScoreHome();
        int a = match.getScoreAway();
        String market = sel.getMarket() == null ? "" : sel.getMarket().toLowerCase().trim();

        log.debug("evaluateSelection: sel {} market='{}' selection='{}' finalScore={}-{}",
                sel.getId(), market, sel.getSelection(), h, a);

        String result = switch (market) {

            case "moneyline"       -> evaluateMoneyline(sel, h, a);

            case "point_spread"    -> evaluatePointSpread(sel, h, a);

            case "game_total"      -> evaluateTotal(sel, h + a, "game_total");

            case "winning_margin"  -> evaluateWinningMargin(sel, match);

            case "overtime"        -> evaluateOvertime(sel, match);

            case "q1_leader"       -> evaluatePeriodLeader(sel, match, "q1");
            case "halftime_leader" -> evaluatePeriodLeader(sel, match, "ht");
            case "q3_leader"       -> evaluatePeriodLeader(sel, match, "q3");

            case "q1_total"        -> evaluateQuarterTotal(sel, match, 1);
            case "q2_total"        -> evaluateQuarterTotal(sel, match, 2);
            case "q3_total"        -> evaluateQuarterTotal(sel, match, 3);
            case "q4_total"        -> evaluateQuarterTotal(sel, match, 4);

            default -> {
                log.warn("evaluateSelection: unknown NBA market '{}' for sel {} — VOID",
                        sel.getMarket(), sel.getId());
                yield "VOID";
            }
        };

        log.debug("evaluateSelection: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── moneyline ─────────────────────────────────────────────────────────────

    /**
     * Selection keys: HOME | AWAY
     * NBA has no draw — one side always wins (OT if necessary).
     * Normalised to uppercase before matching.
     */
    private String evaluateMoneyline(BetSelection sel, int h, int a) {
        String s = normalise(sel.getSelection());
        log.debug("evaluateMoneyline: sel {} normalised='{}' score={}-{}", sel.getId(), s, h, a);

        String result = switch (s) {
            case "HOME" -> h > a ? "WON" : "LOST";
            case "AWAY" -> a > h ? "WON" : "LOST";
            default -> {
                log.warn("evaluateMoneyline: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), sel.getSelection());
                yield "VOID";
            }
        };

        log.debug("evaluateMoneyline: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── point_spread ──────────────────────────────────────────────────────────

    /**
     * Selection key format:
     *   "HOME:-5.5"    — home team -5.5 (half line)
     *   "AWAY:+5.5"    — away team +5.5 (half line)
     *   "HOME:-6"      — home team -6   (whole line)
     *   "AWAY:+6"      — away team +6   (whole line)
     *   "PUSH:-6/+6"   — push/refund selection (pre-settled as PUSH)
     *
     * Normalised goal difference (gd) is from the perspective of the backed side:
     *   HOME: gd = h - a
     *   AWAY: gd = a - h
     *
     * Settlement:
     *   gd + line > 0  → WON
     *   gd + line = 0  → PUSH  (whole lines only; half lines cannot push)
     *   gd + line < 0  → LOST
     */
    private String evaluatePointSpread(BetSelection sel, int h, int a) {
        String raw = sel.getSelection() == null ? "" : sel.getSelection().trim();

        // Push/Refund selections — always settle as PUSH (odds divided out)
        if (raw.toUpperCase().startsWith("PUSH")) {
            log.debug("evaluatePointSpread: sel {} is PUSH selection → PUSH", sel.getId());
            return "PUSH";
        }

        // Parse "SIDE:line"
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            log.warn("evaluatePointSpread: sel {} cannot parse '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        String side = parts[0].trim().toUpperCase();
        double line;
        try {
            line = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException e) {
            log.warn("evaluatePointSpread: sel {} cannot parse line from '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        int gd = switch (side) {
            case "HOME" -> h - a;
            case "AWAY" -> a - h;
            default -> {
                log.warn("evaluatePointSpread: sel {} unrecognised side '{}' — VOID", sel.getId(), side);
                yield Integer.MIN_VALUE;
            }
        };
        if (gd == Integer.MIN_VALUE) return "VOID";

        double adjustedGD = gd + line;
        log.debug("evaluatePointSpread: sel {} side={} line={} gd={} adjustedGD={}",
                sel.getId(), side, line, gd, adjustedGD);

        String result;
        double frac = Math.abs(line % 1);
        boolean isHalfLine = Math.abs(frac - 0.5) < 0.01;

        if (adjustedGD > 0) {
            result = "WON";
        } else if (adjustedGD < 0) {
            result = "LOST";
        } else {
            // adjustedGD == 0
            result = isHalfLine ? "LOST" : "PUSH";  // half lines cannot produce 0 exactly, but guard anyway
        }

        log.debug("evaluatePointSpread: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── game_total / quarter totals shared logic ───────────────────────────────

    /**
     * Selection key format:
     *   "Over:224.5"       — over half line
     *   "Under:224.5"      — under half line
     *   "Over:224"         — over whole line
     *   "Under:224"        — under whole line
     *   "Push/Refund:224"  — push/refund selection
     *
     * Whole-number lines push on exact total.
     */
    private String evaluateTotal(BetSelection sel, int totalPoints, String marketLabel) {
        String raw = sel.getSelection() == null ? "" : sel.getSelection().trim();

        // Push/Refund selection
        if (raw.toLowerCase().startsWith("push")) {
            log.debug("evaluateTotal: sel {} [{}] is Push/Refund selection → PUSH", sel.getId(), marketLabel);
            return "PUSH";
        }

        String lower = raw.toLowerCase();
        boolean isOver;
        String lineStr;
        if (lower.startsWith("over:")) {
            isOver  = true;
            lineStr = raw.substring(5).trim();
        } else if (lower.startsWith("under:")) {
            isOver  = false;
            lineStr = raw.substring(6).trim();
        } else {
            log.warn("evaluateTotal: sel {} [{}] cannot parse direction from '{}' — VOID",
                    sel.getId(), marketLabel, raw);
            return "VOID";
        }

        double line;
        try {
            line = Double.parseDouble(lineStr);
        } catch (NumberFormatException e) {
            log.warn("evaluateTotal: sel {} [{}] cannot parse line '{}' — VOID",
                    sel.getId(), marketLabel, lineStr);
            return "VOID";
        }

        log.debug("evaluateTotal: sel {} [{}] isOver={} line={} totalPoints={}",
                sel.getId(), marketLabel, isOver, line, totalPoints);

        // Whole-number line — push on exact total
        double frac = Math.abs(line % 1);
        if (frac < 0.01 && totalPoints == (int) line) {
            log.debug("evaluateTotal: sel {} [{}] exact total on whole line → PUSH", sel.getId(), marketLabel);
            return "PUSH";
        }

        String result = (isOver == (totalPoints > line)) ? "WON" : "LOST";
        log.debug("evaluateTotal: sel {} [{}] → {}", sel.getId(), marketLabel, result);
        return result;
    }

    // ── winning_margin ────────────────────────────────────────────────────────

    /**
     * Selection key format: "{TeamName} by {range}"
     *   Ranges: 1-5 | 6-10 | 11-15 | 16-20 | 21+
     *
     * The team name in the selection must match match.getHomeTeam() or match.getAwayTeam()
     * (case-insensitive, trimmed).
     *
     * Settlement:
     *   1. Determine winner from final score.
     *   2. Determine point margin.
     *   3. Match team name + margin bucket.
     */
    private String evaluateWinningMargin(BetSelection sel, Match match) {
        int h = match.getScoreHome();
        int a = match.getScoreAway();
        String raw = sel.getSelection() == null ? "" : sel.getSelection().trim();

        // Split on " by " (case-insensitive)
        String[] parts = raw.split("(?i)\\s+by\\s+", 2);
        if (parts.length != 2) {
            log.warn("evaluateWinningMargin: sel {} cannot parse '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        String selectedTeam = parts[0].trim();
        String selectedRange = parts[1].trim();

        // Identify actual winner and margin
        String winningTeam;
        int margin;
        if (h > a) {
            winningTeam = match.getHomeTeam();
            margin = h - a;
        } else if (a > h) {
            winningTeam = match.getAwayTeam();
            margin = a - h;
        } else {
            // NBA games cannot end in a draw (OT keeps going), but guard anyway
            log.warn("evaluateWinningMargin: sel {} match {} ended in a tie — VOID", sel.getId(), match.getId());
            return "VOID";
        }

        log.debug("evaluateWinningMargin: sel {} selectedTeam='{}' selectedRange='{}' winner='{}' margin={}",
                sel.getId(), selectedTeam, selectedRange, winningTeam, margin);

        // Team must match
        if (!selectedTeam.equalsIgnoreCase(winningTeam)) {
            log.debug("evaluateWinningMargin: sel {} wrong team → LOST", sel.getId());
            return "LOST";
        }

        // Margin bucket must match
        boolean inRange = switch (selectedRange) {
            case "1-5"   -> margin >= 1  && margin <= 5;
            case "6-10"  -> margin >= 6  && margin <= 10;
            case "11-15" -> margin >= 11 && margin <= 15;
            case "16-20" -> margin >= 16 && margin <= 20;
            case "21+"   -> margin >= 21;
            default -> {
                log.warn("evaluateWinningMargin: sel {} unrecognised range '{}' — VOID",
                        sel.getId(), selectedRange);
                yield false;
            }
        };

        // Distinguish VOID (unrecognised range) from LOST (wrong range)
        if (!inRange && !isRecognisedMarginRange(selectedRange)) return "VOID";

        String result = inRange ? "WON" : "LOST";
        log.debug("evaluateWinningMargin: sel {} → {}", sel.getId(), result);
        return result;
    }

    private boolean isRecognisedMarginRange(String range) {
        return switch (range) {
            case "1-5", "6-10", "11-15", "16-20", "21+" -> true;
            default -> false;
        };
    }

    // ── overtime ──────────────────────────────────────────────────────────────

    /**
     * Selection keys: "Yes" | "No"
     * Reads "went_to_overtime" from match metadata (stored as "true"/"false" string or Boolean).
     */
    private String evaluateOvertime(BetSelection sel, Match match) {
        Object rawOt = extractFromMetadata(match, "went_to_overtime");
        if (rawOt == null) {
            log.warn("evaluateOvertime: sel {} match {} — 'went_to_overtime' metadata missing → VOID",
                    sel.getId(), match.getId());
            return "VOID";
        }

        boolean wentToOT = Boolean.parseBoolean(rawOt.toString().trim());
        String selection  = sel.getSelection() == null ? "" : sel.getSelection().trim();

        log.debug("evaluateOvertime: sel {} selection='{}' wentToOT={}", sel.getId(), selection, wentToOT);

        String result = switch (selection) {
            case "Yes" ->  wentToOT ? "WON" : "LOST";
            case "No"  -> !wentToOT ? "WON" : "LOST";
            default -> {
                log.warn("evaluateOvertime: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), selection);
                yield "VOID";
            }
        };

        log.debug("evaluateOvertime: sel {} → {}", sel.getId(), result);
        return result;
    }

    // ── period leaders ────────────────────────────────────────────────────────

    /**
     * Selection keys: HOME | AWAY
     * periodKey: "q1" | "ht" | "q3"
     *
     * Metadata keys (cumulative points at end of period):
     *   q1 → score_home_q1 / score_away_q1
     *   ht → score_home_ht / score_away_ht   (= Q1+Q2 cumulative)
     *   q3 → score_home_q3 / score_away_q3   (= Q1+Q2+Q3 cumulative)
     *
     * If metadata is absent → VOID.
     * NBA periods cannot end in a draw in practice, but if they do the bet is LOST for both sides.
     */
    private String evaluatePeriodLeader(BetSelection sel, Match match, String periodKey) {
        String homeKey = "score_home_" + periodKey;
        String awayKey = "score_away_" + periodKey;

        Integer periodHome = extractIntFromMetadata(match, homeKey);
        Integer periodAway = extractIntFromMetadata(match, awayKey);

        if (periodHome == null || periodAway == null) {
            log.warn("evaluatePeriodLeader: sel {} match {} — '{}' or '{}' metadata missing → VOID",
                    sel.getId(), match.getId(), homeKey, awayKey);
            return "VOID";
        }

        String s = normalise(sel.getSelection());
        log.debug("evaluatePeriodLeader: sel {} period={} home={} away={} selection='{}'",
                sel.getId(), periodKey, periodHome, periodAway, s);

        String result = switch (s) {
            case "HOME" -> periodHome > periodAway ? "WON" : "LOST";
            case "AWAY" -> periodAway > periodHome ? "WON" : "LOST";
            default -> {
                log.warn("evaluatePeriodLeader: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), sel.getSelection());
                yield "VOID";
            }
        };

        log.debug("evaluatePeriodLeader: sel {} period={} → {}", sel.getId(), periodKey, result);
        return result;
    }

    // ── quarter totals ─────────────────────────────────────────────────────────

    /**
     * Resolves the points scored in a specific quarter (not cumulative) and delegates to evaluateTotal.
     *
     * Quarter points are derived from cumulative metadata:
     *   Q1 points = score_*_q1
     *   Q2 points = score_*_ht  − score_*_q1
     *   Q3 points = score_*_q3  − score_*_ht
     *   Q4 points = final score − score_*_q3
     *
     * If any required metadata key is absent → VOID.
     */
    private String evaluateQuarterTotal(BetSelection sel, Match match, int quarter) {
        Integer totalPoints = resolveQuarterTotal(match, quarter, sel.getId().toString());
        if (totalPoints == null) return "VOID";

        log.debug("evaluateQuarterTotal: sel {} Q{} totalPoints={}", sel.getId(), quarter, totalPoints);
        return evaluateTotal(sel, totalPoints, "q" + quarter + "_total");
    }

    /**
     * Returns combined home+away points for the given quarter, or null if metadata is missing.
     */
    private Integer resolveQuarterTotal(Match match, int quarter, String selId) {
        Integer hQ1 = extractIntFromMetadata(match, "score_home_q1");
        Integer aQ1 = extractIntFromMetadata(match, "score_away_q1");
        Integer hHT = extractIntFromMetadata(match, "score_home_ht");
        Integer aHT = extractIntFromMetadata(match, "score_away_ht");
        Integer hQ3 = extractIntFromMetadata(match, "score_home_q3");
        Integer aQ3 = extractIntFromMetadata(match, "score_away_q3");
        int    hFin = match.getScoreHome();
        int    aFin = match.getScoreAway();

        return switch (quarter) {
            case 1 -> {
                if (hQ1 == null || aQ1 == null) {
                    log.warn("resolveQuarterTotal: sel {} Q1 metadata missing → VOID", selId);
                    yield null;
                }
                yield hQ1 + aQ1;
            }
            case 2 -> {
                if (hQ1 == null || aQ1 == null || hHT == null || aHT == null) {
                    log.warn("resolveQuarterTotal: sel {} Q2 metadata missing → VOID", selId);
                    yield null;
                }
                yield (hHT - hQ1) + (aHT - aQ1);
            }
            case 3 -> {
                if (hHT == null || aHT == null || hQ3 == null || aQ3 == null) {
                    log.warn("resolveQuarterTotal: sel {} Q3 metadata missing → VOID", selId);
                    yield null;
                }
                yield (hQ3 - hHT) + (aQ3 - aHT);
            }
            case 4 -> {
                if (hQ3 == null || aQ3 == null) {
                    log.warn("resolveQuarterTotal: sel {} Q4 metadata missing → VOID", selId);
                    yield null;
                }
                // Q4 includes any OT points if OT occurred — this is intentional per display guide
                yield (hFin - hQ3) + (aFin - aQ3);
            }
            default -> {
                log.warn("resolveQuarterTotal: sel {} invalid quarter {} — VOID", selId, quarter);
                yield null;
            }
        };
    }

    // ── Metadata helpers ───────────────────────────────────────────────────────

    /**
     * Reads an Integer from match.metadata. Handles Integer, Long, and String values.
     */
    private Integer extractIntFromMetadata(Match match, String key) {
        Object val = extractFromMetadata(match, key);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("extractIntFromMetadata: match {} key '{}' value '{}' is not an int",
                    match.getId(), key, val);
            return null;
        }
    }

    /**
     * Raw metadata accessor — returns null if map is null or key is absent.
     */
    private Object extractFromMetadata(Match match, String key) {
        if (match.getMetadata() == null) return null;
        return match.getMetadata().get(key);
    }

    // ── String helpers ─────────────────────────────────────────────────────────

    /** Null-safe trim + toUpperCase. */
    private String normalise(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
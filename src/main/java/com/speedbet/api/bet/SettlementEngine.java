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
import java.util.List;
import java.util.UUID;

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

                List<Bet> lateBets = betService.getPendingBetsForMatch(match.getId());
                if (!lateBets.isEmpty()) {
                    log.warn("Settlement run: {} late bet(s) found after settling match {} — processing now",
                            lateBets.size(), match.getId());
                    for (var bet : lateBets) {
                        try {
                            settleOneBet(bet, match);
                        } catch (Exception e) {
                            log.error("Settlement run: FAILED late bet {} for match {} — {}",
                                    bet.getId(), match.getId(), e.getMessage(), e);
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Settlement run: FAILED for match {} — {}", match.getId(), e.getMessage(), e);
            }
        }

        log.info("Settlement run: complete");
    }

    // ── Orphan recovery ───────────────────────────────────────────────────

    @Scheduled(fixedDelay = 120_000)
    public void runOrphanedBetRecovery() {
        log.info("Orphan recovery: scanning for pending bets on already-settled matches");

        var settledMatches = matchService.getSettledFinished();
        if (settledMatches.isEmpty()) {
            log.info("Orphan recovery: no settled matches found");
            return;
        }

        int recovered = 0;
        for (var match : settledMatches) {
            if (match.getScoreHome() == null || match.getScoreAway() == null) {
                log.warn("Orphan recovery: match {} has null score(s) — skipping", match.getId());
                continue;
            }

            List<Bet> orphans = betService.getPendingBetsForMatch(match.getId());
            if (orphans.isEmpty()) continue;

            log.warn("Orphan recovery: {} pending bet(s) found on already-settled match {} ({} vs {})",
                    orphans.size(), match.getId(), match.getHomeTeam(), match.getAwayTeam());

            for (var bet : orphans) {
                try {
                    settleOneBet(bet, match);
                    recovered++;
                    log.info("Orphan recovery: settled bet {} for match {}", bet.getId(), match.getId());
                } catch (Exception e) {
                    log.error("Orphan recovery: FAILED for bet {} match {} — {}",
                            bet.getId(), match.getId(), e.getMessage(), e);
                }
            }
        }

        log.info("Orphan recovery: complete — {} orphaned bet(s) recovered", recovered);
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

        Integer htHome = extractIntFromMetadata(match, "score_home_ht");
        Integer htAway = extractIntFromMetadata(match, "score_away_ht");
        if (htHome != null && htAway != null) {
            log.info("settleMatch: match {} half-time score {}-{}", match.getId(), htHome, htAway);
        } else {
            log.warn("settleMatch: match {} has no half-time metadata — HALF_TIME bets will VOID", match.getId());
        }

        var pendingBets = betService.getPendingBetsForMatch(match.getId());
        log.info("settleMatch: {} pending bet(s) found for match {}", pendingBets.size(), match.getId());

        int won = 0, lost = 0, voided = 0, deferred = 0;
        for (var bet : pendingBets) {
            BetStatus before = bet.getStatus();
            settleOneBet(bet, match);
            BetStatus after = bet.getStatus();
            if      (BetStatus.WON.equals(after))  won++;
            else if (BetStatus.LOST.equals(after)) lost++;
            else if (BetStatus.VOID.equals(after)) voided++;
            else                                   deferred++;
            if (!before.equals(after)) {
                log.debug("settleMatch: bet {} {} → {}", bet.getId(), before, after);
            }
        }
        log.info("settleMatch: match {} done — WON={} LOST={} VOID={} DEFERRED={}",
                match.getId(), won, lost, voided, deferred);
    }

    // ── Bet-level settlement ──────────────────────────────────────────────

    private void settleOneBet(Bet bet, com.speedbet.api.match.Match match) {
        log.debug("settleOneBet: bet {} stake={} totalOdds={} selections={}",
                bet.getId(), bet.getStake(), bet.getTotalOdds(), bet.getSelections().size());

        boolean hasFullLoss = false;

        // Step 1: evaluate selections belonging to the current match that are still PENDING
        for (var sel : bet.getSelections()) {
            if (!sel.getMatchId().equals(match.getId())) continue;
            if (!"PENDING".equals(sel.getResult())) continue;

            String result = evaluateSelection(sel, match);
            sel.setResult(result);

            log.info("settleOneBet: bet {} sel {} market={} selection='{}' oddsLocked={} → {}",
                    bet.getId(), sel.getId(), sel.getMarket(), sel.getSelection(),
                    sel.getOddsLocked(), result);

            if ("LOST".equals(result)) {
                hasFullLoss = true;
            }
        }

        // A losing leg ends the bet immediately
        if (hasFullLoss) {
            log.info("settleOneBet: bet {} LOST (outright losing leg on match {})", bet.getId(), match.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return;
        }

        // Step 2: check if any legs are still outstanding on unfinished matches
        boolean allSettled = bet.getSelections().stream().noneMatch(s -> {
            if (!"PENDING".equals(s.getResult())) return false;
            if (s.getMatchId().equals(match.getId())) return false;
            return !isMatchSettled(s.getMatchId());
        });

        if (!allSettled) {
            log.debug("settleOneBet: bet {} — outstanding legs remain, saving progress and deferring", bet.getId());
            betService.saveSelectionsOnly(bet);
            return;
        }

        // Step 3: all legs resolved — calculate effective odds across every selection
        BigDecimal oddsAdjustment = BigDecimal.ONE;
        for (var sel : bet.getSelections()) {
            switch (sel.getResult()) {
                case "WON"  -> {}
                case "LOST" -> hasFullLoss = true;
                case "VOID", "PUSH" -> oddsAdjustment = oddsAdjustment
                        .divide(sel.getOddsLocked(), MathContext.DECIMAL64);
                case "HALF_WON" -> {
                    BigDecimal halfWinMultiplier = sel.getOddsLocked()
                            .add(BigDecimal.ONE)
                            .divide(new BigDecimal("2"), MathContext.DECIMAL64);
                    oddsAdjustment = oddsAdjustment
                            .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                            .multiply(halfWinMultiplier, MathContext.DECIMAL64);
                }
                case "HALF_LOST" -> oddsAdjustment = oddsAdjustment
                        .divide(sel.getOddsLocked(), MathContext.DECIMAL64)
                        .multiply(new BigDecimal("0.5"), MathContext.DECIMAL64);
                default -> log.warn("settleOneBet: unrecognised result '{}' on sel {} — treating as VOID",
                        sel.getResult(), sel.getId());
            }
        }

        if (hasFullLoss) {
            log.info("settleOneBet: bet {} LOST (losing leg found in final sweep)", bet.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return;
        }

        BigDecimal effectiveOdds = bet.getTotalOdds()
                .multiply(oddsAdjustment, MathContext.DECIMAL64);

        log.debug("settleOneBet: bet {} totalOdds={} × oddsAdjustment={} = effectiveOdds={}",
                bet.getId(), bet.getTotalOdds(), oddsAdjustment, effectiveOdds);

        if (effectiveOdds.compareTo(BigDecimal.ONE) < 0) {
            log.warn("settleOneBet: bet {} effectiveOdds {} < 1.0 — clamping to 1.0", bet.getId(), effectiveOdds);
            effectiveOdds = BigDecimal.ONE;
        }

        BigDecimal payout = bet.getStake()
                .multiply(effectiveOdds, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);

        BetStatus finalStatus = effectiveOdds.compareTo(BigDecimal.ONE) == 0
                ? BetStatus.VOID
                : BetStatus.WON;

        log.info("settleOneBet: bet {} → {} effectiveOdds={} payout={}",
                bet.getId(), finalStatus, effectiveOdds, payout);
        betService.settleBet(bet, finalStatus, payout);
    }

    // ── Helper: check if a match is already fully settled ─────────────────

    private boolean isMatchSettled(UUID matchId) {
        try {
            com.speedbet.api.match.Match m = matchService.getById(matchId.toString());
            return m.getSettledAt() != null;
        } catch (Exception e) {
            log.warn("isMatchSettled: could not look up matchId={} — treating as unsettled", matchId);
            return false;
        }
    }

    // ── Market router ─────────────────────────────────────────────────────

    private String evaluateSelection(BetSelection sel, com.speedbet.api.match.Match match) {
        int h = match.getScoreHome();
        int a = match.getScoreAway();

        // Normalise: trim, lowercase, spaces → underscores, then uppercase
        // Handles DB values like "correct_score", "1x2", "Correct Score", "MATCH_RESULT" etc.
        String market = sel.getMarket()
                .trim()
                .toLowerCase()
                .replace(" ", "_")
                .toUpperCase();

        log.debug("evaluateSelection: sel {} market={} selection='{}' score={}-{}",
                sel.getId(), market, sel.getSelection(), h, a);

        return switch (market) {
            case "1X2", "ONE_X_TWO", "MATCH_RESULT", "FT_RESULT", "FULL_TIME_RESULT" ->
                    evaluate1X2(sel.getSelection(), h, a, sel.getId().toString());

            case "HOME_WIN"  -> h > a ? "WON" : "LOST";
            case "AWAY_WIN"  -> a > h ? "WON" : "LOST";

            case "BTTS", "BOTH_TEAMS_TO_SCORE", "GG_NG", "BOTH_TO_SCORE" ->
                    evaluateBtts(sel, h, a);

            case "OVER_UNDER", "TOTAL_GOALS", "GOALS_OU", "O/U", "O_U" ->
                    evaluateOverUnder(sel, h + a);

            case "CORRECT_SCORE", "EXACT_SCORE", "CS", "SCORE" ->
                    evaluateCorrectScore(sel.getSelection(), h, a, sel.getId().toString());

            case "DOUBLE_CHANCE", "DC" ->
                    evaluateDoubleChance(sel.getSelection(), h, a, sel.getId().toString());

            case "HALF_TIME", "HT_RESULT", "FIRST_HALF", "HT", "HALFTIME" ->
                    evaluateHalfTime(sel, match);

            case "ASIAN_HANDICAP", "AH", "HANDICAP", "ASIAN_HAND" ->
                    evaluateAsianHandicap(sel, h, a);

            default -> {
                log.warn("evaluateSelection: unknown market '{}' (normalised='{}') for sel {} — voiding",
                        sel.getMarket(), market, sel.getId());
                yield "VOID";
            }
        };
    }

    // ── 1X2 ──────────────────────────────────────────────────────────────

    private String evaluate1X2(String selection, int h, int a, String selId) {
        String s = selection == null ? "" : selection.trim().toUpperCase();
        log.debug("evaluate1X2: sel {} normalised='{}' score={}-{}", selId, s, h, a);

        return switch (s) {
            case "HOME", "HOME WIN", "1", "H", "TEAM1", "HOMETEAM" -> h > a ? "WON" : "LOST";
            case "DRAW", "X", "DRAW/X", "TIE", "LEVEL"             -> h == a ? "WON" : "LOST";
            case "AWAY", "AWAY WIN", "2", "A", "TEAM2", "AWAYTEAM" -> a > h ? "WON" : "LOST";
            default -> {
                log.warn("evaluate1X2: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
    }

    // ── Correct Score ─────────────────────────────────────────────────────

    private String evaluateCorrectScore(String selection, int h, int a, String selId) {
        if (selection == null || selection.isBlank()) {
            log.warn("evaluateCorrectScore: sel {} null/blank selection — VOID", selId);
            return "VOID";
        }
        // Normalise separator — accept "2-2", "2:2", "2 2", "2_2"
        String normalised = selection.trim().replaceAll("[:\\s_]", "-");
        String expected   = h + "-" + a;
        String r = expected.equals(normalised) ? "WON" : "LOST";
        log.debug("evaluateCorrectScore: sel {} expected='{}' got='{}' normalised='{}' → {}",
                selId, expected, selection, normalised, r);
        return r;
    }

    // ── BTTS ──────────────────────────────────────────────────────────────

    private String evaluateBtts(BetSelection sel, int h, int a) {
        String s = sel.getSelection() == null ? "" : sel.getSelection().trim().toLowerCase();
        boolean bothScored = h > 0 && a > 0;
        Boolean wantsBoth = switch (s) {
            case "yes", "y", "true", "1", "both", "gg"  -> true;
            case "no",  "n", "false", "0", "ng"          -> false;
            default -> {
                log.warn("evaluateBtts: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), sel.getSelection());
                yield null;
            }
        };
        if (wantsBoth == null) return "VOID";
        return wantsBoth == bothScored ? "WON" : "LOST";
    }

    // ── Over / Under ──────────────────────────────────────────────────────

    private String evaluateOverUnder(BetSelection sel, int totalGoals) {
        String raw = sel.getSelection() == null ? "" : sel.getSelection().trim().toLowerCase();

        boolean isOver;
        String numPart;

        if (raw.startsWith("over") || raw.startsWith("o ") || raw.startsWith("+")) {
            isOver  = true;
            numPart = raw.replaceAll("(?i)^(over|o)\\s*|^\\+", "").trim();
        } else if (raw.startsWith("under") || raw.startsWith("u ") || raw.startsWith("u")) {
            isOver  = false;
            numPart = raw.replaceAll("(?i)^(under|u)\\s*", "").trim();
        } else {
            log.warn("evaluateOverUnder: sel {} cannot determine over/under from '{}' — VOID",
                    sel.getId(), sel.getSelection());
            return "VOID";
        }

        double line;
        try {
            line = Double.parseDouble(numPart);
        } catch (NumberFormatException e) {
            log.warn("evaluateOverUnder: sel {} cannot parse line from '{}' numPart='{}' — VOID",
                    sel.getId(), sel.getSelection(), numPart);
            return "VOID";
        }

        if (line == Math.floor(line) && totalGoals == (int) line) return "PUSH";
        return isOver == (totalGoals > line) ? "WON" : "LOST";
    }

    // ── Double Chance ─────────────────────────────────────────────────────

    private String evaluateDoubleChance(String selection, int h, int a, String selId) {
        String s = selection == null ? "" : selection.trim().toUpperCase()
                .replace("/", "")
                .replace(" ", "");
        String r = switch (s) {
            case "1X", "HX", "HOMEORDRAW", "HOMEORLEVEL" -> h >= a ? "WON" : "LOST";
            case "X2", "XA", "DRAWORAWAY", "LEVELORAWAY" -> a >= h ? "WON" : "LOST";
            case "12", "HA", "HOMEORAWAY"                -> h != a ? "WON" : "LOST";
            default -> {
                log.warn("evaluateDoubleChance: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
        log.debug("evaluateDoubleChance: sel {} selection='{}' score={}-{} → {}", selId, s, h, a, r);
        return r;
    }

    // ── Half-Time ─────────────────────────────────────────────────────────

    private String evaluateHalfTime(BetSelection sel, com.speedbet.api.match.Match match) {
        // Try all known metadata key variants
        Integer htHome = extractIntFromMetadata(match, "score_home_ht");
        Integer htAway = extractIntFromMetadata(match, "score_away_ht");
        if (htHome == null) htHome = extractIntFromMetadata(match, "ht_home");
        if (htAway == null) htAway = extractIntFromMetadata(match, "ht_away");
        if (htHome == null) htHome = extractIntFromMetadata(match, "halftime_home");
        if (htAway == null) htAway = extractIntFromMetadata(match, "halftime_away");
        if (htHome == null) htHome = extractIntFromMetadata(match, "home_ht");
        if (htAway == null) htAway = extractIntFromMetadata(match, "away_ht");
        if (htHome == null) htHome = extractIntFromMetadata(match, "ht_score_home");
        if (htAway == null) htAway = extractIntFromMetadata(match, "ht_score_away");

        if (htHome == null || htAway == null) {
            log.warn("evaluateHalfTime: missing ht metadata for match {} sel {} — VOID",
                    match.getId(), sel.getId());
            return "VOID";
        }

        String s = sel.getSelection() == null ? "" : sel.getSelection().trim().toUpperCase();
        String r = switch (s) {
            case "HOME", "HOME WIN", "1", "H" -> htHome > htAway       ? "WON" : "LOST";
            case "DRAW", "X", "TIE"           -> htHome.equals(htAway) ? "WON" : "LOST";
            case "AWAY", "AWAY WIN", "2", "A" -> htAway > htHome       ? "WON" : "LOST";
            default -> {
                log.warn("evaluateHalfTime: sel {} unrecognised selection '{}' — VOID",
                        sel.getId(), sel.getSelection());
                yield "VOID";
            }
        };

        log.debug("evaluateHalfTime: sel {} ht={}-{} selection='{}' → {}",
                sel.getId(), htHome, htAway, s, r);
        return r;
    }

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

    private String evaluateAsianHandicap(BetSelection sel, int h, int a) {
        String raw = sel.getSelection() == null ? "" : sel.getSelection().trim();
        String upper = raw.toUpperCase();

        boolean bettingHome;
        String rest;

        if (upper.startsWith("HOME")) {
            bettingHome = true;
            rest = raw.substring(4).trim();
        } else if (upper.startsWith("AWAY")) {
            bettingHome = false;
            rest = raw.substring(4).trim();
        } else if (upper.startsWith("H")) {
            bettingHome = true;
            rest = raw.substring(1).trim();
        } else if (upper.startsWith("A")) {
            bettingHome = false;
            rest = raw.substring(1).trim();
        } else if (upper.startsWith("1")) {
            bettingHome = true;
            rest = raw.substring(1).trim();
        } else if (upper.startsWith("2")) {
            bettingHome = false;
            rest = raw.substring(1).trim();
        } else {
            log.warn("evaluateAsianHandicap: sel {} cannot parse side from '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        // Strip any leading colon or equals some feeds add e.g. "HOME: -1.5"
        rest = rest.replaceAll("^[:=]\\s*", "");

        double line;
        try {
            line = Double.parseDouble(rest);
        } catch (NumberFormatException e) {
            log.warn("evaluateAsianHandicap: sel {} cannot parse line from '{}' rest='{}' — VOID",
                    sel.getId(), raw, rest);
            return "VOID";
        }

        int    gd            = bettingHome ? (h - a) : (a - h);
        double effectiveLine = bettingHome ? line : -line;
        double frac          = Math.abs(effectiveLine % 1);

        log.debug("evaluateAsianHandicap: sel {} side={} line={} score={}-{} gd={} effectiveLine={}",
                sel.getId(), bettingHome ? "HOME" : "AWAY", line, h, a, gd, effectiveLine);

        String result;
        if (frac < 0.01) {
            result = evaluateWholeHandicap(gd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.5) < 0.01) {
            result = evaluateHalfHandicap(gd + effectiveLine, sel.getId().toString());
        } else if (Math.abs(frac - 0.25) < 0.01 || Math.abs(frac - 0.75) < 0.01) {
            result = evaluateQuarterHandicap(effectiveLine, gd, sel.getId().toString());
        } else {
            log.warn("evaluateAsianHandicap: sel {} unrecognised frac={} for line={} — VOID",
                    sel.getId(), frac, line);
            result = "VOID";
        }

        log.debug("evaluateAsianHandicap: sel {} → {}", sel.getId(), result);
        return result;
    }

    private String evaluateHalfHandicap(double adjustedGD, String selId) {
        String r = adjustedGD > 0 ? "WON" : "LOST";
        log.debug("evaluateHalfHandicap: sel {} adjustedGD={} → {}", selId, adjustedGD, r);
        return r;
    }

    private String evaluateWholeHandicap(double adjustedGD, String selId) {
        String r;
        if      (adjustedGD > 0)  r = "WON";
        else if (adjustedGD == 0) r = "PUSH";
        else                      r = "LOST";
        log.debug("evaluateWholeHandicap: sel {} adjustedGD={} → {}", selId, adjustedGD, r);
        return r;
    }

    private String evaluateQuarterHandicap(double effectiveLine, int gd, String selId) {
        double lower = effectiveLine - 0.25;
        double upper = effectiveLine + 0.25;

        String lowerResult = settleSingleLine(lower, gd, selId);
        String upperResult = settleSingleLine(upper, gd, selId);

        log.debug("evaluateQuarterHandicap: sel {} sub-lines=[{},{}] results=[{},{}]",
                selId, lower, upper, lowerResult, upperResult);

        return combineQuarterSubResults(lowerResult, upperResult);
    }

    private String settleSingleLine(double subLine, int gd, String selId) {
        double adjGD = gd + subLine;
        double frac  = Math.abs(subLine % 1);

        if (frac < 0.01)                  return evaluateWholeHandicap(adjGD, selId + "-whole");
        if (Math.abs(frac - 0.5) < 0.01) return evaluateHalfHandicap(adjGD,  selId + "-half");

        log.warn("settleSingleLine: sel {} unexpected frac={} for subLine={} — VOID", selId, frac, subLine);
        return "VOID";
    }

    private String combineQuarterSubResults(String lower, String upper) {
        if (lower.equals(upper)) return lower;

        if (isWon(lower)  && isPush(upper)) return "HALF_WON";
        if (isPush(lower) && isWon(upper))  return "HALF_WON";
        if (isLost(lower) && isPush(upper)) return "HALF_LOST";
        if (isPush(lower) && isLost(upper)) return "HALF_LOST";
        if ((isWon(lower) && isLost(upper)) || (isLost(lower) && isWon(upper))) return "PUSH";

        log.warn("combineQuarterSubResults: unexpected combination lower={} upper={} — VOID", lower, upper);
        return "VOID";
    }

    private boolean isWon(String r)  { return "WON".equals(r); }
    private boolean isLost(String r) { return "LOST".equals(r); }
    private boolean isPush(String r) { return "PUSH".equals(r); }
}
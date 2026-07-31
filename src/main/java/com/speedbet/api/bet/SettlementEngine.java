package com.speedbet.api.bet;

import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEngine {

    private static final BigDecimal TWO  = new BigDecimal("2");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** First signed number in a string: "-1.5", "+2", "2.5" … */
    private static final Pattern LINE_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private final MatchService matchService;
    private final BetService   betService;

    /**
     * Self-reference used so that @Transactional actually applies. Calling
     * settleMatch(...) directly from run() goes through `this`, bypassing the
     * Spring proxy, which means the method was NOT running in a transaction.
     * ObjectProvider is resolved lazily, so this does not create a cycle.
     */
    private final ObjectProvider<SettlementEngine> selfProvider;

    private SettlementEngine self() {
        SettlementEngine proxy = selfProvider.getIfAvailable();
        return proxy != null ? proxy : this;
    }

    // ── Outcome + tally ───────────────────────────────────────────────────

    /** What happened to a single slip during one pass. */
    public enum Outcome { WON, LOST, VOID, DEFERRED, SKIPPED }

    /** Running counts, printed at the end of every scheduled pass. */
    public static final class Tally {
        public int won, lost, voided, deferred, skipped, failed;

        void record(Outcome o) {
            switch (o) {
                case WON      -> won++;
                case LOST     -> lost++;
                case VOID     -> voided++;
                case DEFERRED -> deferred++;
                case SKIPPED  -> skipped++;
            }
        }

        void add(Tally other) {
            won      += other.won;
            lost     += other.lost;
            voided   += other.voided;
            deferred += other.deferred;
            skipped  += other.skipped;
            failed   += other.failed;
        }

        /** Slips that reached a final state (money moved) in this pass. */
        public int finalised() { return won + lost + voided; }

        @Override
        public String toString() {
            return String.format("WON=%d LOST=%d VOID=%d DEFERRED=%d SKIPPED=%d FAILED=%d",
                    won, lost, voided, deferred, skipped, failed);
        }
    }

    // ── Scheduled runner ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        List<Match> finishedMatches = matchService.getUnsettledFinished();
        log.info("Settlement run: {} finished match(es) to process", finishedMatches.size());

        Tally total = new Tally();
        int matchesSettled = 0, matchesSkipped = 0, matchesFailed = 0;

        for (Match match : finishedMatches) {

            // Not settleable → leave it alone. Do NOT mark it settled: the old
            // code returned early from settleMatch() but still called
            // markSettled(), stranding every bet on that match forever.
            if (!isSettleable(match)) {
                matchesSkipped++;
                log.warn("Settlement run: SKIP match {} ({} vs {}) — score(s) missing (home={}, away={}); "
                                + "leaving UNSETTLED for a later run",
                        match.getId(), match.getHomeTeam(), match.getAwayTeam(),
                        match.getScoreHome(), match.getScoreAway());
                continue;
            }

            try {
                log.info("Settlement run: processing match {} ({} vs {})",
                        match.getId(), match.getHomeTeam(), match.getAwayTeam());

                Tally matchTally = self().settleMatch(match);
                total.add(matchTally);

                matchService.markSettled(match.getId().toString());
                matchesSettled++;
                log.info("Settlement run: match {} marked settled — {}", match.getId(), matchTally);

                // Second pass: catch slips placed between the first pass and
                // markSettled(). Same call — it is a cheap no-op when there is
                // nothing pending.
                Tally lateTally = self().settleMatch(match);
                if (lateTally.finalised() > 0 || lateTally.deferred > 0) {
                    log.warn("Settlement run: {} late bet(s) picked up after settling match {} — {}",
                            lateTally.finalised() + lateTally.deferred, match.getId(), lateTally);
                }
                total.add(lateTally);

            } catch (Exception e) {
                matchesFailed++;
                log.error("Settlement run: FAILED for match {} — {} (match NOT marked settled, will retry)",
                        match.getId(), e.getMessage(), e);
            }
        }

        log.info("Settlement run COMPLETE — matches: settled={} skipped={} failed={} | bets: {}",
                matchesSettled, matchesSkipped, matchesFailed, total);
    }

    // ── Orphan recovery ───────────────────────────────────────────────────

    /**
     * Picks up slips left PENDING on matches that were already marked settled —
     * e.g. legs that were deferred while waiting on another fixture, or bets
     * that failed mid-pass.
     */
    @Scheduled(fixedDelay = 120_000)
    public void runOrphanedBetRecovery() {
        log.info("Orphan recovery: scanning for pending bets on already-settled matches");

        List<Match> settledMatches = matchService.getSettledFinished();
        if (settledMatches.isEmpty()) {
            log.info("Orphan recovery COMPLETE — no settled matches found");
            return;
        }

        Tally total = new Tally();
        int matchesScanned = 0, matchesSkipped = 0, betsExamined = 0;

        for (Match match : settledMatches) {
            if (!isSettleable(match)) {
                matchesSkipped++;
                log.warn("Orphan recovery: SKIP match {} — score(s) missing", match.getId());
                continue;
            }

            List<Bet> orphans = betService.getPendingBetsForMatch(match.getId());
            if (orphans.isEmpty()) continue;

            matchesScanned++;
            betsExamined += orphans.size();
            log.info("Orphan recovery: {} pending bet(s) on settled match {} ({} vs {})",
                    orphans.size(), match.getId(), match.getHomeTeam(), match.getAwayTeam());

            try {
                total.add(self().settleOrphansForMatch(match, orphans));
            } catch (Exception e) {
                total.failed++;
                log.error("Orphan recovery: FAILED batch for match {} — {}", match.getId(), e.getMessage(), e);
            }
        }

        log.info("Orphan recovery COMPLETE — matches scanned={} skipped={} | bets examined={} | {}",
                matchesScanned, matchesSkipped, betsExamined, total);
    }

    @Transactional
    public Tally settleOrphansForMatch(Match match, List<Bet> orphans) {
        Tally tally = new Tally();
        Map<UUID, Match> cache = newCache(match);

        for (Bet bet : orphans) {
            try {
                Outcome outcome = settleOneBet(bet, match, cache);
                tally.record(outcome);
                if (outcome == Outcome.DEFERRED || outcome == Outcome.SKIPPED) {
                    // Legitimately still waiting on another fixture — debug, not warn,
                    // otherwise this logs every two minutes until the other leg lands.
                    log.debug("Orphan recovery: bet {} → {}", bet.getId(), outcome);
                } else {
                    log.info("Orphan recovery: settled bet {} for match {} → {}",
                            bet.getId(), match.getId(), outcome);
                }
            } catch (Exception e) {
                tally.failed++;
                log.error("Orphan recovery: FAILED for bet {} match {} — {}",
                        bet.getId(), match.getId(), e.getMessage(), e);
            }
        }
        return tally;
    }

    // ── Match-level settlement ────────────────────────────────────────────

    @Transactional
    public Tally settleMatch(Match match) {
        Tally tally = new Tally();

        if (!isSettleable(match)) {
            tally.skipped++;
            log.warn("settleMatch: match {} has null score(s) — not settleable, skipping", match.getId());
            return tally;
        }

        int h = match.getScoreHome();
        int a = match.getScoreAway();
        log.info("settleMatch: match {} final score {}-{}", match.getId(), h, a);

        int[] ht = resolveHalfTimeScore(match);
        if (ht != null) {
            log.info("settleMatch: match {} half-time score {}-{}", match.getId(), ht[0], ht[1]);
        } else {
            log.warn("settleMatch: match {} has no half-time metadata — HALF_TIME bets will VOID", match.getId());
        }

        List<Bet> pendingBets = betService.getPendingBetsForMatch(match.getId());
        log.info("settleMatch: {} pending bet(s) found for match {}", pendingBets.size(), match.getId());

        Map<UUID, Match> cache = newCache(match);

        for (Bet bet : pendingBets) {
            try {
                Outcome outcome = settleOneBet(bet, match, cache);
                tally.record(outcome);
                log.debug("settleMatch: bet {} → {}", bet.getId(), outcome);
            } catch (Exception e) {
                // NOTE: if the failure came from the persistence layer the
                // surrounding transaction may already be rollback-only, in which
                // case the whole match retries on the next pass — which is safe,
                // because settleOneBet only touches PENDING selections.
                tally.failed++;
                log.error("settleMatch: FAILED bet {} on match {} — {}",
                        bet.getId(), match.getId(), e.getMessage(), e);
            }
        }

        log.info("settleMatch: match {} done — {}", match.getId(), tally);
        return tally;
    }

    // ── Bet-level settlement ──────────────────────────────────────────────

    /**
     * Settles a single slip if — and only if — every one of its legs can be
     * resolved right now. Anything else is saved and deferred; nothing is ever
     * paid out while a leg is still unresolved.
     */
    private Outcome settleOneBet(Bet bet, Match triggerMatch, Map<UUID, Match> matchCache) {

        if (bet.getSelections() == null || bet.getSelections().isEmpty()) {
            log.error("settleOneBet: bet {} has no selections — cannot settle, leaving PENDING", bet.getId());
            return Outcome.SKIPPED;
        }

        log.debug("settleOneBet: bet {} stake={} totalOdds={} selections={}",
                bet.getId(), bet.getStake(), bet.getTotalOdds(), bet.getSelections().size());

        boolean anyLegUnsettleable = false;

        // ── Step 1: evaluate every PENDING leg whose match can be settled now.
        // The old code only looked at legs belonging to the trigger match, so a
        // leg on a match that had already been settled stayed "PENDING" forever
        // and was then silently priced at full odds in step 3.
        for (BetSelection sel : bet.getSelections()) {
            if (!"PENDING".equals(sel.getResult())) continue;

            Match legMatch = resolveSettleableMatch(sel.getMatchId(), triggerMatch, matchCache);
            if (legMatch == null) {
                anyLegUnsettleable = true;
                log.debug("settleOneBet: bet {} sel {} waiting on match {} — not settleable yet",
                        bet.getId(), sel.getId(), sel.getMatchId());
                continue;
            }

            String result = evaluateSelection(sel, legMatch);
            sel.setResult(result);

            log.info("settleOneBet: bet {} sel {} match={} market={} selection='{}' oddsLocked={} → {}",
                    bet.getId(), sel.getId(), sel.getMatchId(), sel.getMarket(),
                    sel.getSelection(), sel.getOddsLocked(), result);
        }

        // ── Step 2: a losing leg kills the slip immediately, even if other legs
        // are still outstanding. Covers legs lost on an earlier pass too.
        boolean hasLoss = bet.getSelections().stream().anyMatch(s -> "LOST".equals(s.getResult()));
        if (hasLoss) {
            log.info("settleOneBet: bet {} LOST (losing leg present)", bet.getId());
            betService.settleBet(bet, BetStatus.LOST, null);
            return Outcome.LOST;
        }

        // ── Step 3: anything not fully resolved → save progress and defer.
        long unresolved = bet.getSelections().stream().filter(s -> !isResolved(s.getResult())).count();
        if (anyLegUnsettleable || unresolved > 0) {
            log.info("settleOneBet: bet {} DEFERRED — {} leg(s) still unresolved", bet.getId(), unresolved);
            betService.saveSelectionsOnly(bet);
            return Outcome.DEFERRED;
        }

        // ── Step 4: every leg resolved — price the slip.
        if (bet.getStake() == null || bet.getTotalOdds() == null) {
            log.error("settleOneBet: bet {} has null stake ({}) or totalOdds ({}) — cannot price, leaving PENDING",
                    bet.getId(), bet.getStake(), bet.getTotalOdds());
            betService.saveSelectionsOnly(bet);
            return Outcome.SKIPPED;
        }

        BigDecimal oddsAdjustment = BigDecimal.ONE;
        boolean anyWin = false;
        boolean allNeutral = true;   // every leg VOID/PUSH → stake refund

        for (BetSelection sel : bet.getSelections()) {
            String result = sel.getResult();
            BigDecimal odds = sel.getOddsLocked();

            // Every branch except a clean WON has to divide the leg back out, so
            // bad odds make the slip unpriceable rather than silently wrong.
            if (!"WON".equals(result) && (odds == null || odds.compareTo(BigDecimal.ZERO) <= 0)) {
                log.error("settleOneBet: bet {} sel {} has invalid oddsLocked={} for result {} — "
                                + "cannot price, leaving PENDING", bet.getId(), sel.getId(), odds, result);
                betService.saveSelectionsOnly(bet);
                return Outcome.SKIPPED;
            }

            switch (result) {
                case "WON" -> {
                    anyWin = true;
                    allNeutral = false;
                }
                case "VOID", "PUSH" -> oddsAdjustment = oddsAdjustment
                        .divide(odds, MathContext.DECIMAL64);
                case "HALF_WON" -> {
                    anyWin = true;
                    allNeutral = false;
                    BigDecimal halfWinMultiplier = odds.add(BigDecimal.ONE)
                            .divide(TWO, MathContext.DECIMAL64);
                    oddsAdjustment = oddsAdjustment
                            .divide(odds, MathContext.DECIMAL64)
                            .multiply(halfWinMultiplier, MathContext.DECIMAL64);
                }
                case "HALF_LOST" -> {
                    allNeutral = false;
                    oddsAdjustment = oddsAdjustment
                            .divide(odds, MathContext.DECIMAL64)
                            .multiply(HALF, MathContext.DECIMAL64);
                }
                default -> {
                    // Unreachable: isResolved() gated this above. Defensive only.
                    log.error("settleOneBet: bet {} sel {} unrecognised result '{}' — leaving PENDING",
                            bet.getId(), sel.getId(), result);
                    betService.saveSelectionsOnly(bet);
                    return Outcome.SKIPPED;
                }
            }
        }

        BetStatus finalStatus;
        BigDecimal payout;

        if (allNeutral) {
            // Whole slip voided/pushed — refund the stake exactly, rather than
            // relying on the odds cancelling back to 1.0 through DECIMAL64.
            finalStatus = BetStatus.VOID;
            payout = bet.getStake().setScale(2, RoundingMode.HALF_UP);
            log.info("settleOneBet: bet {} VOID (all legs void/push) — refunding stake {}", bet.getId(), payout);
        } else {
            BigDecimal effectiveOdds = bet.getTotalOdds().multiply(oddsAdjustment, MathContext.DECIMAL64);

            // The old code clamped effectiveOdds up to 1.0. That is wrong for a
            // HALF_LOST leg, where a payout below stake is the correct result —
            // it turned half-losses into full stake refunds. Only guard against
            // a genuinely nonsensical negative.
            if (effectiveOdds.compareTo(BigDecimal.ZERO) < 0) {
                log.error("settleOneBet: bet {} negative effectiveOdds {} — clamping to 0",
                        bet.getId(), effectiveOdds);
                effectiveOdds = BigDecimal.ZERO;
            }

            payout = bet.getStake().multiply(effectiveOdds, MathContext.DECIMAL64)
                    .setScale(2, RoundingMode.HALF_UP);
            finalStatus = BetStatus.WON;

            log.debug("settleOneBet: bet {} totalOdds={} × oddsAdjustment={} = effectiveOdds={}",
                    bet.getId(), bet.getTotalOdds(), oddsAdjustment, effectiveOdds);

            if (!anyWin) {
                // Only reachable via HALF_LOST legs: pays out, but less than stake.
                log.warn("settleOneBet: bet {} has no winning leg but is not all-void "
                        + "(half-lost legs) — payout {} vs stake {}", bet.getId(), payout, bet.getStake());
            }
        }

        log.info("settleOneBet: bet {} → {} payout={}", bet.getId(), finalStatus, payout);
        betService.settleBet(bet, finalStatus, payout);
        return finalStatus == BetStatus.WON ? Outcome.WON : Outcome.VOID;
    }

    // ── Settleability helpers ─────────────────────────────────────────────

    /** A match can be settled once we actually have both scores. */
    private boolean isSettleable(Match match) {
        return match != null && match.getScoreHome() != null && match.getScoreAway() != null;
    }

    private boolean isResolved(String result) {
        if (result == null) return false;
        return switch (result) {
            case "WON", "LOST", "VOID", "PUSH", "HALF_WON", "HALF_LOST" -> true;
            default -> false;
        };
    }

    private Map<UUID, Match> newCache(Match match) {
        Map<UUID, Match> cache = new HashMap<>();
        if (match != null && match.getId() != null) cache.put(match.getId(), match);
        return cache;
    }

    /**
     * Returns the match for a leg if it can be settled right now, otherwise null.
     * For legs outside the trigger match we additionally require settledAt, so a
     * fixture that merely has a provisional score does not settle other slips.
     * Results (including misses) are cached per pass to keep lookups down.
     */
    private Match resolveSettleableMatch(UUID matchId, Match triggerMatch, Map<UUID, Match> cache) {
        if (matchId == null) {
            log.warn("resolveSettleableMatch: selection has null matchId");
            return null;
        }

        if (triggerMatch != null && matchId.equals(triggerMatch.getId())) {
            return isSettleable(triggerMatch) ? triggerMatch : null;
        }

        if (cache.containsKey(matchId)) return cache.get(matchId);

        Match m = null;
        try {
            m = matchService.getById(matchId.toString());
        } catch (Exception e) {
            log.warn("resolveSettleableMatch: could not look up matchId={} — treating as unsettleable ({})",
                    matchId, e.getMessage());
        }

        Match usable = (m != null && m.getSettledAt() != null && isSettleable(m)) ? m : null;
        cache.put(matchId, usable);
        return usable;
    }

    // ── Market router ─────────────────────────────────────────────────────

    private String evaluateSelection(BetSelection sel, Match match) {
        int h = match.getScoreHome();
        int a = match.getScoreAway();

        if (sel.getMarket() == null) {
            log.warn("evaluateSelection: sel {} has null market — voiding", sel.getId());
            return "VOID";
        }

        // Normalise: trim, spaces/hyphens → underscores, uppercase.
        // Handles DB values like "correct_score", "1x2", "Correct Score", "over-under".
        String market = sel.getMarket()
                .trim()
                .replace(' ', '_')
                .replace('-', '_')
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
            case "yes", "y", "true", "1", "both", "gg" -> true;
            case "no",  "n", "false", "0", "ng"        -> false;
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
        if (raw.startsWith("over") || raw.startsWith("+") || raw.startsWith("o")) {
            isOver = true;
        } else if (raw.startsWith("under") || raw.startsWith("u")) {
            isOver = false;
        } else {
            log.warn("evaluateOverUnder: sel {} cannot determine over/under from '{}' — VOID",
                    sel.getId(), sel.getSelection());
            return "VOID";
        }

        // Pull the line out by pattern rather than prefix-stripping, so "o2.5",
        // "over 2.5", "Over 2,5 goals" and "+2.5" all parse.
        Double parsed = parseLine(raw);
        if (parsed == null) {
            log.warn("evaluateOverUnder: sel {} cannot parse line from '{}' — VOID",
                    sel.getId(), sel.getSelection());
            return "VOID";
        }
        double line = Math.abs(parsed);

        double frac = Math.abs(line % 1);
        if (frac > 0.01 && Math.abs(frac - 0.5) > 0.01) {
            // Quarter goal lines (2.25 / 2.75) would need split-stake handling.
            log.warn("evaluateOverUnder: sel {} unsupported quarter line {} — VOID", sel.getId(), line);
            return "VOID";
        }

        if (frac < 0.01 && totalGoals == (int) line) return "PUSH";
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

    private String evaluateHalfTime(BetSelection sel, Match match) {
        int[] ht = resolveHalfTimeScore(match);
        if (ht == null) {
            log.warn("evaluateHalfTime: missing ht metadata for match {} sel {} — VOID",
                    match.getId(), sel.getId());
            return "VOID";
        }
        int htHome = ht[0];
        int htAway = ht[1];

        String s = sel.getSelection() == null ? "" : sel.getSelection().trim().toUpperCase();
        String r = switch (s) {
            case "HOME", "HOME WIN", "1", "H" -> htHome > htAway  ? "WON" : "LOST";
            case "DRAW", "X", "TIE"           -> htHome == htAway ? "WON" : "LOST";
            case "AWAY", "AWAY WIN", "2", "A" -> htAway > htHome  ? "WON" : "LOST";
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

    /** Half-time score as {home, away}, or null if no key variant is present. */
    private int[] resolveHalfTimeScore(Match match) {
        String[][] keyPairs = {
                {"score_home_ht", "score_away_ht"},
                {"ht_home",       "ht_away"},
                {"halftime_home", "halftime_away"},
                {"home_ht",       "away_ht"},
                {"ht_score_home", "ht_score_away"},
        };
        for (String[] pair : keyPairs) {
            Integer home = extractIntFromMetadata(match, pair[0]);
            Integer away = extractIntFromMetadata(match, pair[1]);
            if (home != null && away != null) return new int[]{home, away};
        }
        return null;
    }

    private Integer extractIntFromMetadata(Match match, String key) {
        if (match.getMetadata() == null) return null;
        Object val = match.getMetadata().get(key);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.toString().trim());
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
            rest = raw.substring(4);
        } else if (upper.startsWith("AWAY")) {
            bettingHome = false;
            rest = raw.substring(4);
        } else if (upper.startsWith("H") || upper.startsWith("1")) {
            bettingHome = true;
            rest = raw.substring(1);
        } else if (upper.startsWith("A") || upper.startsWith("2")) {
            bettingHome = false;
            rest = raw.substring(1);
        } else {
            log.warn("evaluateAsianHandicap: sel {} cannot parse side from '{}' — VOID", sel.getId(), raw);
            return "VOID";
        }

        // Pattern-match the line so feed noise ("HOME: -1.5", "H -1,5 AH") parses.
        Double parsed = parseLine(rest);
        if (parsed == null) {
            log.warn("evaluateAsianHandicap: sel {} cannot parse line from '{}' rest='{}' — VOID",
                    sel.getId(), raw, rest);
            return "VOID";
        }
        double line = parsed;

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
        if      (Math.abs(adjustedGD) < 0.01) r = "PUSH";
        else if (adjustedGD > 0)              r = "WON";
        else                                  r = "LOST";
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

        if (frac < 0.01)                 return evaluateWholeHandicap(adjGD, selId + "-whole");
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

    /** First signed decimal in the string, comma-decimals tolerated. */
    private Double parseLine(String s) {
        if (s == null) return null;
        Matcher m = LINE_PATTERN.matcher(s.replace(',', '.'));
        if (!m.find()) return null;
        try {
            return Double.valueOf(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
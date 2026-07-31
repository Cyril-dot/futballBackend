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
import java.util.ArrayList;
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

    /**
     * Asian handicap convention. FALSE (default) = the line is written for the side
     * named in the selection, so "AWAY +0.5" means the away team starts half a goal
     * up. TRUE = the line is always quoted from the home team's perspective, so
     * "AWAY -1.5" means home -1.5, i.e. away +1.5, and the sign must be flipped.
     *
     * Set this to match what HandicapOddsService actually emits. Getting it wrong
     * inverts every away-side handicap settlement.
     */
    private static final boolean AH_LINE_IS_HOME_PERSPECTIVE = false;

    /** First signed number in a string: "-1.5", "+2", "2.5" … */
    private static final Pattern LINE_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private final MatchService matchService;
    private final BetService   betService;

    /**
     * Self-reference so @Transactional actually applies. Calling settleMatch(...)
     * directly from run() goes through `this` and bypasses the Spring proxy, which
     * means it was NOT running in a transaction. ObjectProvider resolves lazily,
     * so this does not create a dependency cycle.
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

    // ══════════════════════════════════════════════════════════════════════
    // SCHEDULED RUNNER
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        List<Match> finishedMatches = matchService.getUnsettledFinished();
        log.info("Settlement run: {} finished match(es) to process", finishedMatches.size());

        Tally total = new Tally();
        int matchesSettled = 0, matchesSkipped = 0, matchesFailed = 0;

        for (Match match : finishedMatches) {

            // Not settleable → leave it alone. Do NOT mark it settled: the old code
            // returned early from settleMatch() but still called markSettled(),
            // stranding every bet on that match forever.
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
                // markSettled(). Same call — a cheap no-op when nothing is pending.
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

    // ══════════════════════════════════════════════════════════════════════
    // ORPHAN RECOVERY
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Picks up slips left PENDING on matches that were already marked settled —
     * legs deferred while waiting on another fixture, or bets that failed mid-pass.
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
                    // Legitimately waiting on another fixture — debug, not warn,
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

    // ══════════════════════════════════════════════════════════════════════
    // MATCH-LEVEL SETTLEMENT
    // ══════════════════════════════════════════════════════════════════════

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
                // NOTE: if the failure came from the persistence layer the surrounding
                // transaction may already be rollback-only, in which case the whole
                // match retries next pass — safe, because settleOneBet only ever
                // touches PENDING selections.
                tally.failed++;
                log.error("settleMatch: FAILED bet {} on match {} — {}",
                        bet.getId(), match.getId(), e.getMessage(), e);
            }
        }

        log.info("settleMatch: match {} done — {}", match.getId(), tally);
        return tally;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BET-LEVEL SETTLEMENT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Settles a single slip if — and only if — every leg can be resolved right now.
     * Anything else is saved and deferred; nothing is paid while a leg is unresolved.
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
        // The old code only looked at legs on the trigger match, so a leg on a match
        // settled in an earlier pass stayed "PENDING" forever and was then silently
        // priced at full odds in the payout step.
        for (BetSelection sel : bet.getSelections()) {
            if (!isPending(sel.getResult())) continue;

            Match legMatch = resolveSettleableMatch(sel.getMatchId(), triggerMatch, matchCache);
            if (legMatch == null) {
                anyLegUnsettleable = true;
                log.debug("settleOneBet: bet {} sel {} waiting on match {} — not settleable yet",
                        bet.getId(), selId(sel), sel.getMatchId());
                continue;
            }

            String result = evaluateSelection(sel, legMatch);
            sel.setResult(result);

            log.info("settleOneBet: bet {} sel {} match={} market={} selection='{}' oddsLocked={} → {}",
                    bet.getId(), selId(sel), sel.getMatchId(), sel.getMarket(),
                    sel.getSelection(), sel.getOddsLocked(), result);
        }

        // ── Step 2: a losing leg kills the slip immediately, even with legs outstanding.
        if (bet.getSelections().stream().anyMatch(s -> "LOST".equals(normaliseResult(s.getResult())))) {
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
        List<String>     results = new ArrayList<>(bet.getSelections().size());
        List<BigDecimal> odds    = new ArrayList<>(bet.getSelections().size());
        for (BetSelection sel : bet.getSelections()) {
            results.add(sel.getResult());
            odds.add(sel.getOddsLocked());
        }

        Pricing pricing = price(bet.getStake(), bet.getTotalOdds(), results, odds);

        if (!pricing.priceable()) {
            log.error("settleOneBet: bet {} cannot be priced ({}) — leaving PENDING", bet.getId(), pricing.detail());
            betService.saveSelectionsOnly(bet);
            return Outcome.SKIPPED;
        }

        log.info("settleOneBet: bet {} → {} payout={} ({})",
                bet.getId(), pricing.status(), pricing.payout(), pricing.detail());
        betService.settleBet(bet, pricing.status(), pricing.payout());

        return switch (pricing.status()) {
            case WON  -> Outcome.WON;
            case LOST -> Outcome.LOST;
            default   -> Outcome.VOID;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRICING (pure — no entities, no I/O)
    // ══════════════════════════════════════════════════════════════════════

    /** Outcome of pricing a slip. `priceable=false` means: do not touch money. */
    private record Pricing(boolean priceable, BetStatus status, BigDecimal payout, String detail) {
        static Pricing unpriceable(String detail) { return new Pricing(false, null, null, detail); }
    }

    /**
     * Turns a set of leg results + locked odds into a final status and payout.
     * Legs that void or push are divided back out of the accumulator; half-won and
     * half-lost legs are re-priced at (odds+1)/2 and 0.5 respectively.
     */
    private Pricing price(BigDecimal stake, BigDecimal totalOdds,
                          List<String> results, List<BigDecimal> oddsLocked) {

        if (results == null || results.isEmpty())      return Pricing.unpriceable("no selections");
        if (stake == null || totalOdds == null)        return Pricing.unpriceable("null stake or totalOdds");
        if (results.size() != oddsLocked.size())       return Pricing.unpriceable("results/odds size mismatch");

        for (String r : results) {
            if ("LOST".equals(normaliseResult(r))) return new Pricing(true, BetStatus.LOST, null, "losing leg");
        }

        BigDecimal adjustment = BigDecimal.ONE;
        boolean anyWin = false;
        boolean allNeutral = true;   // every leg VOID/PUSH → stake refund

        for (int i = 0; i < results.size(); i++) {
            String result   = normaliseResult(results.get(i));
            BigDecimal odds = oddsLocked.get(i);

            if (!isResolved(result)) return Pricing.unpriceable("unresolved leg result '" + result + "'");

            // Every branch except a clean WON divides the leg back out, so bad odds
            // make the slip unpriceable rather than silently wrong.
            if (!"WON".equals(result) && (odds == null || odds.compareTo(BigDecimal.ZERO) <= 0)) {
                return Pricing.unpriceable("invalid oddsLocked " + odds + " for result " + result);
            }

            switch (result) {
                case "WON" -> {
                    anyWin = true;
                    allNeutral = false;
                }
                case "VOID", "PUSH" -> adjustment = adjustment.divide(odds, MathContext.DECIMAL64);
                case "HALF_WON" -> {
                    anyWin = true;
                    allNeutral = false;
                    BigDecimal halfWinMultiplier = odds.add(BigDecimal.ONE).divide(TWO, MathContext.DECIMAL64);
                    adjustment = adjustment.divide(odds, MathContext.DECIMAL64)
                            .multiply(halfWinMultiplier, MathContext.DECIMAL64);
                }
                case "HALF_LOST" -> {
                    allNeutral = false;
                    adjustment = adjustment.divide(odds, MathContext.DECIMAL64)
                            .multiply(HALF, MathContext.DECIMAL64);
                }
                default -> {
                    return Pricing.unpriceable("unhandled leg result '" + result + "'");
                }
            }
        }

        if (allNeutral) {
            // Whole slip voided/pushed — refund the stake exactly, rather than relying
            // on the odds cancelling back to 1.0 through DECIMAL64 division.
            return new Pricing(true, BetStatus.VOID,
                    stake.setScale(2, RoundingMode.HALF_UP), "all legs void/push — stake refund");
        }

        BigDecimal effectiveOdds = totalOdds.multiply(adjustment, MathContext.DECIMAL64);

        // The old code clamped effectiveOdds up to 1.0. That is wrong for a HALF_LOST
        // leg, where a payout below stake is correct — it turned half-losses into full
        // stake refunds. Only guard against a genuinely nonsensical negative.
        String detail = "effectiveOdds=" + effectiveOdds;
        if (effectiveOdds.compareTo(BigDecimal.ZERO) < 0) {
            effectiveOdds = BigDecimal.ZERO;
            detail = "negative effectiveOdds clamped to 0";
        } else if (!anyWin) {
            // Only reachable via HALF_LOST legs: pays out, but less than stake.
            detail = "no winning leg, half-lost only — " + detail;
        }

        BigDecimal payout = stake.multiply(effectiveOdds, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);

        return new Pricing(true, BetStatus.WON, payout, detail);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SETTLEABILITY HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /** A match can be settled once we actually have both scores. */
    private boolean isSettleable(Match match) {
        return match != null && match.getScoreHome() != null && match.getScoreAway() != null;
    }

    /** Null or blank counts as PENDING: a selection may not have been persisted yet. */
    private boolean isPending(String result) {
        return "PENDING".equals(normaliseResult(result));
    }

    /** Null/blank/mixed-case tolerant. Null becomes PENDING, never a silent pass. */
    private String normaliseResult(String result) {
        if (result == null || result.isBlank()) return "PENDING";
        return result.trim().toUpperCase();
    }

    private boolean isResolved(String result) {
        return switch (normaliseResult(result)) {
            case "WON", "LOST", "VOID", "PUSH", "HALF_WON", "HALF_LOST" -> true;
            default -> false;
        };
    }

    private String selId(BetSelection sel) {
        return sel.getId() == null ? "<unsaved>" : sel.getId().toString();
    }

    private Map<UUID, Match> newCache(Match match) {
        Map<UUID, Match> cache = new HashMap<>();
        if (match != null && match.getId() != null) cache.put(match.getId(), match);
        return cache;
    }

    /**
     * Returns the match for a leg if it can be settled right now, otherwise null.
     * For legs outside the trigger match we additionally require settledAt, so a
     * fixture with a merely provisional score does not settle other slips. Results
     * (including misses) are cached per pass to keep lookups down.
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

    // ══════════════════════════════════════════════════════════════════════
    // MARKET ROUTER
    // ══════════════════════════════════════════════════════════════════════

    private String evaluateSelection(BetSelection sel, Match match) {
        int[] ht = resolveHalfTimeScore(match);
        return evaluateMarket(
                sel.getMarket(), sel.getSelection(),
                match.getScoreHome(), match.getScoreAway(),
                ht == null ? null : ht[0],
                ht == null ? null : ht[1],
                selId(sel));
    }

    /**
     * The whole settlement decision, expressed over plain values. Everything that
     * decides whether a leg won or lost lives below this line and touches no
     * entities, no database and no clock — which is what makes selfCheck() possible.
     */
    private String evaluateMarket(String rawMarket, String selection,
                                  int h, int a, Integer htHome, Integer htAway, String selId) {

        if (rawMarket == null) {
            log.warn("evaluateMarket: sel {} has null market — voiding", selId);
            return "VOID";
        }

        // Normalise: trim, spaces/hyphens → underscores, uppercase. Handles DB values
        // like "correct_score", "1x2", "Correct Score", "over-under".
        String market = rawMarket.trim().replace(' ', '_').replace('-', '_').toUpperCase();

        log.debug("evaluateMarket: sel {} market={} selection='{}' score={}-{}", selId, market, selection, h, a);

        return switch (market) {
            case "1X2", "ONE_X_TWO", "MATCH_RESULT", "FT_RESULT", "FULL_TIME_RESULT" ->
                    evaluate1X2(selection, h, a, selId);

            case "HOME_WIN"  -> h > a ? "WON" : "LOST";
            case "AWAY_WIN"  -> a > h ? "WON" : "LOST";

            case "BTTS", "BOTH_TEAMS_TO_SCORE", "GG_NG", "BOTH_TO_SCORE" ->
                    evaluateBtts(selection, h, a, selId);

            case "OVER_UNDER", "TOTAL_GOALS", "GOALS_OU", "O/U", "O_U" ->
                    evaluateOverUnder(selection, h + a, selId);

            case "CORRECT_SCORE", "EXACT_SCORE", "CS", "SCORE" ->
                    evaluateCorrectScore(selection, h, a, selId);

            case "DOUBLE_CHANCE", "DC" ->
                    evaluateDoubleChance(selection, h, a, selId);

            case "HALF_TIME", "HT_RESULT", "FIRST_HALF", "HT", "HALFTIME" ->
                    evaluateHalfTime(selection, htHome, htAway, selId);

            case "ASIAN_HANDICAP", "AH", "HANDICAP", "ASIAN_HAND" ->
                    evaluateAsianHandicap(selection, h, a, selId);

            default -> {
                log.warn("evaluateMarket: unknown market '{}' (normalised='{}') for sel {} — voiding",
                        rawMarket, market, selId);
                yield "VOID";
            }
        };
    }

    // ── 1X2 ──────────────────────────────────────────────────────────────

    private String evaluate1X2(String selection, int h, int a, String selId) {
        String s = selection == null ? "" : selection.trim().toUpperCase();
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
        return expected.equals(normalised) ? "WON" : "LOST";
    }

    // ── BTTS ──────────────────────────────────────────────────────────────

    private String evaluateBtts(String selection, int h, int a, String selId) {
        String s = selection == null ? "" : selection.trim().toLowerCase();
        boolean bothScored = h > 0 && a > 0;
        Boolean wantsBoth = switch (s) {
            case "yes", "y", "true", "1", "both", "gg" -> true;
            case "no",  "n", "false", "0", "ng"        -> false;
            default -> {
                log.warn("evaluateBtts: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield null;
            }
        };
        if (wantsBoth == null) return "VOID";
        return wantsBoth == bothScored ? "WON" : "LOST";
    }

    // ── Over / Under ──────────────────────────────────────────────────────

    private String evaluateOverUnder(String selection, int totalGoals, String selId) {
        String raw = selection == null ? "" : selection.trim().toLowerCase();

        boolean isOver;
        if (raw.startsWith("over") || raw.startsWith("+") || raw.startsWith("o")) {
            isOver = true;
        } else if (raw.startsWith("under") || raw.startsWith("u")) {
            isOver = false;
        } else {
            log.warn("evaluateOverUnder: sel {} cannot determine over/under from '{}' — VOID", selId, selection);
            return "VOID";
        }

        // Pull the line out by pattern rather than prefix-stripping, so "o2.5",
        // "over 2.5", "Over 2,5 goals" and "+2.5" all parse.
        Double parsed = parseLine(raw);
        if (parsed == null) {
            log.warn("evaluateOverUnder: sel {} cannot parse line from '{}' — VOID", selId, selection);
            return "VOID";
        }
        double line = Math.abs(parsed);
        double frac = Math.abs(line % 1);

        if (frac > 0.01 && Math.abs(frac - 0.5) > 0.01) {
            // Quarter goal lines (2.25 / 2.75) would need split-stake handling.
            log.warn("evaluateOverUnder: sel {} unsupported quarter line {} — VOID", selId, line);
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
        return switch (s) {
            case "1X", "HX", "HOMEORDRAW", "HOMEORLEVEL" -> h >= a ? "WON" : "LOST";
            case "X2", "XA", "DRAWORAWAY", "LEVELORAWAY" -> a >= h ? "WON" : "LOST";
            case "12", "HA", "HOMEORAWAY"                -> h != a ? "WON" : "LOST";
            default -> {
                log.warn("evaluateDoubleChance: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
    }

    // ── Half-Time ─────────────────────────────────────────────────────────

    private String evaluateHalfTime(String selection, Integer htHome, Integer htAway, String selId) {
        if (htHome == null || htAway == null) {
            log.warn("evaluateHalfTime: sel {} missing half-time metadata — VOID", selId);
            return "VOID";
        }

        String s = selection == null ? "" : selection.trim().toUpperCase();
        return switch (s) {
            case "HOME", "HOME WIN", "1", "H" -> htHome > htAway         ? "WON" : "LOST";
            case "DRAW", "X", "TIE"           -> htHome.equals(htAway)   ? "WON" : "LOST";
            case "AWAY", "AWAY WIN", "2", "A" -> htAway > htHome         ? "WON" : "LOST";
            default -> {
                log.warn("evaluateHalfTime: sel {} unrecognised selection '{}' — VOID", selId, selection);
                yield "VOID";
            }
        };
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

    private String evaluateAsianHandicap(String selection, int h, int a, String selId) {
        String raw = selection == null ? "" : selection.trim();
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
            log.warn("evaluateAsianHandicap: sel {} cannot parse side from '{}' — VOID", selId, raw);
            return "VOID";
        }

        // Pattern-match the line so feed noise ("HOME: -1.5", "H -1,5 AH") parses.
        Double parsed = parseLine(rest);
        if (parsed == null) {
            log.warn("evaluateAsianHandicap: sel {} cannot parse line from '{}' rest='{}' — VOID",
                    selId, raw, rest);
            return "VOID";
        }
        double line = parsed;

        // gd is already from the backed side's point of view, so a side-specific
        // line is applied as written. The old code negated it unconditionally for
        // away bets, which paid out losing bets: "AWAY -1.5" on a 0-1 away win
        // became gd(1) + 1.5 = 2.5 -> WON, when the away side did not cover.
        int    gd            = bettingHome ? (h - a) : (a - h);
        double effectiveLine = (bettingHome || !AH_LINE_IS_HOME_PERSPECTIVE) ? line : -line;
        double frac          = Math.abs(effectiveLine % 1);

        log.debug("evaluateAsianHandicap: sel {} side={} line={} score={}-{} gd={} effectiveLine={}",
                selId, bettingHome ? "HOME" : "AWAY", line, h, a, gd, effectiveLine);

        if (frac < 0.01)                        return evaluateWholeHandicap(gd + effectiveLine, selId);
        if (Math.abs(frac - 0.5) < 0.01)        return evaluateHalfHandicap(gd + effectiveLine, selId);
        if (Math.abs(frac - 0.25) < 0.01
                || Math.abs(frac - 0.75) < 0.01) return evaluateQuarterHandicap(effectiveLine, gd, selId);

        log.warn("evaluateAsianHandicap: sel {} unrecognised frac={} for line={} — VOID", selId, frac, line);
        return "VOID";
    }

    private String evaluateHalfHandicap(double adjustedGD, String selId) {
        return adjustedGD > 0 ? "WON" : "LOST";
    }

    private String evaluateWholeHandicap(double adjustedGD, String selId) {
        if (Math.abs(adjustedGD) < 0.01) return "PUSH";
        return adjustedGD > 0 ? "WON" : "LOST";
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

        if (frac < 0.01)                 return evaluateWholeHandicap(adjGD, selId);
        if (Math.abs(frac - 0.5) < 0.01) return evaluateHalfHandicap(adjGD,  selId);

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

    // ══════════════════════════════════════════════════════════════════════
    // SELF-CHECK
    // ══════════════════════════════════════════════════════════════════════
    //
    // Runs the settlement logic against known-answer fixtures. Touches no database,
    // no wallet and no entities — it calls evaluateMarket() and price() directly, so
    // it is safe to run at any time (startup, admin endpoint, after a deploy).
    //
    //   int failures = settlementEngine.selfCheck();
    //
    // Returns the number of failing fixtures; 0 means the engine agrees with every
    // expectation below. Add a row whenever a settlement bug is found in the wild.

    /** market | selection | homeScore | awayScore | htHome | htAway | expected result */
    private static final String[][] MARKET_FIXTURES = {
            // 1X2 and its aliases
            {"1X2",            "HOME",        "2", "0", "", "", "WON"},
            {"match_result",   "away",        "2", "0", "", "", "LOST"},
            {"FT_RESULT",      "X",           "1", "1", "", "", "WON"},
            {"1X2",            "nonsense",    "1", "1", "", "", "VOID"},
            // Double chance
            {"DOUBLE_CHANCE",  "1X",          "0", "0", "", "", "WON"},
            {"DC",             "X2",          "1", "0", "", "", "LOST"},
            {"DOUBLE_CHANCE",  "12",          "1", "1", "", "", "LOST"},
            // Both teams to score
            {"BTTS",           "yes",         "1", "1", "", "", "WON"},
            {"both_to_score",  "NG",          "1", "0", "", "", "WON"},
            // Correct score, separator variants
            {"CORRECT_SCORE",  "2-1",         "2", "1", "", "", "WON"},
            {"Correct Score",  "2:1",         "2", "1", "", "", "WON"},
            {"CS",             "2 1",         "1", "2", "", "", "LOST"},
            // Over/under, including formats the old parser rejected
            {"OVER_UNDER",     "o2.5",        "1", "1", "", "", "LOST"},
            {"OVER_UNDER",     "Over 1,5",    "1", "1", "", "", "WON"},
            {"total_goals",    "under 2.5",   "1", "1", "", "", "WON"},
            {"OVER_UNDER",     "U2",          "1", "1", "", "", "PUSH"},
            {"OVER_UNDER",     "+2.5",        "1", "1", "", "", "LOST"},
            {"OVER_UNDER",     "over 2.25",   "1", "1", "", "", "VOID"},
            // Half time, present and missing
            {"HALF_TIME",      "AWAY",        "2", "2", "0",  "1", "WON"},
            {"Half Time",      "HOME",        "2", "2", "0",  "1", "LOST"},
            {"HT_RESULT",      "HOME",        "2", "2", "",   "",  "VOID"},
            // Asian handicap: whole, half, quarter
            {"ASIAN_HANDICAP", "HOME -1",     "2", "1", "", "", "PUSH"},
            {"ASIAN_HANDICAP", "HOME -1.5",   "2", "0", "", "", "WON"},
            {"AH",             "AWAY +0.5",   "1", "1", "", "", "WON"},
            {"ASIAN_HANDICAP", "HOME -0.25",  "0", "0", "", "", "HALF_LOST"},
            {"ASIAN_HANDICAP", "HOME +0.25",  "0", "0", "", "", "HALF_WON"},
            {"ASIAN_HANDICAP", "HOME +0.25",  "1", "0", "", "", "WON"},
            {"ASIAN_HANDICAP", "HOME: -1,5",  "3", "0", "", "", "WON"},
            {"AH",             "AWAY -1.5",   "0", "1", "", "", "LOST"},
            {"AH",             "AWAY -1.5",   "0", "3", "", "", "WON"},
            {"AH",             "AWAY +0.25",  "0", "0", "", "", "HALF_WON"},
            {"AH",             "AWAY -1",     "0", "1", "", "", "PUSH"},
            {"ASIAN_HANDICAP", "-1.5",        "3", "0", "", "", "VOID"},
            // Unknown market must void, never guess
            {"MYSTERY_MARKET", "HOME",        "1", "0", "", "", "VOID"},
    };

    /** stake | totalOdds | leg results as RESULT:ODDS,… | expected status | expected payout */
    private static final String[][] PRICING_FIXTURES = {
            {"100", "4.00", "WON:2.00,WON:2.00",       "WON",  "400.00"},
            {"100", "4.00", "WON:2.00,LOST:2.00",      "LOST", ""},
            {"50",  "3.00", "VOID:3.00",               "VOID", "50.00"},
            {"50",  "6.00", "VOID:3.00,PUSH:2.00",     "VOID", "50.00"},
            {"10",  "6.00", "WON:2.00,VOID:3.00",      "WON",  "20.00"},
            {"100", "2.00", "HALF_LOST:2.00",          "WON",  "50.00"},
            {"100", "2.00", "HALF_WON:2.00",           "WON",  "150.00"},
            {"100", "4.00", "WON:2.00,HALF_WON:2.00",  "WON",  "300.00"},
            // Must refuse to price rather than guess
            {"100", "4.00", "WON:2.00,PENDING:2.00",   "",     ""},
            {"100", "4.00", "WON:2.00,VOID:0",         "",     ""},
            // A won leg never divides its own odds back out, so a corrupt oddsLocked
            // on a winning leg is harmless — the payout comes from totalOdds.
            {"100", "4.00", "WON:2.00,WON:0",          "WON",  "400.00"},
    };

    /**
     * Verifies the settlement logic against the fixtures above.
     * @return number of failing fixtures (0 = everything agrees)
     */
    public int selfCheck() {
        int passed = 0, failed = 0;

        for (String[] f : MARKET_FIXTURES) {
            String market = f[0], selection = f[1], expected = f[6];
            int h = Integer.parseInt(f[2]), a = Integer.parseInt(f[3]);
            Integer htHome = f[4].isEmpty() ? null : Integer.valueOf(f[4]);
            Integer htAway = f[5].isEmpty() ? null : Integer.valueOf(f[5]);

            String actual;
            try {
                actual = evaluateMarket(market, selection, h, a, htHome, htAway, "self-check");
            } catch (Exception e) {
                actual = "EXCEPTION: " + e;
            }

            if (expected.equals(actual)) {
                passed++;
            } else {
                failed++;
                log.error("SELF-CHECK FAIL [market] {} '{}' at {}-{} → {} (expected {})",
                        market, selection, h, a, actual, expected);
            }
        }

        for (String[] f : PRICING_FIXTURES) {
            String expectedStatus = f[3], expectedPayout = f[4];

            List<String>     results = new ArrayList<>();
            List<BigDecimal> odds    = new ArrayList<>();
            for (String leg : f[2].split(",")) {
                String[] parts = leg.split(":");
                results.add(parts[0]);
                odds.add(new BigDecimal(parts[1]));
            }

            Pricing p;
            try {
                p = price(new BigDecimal(f[0]), new BigDecimal(f[1]), results, odds);
            } catch (Exception e) {
                failed++;
                log.error("SELF-CHECK FAIL [pricing] {} → EXCEPTION {}", f[2], e.toString());
                continue;
            }

            String actualStatus = p.priceable() ? p.status().name() : "";
            String actualPayout = p.payout() == null ? "" : p.payout().toPlainString();

            if (expectedStatus.equals(actualStatus) && expectedPayout.equals(actualPayout)) {
                passed++;
            } else {
                failed++;
                log.error("SELF-CHECK FAIL [pricing] stake={} odds={} legs={} → {}/{} (expected {}/{}) — {}",
                        f[0], f[1], f[2], actualStatus, actualPayout,
                        expectedStatus, expectedPayout, p.detail());
            }
        }

        if (failed == 0) {
            log.info("SELF-CHECK COMPLETE — {} fixture(s) passed, 0 failed", passed);
        } else {
            log.error("SELF-CHECK COMPLETE — {} passed, {} FAILED (see errors above)", passed, failed);
        }
        return failed;
    }
}
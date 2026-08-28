package com.speedbet.api.bet.cashout;

import com.speedbet.api.bet.Bet;
import com.speedbet.api.bet.BetRepository;
import com.speedbet.api.bet.BetSelection;
import com.speedbet.api.bet.BetStatus;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.match.MatchRepository;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cashout logic modelled after Sportybet's cashout behaviour.
 *
 * ── Formula (full cashout) ────────────────────────────────────────────────
 *
 *   rawCashout  = (stake × currentTotalOdds) / originalTotalOdds
 *   finalPayout = rawCashout × (1 − CASHOUT_MARGIN)
 *
 * ── Partial cashout ───────────────────────────────────────────────────────
 *
 *   partialPayout      = finalPayout × (percentage / 100)
 *   remainingStake     = stake       × (1 − percentage / 100)
 *   remainingPotReturn = potentialReturn × (1 − percentage / 100)
 *
 *   The original bet is updated in-place (stake + potentialReturn reduced).
 *   Status stays PENDING so the remaining portion can still be settled or
 *   cashed out again later.
 *
 * ── Cashout lock rules (mirrors Sportybet) ───────────────────────────────
 *
 *   Cashout is rejected when:
 *     - bet status is not PENDING
 *     - all selections have match status FINISHED / CANCELLED / POSTPONED
 *     - payout would be below MIN_CASHOUT_AMOUNT
 *     - partial percentage is outside [10, 90]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashoutService {

    // ── Config constants ──────────────────────────────────────────────────

    /** House margin deducted from the raw cashout value (4%). */
    private static final BigDecimal CASHOUT_MARGIN    = new BigDecimal("0.04");

    /** Minimum payout we will credit — anything below this is rejected. */
    private static final BigDecimal MIN_CASHOUT_AMOUNT = new BigDecimal("1.00");

    /** Partial cashout percentage bounds (Sportybet range). Full = 100. */
    private static final int PARTIAL_MIN_PCT = 10;
    private static final int PARTIAL_MAX_PCT = 90;

    // ── Dependencies ──────────────────────────────────────────────────────

    private final BetRepository   betRepo;
    private final OddsRepository  oddsRepo;
    private final MatchRepository matchRepo;
    private final WalletService   walletService;

    // ─────────────────────────────────────────────────────────────────────
    // Public DTOs
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returned after a successful cashout (full or partial).
     *
     * @param betId          the affected bet
     * @param type           "FULL" or "PARTIAL"
     * @param payout         amount credited to the wallet
     * @param remainingStake stake still live after a partial cashout (ZERO for full)
     */
    public record CashoutResult(
            UUID       betId,
            String     type,
            BigDecimal payout,
            BigDecimal remainingStake
    ) {}

    /**
     * Read-only preview — no state change.
     *
     * @param betId           the bet being previewed
     * @param type            "FULL" or "PARTIAL"
     * @param estimatedPayout what the user would receive right now
     * @param remainingStake  stake that would stay live (ZERO for full)
     * @param currentTotalOdds live total odds used for this calculation
     * @param status          whether cashout is currently AVAILABLE or UNAVAILABLE
     */
    public record CashoutPreview(
            UUID          betId,
            String        type,
            BigDecimal    estimatedPayout,
            BigDecimal    remainingStake,
            BigDecimal    currentTotalOdds,
            CashoutStatus status
    ) {}

    // ─────────────────────────────────────────────────────────────────────
    // Preview (no state change)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Preview cashout value without committing anything.
     * Safe to call on every bet-slip render.
     *
     * @param pct 100 = full; 10–90 = partial
     */
    public CashoutPreview preview(UUID betId, UUID userId, int pct) {
        Bet bet = fetchAndAuthorise(betId, userId);
        validateCashoutEligibility(bet);
        validatePartialPct(pct);

        BigDecimal currentOdds = resolveCurrentTotalOdds(bet);
        BigDecimal raw         = computeRawCashout(bet.getStake(), currentOdds, bet.getTotalOdds());
        BigDecimal full        = applyMargin(raw);

        BigDecimal factorBD  = BigDecimal.valueOf(pct).divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        BigDecimal payout    = pct == 100 ? full : full.multiply(factorBD, MathContext.DECIMAL64);
        BigDecimal remaining = pct == 100
                ? BigDecimal.ZERO
                : bet.getStake().multiply(BigDecimal.ONE.subtract(factorBD, MathContext.DECIMAL64), MathContext.DECIMAL64);

        return new CashoutPreview(
                betId,
                pct == 100 ? "FULL" : "PARTIAL",
                payout.setScale(2, RoundingMode.HALF_DOWN),
                remaining.setScale(2, RoundingMode.HALF_DOWN),
                currentOdds,
                cashoutStatusFor(bet)
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Full cashout
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Close the bet and credit the full cashout payout to the wallet.
     *
     * SERIALIZABLE isolation + the wallet's own PESSIMISTIC_WRITE lock
     * (inside WalletService.credit) prevent double-cashout races.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CashoutResult cashoutFull(UUID betId, UUID userId) {
        log.info("cashoutFull — betId={} userId={}", betId, userId);

        Bet bet = fetchAndAuthorise(betId, userId);
        validateCashoutEligibility(bet);

        BigDecimal currentOdds = resolveCurrentTotalOdds(bet);
        BigDecimal raw         = computeRawCashout(bet.getStake(), currentOdds, bet.getTotalOdds());
        BigDecimal payout      = applyMargin(raw);

        validateMinimumPayout(payout);

        // Stamp cashout fields on the bet then close it
        bet.setCashedOutAmount(payout.setScale(2, RoundingMode.HALF_DOWN));
        bet.setCashoutType("FULL");
        bet.setStatus(BetStatus.CASHED_OUT);
        bet.setSettledAt(Instant.now());
        betRepo.save(bet);
        log.info("cashoutFull — bet {} marked CASHED_OUT", betId);

        // Credit wallet — providerRef makes this idempotent
        String providerRef = "CASHOUT-FULL-" + betId;
        walletService.credit(
                userId,
                payout,
                TxKind.BET_CASHOUT_FULL,
                providerRef,
                Map.of(
                        "betId",        betId.toString(),
                        "originalOdds", bet.getTotalOdds().toPlainString(),
                        "currentOdds",  currentOdds.toPlainString(),
                        "rawCashout",   raw.toPlainString(),
                        "margin",       CASHOUT_MARGIN.toPlainString()
                )
        );
        log.info("cashoutFull — credited {} to userId={}", payout, userId);

        return new CashoutResult(
                betId,
                "FULL",
                payout.setScale(2, RoundingMode.HALF_DOWN),
                BigDecimal.ZERO
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Partial cashout
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cash out a percentage of the bet; the rest continues at a reduced stake.
     *
     * Sportybet behaviour replicated:
     *   - The remaining portion stays PENDING at proportionally reduced stake.
     *   - The user can cash out again later (odds will have moved).
     *   - pct must be in [10, 90].
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CashoutResult cashoutPartial(UUID betId, UUID userId, int pct) {
        log.info("cashoutPartial — betId={} userId={} pct={}", betId, userId, pct);

        validatePartialPct(pct);

        Bet bet = fetchAndAuthorise(betId, userId);
        validateCashoutEligibility(bet);

        BigDecimal factorBD     = BigDecimal.valueOf(pct).divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        BigDecimal remainFactor = BigDecimal.ONE.subtract(factorBD, MathContext.DECIMAL64);

        BigDecimal currentOdds  = resolveCurrentTotalOdds(bet);
        BigDecimal raw          = computeRawCashout(bet.getStake(), currentOdds, bet.getTotalOdds());
        BigDecimal fullPayout   = applyMargin(raw);
        BigDecimal partialPayout = fullPayout.multiply(factorBD, MathContext.DECIMAL64);

        validateMinimumPayout(partialPayout);

        // Reduce stake + potentialReturn in-place; keep bet PENDING
        BigDecimal newStake     = bet.getStake().multiply(remainFactor, MathContext.DECIMAL64);
        BigDecimal newPotReturn = bet.getPotentialReturn().multiply(remainFactor, MathContext.DECIMAL64);

        bet.setStake(newStake);
        bet.setPotentialReturn(newPotReturn);
        bet.setCashedOutAmount(partialPayout.setScale(2, RoundingMode.HALF_DOWN));
        bet.setCashoutType("PARTIAL");
        betRepo.save(bet);
        log.info("cashoutPartial — bet {} reduced stake={} potReturn={}", betId, newStake, newPotReturn);

        // providerRef includes timestamp so repeated partial cashouts don't collide
        String providerRef = "CASHOUT-PARTIAL-" + betId + "-PCT" + pct + "-" + Instant.now().toEpochMilli();
        walletService.credit(
                userId,
                partialPayout,
                TxKind.BET_CASHOUT_PARTIAL,
                providerRef,
                Map.of(
                        "betId",          betId.toString(),
                        "pct",            pct,
                        "originalOdds",   bet.getTotalOdds().toPlainString(),
                        "currentOdds",    currentOdds.toPlainString(),
                        "remainingStake", newStake.toPlainString()
                )
        );
        log.info("cashoutPartial — credited {} to userId={}", partialPayout, userId);

        return new CashoutResult(
                betId,
                "PARTIAL",
                partialPayout.setScale(2, RoundingMode.HALF_DOWN),
                newStake.setScale(2, RoundingMode.HALF_DOWN)
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Eligibility & validation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Throws if the bet cannot be cashed out right now.
     * Mirrors Sportybet's rules:
     *   - Bet must be PENDING.
     *   - At least one match must still be live/upcoming.
     */
    private void validateCashoutEligibility(Bet bet) {
        if (bet.getStatus() != BetStatus.PENDING) {
            log.warn("cashout rejected — bet {} status is {}", bet.getId(), bet.getStatus());
            throw ApiException.unprocessable(
                    "Cashout unavailable — bet is already " + bet.getStatus());
        }

        boolean allFinished = bet.getSelections().stream()
                .map(BetSelection::getMatchId)
                .distinct()
                .map(matchRepo::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .allMatch(m -> "FINISHED".equalsIgnoreCase(m.getStatus())
                        || "CANCELLED".equalsIgnoreCase(m.getStatus())
                        || "POSTPONED".equalsIgnoreCase(m.getStatus()));

        if (allFinished) {
            log.warn("cashout rejected — all matches for bet {} are finished", bet.getId());
            throw ApiException.unprocessable("Cashout unavailable — all matches have ended");
        }
    }

    private void validatePartialPct(int pct) {
        if (pct == 100) return;
        if (pct < PARTIAL_MIN_PCT || pct > PARTIAL_MAX_PCT)
            throw ApiException.badRequest(
                    "Partial cashout percentage must be between "
                            + PARTIAL_MIN_PCT + " and " + PARTIAL_MAX_PCT);
    }

    private void validateMinimumPayout(BigDecimal payout) {
        if (payout.compareTo(MIN_CASHOUT_AMOUNT) < 0)
            throw ApiException.unprocessable(
                    "Cashout value is below the minimum of GHS " + MIN_CASHOUT_AMOUNT);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Odds resolution
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Recalculate current total odds across all selections.
     *
     * Priority (mirrors BetService.resolveOdds):
     *   1. DB odds table (fresh value)
     *   2. oddsLocked on the selection (graceful fallback — warn + continue)
     */
    private BigDecimal resolveCurrentTotalOdds(Bet bet) {
        return bet.getSelections().stream()
                .map(s -> {
                    Optional<com.speedbet.api.odds.Odds> dbOdds =
                            oddsRepo.findFirstByMatchIdAndMarketAndSelection(
                                    s.getMatchId(), s.getMarket(), s.getSelection());
                    if (dbOdds.isPresent()) {
                        return dbOdds.get().getValue();
                    }
                    log.warn("resolveCurrentTotalOdds — DB miss matchId={} market={} selection={}, "
                                    + "falling back to lockedOdds={}",
                            s.getMatchId(), s.getMarket(), s.getSelection(), s.getOddsLocked());
                    return s.getOddsLocked();
                })
                .reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, MathContext.DECIMAL64));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Math helpers
    // ─────────────────────────────────────────────────────────────────────

    /** rawCashout = (stake × currentOdds) / originalOdds */
    private BigDecimal computeRawCashout(BigDecimal stake,
                                          BigDecimal currentOdds,
                                          BigDecimal originalOdds) {
        return stake.multiply(currentOdds, MathContext.DECIMAL64)
                    .divide(originalOdds, MathContext.DECIMAL64);
    }

    /** finalPayout = raw × (1 − CASHOUT_MARGIN) */
    private BigDecimal applyMargin(BigDecimal raw) {
        return raw.multiply(
                BigDecimal.ONE.subtract(CASHOUT_MARGIN, MathContext.DECIMAL64),
                MathContext.DECIMAL64);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────────────────────────────

    private Bet fetchAndAuthorise(UUID betId, UUID userId) {
        Bet bet = betRepo.findById(betId)
                .orElseThrow(() -> ApiException.notFound("Bet not found"));
        if (!bet.getUserId().equals(userId))
            throw ApiException.forbidden("Not your bet");
        return bet;
    }

    /**
     * Non-throwing status check — safe to call from preview and list endpoints.
     */
    public CashoutStatus cashoutStatusFor(Bet bet) {
        if (bet.getStatus() != BetStatus.PENDING) return CashoutStatus.UNAVAILABLE;

        boolean allFinished = bet.getSelections().stream()
                .map(BetSelection::getMatchId)
                .distinct()
                .map(matchRepo::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .allMatch(m -> "FINISHED".equalsIgnoreCase(m.getStatus())
                        || "CANCELLED".equalsIgnoreCase(m.getStatus())
                        || "POSTPONED".equalsIgnoreCase(m.getStatus()));

        return allFinished ? CashoutStatus.UNAVAILABLE : CashoutStatus.AVAILABLE;
    }
}
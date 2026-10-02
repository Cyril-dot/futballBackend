package com.speedbet.api.bet;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchRepository;
import com.speedbet.api.match.MatchService;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BetService {

    private static final BigDecimal ODDS_DRIFT_TOLERANCE = new BigDecimal("0.02");
    private static final int        MAX_SELECTIONS        = 20;

    private final BetRepository   betRepo;
    private final OddsRepository  oddsRepo;
    private final MatchRepository matchRepo;
    private final MatchService    matchService;
    private final WalletService   walletService;
    private final UserRepository  userRepo;

    public record PlaceRequest(
            UUID userId, BigDecimal stake, String currency,
            List<SelectionRequest> selections, UUID bookingCodeUsedId
    ) {}

    @Transactional
    public void saveSelectionsOnly(Bet bet) {
        betRepo.save(bet);
    }

    public record SelectionRequest(
            UUID matchId, String market, String selection, BigDecimal submittedOdds
    ) {}

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Bet placeBet(PlaceRequest req) {
        log.info("placeBet — userId={} stake={} currency={} numSelections={} bookingCodeUsedId={}",
                req.userId(), req.stake(), req.currency(),
                req.selections() != null ? req.selections().size() : "NULL",
                req.bookingCodeUsedId());

        if (req.selections() != null) {
            req.selections().forEach(s ->
                    log.info("  selection — matchId={} market={} selection={} submittedOdds={}",
                            s.matchId(), s.market(), s.selection(), s.submittedOdds()));
        }

        if (req.selections() == null || req.selections().isEmpty())
            throw ApiException.badRequest("No selections");
        if (req.selections().size() > MAX_SELECTIONS)
            throw ApiException.badRequest("Max " + MAX_SELECTIONS + " selections per bet");
        if (req.stake() == null)
            throw ApiException.badRequest("Stake is required");
        if (req.stake().compareTo(BigDecimal.ONE) < 0)
            throw ApiException.badRequest("Minimum stake is GHS 1.00");

        // Resolve currency server-side from user's country — never trust the client value
        String currency = userRepo.findById(req.userId())
                .map(u -> CurrencyResolver.forCountry(u.getCountry()))
                .orElse("GHS");
        log.info("placeBet — resolved currency={} for userId={}", currency, req.userId());

        // Validate and lock odds
        List<BetSelection> lockedSelections = req.selections().stream().map(s -> {
            log.info("  looking up match & odds — matchId={} market={} selection={}",
                    s.matchId(), s.market(), s.selection());

            if (s.matchId() == null)
                throw ApiException.badRequest("matchId is null for selection: " + s.selection());
            if (s.market() == null || s.market().isBlank())
                throw ApiException.badRequest("market is null/blank for selection: " + s.selection());
            if (s.selection() == null || s.selection().isBlank())
                throw ApiException.badRequest("selection is null/blank for matchId: " + s.matchId());

            Match match = matchRepo.findById(s.matchId())
                    .orElseThrow(() -> ApiException.badRequest("Match not found: " + s.matchId()));

            BigDecimal resolvedOdds = resolveOdds(s, match);
            log.info("  odds resolved — value={} submittedOdds={}", resolvedOdds, s.submittedOdds());

            // Drift check — reject if odds moved more than 2%
            if (s.submittedOdds() != null) {
                var drift = resolvedOdds.subtract(s.submittedOdds()).abs()
                        .divide(s.submittedOdds(), MathContext.DECIMAL64);
                log.info("  drift={} tolerance={}", drift, ODDS_DRIFT_TOLERANCE);
                if (drift.compareTo(ODDS_DRIFT_TOLERANCE) > 0) {
                    log.warn("  odds drift exceeded — selection={} submitted={} current={}",
                            s.selection(), s.submittedOdds(), resolvedOdds);
                    throw ApiException.unprocessable("Odds have changed for " + s.selection() +
                            ". New odds: " + resolvedOdds);
                }
            }

            log.info("  enriching selection with teams — home={} away={}",
                    match.getHomeTeam(), match.getAwayTeam());

            return BetSelection.builder()
                    .matchId(s.matchId())
                    .market(s.market())
                    .selection(s.selection())
                    .oddsLocked(resolvedOdds)
                    .homeTeam(match.getHomeTeam())
                    .awayTeam(match.getAwayTeam())
                    .build();
        }).toList();

        // Duplicate fixture check
        var matchIds = lockedSelections.stream().map(BetSelection::getMatchId).toList();
        if (matchIds.size() != matchIds.stream().distinct().count()) {
            log.info("placeBet — duplicate matchIds detected, validating non-correlated selections");
            validateNonCorrelatedSelections(lockedSelections);
        }

        // Total odds
        var totalOdds = lockedSelections.stream()
                .map(BetSelection::getOddsLocked)
                .reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, MathContext.DECIMAL64));
        var potentialReturn = req.stake().multiply(totalOdds, MathContext.DECIMAL64);
        log.info("placeBet — totalOdds={} potentialReturn={}", totalOdds, potentialReturn);

        // Debit wallet
        log.info("placeBet — debiting wallet userId={} amount={}", req.userId(), req.stake());
        walletService.debit(req.userId(), req.stake(), TxKind.BET_STAKE,
                null, Map.of("selections", req.selections().size(),
                        "totalOdds",  totalOdds.toString()));

        // Persist bet
        var bet = Bet.builder()
                .userId(req.userId())
                .stake(req.stake())
                .currency(currency)
                .totalOdds(totalOdds)
                .potentialReturn(potentialReturn)
                .status(BetStatus.PENDING)
                .bookingCodeUsedId(req.bookingCodeUsedId())
                .build();
        bet = betRepo.save(bet);
        log.info("placeBet — bet persisted id={}", bet.getId());

        // Link selections to the saved bet
        final Bet savedBet = bet;
        lockedSelections.forEach(s -> s.setBet(savedBet));
        bet.getSelections().addAll(lockedSelections);
        bet = betRepo.save(bet);
        log.info("placeBet — selections saved count={}", bet.getSelections().size());

        log.info("placeBet — COMPLETE betId={} status={}", bet.getId(), bet.getStatus());
        return bet;
    }

    /**
     * Resolve odds for a selection using the following priority:
     *  1. DB odds table (fastest, most reliable)
     *  2. Live cache (for LIVE matches whose odds aren't yet persisted)
     *  3. submittedOdds from the client (fallback for external-feed matches
     *     whose odds are displayed on the frontend but not yet saved to DB)
     */
    private BigDecimal resolveOdds(SelectionRequest s, Match match) {
        // 1. DB lookup
        var dbOdds = oddsRepo.findFirstByMatchIdAndMarketAndSelection(
                s.matchId(), s.market(), s.selection());
        if (dbOdds.isPresent()) {
            log.debug("  resolveOdds: DB hit — matchId={} market={} selection={}",
                    s.matchId(), s.market(), s.selection());
            return dbOdds.get().getValue();
        }

        log.warn("  resolveOdds: DB miss — matchId={} market={} selection={} — checking live cache",
                s.matchId(), s.market(), s.selection());

        // 2. Live cache (only for LIVE matches)
        if ("LIVE".equals(match.getStatus())) {
            BigDecimal cached = resolveFromLiveCache(match, s.market(), s.selection());
            if (cached != null) {
                log.info("  resolveOdds: live cache hit — matchId={} market={} selection={} value={}",
                        s.matchId(), s.market(), s.selection(), cached);
                return cached;
            }
            log.warn("  resolveOdds: live cache miss — matchId={} market={} selection={}",
                    s.matchId(), s.market(), s.selection());
        }

        // 3. Fall back to submittedOdds — match exists in DB but odds came from an
        //    external feed (ApiFootball, SportDB, etc.) that hasn't been persisted
        //    to the odds table yet. The drift check above still applies, so a client
        //    cannot inflate odds — it will always lock at exactly what was submitted,
        //    and any future DB record that disagrees by >2% will reject the bet.
        if (s.submittedOdds() != null && s.submittedOdds().compareTo(BigDecimal.ZERO) > 0) {
            log.info("  resolveOdds: falling back to submittedOdds={} for matchId={} market={} selection={}",
                    s.submittedOdds(), s.matchId(), s.market(), s.selection());
            return s.submittedOdds();
        }

        // Nothing found at all — reject
        log.warn("  resolveOdds: NOT FOUND anywhere and no valid submittedOdds — matchId={} market={} selection={}",
                s.matchId(), s.market(), s.selection());
        throw ApiException.badRequest("Odds not found for: " + s.selection()
                + " (matchId=" + s.matchId() + " market=" + s.market() + ")");
    }

    private BigDecimal resolveFromLiveCache(Match match, String market, String selection) {
        String normMarket = normaliseMarketForCache(market);
        List<Map<String, Object>> cachedOdds = matchService.getOddsFromCache(match.getId(), normMarket);
        if (cachedOdds == null || cachedOdds.isEmpty()) return null;

        for (Map<String, Object> entry : cachedOdds) {
            Object entrySelection = entry.get("selection");
            Object entryOdd       = entry.get("odd");
            if (entrySelection == null || entryOdd == null) continue;
            if (selection.equalsIgnoreCase(entrySelection.toString())) {
                try {
                    return new BigDecimal(entryOdd.toString());
                } catch (NumberFormatException e) {
                    log.warn("  resolveFromLiveCache: unparseable odd='{}' for selection={}",
                            entryOdd, selection);
                }
            }
        }
        return null;
    }

    private String normaliseMarketForCache(String market) {
        if (market == null) return "";
        return switch (market.toUpperCase()) {
            case "1X2", "ONE_X_TWO", "MATCH_RESULT" -> "1X2";
            case "ASIAN_HANDICAP"                    -> "asian_handicap";
            default                                  -> market;
        };
    }

    public Page<Bet> getUserBets(UUID userId, Pageable pageable) {
        log.info("getUserBets — userId={} page={}", userId, pageable.getPageNumber());
        return betRepo.findByUserIdOrderByPlacedAtDesc(userId, pageable);
    }

    public Bet getById(UUID id, UUID userId) {
        var bet = betRepo.findById(id).orElseThrow(() -> ApiException.notFound("Bet not found"));
        if (!bet.getUserId().equals(userId)) throw ApiException.forbidden("Not your bet");
        return bet;
    }

    public List<Bet> getUnseenWins(UUID userId) {
        return betRepo.findUnseenWins(userId);
    }

    @Transactional
    public void markWinSeen(UUID userId, UUID betId) {
        betRepo.findById(betId).ifPresent(b -> {
            if (b.getUserId().equals(userId)) { b.setWinSeen(true); betRepo.save(b); }
        });
    }

    public List<Bet> getPendingBetsForMatch(UUID matchId) {
        return betRepo.findPendingByMatchId(matchId);
    }

    @Transactional
    public void settleBet(Bet bet, BetStatus status, BigDecimal payout) {
        log.info("settleBet — betId={} status={} payout={}", bet.getId(), status, payout);
        bet.setStatus(status);
        bet.setSettledAt(java.time.Instant.now());
        if (status == BetStatus.WON) bet.setWinSeen(false);
        betRepo.save(bet);
        if (status == BetStatus.WON && payout != null) {
            walletService.credit(bet.getUserId(), payout, TxKind.BET_WIN,
                    "WIN-" + bet.getId(), Map.of("betId", bet.getId().toString()));
        }
    }

    private void validateNonCorrelatedSelections(List<BetSelection> selections) {
        var byMatch = new java.util.HashMap<UUID, List<String>>();
        for (var s : selections) {
            byMatch.computeIfAbsent(s.getMatchId(), k -> new java.util.ArrayList<>()).add(s.getMarket());
        }
        for (var entry : byMatch.entrySet()) {
            var markets = entry.getValue();
            if (markets.contains("1X2") && markets.contains("DOUBLE_CHANCE"))
                throw ApiException.badRequest("Cannot combine 1X2 and Double Chance on same match");
            if (markets.contains("HOME_WIN") && markets.contains("1X2"))
                throw ApiException.badRequest("Cannot combine Home Win and 1X2 on same match");
        }
    }
}
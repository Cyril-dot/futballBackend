package com.speedbet.api.sportsdata.odds;

import com.speedbet.api.match.Match;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OddsPersistenceService {

    private final OddsRepository          oddsRepository;
    private final OddsGeneratorService     preMatchGenerator;
    private final LiveOddsGeneratorService liveGenerator;
    private final HalfTimeOddsService      halfTimeGenerator;
    private final HandicapOddsService      handicapGenerator;
    private final CorrectScoreOddsService  correctScoreGenerator;
    private final EntityManager            entityManager;

    // ── Pre-match: saves all markets (1X2, HT, handicap, correct score) ──

    @Transactional
    public void generateAndSaveAllOdds(Match match) {
        String home    = match.getHomeTeam();
        String away    = match.getAwayTeam();
        String league  = match.getLeague();
        UUID   matchId = match.getId();

        List<Map<String, Object>> allOdds = new ArrayList<>();
        allOdds.addAll(preMatchGenerator.generatePreMatchOdds(home, away, league));
        allOdds.addAll(halfTimeGenerator.generateHalfTimeOdds(home, away, league));
        allOdds.addAll(handicapGenerator.generateHandicapOdds(home, away, league));
        allOdds.addAll(correctScoreGenerator.generateCorrectScoreOdds(home, away, league));

        List<Odds> entities = toEntities(allOdds, matchId, home, away);

        oddsRepository.deleteByMatchId(matchId);
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveAllOdds: matchId={} {} vs {} — saved {} odds rows",
                matchId, home, away, entities.size());
    }

    // ── Live: replaces only 1X2 + asian_handicap rows ────────────────────

    @Transactional
    public void generateAndSaveLiveOdds(Match match) {
        String home      = match.getHomeTeam();
        String away      = match.getAwayTeam();
        int    scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
        int    scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
        int    minute    = extractMinute(match);
        UUID   matchId   = match.getId();

        List<Map<String, Object>> liveOdds = new ArrayList<>();
        liveOdds.addAll(liveGenerator.generateLiveOdds(home, away, scoreHome, scoreAway, minute));
        liveOdds.addAll(handicapGenerator.generateLiveHandicapOdds(home, away, scoreHome, scoreAway, minute));

        List<Odds> entities = toEntities(liveOdds, matchId, home, away);

        oddsRepository.deleteByMatchIdAndMarketIn(matchId, List.of("1X2", "asian_handicap"));
        oddsRepository.flush();
        oddsRepository.saveAll(entities);

        log.info("generateAndSaveLiveOdds: matchId={} score={}-{} min={} — saved {} rows",
                matchId, scoreHome, scoreAway, minute, entities.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private int extractMinute(Match match) {
        if (match.getMetadata() != null) {
            Object min = match.getMetadata().get("minute");
            if (min != null) {
                try { return Integer.parseInt(min.toString()); } catch (NumberFormatException ignored) {}
            }
        }
        if (match.getKickoffAt() != null) {
            long elapsed = ChronoUnit.MINUTES.between(match.getKickoffAt(), Instant.now());
            return (int) Math.min(Math.max(elapsed, 0), 95);
        }
        return 45;
    }

    private List<Odds> toEntities(List<Map<String, Object>> odds, UUID matchId,
                                  String home, String away) {
        Instant now = Instant.now();
        List<Odds> result = new ArrayList<>();
        // O(1) duplicate check — replaces the O(n²) stream scan
        Set<String> seen = new HashSet<>();

        for (Map<String, Object> o : odds) {
            Object rawOdd = o.get("odd");
            if (rawOdd == null) {
                log.warn("toEntities: matchId={} skipping null odd — selection={}",
                        matchId, o.get("selection"));
                continue;
            }

            BigDecimal oddValue;
            try {
                oddValue = parseOddValue(rawOdd.toString());
            } catch (Exception e) {
                log.warn("toEntities: matchId={} unparseable odd='{}' selection={} — {}",
                        matchId, rawOdd, o.get("selection"), e.getMessage());
                continue;
            }

            if (oddValue.compareTo(BigDecimal.ONE) < 0) {
                log.warn("toEntities: matchId={} skipping odd={} < 1.0 for selection={}",
                        matchId, oddValue, o.get("selection"));
                continue;
            }

            BigDecimal handicapVal = null;
            if (o.get("handicap") != null) {
                try {
                    handicapVal = parseSpread(o.get("handicap").toString());
                } catch (Exception e) {
                    log.warn("toEntities: matchId={} could not parse handicap='{}' — setting null",
                            matchId, o.get("handicap"));
                }
            }

            String normalizedMarket    = normalizeMarket((String) o.get("market"));
            String normalizedSelection = normalizeSelection((String) o.get("selection"), home, away);

            String dedupeKey = normalizedMarket + "|" + normalizedSelection;
            if (!seen.add(dedupeKey)) {
                log.debug("toEntities: matchId={} skipping duplicate market={} selection={}",
                        matchId, normalizedMarket, normalizedSelection);
                continue;
            }

            result.add(Odds.builder()
                    .matchId(matchId)
                    .market(normalizedMarket)
                    .selection(normalizedSelection)
                    .value(oddValue)
                    .handicap(handicapVal)
                    .capturedAt(now)
                    .build());
        }

        return result;
    }

    /**
     * Parses an odds value that may arrive in multiple formats:
     *
     *   Decimal:    "1.85"   → BigDecimal("1.85")
     *   Fractional: "3/1"    → BigDecimal("4.00")   (numerator/denominator + 1)
     *   Integer:    "2"      → BigDecimal("2")
     */
    private BigDecimal parseOddValue(String raw) {
        String s = raw.trim();

        // Fractional format — e.g. "3/1", "11/2", "1/4"
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length != 2) {
                throw new NumberFormatException("Cannot parse fractional odd: " + raw);
            }
            BigDecimal numerator   = new BigDecimal(parts[0].trim());
            BigDecimal denominator = new BigDecimal(parts[1].trim());
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                throw new ArithmeticException("Zero denominator in fractional odd: " + raw);
            }
            return numerator
                    .divide(denominator, MathContext.DECIMAL64)
                    .add(BigDecimal.ONE)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return new BigDecimal(s);
    }

    /**
     * Parses spread/handicap values that may arrive as double-sided strings:
     *
     *   "+4/-4"   → BigDecimal("4")   (take the positive side)
     *   "-3/+3"   → BigDecimal("-3")  (take the first token)
     *   "+6.5"    → BigDecimal("6.5")
     *   "-2.5"    → BigDecimal("-2.5")
     */
    private BigDecimal parseSpread(String raw) {
        String s = raw.trim();
        // Double-sided format e.g. "+4/-4" — take the first token
        if (s.contains("/")) {
            s = s.split("/")[0].trim();
        }
        return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeMarket(String market) {
        if (market == null) return "UNKNOWN";
        return switch (market.toLowerCase()) {
            case "1x2", "match_result" -> "1X2";
            case "half_time"           -> "half_time";
            case "asian_handicap"      -> "asian_handicap";
            case "correct_score"       -> "correct_score";
            default                    -> market.toUpperCase();
        };
    }

    private String normalizeSelection(String selection, String homeTeam, String awayTeam) {
        if (selection == null) return "UNKNOWN";
        if (selection.equalsIgnoreCase("draw"))                    return "DRAW";
        if (selection.equalsIgnoreCase(homeTeam))                  return "HOME";
        if (selection.equalsIgnoreCase(awayTeam))                  return "AWAY";
        if (selection.equalsIgnoreCase("Push/Refund") ||
                selection.toLowerCase().contains("push"))          return "PUSH";
        return selection;
    }
}
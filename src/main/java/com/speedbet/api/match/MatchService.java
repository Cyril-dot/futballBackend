package com.speedbet.api.match;

import com.speedbet.api.ai.MistralClient;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.CompetitionIds;
import com.speedbet.api.sportsdata.EspnFootballDataService;
import com.speedbet.api.sportsdata.Top6LeagueTeams;
import com.speedbet.api.sportsdata.odds.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchRepository             matchRepo;
    private final OddsRepository              oddsRepo;
    private final MistralClient               mistralClient;
    private final EspnFootballDataService     espnFootballDataService;

    // ── Odds generators ───────────────────────────────────────────────────
    private final OddsGeneratorService        oddsGeneratorService;
    private final LiveOddsGeneratorService    liveOddsGeneratorService;
    private final CorrectScoreOddsService     correctScoreOddsService;
    private final HalfTimeOddsService         halfTimeOddsService;
    private final HandicapOddsService         handicapOddsService;

    // ── Live odds caches ──────────────────────────────────────────────────
    private static final long LIVE_ODDS_TTL_MS = 2 * 60_000L;

    private final ConcurrentHashMap<UUID, OddsCacheEntry>            liveOddsCache         = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OddsCacheEntry>            liveHandicapCache     = new ConcurrentHashMap<>();

    // ── Pre-match odds caches (deterministic — no TTL needed) ─────────────
    // Prevents re-computing 18 handicap lines × 5 bookmakers per match on every request.
    private final ConcurrentHashMap<UUID, List<Map<String, Object>>> preMatchHandicapCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<Map<String, Object>>> preMatchOddsCache     = new ConcurrentHashMap<>();

    private record OddsCacheEntry(List<Map<String, Object>> odds, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() <= expiresAt; }
    }

    // ── Status transition guard ───────────────────────────────────────────
    private static boolean isPermittedTransition(String existing, String incoming) {
        if (existing == null || existing.equals(incoming)) return true;
        return switch (existing) {
            case "FINISHED" -> false;
            case "LIVE"     -> "FINISHED".equals(incoming);
            default         -> true;
        };
    }

    private final Set<String> warnedDemotions = ConcurrentHashMap.newKeySet();

    // ── Pre-built display-name sets ───────────────────────────────────────
    private static final Set<String> CUP_NAMES =
            Arrays.stream(CompetitionIds.CupCompetition.values())
                    .map(CompetitionIds.CupCompetition::displayName)
                    .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> TOP6_CUP_NAMES =
            Arrays.stream(CompetitionIds.CupCompetition.top6Related())
                    .map(CompetitionIds.CupCompetition::displayName)
                    .collect(Collectors.toUnmodifiableSet());

    // ── Helpers ───────────────────────────────────────────────────────────
    private static boolean isMissing(String val) {
        return val == null || val.isBlank();
    }

    private static boolean isRealKickoff(Instant t) {
        return t != null && t.getNano() == 0;
    }

    private static boolean hasLogos(Match m) {
        return !isMissing(m.getHomeLogo()) && !isMissing(m.getAwayLogo());
    }

    private static final Comparator<Match> LOGO_THEN_KICKOFF =
            Comparator.comparingInt((Match m) -> hasLogos(m) ? 0 : 1)
                    .thenComparing(m -> m.getKickoffAt() != null ? m.getKickoffAt() : Instant.MAX);

    private static boolean leagueIn(Match m, Set<String> names) {
        if (m.getLeague() == null) return false;
        return names.stream().anyMatch(n -> n.equalsIgnoreCase(m.getLeague()));
    }

    private UUID toUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw ApiException.notFound("Match not found: " + id); }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    public boolean isOddsCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveOddsCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveOddsCache.put(matchId, new OddsCacheEntry(odds, expires));
        log.debug("cacheLiveOdds: cached 1X2 odds for matchId={} expires in {}ms", matchId, LIVE_ODDS_TTL_MS);
    }

    public boolean isHandicapCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveHandicapCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveHandicapOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveHandicapCache.put(matchId, new OddsCacheEntry(odds, expires));
        log.debug("cacheLiveHandicapOdds: cached handicap odds for matchId={} expires in {}ms", matchId, LIVE_ODDS_TTL_MS);
    }

    public List<Map<String, Object>> getOddsFromCache(UUID matchId, String market) {
        return switch (market) {
            case "1X2" -> {
                OddsCacheEntry entry = liveOddsCache.get(matchId);
                yield (entry != null && entry.isValid()) ? entry.odds() : null;
            }
            case "asian_handicap" -> {
                OddsCacheEntry entry = liveHandicapCache.get(matchId);
                yield (entry != null && entry.isValid()) ? entry.odds() : null;
            }
            default -> {
                log.debug("getOddsFromCache: no cache for market='{}' matchId={}", market, matchId);
                yield null;
            }
        };
    }

    // ── Pre-match cache helpers ───────────────────────────────────────────

    /**
     * Evicts a match from both pre-match caches. Call this after a match
     * transitions away from UPCOMING/SCHEDULED (e.g. goes LIVE or FINISHED)
     * so stale odds aren't served.
     */
    public void evictPreMatchCache(UUID matchId) {
        preMatchOddsCache.remove(matchId);
        preMatchHandicapCache.remove(matchId);
        log.debug("evictPreMatchCache: evicted matchId={}", matchId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // BASIC QUERIES — DB-backed
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "LIVE");
        log.info("getLiveMatches: {} LIVE football match(es) found", matches.size());
        return matches;
    }

    public List<Match> getLiveMatches(String sport) {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt(sport, "LIVE");
        log.info("getLiveMatches(sport='{}'): {} LIVE match(es) found", sport, matches.size());
        return matches;
    }

    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport("football", now, now.plus(7, ChronoUnit.DAYS));
        List<Match> sorted  = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        int withLogos    = (int) sorted.stream().filter(MatchService::hasLogos).count();
        int withoutLogos = sorted.size() - withLogos;
        log.info("getUpcomingMatches: {} upcoming football matches — {} with logos, {} without",
                sorted.size(), withLogos, withoutLogos);
        return sorted;
    }

    @Cacheable("todayMatches")
    public List<Match> getTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetweenAndSport("football", startOfDay, endOfDay);
        log.info("getTodayMatches: {} football match(es) today UTC", matches.size());
        return matches;
    }

    public List<Match> getFutureMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport("football", now, now.plus(7, ChronoUnit.DAYS));
        List<Match> sorted  = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        int withLogos    = (int) sorted.stream().filter(MatchService::hasLogos).count();
        int withoutLogos = sorted.size() - withLogos;
        log.info("getFutureMatches: {} football match(es) next 7 days — {} with logos, {} without",
                sorted.size(), withLogos, withoutLogos);
        return sorted;
    }

    public List<Match> getRecentResults() {
        return getRecentResultsLimited(20);
    }

    public List<Match> getRecentResultsLimited(int limit) {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "FINISHED").stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .limit(limit)
                .toList();
        log.info("getRecentResults: returning {} FINISHED football match(es) (72h window, cap={})",
                matches.size(), limit);
        return matches;
    }

    @Cacheable("featuredMatches")
    public List<Match> getFeaturedMatches() {
        List<Match> matches = matchRepo.findByFeaturedTrueOrderByKickoffAt().stream()
                .filter(m -> "football".equalsIgnoreCase(m.getSport()))
                .toList();
        log.info("getFeaturedMatches: {} featured football match(es)", matches.size());
        return matches;
    }

    public Match getById(String id) {
        return matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("Match not found: " + id));
    }

    // ══════════════════════════════════════════════════════════════════════
    // TOP-6 LEAGUES
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getTop6LiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "LIVE").stream()
                .filter(Top6LeagueTeams::isKnownTop6Match)
                .toList();
        log.info("getTop6LiveMatches: {} LIVE football match(es) in top-6 leagues (team-validated)", matches.size());
        return matches;
    }

    public List<Match> getTop6UpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport("football", now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(Top6LeagueTeams::isKnownTop6Match)
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getTop6UpcomingMatches: {} upcoming football match(es) in top-6 leagues (team-validated)", matches.size());
        return matches;
    }

    public List<Match> getTop6TodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetweenAndSport("football", startOfDay, endOfDay).stream()
                .filter(Top6LeagueTeams::isKnownTop6Match)
                .toList();
        log.info("getTop6TodayMatches: {} football match(es) today in top-6 leagues (team-validated)", matches.size());
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TOP-6 CUPS
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getTop6CupsLiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "LIVE").stream()
                .filter(m -> leagueIn(m, TOP6_CUP_NAMES))
                .toList();
        log.info("getTop6CupsLiveMatches: {} LIVE football cup match(es)", matches.size());
        return matches;
    }

    public List<Match> getTop6CupsUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport("football", now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> leagueIn(m, TOP6_CUP_NAMES))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getTop6CupsUpcomingMatches: {} upcoming football cup match(es)", matches.size());
        return matches;
    }

    public List<Match> getTop6CupsTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetweenAndSport("football", startOfDay, endOfDay).stream()
                .filter(m -> leagueIn(m, TOP6_CUP_NAMES))
                .toList();
        log.info("getTop6CupsTodayMatches: {} football cup match(es) today", matches.size());
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ALL CUPS
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getAllCupsLiveMatches() {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "LIVE").stream()
                .filter(m -> leagueIn(m, CUP_NAMES))
                .toList();
        log.info("getAllCupsLiveMatches: {} LIVE football cup match(es) (all cups)", matches.size());
        return matches;
    }

    public List<Match> getAllCupsUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport("football", now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> leagueIn(m, CUP_NAMES))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getAllCupsUpcomingMatches: {} upcoming football cup match(es) (all cups)", matches.size());
        return matches;
    }

    public List<Match> getAllCupsTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetweenAndSport("football", startOfDay, endOfDay).stream()
                .filter(m -> leagueIn(m, CUP_NAMES))
                .toList();
        log.info("getAllCupsTodayMatches: {} football cup match(es) today (all cups)", matches.size());
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BY-COMPETITION-ENUM QUERIES — CompetitionIds variants (kept for
    // internal / admin callers that still use the old enum types)
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getLiveMatchesByLeagueEnum(CompetitionIds.Top6League league) {
        return getLiveMatchesByLeague(league.displayName());
    }

    public List<Match> getUpcomingMatchesByLeagueEnum(CompetitionIds.Top6League league) {
        return getUpcomingMatchesByLeague(league.displayName());
    }

    public List<Match> getTodayMatchesByLeagueEnum(CompetitionIds.Top6League league) {
        return getTodayMatchesByLeague(league.displayName());
    }

    public List<Match> getLiveMatchesByCupEnum(CompetitionIds.CupCompetition cup) {
        return getLiveMatchesByLeague(cup.displayName());
    }

    public List<Match> getUpcomingMatchesByCupEnum(CompetitionIds.CupCompetition cup) {
        return getUpcomingMatchesByLeague(cup.displayName());
    }

    public List<Match> getTodayMatchesByCupEnum(CompetitionIds.CupCompetition cup) {
        return getTodayMatchesByLeague(cup.displayName());
    }

    // ── EspnLeague overloads — used directly by MatchController ──────────

    public List<Match> getLiveMatchesByLeagueEnum(EspnFootballDataService.EspnLeague league) {
        return getLiveMatchesByLeague(league.displayName());
    }

    public List<Match> getTodayMatchesByLeagueEnum(EspnFootballDataService.EspnLeague league) {
        return getTodayMatchesByLeague(league.displayName());
    }

    public List<Match> getUpcomingMatchesByLeagueEnum(EspnFootballDataService.EspnLeague league) {
        return getUpcomingMatchesByLeague(league.displayName());
    }

    // ── EspnCup overloads — used directly by MatchController ─────────────

    public List<Match> getLiveMatchesByCupEnum(EspnFootballDataService.EspnCup cup) {
        return getLiveMatchesByLeague(cup.displayName());
    }

    public List<Match> getTodayMatchesByCupEnum(EspnFootballDataService.EspnCup cup) {
        return getTodayMatchesByLeague(cup.displayName());
    }

    public List<Match> getUpcomingMatchesByCupEnum(EspnFootballDataService.EspnCup cup) {
        return getUpcomingMatchesByLeague(cup.displayName());
    }

    // ══════════════════════════════════════════════════════════════════════
    // BY-LEAGUE QUERIES
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getLiveMatchesByLeague(String leagueName) {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "LIVE").stream()
                .filter(m -> leagueName.equalsIgnoreCase(m.getLeague()))
                .filter(m -> isTop6LeagueValidatedOrPassThrough(m, leagueName))
                .toList();
        log.info("getLiveMatchesByLeague: {} LIVE football match(es) for league='{}'", matches.size(), leagueName);
        return matches;
    }

    public List<Match> getUpcomingMatchesByLeague(String leagueName) {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport("football", now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> leagueName.equalsIgnoreCase(m.getLeague()))
                .filter(m -> isTop6LeagueValidatedOrPassThrough(m, leagueName))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getUpcomingMatchesByLeague: {} upcoming football match(es) for league='{}'", matches.size(), leagueName);
        return matches;
    }

    public List<Match> getTodayMatchesByLeague(String leagueName) {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetweenAndSport("football", startOfDay, endOfDay).stream()
                .filter(m -> leagueName.equalsIgnoreCase(m.getLeague()))
                .filter(m -> isTop6LeagueValidatedOrPassThrough(m, leagueName))
                .toList();
        log.info("getTodayMatchesByLeague: {} football match(es) today for league='{}'", matches.size(), leagueName);
        return matches;
    }

    private boolean isTop6LeagueValidatedOrPassThrough(Match m, String leagueName) {
        return Top6LeagueTeams.fromLeagueName(leagueName)
                .map(t -> t.isValidMatch(m))
                .orElse(true);
    }

    // ══════════════════════════════════════════════════════════════════════
    // BY-TEAM QUERIES
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getLiveMatchesByTeamName(String teamName) {
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "LIVE").stream()
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("getLiveMatchesByTeamName: {} LIVE football match(es) for team='{}'", matches.size(), teamName);
        return matches;
    }

    public List<Match> getUpcomingMatchesByTeamName(String teamName) {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduledBySport("football", now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getUpcomingMatchesByTeamName: {} upcoming football match(es) for team='{}'", matches.size(), teamName);
        return matches;
    }

    public List<Match> getRecentResultsByTeamName(String teamName) {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findBySportAndStatusOrderByKickoffAt("football", "FINISHED").stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("getRecentResultsByTeamName: {} recent football result(s) for team='{}'", matches.size(), teamName);
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIST + ODDS BUNDLES
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> withOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        List<Match> sorted = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        log.debug("withOdds: bundling odds for {} match(es)", sorted.size());
        List<Map<String, Object>> out = new ArrayList<>(sorted.size());
        for (Match match : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", match);
            String status = match.getStatus();
            if ("LIVE".equals(status)) {
                OddsCacheEntry cached = liveOddsCache.get(match.getId());
                if (cached != null && cached.isValid()) {
                    entry.put("odds", cached.odds());
                } else {
                    entry.put("odds", oddsGeneratorService.generatePreMatchOdds(
                            match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
                }
            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                entry.put("odds", preMatchOddsCache.computeIfAbsent(match.getId(), id ->
                        oddsGeneratorService.generatePreMatchOdds(
                                match.getHomeTeam(), match.getAwayTeam(), match.getLeague())));
            } else {
                entry.put("odds", List.of());
            }
            out.add(entry);
        }
        log.debug("withOdds: bundled {} entries", out.size());
        return out;
    }

    public List<Map<String, Object>> withAllOdds(List<Match> matches) {
        if (matches.isEmpty()) return Collections.emptyList();
        List<Match> sorted = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        log.debug("withAllOdds: bundling all markets for {} match(es)", sorted.size());
        List<Map<String, Object>> out = new ArrayList<>(sorted.size());
        for (Match match : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", match);
            String status = match.getStatus();
            if ("LIVE".equals(status)) {
                OddsCacheEntry oddsEntry     = liveOddsCache.get(match.getId());
                OddsCacheEntry handicapEntry = liveHandicapCache.get(match.getId());
                List<Map<String, Object>> matchResult = (oddsEntry != null && oddsEntry.isValid())
                        ? oddsEntry.odds()
                        : oddsGeneratorService.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
                List<Map<String, Object>> asianHandicap = (handicapEntry != null && handicapEntry.isValid())
                        ? handicapEntry.odds()
                        : handicapOddsService.generateHandicapOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
                entry.put("match_result",   matchResult);
                entry.put("asian_handicap", asianHandicap);
            } else if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
                // Use pre-match caches: odds are seeded/deterministic so no TTL needed.
                // computeIfAbsent is thread-safe and prevents redundant generation
                // across concurrent requests for the same match.
                List<Map<String, Object>> matchResult = preMatchOddsCache.computeIfAbsent(
                        match.getId(), id -> oddsGeneratorService.generatePreMatchOdds(
                                match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
                List<Map<String, Object>> asianHandicap = preMatchHandicapCache.computeIfAbsent(
                        match.getId(), id -> handicapOddsService.generateHandicapOdds(
                                match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
                entry.put("match_result",   matchResult);
                entry.put("asian_handicap", asianHandicap);
            } else {
                entry.put("match_result",   List.of());
                entry.put("asian_handicap", List.of());
            }
            out.add(entry);
        }
        log.debug("withAllOdds: bundled {} entries", out.size());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVE ODDS CACHE REFRESH
    // ══════════════════════════════════════════════════════════════════════

    public void refreshLiveOddsCache(List<Match> liveMatches) {
        if (liveMatches.isEmpty()) return;
        int refreshed1X2 = 0, refreshedHandicap = 0;
        for (Match match : liveMatches) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            try {
                List<Map<String, Object>> liveOdds = liveOddsGeneratorService.generateLiveOdds(
                        match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
                cacheLiveOdds(match.getId(), liveOdds);
                refreshed1X2++;
            } catch (Exception e) {
                log.warn("refreshLiveOddsCache [1X2]: matchId={} failed — {}", match.getId(), e.getMessage());
            }
            try {
                List<Map<String, Object>> liveHandicap = handicapOddsService.generateLiveHandicapOdds(
                        match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
                cacheLiveHandicapOdds(match.getId(), liveHandicap);
                refreshedHandicap++;
            } catch (Exception e) {
                log.warn("refreshLiveOddsCache [Handicap]: matchId={} failed — {}", match.getId(), e.getMessage());
            }
        }
        log.info("refreshLiveOddsCache: 1X2={}/{} Handicap={}/{} match(es) refreshed",
                refreshed1X2, liveMatches.size(), refreshedHandicap, liveMatches.size());
    }

    private int extractMinute(Match match) {
        if (match.getMetadata() != null) {
            Object min = match.getMetadata().get("minute");
            if (min != null) {
                try { return Integer.parseInt(min.toString()); } catch (NumberFormatException ignored) {}
            }
        }
        if (match.getKickoffAt() != null && isRealKickoff(match.getKickoffAt())) {
            long elapsed = ChronoUnit.MINUTES.between(match.getKickoffAt(), Instant.now());
            return (int) Math.min(Math.max(elapsed, 0), 95);
        }
        return 45;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ODDS — direct endpoints
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getMatchOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();
        if ("LIVE".equals(status)) {
            OddsCacheEntry cached = liveOddsCache.get(match.getId());
            if (cached != null && cached.isValid()) return cached.odds();
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> generated = liveOddsGeneratorService.generateLiveOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            cacheLiveOdds(match.getId(), generated);
            return generated;
        }
        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return preMatchOddsCache.computeIfAbsent(match.getId(), id2 ->
                    oddsGeneratorService.generatePreMatchOdds(
                            match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
        }
        return List.of();
    }

    public List<Map<String, Object>> getCorrectScoreOdds(String id) {
        Match match = getById(id);
        return correctScoreOddsService.generateCorrectScoreOdds(
                match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
    }

    public List<Map<String, Object>> getHalfTimeOdds(String id) {
        Match match = getById(id);
        if ("LIVE".equals(match.getStatus())) {
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> liveHt = halfTimeOddsService.generateLiveHalfTimeOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            if (!liveHt.isEmpty()) return liveHt;
        }
        return halfTimeOddsService.generateHalfTimeOdds(
                match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
    }

    public List<Map<String, Object>> getHandicapOdds(String id) {
        Match match = getById(id);
        String status = match.getStatus();
        if ("LIVE".equals(status)) {
            OddsCacheEntry cached = liveHandicapCache.get(match.getId());
            if (cached != null && cached.isValid()) return cached.odds();
            int scoreHome = match.getScoreHome() != null ? match.getScoreHome() : 0;
            int scoreAway = match.getScoreAway() != null ? match.getScoreAway() : 0;
            int minute    = extractMinute(match);
            List<Map<String, Object>> generated = handicapOddsService.generateLiveHandicapOdds(
                    match.getHomeTeam(), match.getAwayTeam(), scoreHome, scoreAway, minute);
            cacheLiveHandicapOdds(match.getId(), generated);
            return generated;
        }
        if ("UPCOMING".equals(status) || "SCHEDULED".equals(status)) {
            return preMatchHandicapCache.computeIfAbsent(match.getId(), id2 ->
                    handicapOddsService.generateHandicapOdds(
                            match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
        }
        return List.of();
    }

    public Map<String, Object> getAllOddsForMatch(String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("match_result",   getMatchOdds(id));
        result.put("correct_score",  getCorrectScoreOdds(id));
        result.put("half_time",      getHalfTimeOdds(id));
        result.put("asian_handicap", getHandicapOdds(id));
        return result;
    }

    public List<Odds> getOddsForMatch(String id) {
        return oddsRepo.findByMatchId(toUuid(id));
    }

    // ══════════════════════════════════════════════════════════════════════
    // MATCH DETAIL / EVENTS / H2H / STATS / LINEUPS
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getMatchDetail(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league != null) {
            String eventId = stripEspnPrefix(match.getExternalId());
            Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
            if (!summary.isEmpty()) return Map.of("source", "espn-football", "data", summary);
        }
        return Map.of();
    }

    public Map<String, Object> getEvents(String id) {
        Match match = getById(id);
        if (match.getMetadata() != null && !match.getMetadata().isEmpty()) {
            return match.getMetadata();
        }
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league != null && match.getExternalId() != null) {
            String eventId = stripEspnPrefix(match.getExternalId());
            Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
            if (!summary.isEmpty()) return Map.of("source", "espn-football", "data", summary);
        }
        return Map.of("events", List.of());
    }

    @Cacheable(value = "h2h", key = "#id")
    public Map<String, Object> getH2H(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league != null) {
            String eventId = stripEspnPrefix(match.getExternalId());
            Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
            List<Map<String, Object>> h2h = espnFootballDataService.extractHeadToHead(summary);
            if (!h2h.isEmpty()) return Map.of("source", "espn-football", "data", h2h);
        }
        return Map.of();
    }

    public Map<String, Object> getStats(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league != null) {
            String eventId = stripEspnPrefix(match.getExternalId());
            Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
            if (!summary.isEmpty()) {
                return Map.of("source", "espn-football", "type", "match_stats", "data", summary);
            }
        }
        return Map.of();
    }

    @Cacheable(value = "lineups", key = "#id")
    public Map<String, Object> getLineups(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league != null) {
            String eventId = stripEspnPrefix(match.getExternalId());
            Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
            if (!summary.isEmpty()) return Map.of("source", "espn-football", "data", summary);
        }
        return Map.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // FINISHED MATCHES (ESPN) — CompetitionIds variants + EspnLeague/Cup overloads
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getEspnFinishedMatchesByLeague(CompetitionIds.Top6League league) {
        EspnFootballDataService.EspnLeague espnLeague = toEspnLeague(league);
        if (espnLeague == null) {
            log.warn("getEspnFinishedMatchesByLeague: no ESPN mapping for league='{}'", league.displayName());
            return List.of();
        }
        return fetchFinishedByEspnLeague(espnLeague, league.displayName());
    }

    /** Called directly from MatchController via EspnLeague path variable. */
    public List<Map<String, Object>> getEspnFinishedMatchesByLeague(EspnFootballDataService.EspnLeague league) {
        return fetchFinishedByEspnLeague(league, league.displayName());
    }

    private List<Map<String, Object>> fetchFinishedByEspnLeague(
            EspnFootballDataService.EspnLeague espnLeague, String displayName) {
        log.info("getEspnFinishedMatchesByLeague: fetching finished events for league='{}'", displayName);
        List<Map<String, Object>> finished = espnFootballDataService.getFinishedMatches(espnLeague);
        log.info("getEspnFinishedMatchesByLeague: {} finished event(s) for league='{}'", finished.size(), displayName);
        return finished;
    }

    public List<Map<String, Object>> getEspnFinishedMatchesByCup(CompetitionIds.CupCompetition cup) {
        EspnFootballDataService.EspnCup espnCup = toEspnCup(cup);
        if (espnCup == null) {
            log.warn("getEspnFinishedMatchesByCup: no ESPN mapping for cup='{}'", cup.displayName());
            return List.of();
        }
        return fetchFinishedByEspnCup(espnCup, cup.displayName());
    }

    /** Called directly from MatchController via EspnCup path variable. */
    public List<Map<String, Object>> getEspnFinishedMatchesByCup(EspnFootballDataService.EspnCup cup) {
        return fetchFinishedByEspnCup(cup, cup.displayName());
    }

    private List<Map<String, Object>> fetchFinishedByEspnCup(
            EspnFootballDataService.EspnCup espnCup, String displayName) {
        log.info("getEspnFinishedMatchesByCup: fetching finished events for cup='{}'", displayName);
        List<Map<String, Object>> finished = espnFootballDataService.getCupFinishedMatches(espnCup);
        log.info("getEspnFinishedMatchesByCup: {} finished event(s) for cup='{}'", finished.size(), displayName);
        return finished;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ALL-LEAGUES TODAY (ESPN)
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getEspnAllLeaguesTodayMatches() {
        log.info("getEspnAllLeaguesTodayMatches: fetching today's events from all ESPN leagues");
        List<Map<String, Object>> events = espnFootballDataService.getAllLeaguesTodayMatches();
        log.info("getEspnAllLeaguesTodayMatches: {} total deduplicated event(s) returned", events.size());
        return events;
    }

    public List<Map<String, Object>> getEspnAllCupsTodayMatches() {
        log.info("getEspnAllCupsTodayMatches: fetching today's events from all ESPN cup competitions");
        List<Map<String, Object>> events = espnFootballDataService.getAllCupsTodayMatches();
        log.info("getEspnAllCupsTodayMatches: {} total deduplicated event(s) returned", events.size());
        return events;
    }

    // ══════════════════════════════════════════════════════════════════════
    // FIXTURES BY DATE (ESPN)
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getEspnTop6FixturesByDate(String date) {
        log.info("getEspnTop6FixturesByDate: fetching Top-6 league fixtures for date='{}'", date);
        List<Map<String, Object>> fixtures = espnFootballDataService.getTop6UpcomingFixturesByDate(date);
        log.info("getEspnTop6FixturesByDate: {} event(s) for date='{}'", fixtures.size(), date);
        return fixtures;
    }

    public List<Map<String, Object>> getEspnTop6CupsFixturesByDate(String date) {
        log.info("getEspnTop6CupsFixturesByDate: fetching Top-6 cup fixtures for date='{}'", date);
        List<Map<String, Object>> fixtures = espnFootballDataService.getTop6CupsUpcomingFixturesByDate(date);
        log.info("getEspnTop6CupsFixturesByDate: {} event(s) for date='{}'", fixtures.size(), date);
        return fixtures;
    }

    // ══════════════════════════════════════════════════════════════════════
    // CUP MATCH SUMMARY — CompetitionIds + EspnCup overloads
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getCupMatchDetail(CompetitionIds.CupCompetition cup, String eventId) {
        EspnFootballDataService.EspnCup espnCup = toEspnCup(cup);
        if (espnCup == null) {
            log.warn("getCupMatchDetail: no ESPN mapping for cup='{}' eventId='{}'", cup.displayName(), eventId);
            return Map.of();
        }
        return fetchCupMatchDetail(espnCup, cup.displayName(), eventId);
    }

    /** Called directly from MatchController via EspnCup path variable. */
    public Map<String, Object> getCupMatchDetail(EspnFootballDataService.EspnCup cup, String eventId) {
        return fetchCupMatchDetail(cup, cup.displayName(), eventId);
    }

    private Map<String, Object> fetchCupMatchDetail(
            EspnFootballDataService.EspnCup espnCup, String displayName, String eventId) {
        log.info("getCupMatchDetail: fetching summary for cup='{}' eventId='{}'", displayName, eventId);
        Map<String, Object> summary = espnFootballDataService.getCupMatchSummary(espnCup, eventId);
        if (summary.isEmpty()) {
            log.warn("getCupMatchDetail: empty summary for cup='{}' eventId='{}'", displayName, eventId);
            return Map.of();
        }
        log.info("getCupMatchDetail: summary fetched for cup='{}' eventId='{}'", displayName, eventId);
        return Map.of("source", "espn-football", "cup", displayName, "data", summary);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ENRICHED MATCH DATA EXTRACTORS (ESPN)
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getEspnMatchOdds(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) {
            log.debug("getEspnMatchOdds: matchId={} has no externalId", id);
            return Map.of();
        }
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league == null) {
            log.debug("getEspnMatchOdds: no ESPN league resolved for matchId={}", id);
            return Map.of();
        }
        String eventId = stripEspnPrefix(match.getExternalId());
        log.info("getEspnMatchOdds: extracting ESPN odds for matchId={} eventId='{}'", id, eventId);
        Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
        Map<String, Object> odds    = espnFootballDataService.extractMatchOdds(summary);
        log.info("getEspnMatchOdds: {} provider(s) found for matchId={}", odds.size(), id);
        return odds;
    }

    public Map<String, List<Map<String, Object>>> getEspnGoalscorerOdds(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) {
            log.debug("getEspnGoalscorerOdds: matchId={} has no externalId", id);
            return Map.of();
        }
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league == null) {
            log.debug("getEspnGoalscorerOdds: no ESPN league resolved for matchId={}", id);
            return Map.of();
        }
        String eventId = stripEspnPrefix(match.getExternalId());
        log.info("getEspnGoalscorerOdds: extracting goalscorer markets for matchId={} eventId='{}'", id, eventId);
        Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
        Map<String, List<Map<String, Object>>> markets = espnFootballDataService.extractPlayerGoalscorerOdds(summary);
        log.info("getEspnGoalscorerOdds: {} market(s) found for matchId={}", markets.size(), id);
        return markets;
    }

    public List<Map<String, Object>> getEspnRecentForm(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) {
            log.debug("getEspnRecentForm: matchId={} has no externalId", id);
            return List.of();
        }
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league == null) {
            log.debug("getEspnRecentForm: no ESPN league resolved for matchId={}", id);
            return List.of();
        }
        String eventId = stripEspnPrefix(match.getExternalId());
        log.info("getEspnRecentForm: fetching recent form for matchId={} eventId='{}'", id, eventId);
        Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
        List<Map<String, Object>> form = espnFootballDataService.extractRecentForm(summary);
        log.info("getEspnRecentForm: {} team form block(s) for matchId={}", form.size(), id);
        return form;
    }

    public List<Map<String, Object>> getEspnMatchNews(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) {
            log.debug("getEspnMatchNews: matchId={} has no externalId", id);
            return List.of();
        }
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league == null) {
            log.debug("getEspnMatchNews: no ESPN league resolved for matchId={}", id);
            return List.of();
        }
        String eventId = stripEspnPrefix(match.getExternalId());
        log.info("getEspnMatchNews: fetching news articles for matchId={} eventId='{}'", id, eventId);
        Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
        List<Map<String, Object>> articles = espnFootballDataService.extractMatchNews(summary);
        log.info("getEspnMatchNews: {} article(s) for matchId={}", articles.size(), id);
        return articles;
    }

    public List<Map<String, Object>> getEspnMatchVideos(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) {
            log.debug("getEspnMatchVideos: matchId={} has no externalId", id);
            return List.of();
        }
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league == null) {
            log.debug("getEspnMatchVideos: no ESPN league resolved for matchId={}", id);
            return List.of();
        }
        String eventId = stripEspnPrefix(match.getExternalId());
        log.info("getEspnMatchVideos: fetching videos for matchId={} eventId='{}'", id, eventId);
        Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
        List<Map<String, Object>> videos = espnFootballDataService.extractMatchVideos(summary);
        log.info("getEspnMatchVideos: {} video(s) for matchId={}", videos.size(), id);
        return videos;
    }

    public String getEspnMatchVenue(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) {
            log.debug("getEspnMatchVenue: matchId={} has no externalId", id);
            return "";
        }
        EspnFootballDataService.EspnLeague league = resolveEspnLeague(match);
        if (league == null) {
            log.debug("getEspnMatchVenue: no ESPN league resolved for matchId={}", id);
            return "";
        }
        String eventId = stripEspnPrefix(match.getExternalId());
        log.info("getEspnMatchVenue: fetching venue for matchId={} eventId='{}'", id, eventId);
        Map<String, Object> summary = espnFootballDataService.getMatchSummary(league, eventId);
        Object eventData = summary.get("header");
        if (eventData instanceof Map<?, ?> header) {
            @SuppressWarnings("unchecked")
            Map<String, Object> headerMap = (Map<String, Object>) header;
            Object competitions = headerMap.get("competitions");
            if (competitions instanceof java.util.List<?> compList && !compList.isEmpty()) {
                Map<String, Object> fakeEvent = new LinkedHashMap<>();
                fakeEvent.put("competitions", compList);
                String venue = EspnFootballDataService.extractVenue(fakeEvent);
                log.info("getEspnMatchVenue: venue='{}' for matchId={}", venue, id);
                return venue;
            }
        }
        log.debug("getEspnMatchVenue: no venue data in summary for matchId={}", id);
        return "";
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEAMS & TEAM SCHEDULES — CompetitionIds + EspnLeague overloads
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getEspnTeamsByLeague(CompetitionIds.Top6League league) {
        EspnFootballDataService.EspnLeague espnLeague = toEspnLeague(league);
        if (espnLeague == null) {
            log.warn("getEspnTeamsByLeague: no ESPN mapping for league='{}'", league.displayName());
            return Map.of();
        }
        return fetchTeamsByEspnLeague(espnLeague, league.displayName());
    }

    /** Called directly from MatchController via EspnLeague path variable. */
    public Map<String, Object> getEspnTeamsByLeague(EspnFootballDataService.EspnLeague league) {
        return fetchTeamsByEspnLeague(league, league.displayName());
    }

    private Map<String, Object> fetchTeamsByEspnLeague(
            EspnFootballDataService.EspnLeague espnLeague, String displayName) {
        log.info("getEspnTeamsByLeague: fetching team list for league='{}'", displayName);
        Map<String, Object> teams = espnFootballDataService.getTeams(espnLeague);
        log.info("getEspnTeamsByLeague: team data fetched for league='{}'", displayName);
        return teams;
    }

    public Map<String, Object> getEspnTeamSchedule(CompetitionIds.Top6League league, String teamId) {
        EspnFootballDataService.EspnLeague espnLeague = toEspnLeague(league);
        if (espnLeague == null) {
            log.warn("getEspnTeamSchedule: no ESPN mapping for league='{}' teamId='{}'", league.displayName(), teamId);
            return Map.of();
        }
        return fetchTeamSchedule(espnLeague, league.displayName(), teamId);
    }

    /** Called directly from MatchController via EspnLeague path variable. */
    public Map<String, Object> getEspnTeamSchedule(EspnFootballDataService.EspnLeague league, String teamId) {
        return fetchTeamSchedule(league, league.displayName(), teamId);
    }

    private Map<String, Object> fetchTeamSchedule(
            EspnFootballDataService.EspnLeague espnLeague, String displayName, String teamId) {
        log.info("getEspnTeamSchedule: fetching schedule for teamId='{}' in league='{}'", teamId, displayName);
        Map<String, Object> schedule = espnFootballDataService.getTeamSchedule(espnLeague, teamId);
        log.info("getEspnTeamSchedule: schedule data fetched for teamId='{}' league='{}'", teamId, displayName);
        return schedule;
    }

    // ══════════════════════════════════════════════════════════════════════
    // CACHE MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════

    public void clearEspnCache() {
        log.info("clearEspnCache: clearing all ESPN scoreboard/standings cache entries");
        espnFootballDataService.clearCache();
        log.info("clearEspnCache: ESPN cache cleared");
    }

    public void invalidateEspnCacheKey(String key) {
        log.info("invalidateEspnCacheKey: invalidating ESPN cache key='{}'", key);
        espnFootballDataService.invalidateCache(key);
        log.info("invalidateEspnCacheKey: done for key='{}'", key);
    }

    // ══════════════════════════════════════════════════════════════════════
    // STANDINGS / SCORERS — CompetitionIds + EspnLeague/Cup overloads
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getStandingsByLeague(CompetitionIds.Top6League league) {
        EspnFootballDataService.EspnLeague espnLeague = toEspnLeague(league);
        if (espnLeague == null) return Map.of();
        return espnFootballDataService.getStandings(espnLeague);
    }

    /** Called directly from MatchController via EspnLeague path variable. */
    public Map<String, Object> getStandingsByLeague(EspnFootballDataService.EspnLeague league) {
        return espnFootballDataService.getStandings(league);
    }

    public Map<String, Object> getStandingsByCup(CompetitionIds.CupCompetition cup) {
        EspnFootballDataService.EspnCup espnCup = toEspnCup(cup);
        if (espnCup == null) return Map.of();
        return espnFootballDataService.getCupStandings(espnCup);
    }

    /** Called directly from MatchController via EspnCup path variable. */
    public Map<String, Object> getStandingsByCup(EspnFootballDataService.EspnCup cup) {
        return espnFootballDataService.getCupStandings(cup);
    }

    public Map<String, Object> getStandingsByLeagueComp(CompetitionIds.LeagueCompetition league) {
        EspnFootballDataService.EspnLeague espnLeague = toEspnLeagueFromComp(league);
        if (espnLeague == null) return Map.of();
        return espnFootballDataService.getStandings(espnLeague);
    }

    public Map<String, Map<String, Object>> getAllTop6Standings() {
        return espnFootballDataService.getTop6Standings();
    }

    public Map<String, Object> getTopScorersByLeague(CompetitionIds.Top6League league) {
        log.debug("getTopScorersByLeague: top-scorers endpoint not available via ESPN for league='{}'",
                league.displayName());
        return Map.of();
    }

    /** Called directly from MatchController via EspnLeague path variable. */
    public Map<String, Object> getTopScorersByLeague(EspnFootballDataService.EspnLeague league) {
        log.debug("getTopScorersByLeague: top-scorers endpoint not available via ESPN for league='{}'",
                league.displayName());
        return Map.of();
    }

    public Map<String, Object> getTopScorersByLeagueComp(CompetitionIds.LeagueCompetition league) {
        log.debug("getTopScorersByLeagueComp: top-scorers endpoint not available via ESPN for league='{}'",
                league.displayName());
        return Map.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ESPN FOOTBALL PASS-THROUGH HELPERS
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getEspnFootballLive() {
        return espnFootballDataService.getTop6LiveMatches();
    }

    public List<Map<String, Object>> getEspnFootballToday() {
        return espnFootballDataService.getTop6TodayMatches();
    }

    public List<Map<String, Object>> getEspnFootballFixtures() {
        return espnFootballDataService.getTop6UpcomingMatches();
    }

    public List<Map<String, Object>> getEspnFootballTop6Live() {
        return espnFootballDataService.getTop6LiveMatches();
    }

    public List<Map<String, Object>> getEspnFootballTop6CupsLive() {
        return espnFootballDataService.getTop6CupsLiveMatches();
    }

    public List<Map<String, Object>> getEspnFootballTop6Fixtures() {
        return espnFootballDataService.getTop6UpcomingMatches();
    }

    public List<Map<String, Object>> getEspnFootballTop6CupFixtures() {
        return espnFootballDataService.getTop6CupsUpcomingMatches();
    }

    public List<Map<String, Object>> getEspnFootballTop6AndCupFixtures() {
        List<Map<String, Object>> combined = new ArrayList<>();
        combined.addAll(espnFootballDataService.getTop6UpcomingMatches());
        combined.addAll(espnFootballDataService.getTop6CupsUpcomingMatches());
        return combined;
    }

    // ── EspnLeague overloads ──────────────────────────────────────────────

    public List<Map<String, Object>> getEspnFootballLiveByLeague(CompetitionIds.Top6League league) {
        EspnFootballDataService.EspnLeague espnLeague = toEspnLeague(league);
        if (espnLeague == null) return List.of();
        return espnFootballDataService.getLiveMatches(espnLeague);
    }

    /** Called directly from MatchController via EspnLeague path variable. */
    public List<Map<String, Object>> getEspnFootballLiveByLeague(EspnFootballDataService.EspnLeague league) {
        return espnFootballDataService.getLiveMatches(league);
    }

    public List<Map<String, Object>> getEspnFootballFixturesByLeague(CompetitionIds.Top6League league) {
        EspnFootballDataService.EspnLeague espnLeague = toEspnLeague(league);
        if (espnLeague == null) return List.of();
        return espnFootballDataService.getUpcomingMatches(espnLeague);
    }

    /** Called directly from MatchController via EspnLeague path variable. */
    public List<Map<String, Object>> getEspnFootballFixturesByLeague(EspnFootballDataService.EspnLeague league) {
        return espnFootballDataService.getUpcomingMatches(league);
    }

    // ── EspnCup overloads ─────────────────────────────────────────────────

    public List<Map<String, Object>> getEspnFootballLiveByCup(CompetitionIds.CupCompetition cup) {
        EspnFootballDataService.EspnCup espnCup = toEspnCup(cup);
        if (espnCup == null) return List.of();
        return espnFootballDataService.getCupLiveMatches(espnCup);
    }

    /** Called directly from MatchController via EspnCup path variable. */
    public List<Map<String, Object>> getEspnFootballLiveByCup(EspnFootballDataService.EspnCup cup) {
        return espnFootballDataService.getCupLiveMatches(cup);
    }

    public List<Map<String, Object>> getEspnFootballFixturesByCup(CompetitionIds.CupCompetition cup) {
        EspnFootballDataService.EspnCup espnCup = toEspnCup(cup);
        if (espnCup == null) return List.of();
        return espnFootballDataService.getCupUpcomingMatches(espnCup);
    }

    /** Called directly from MatchController via EspnCup path variable. */
    public List<Map<String, Object>> getEspnFootballFixturesByCup(EspnFootballDataService.EspnCup cup) {
        return espnFootballDataService.getCupUpcomingMatches(cup);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PREDICTIONS
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getPrediction(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        Map<String, Object> context = new HashMap<>();
        context.put("home_team", match.getHomeTeam());
        context.put("away_team", match.getAwayTeam());
        context.put("league",    match.getLeague());
        context.put("kickoff",   match.getKickoffAt());
        try {
            Map<String, Object> ai = mistralClient.predictMatch(context);
            if (ai != null && !ai.isEmpty()) return Map.of("source", "ai", "data", ai);
        } catch (Exception e) {
            log.warn("getPrediction: matchId={} failed — {}", id, e.getMessage());
        }
        return Map.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match saveOrUpdate(Match match) {
        if (match.getExternalId() == null || match.getExternalId().isBlank())
            return matchRepo.save(match);

        return matchRepo.findByExternalId(match.getExternalId())
                .map(existing -> {
                    if (match.getStatus() != null) {
                        if (isPermittedTransition(existing.getStatus(), match.getStatus())) {
                            // If transitioning out of UPCOMING/SCHEDULED, evict pre-match odds caches
                            if (("UPCOMING".equals(existing.getStatus()) || "SCHEDULED".equals(existing.getStatus()))
                                    && !match.getStatus().equals(existing.getStatus())) {
                                evictPreMatchCache(existing.getId());
                            }
                            existing.setStatus(match.getStatus());
                        } else {
                            if (warnedDemotions.add(existing.getExternalId())) {
                                log.warn("saveOrUpdate: blocked status demotion externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            } else {
                                log.debug("saveOrUpdate: repeated demotion blocked externalId={} {} → {} (keeping {})",
                                        existing.getExternalId(),
                                        existing.getStatus(), match.getStatus(),
                                        existing.getStatus());
                            }
                            return matchRepo.save(existing);
                        }
                    }

                    if (match.getScoreHome() != null) existing.setScoreHome(match.getScoreHome());
                    if (match.getScoreAway() != null) existing.setScoreAway(match.getScoreAway());
                    if (match.getMetadata()  != null) existing.setMetadata(match.getMetadata());
                    if (!isMissing(match.getLeague())) existing.setLeague(match.getLeague());
                    if (isMissing(existing.getHomeTeam())   && !isMissing(match.getHomeTeam()))   existing.setHomeTeam(match.getHomeTeam());
                    if (isMissing(existing.getAwayTeam())   && !isMissing(match.getAwayTeam()))   existing.setAwayTeam(match.getAwayTeam());
                    if ((isMissing(existing.getSport()) || existing.getSportEnum() == null)
                            && (!isMissing(match.getSport()) || match.getSportEnum() != null)) {
                        Sport sportEnum = match.getSportEnum() != null
                                ? match.getSportEnum()
                                : Sport.fromKey(match.getSport());
                        existing.setSportEnum(sportEnum);
                        System.out.println("Updated sport enum: " + sportEnum);
                    }
                    if (isMissing(existing.getHomeLogo())   && !isMissing(match.getHomeLogo()))   existing.setHomeLogo(match.getHomeLogo());
                    if (isMissing(existing.getAwayLogo())   && !isMissing(match.getAwayLogo()))   existing.setAwayLogo(match.getAwayLogo());
                    if (isMissing(existing.getLeagueLogo()) && !isMissing(match.getLeagueLogo())) existing.setLeagueLogo(match.getLeagueLogo());
                    if (existing.getSource() == null && match.getSource() != null)                existing.setSource(match.getSource());

                    if (match.getKickoffAt() != null) {
                        boolean existingMissing = existing.getKickoffAt() == null
                                || !isRealKickoff(existing.getKickoffAt());
                        boolean incomingReal    = isRealKickoff(match.getKickoffAt());
                        if (existingMissing && incomingReal) {
                            log.debug("saveOrUpdate: healing kickoffAt externalId={} old={} new={}",
                                    existing.getExternalId(), existing.getKickoffAt(), match.getKickoffAt());
                            existing.setKickoffAt(match.getKickoffAt());
                        } else if (existing.getKickoffAt() == null) {
                            existing.setKickoffAt(match.getKickoffAt());
                        }
                    }

                    log.debug("saveOrUpdate: updated externalId={} status='{}' home='{}' away='{}' league='{}' kickoff='{}'",
                            existing.getExternalId(), existing.getStatus(),
                            existing.getHomeTeam(), existing.getAwayTeam(),
                            existing.getLeague(), existing.getKickoffAt());
                    return matchRepo.save(existing);
                })
                .orElseGet(() -> {
                    log.debug("saveOrUpdate: inserting new externalId={} home='{}' away='{}' league='{}' kickoff='{}'",
                            match.getExternalId(), match.getHomeTeam(), match.getAwayTeam(),
                            match.getLeague(), match.getKickoffAt());
                    return matchRepo.save(match);
                });
    }

    @Transactional
    @CacheEvict(value = {"matches", "todayMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff) {
        List<Match> stale = matchRepo.findStaleLiveBySport("football", cutoff);
        if (stale.isEmpty()) return 0;
        log.info("finishStaleLiveMatches: force-finishing {} stale football match(es)", stale.size());
        for (Match m : stale) { m.setStatus("FINISHED"); matchRepo.save(m); }
        return stale.size();
    }

    @Transactional
    @CacheEvict(value = {"matches", "todayMatches"}, allEntries = true)
    public int finishStaleLiveMatches(Instant cutoff, String sport) {
        List<Match> stale = matchRepo.findStaleLiveBySport(sport, cutoff);
        if (stale.isEmpty()) return 0;
        log.info("finishStaleLiveMatches(sport='{}'): force-finishing {} stale match(es)", sport, stale.size());
        for (Match m : stale) { m.setStatus("FINISHED"); matchRepo.save(m); }
        return stale.size();
    }

    public List<Match> getUnsettledFinished() {
        return matchRepo.findUnsettledFinishedBySport("football");
    }

    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE — ESPN ROUTING HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private static String stripEspnPrefix(String externalId) {
        if (externalId.startsWith("espn-")) return externalId.substring(5);
        return externalId;
    }

    private EspnFootballDataService.EspnLeague resolveEspnLeague(Match match) {
        if (match.getLeague() == null) return null;
        String league = match.getLeague();
        for (EspnFootballDataService.EspnLeague l : EspnFootballDataService.EspnLeague.values()) {
            if (l.displayName().equalsIgnoreCase(league)) return l;
        }
        log.debug("resolveEspnLeague: no EspnLeague mapping for league='{}' matchId={}", league, match.getId());
        return null;
    }

    private EspnFootballDataService.EspnLeague toEspnLeague(CompetitionIds.Top6League league) {
        for (EspnFootballDataService.EspnLeague l : EspnFootballDataService.EspnLeague.values()) {
            if (l.displayName().equalsIgnoreCase(league.displayName())) return l;
        }
        log.debug("toEspnLeague: no mapping for Top6League='{}'", league.displayName());
        return null;
    }

    private EspnFootballDataService.EspnLeague toEspnLeagueFromComp(CompetitionIds.LeagueCompetition league) {
        for (EspnFootballDataService.EspnLeague l : EspnFootballDataService.EspnLeague.values()) {
            if (l.displayName().equalsIgnoreCase(league.displayName())) return l;
        }
        log.debug("toEspnLeagueFromComp: no mapping for LeagueCompetition='{}'", league.displayName());
        return null;
    }

    private EspnFootballDataService.EspnCup toEspnCup(CompetitionIds.CupCompetition cup) {
        for (EspnFootballDataService.EspnCup c : EspnFootballDataService.EspnCup.values()) {
            if (c.displayName().equalsIgnoreCase(cup.displayName())) return c;
        }
        log.debug("toEspnCup: no mapping for CupCompetition='{}'", cup.displayName());
        return null;
    }
}
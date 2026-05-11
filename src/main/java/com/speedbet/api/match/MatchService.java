package com.speedbet.api.match;

import com.speedbet.api.ai.MistralClient;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.odds.Odds;
import com.speedbet.api.odds.OddsRepository;
import com.speedbet.api.sportsdata.CompetitionIds;
import com.speedbet.api.sportsdata.LiveScoreApiClient;
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

    private final MatchRepository           matchRepo;
    private final OddsRepository            oddsRepo;
    private final MistralClient             mistralClient;
    private final LiveScoreApiClient        liveScoreApiClient;

    // ── Odds generators ───────────────────────────────────────────────────
    private final OddsGeneratorService      oddsGeneratorService;
    private final LiveOddsGeneratorService  liveOddsGeneratorService;
    private final CorrectScoreOddsService   correctScoreOddsService;
    private final HalfTimeOddsService       halfTimeOddsService;
    private final HandicapOddsService       handicapOddsService;

    // ── Live odds caches ──────────────────────────────────────────────────
    private static final long LIVE_ODDS_TTL_MS = 2 * 60_000L;

    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveOddsCache     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OddsCacheEntry> liveHandicapCache = new ConcurrentHashMap<>();

    private record OddsCacheEntry(List<Map<String, Object>> odds, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() <= expiresAt; }
    }

    // ── Status transition guard ───────────────────────────────────────────
    // A FINISHED match must never be demoted back to LIVE or UPCOMING by a
    // stale poll event.  This table defines which transitions are permitted:
    //
    //   UPCOMING  → LIVE      ✓
    //   UPCOMING  → FINISHED  ✓
    //   LIVE      → FINISHED  ✓
    //   LIVE      → UPCOMING  ✗  (poller mis-classified; ignore)
    //   FINISHED  → LIVE      ✗  (ghost event from stale API data)
    //   FINISHED  → UPCOMING  ✗  (ghost event from stale API data)
    //
    private static boolean isPermittedTransition(String existing, String incoming) {
        if (existing == null || existing.equals(incoming)) return true;
        return switch (existing) {
            case "FINISHED" -> false;                            // FINISHED is terminal
            case "LIVE"     -> "FINISHED".equals(incoming);     // LIVE → FINISHED only
            default         -> true;                             // UPCOMING → anything
        };
    }

    // ── Demotion warn-once guard ──────────────────────────────────────────
    // Emits a WARN on the first blocked demotion for a given externalId, then
    // silently DEBUGs on every subsequent attempt.  Prevents log flooding when
    // the upstream API is stuck reporting a finished match as IN PLAY across
    // many consecutive poll cycles.
    //
    // The set is intentionally never cleared — once a transition has been
    // warned about, further WARNs for the same ID add no diagnostic value.
    // Memory impact is negligible (only external IDs of blocked matches).
    private final Set<String> warnedDemotions = ConcurrentHashMap.newKeySet();

    // ── Pre-built display-name sets for DB filtering (cups only — league
    //    filtering now delegates to Top6LeagueTeams for team validation) ──
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
    }

    public boolean isHandicapCacheValid(UUID matchId) {
        OddsCacheEntry entry = liveHandicapCache.get(matchId);
        return entry != null && entry.isValid();
    }

    public void cacheLiveHandicapOdds(UUID matchId, List<Map<String, Object>> odds) {
        long expires = System.currentTimeMillis() + LIVE_ODDS_TTL_MS;
        liveHandicapCache.put(matchId, new OddsCacheEntry(odds, expires));
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

    // ══════════════════════════════════════════════════════════════════════
    // BASIC QUERIES — DB-backed
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getLiveMatches() {
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE");
        log.info("getLiveMatches: {} LIVE match(es) found", matches.size());
        return matches;
    }

    public List<Match> getUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS));
        List<Match> sorted  = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        int withLogos    = (int) sorted.stream().filter(MatchService::hasLogos).count();
        int withoutLogos = sorted.size() - withLogos;
        log.info("getUpcomingMatches: {} upcoming — {} with logos, {} without",
                sorted.size(), withLogos, withoutLogos);
        return sorted;
    }

    @Cacheable("todayMatches")
    public List<Match> getTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetween(startOfDay, endOfDay);
        log.info("getTodayMatches: {} match(es) today UTC", matches.size());
        return matches;
    }

    public List<Match> getFutureMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS));
        List<Match> sorted  = matches.stream().sorted(LOGO_THEN_KICKOFF).toList();
        int withLogos    = (int) sorted.stream().filter(MatchService::hasLogos).count();
        int withoutLogos = sorted.size() - withLogos;
        log.info("getFutureMatches: {} match(es) next 7 days — {} with logos, {} without",
                sorted.size(), withLogos, withoutLogos);
        return sorted;
    }

    public List<Match> getRecentResults() {
        return getRecentResultsLimited(20);
    }

    public List<Match> getRecentResultsLimited(int limit) {
        // 72h window (was 48h) — ensures matches finished yesterday evening
        // are still visible when queried the following morning.
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("FINISHED").stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .limit(limit)
                .toList();
        log.info("getRecentResults: returning {} FINISHED match(es) (72h window, cap={})",
                matches.size(), limit);
        return matches;
    }

    @Cacheable("featuredMatches")
    public List<Match> getFeaturedMatches() {
        List<Match> matches = matchRepo.findByFeaturedTrueOrderByKickoffAt();
        log.info("getFeaturedMatches: {} featured match(es)", matches.size());
        return matches;
    }

    public Match getById(String id) {
        return matchRepo.findById(toUuid(id))
                .orElseThrow(() -> ApiException.notFound("Match not found: " + id));
    }

    // ══════════════════════════════════════════════════════════════════════
    // TOP-6 LEAGUES — validated via Top6LeagueTeams whitelist
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getTop6LiveMatches() {
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE").stream()
                .filter(Top6LeagueTeams::isKnownTop6Match)
                .toList();
        log.info("getTop6LiveMatches: {} LIVE match(es) in top-6 leagues (team-validated)", matches.size());
        return matches;
    }

    public List<Match> getTop6UpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(Top6LeagueTeams::isKnownTop6Match)
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getTop6UpcomingMatches: {} upcoming match(es) in top-6 leagues (team-validated)", matches.size());
        return matches;
    }

    public List<Match> getTop6TodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetween(startOfDay, endOfDay).stream()
                .filter(Top6LeagueTeams::isKnownTop6Match)
                .toList();
        log.info("getTop6TodayMatches: {} match(es) today in top-6 leagues (team-validated)", matches.size());
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TOP-6 CUPS
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getTop6CupsLiveMatches() {
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE").stream()
                .filter(m -> leagueIn(m, TOP6_CUP_NAMES))
                .toList();
        log.info("getTop6CupsLiveMatches: {} LIVE cup match(es)", matches.size());
        return matches;
    }

    public List<Match> getTop6CupsUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> leagueIn(m, TOP6_CUP_NAMES))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getTop6CupsUpcomingMatches: {} upcoming cup match(es)", matches.size());
        return matches;
    }

    public List<Match> getTop6CupsTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetween(startOfDay, endOfDay).stream()
                .filter(m -> leagueIn(m, TOP6_CUP_NAMES))
                .toList();
        log.info("getTop6CupsTodayMatches: {} cup match(es) today", matches.size());
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ALL CUPS
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getAllCupsLiveMatches() {
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE").stream()
                .filter(m -> leagueIn(m, CUP_NAMES))
                .toList();
        log.info("getAllCupsLiveMatches: {} LIVE cup match(es) (all cups)", matches.size());
        return matches;
    }

    public List<Match> getAllCupsUpcomingMatches() {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> leagueIn(m, CUP_NAMES))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getAllCupsUpcomingMatches: {} upcoming cup match(es) (all cups)", matches.size());
        return matches;
    }

    public List<Match> getAllCupsTodayMatches() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetween(startOfDay, endOfDay).stream()
                .filter(m -> leagueIn(m, CUP_NAMES))
                .toList();
        log.info("getAllCupsTodayMatches: {} cup match(es) today (all cups)", matches.size());
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BY-COMPETITION-ENUM QUERIES
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

    // ══════════════════════════════════════════════════════════════════════
    // BY-LEAGUE QUERIES — free-text, case-insensitive
    // ══════════════════════════════════════════════════════════════════════

    public List<Match> getLiveMatchesByLeague(String leagueName) {
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE").stream()
                .filter(m -> leagueName.equalsIgnoreCase(m.getLeague()))
                .filter(m -> isTop6LeagueValidatedOrPassThrough(m, leagueName))
                .toList();
        log.info("getLiveMatchesByLeague: {} LIVE match(es) for league='{}'", matches.size(), leagueName);
        return matches;
    }

    public List<Match> getUpcomingMatchesByLeague(String leagueName) {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> leagueName.equalsIgnoreCase(m.getLeague()))
                .filter(m -> isTop6LeagueValidatedOrPassThrough(m, leagueName))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getUpcomingMatchesByLeague: {} upcoming match(es) for league='{}'", matches.size(), leagueName);
        return matches;
    }

    public List<Match> getTodayMatchesByLeague(String leagueName) {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay   = startOfDay.plus(1, ChronoUnit.DAYS);
        List<Match> matches = matchRepo.findByKickoffBetween(startOfDay, endOfDay).stream()
                .filter(m -> leagueName.equalsIgnoreCase(m.getLeague()))
                .filter(m -> isTop6LeagueValidatedOrPassThrough(m, leagueName))
                .toList();
        log.info("getTodayMatchesByLeague: {} match(es) today for league='{}'", matches.size(), leagueName);
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
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("LIVE").stream()
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("getLiveMatchesByTeamName: {} LIVE match(es) for team='{}'", matches.size(), teamName);
        return matches;
    }

    public List<Match> getUpcomingMatchesByTeamName(String teamName) {
        Instant now = Instant.now();
        List<Match> matches = matchRepo.findUpcomingScheduled(now, now.plus(7, ChronoUnit.DAYS)).stream()
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .sorted(LOGO_THEN_KICKOFF)
                .toList();
        log.info("getUpcomingMatchesByTeamName: {} upcoming match(es) for team='{}'", matches.size(), teamName);
        return matches;
    }

    public List<Match> getRecentResultsByTeamName(String teamName) {
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
        List<Match> matches = matchRepo.findByStatusOrderByKickoffAt("FINISHED").stream()
                .filter(m -> m.getKickoffAt() != null && m.getKickoffAt().isAfter(cutoff))
                .filter(m -> teamName.equalsIgnoreCase(m.getHomeTeam())
                        || teamName.equalsIgnoreCase(m.getAwayTeam()))
                .toList();
        log.info("getRecentResultsByTeamName: {} recent result(s) for team='{}'", matches.size(), teamName);
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
                entry.put("odds", oddsGeneratorService.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
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
                entry.put("match_result", oddsGeneratorService.generatePreMatchOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
                entry.put("asian_handicap", handicapOddsService.generateHandicapOdds(
                        match.getHomeTeam(), match.getAwayTeam(), match.getLeague()));
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
            return oddsGeneratorService.generatePreMatchOdds(
                    match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
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
            return handicapOddsService.generateHandicapOdds(
                    match.getHomeTeam(), match.getAwayTeam(), match.getLeague());
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
        if (match.getExternalId() == null || match.getSource() != MatchSource.LIVESCORE) return Map.of();
        try {
            int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
            Map<String, Object> detail = liveScoreApiClient.getFullMatchDetails(matchId);
            if (detail != null && !detail.isEmpty()) return Map.of("source", "livescore-api", "data", detail);
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    public Map<String, Object> getEvents(String id) {
        Match match = getById(id);
        if (match.getSource() == MatchSource.LIVESCORE && match.getExternalId() != null) {
            try {
                int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
                Map<String, Object> events = liveScoreApiClient.getMatchEvents(matchId);
                if (events != null && !events.isEmpty()) return Map.of("source", "livescore-api", "data", events);
            } catch (NumberFormatException ignored) {}
        }
        return match.getMetadata() != null ? match.getMetadata() : Map.of("events", List.of());
    }

    @Cacheable(value = "h2h", key = "#id")
    public Map<String, Object> getH2H(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null) return Map.of();
        if (match.getSource() == MatchSource.LIVESCORE && match.getMetadata() != null) {
            try {
                Object t1 = match.getMetadata().get("home_team_id");
                Object t2 = match.getMetadata().get("away_team_id");
                if (t1 != null && t2 != null) {
                    Map<String, Object> h2h = liveScoreApiClient.getHeadToHead(
                            Integer.parseInt(t1.toString()), Integer.parseInt(t2.toString()));
                    if (h2h != null && !h2h.isEmpty()) return Map.of("source", "livescore-api", "data", h2h);
                }
            } catch (NumberFormatException ignored) {}
        }
        return Map.of();
    }

    public Map<String, Object> getStats(String id) {
        Match match = getById(id);
        if (match.getExternalId() == null || match.getSource() != MatchSource.LIVESCORE) return Map.of();
        try {
            int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
            Map<String, Object> stats = liveScoreApiClient.getMatchStats(matchId);
            if (stats != null && !stats.isEmpty())
                return Map.of("source", "livescore-api", "type", "match_stats", "data", stats);
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    @Cacheable(value = "lineups", key = "#id")
    public Map<String, Object> getLineups(String id) {
        Match match = getById(id);
        if (match.getSource() == MatchSource.LIVESCORE && match.getExternalId() != null) {
            try {
                int matchId = Integer.parseInt(match.getExternalId().replace("ls-", ""));
                Map<String, Object> lineup = liveScoreApiClient.getMatchLineup(matchId);
                if (lineup != null && !lineup.isEmpty()) return Map.of("source", "livescore-api", "data", lineup);
            } catch (NumberFormatException ignored) {}
        }
        return Map.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    // STANDINGS / SCORERS — enum-driven
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getStandingsByLeague(CompetitionIds.Top6League league) {
        return liveScoreApiClient.getStandingsByLeague(league);
    }

    public Map<String, Object> getStandingsByCup(CompetitionIds.CupCompetition cup) {
        return liveScoreApiClient.getStandingsByCup(cup);
    }

    public Map<String, Object> getStandingsByLeagueComp(CompetitionIds.LeagueCompetition league) {
        return liveScoreApiClient.getStandingsByLeagueComp(league);
    }

    public Map<String, Map<String, Object>> getAllTop6Standings() {
        return liveScoreApiClient.getAllTop6Standings();
    }

    public Map<String, Object> getTopScorersByLeague(CompetitionIds.Top6League league) {
        return liveScoreApiClient.getTopScorersByLeague(league);
    }

    public Map<String, Object> getTopScorersByLeagueComp(CompetitionIds.LeagueCompetition league) {
        return liveScoreApiClient.getTopScorersByLeagueComp(league);
    }

    public Map<String, Object> getLiveScoreApiStandings(int competitionId) {
        return liveScoreApiClient.getStandings(competitionId);
    }

    public Map<String, Object> getLiveScoreApiTopScorers(int competitionId) {
        return liveScoreApiClient.getTopScorers(competitionId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIVESCORE API PASS-THROUGH HELPERS
    // ══════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getLiveScoreApiLive()               { return liveScoreApiClient.getLiveScores(); }
    public List<Map<String, Object>> getLiveScoreApiToday()              { return liveScoreApiClient.getTodayMatches(); }
    public List<Map<String, Object>> getLiveScoreApiFixtures()           { return liveScoreApiClient.getUpcomingFixtures(); }
    public List<Map<String, Object>> getLiveScoreApiTop6Live()           { return liveScoreApiClient.getTop6LiveScores(); }
    public List<Map<String, Object>> getLiveScoreApiTop6CupsLive()       { return liveScoreApiClient.getTop6CupsLiveScores(); }
    public List<Map<String, Object>> getLiveScoreApiTop6Fixtures()       { return liveScoreApiClient.getTop6Fixtures(); }
    public List<Map<String, Object>> getLiveScoreApiTop6CupFixtures()    { return liveScoreApiClient.getTop6CupFixtures(); }
    public List<Map<String, Object>> getLiveScoreApiTop6AndCupFixtures() { return liveScoreApiClient.getTop6AndCupFixtures(); }

    public List<Map<String, Object>> getLiveScoreApiLiveByLeague(CompetitionIds.Top6League league) {
        return liveScoreApiClient.getLiveScoresByLeague(league);
    }

    public List<Map<String, Object>> getLiveScoreApiLiveByCup(CompetitionIds.CupCompetition cup) {
        return liveScoreApiClient.getLiveScoresByCup(cup);
    }

    public List<Map<String, Object>> getLiveScoreApiFixturesByLeague(CompetitionIds.Top6League league) {
        return liveScoreApiClient.getFixturesByLeague(league);
    }

    public List<Map<String, Object>> getLiveScoreApiFixturesByCup(CompetitionIds.CupCompetition cup) {
        return liveScoreApiClient.getFixturesByCup(cup);
    }

    public List<Map<String, Object>> getLiveScoreApiFixturesByTeam(int teamId) {
        return liveScoreApiClient.getFixturesByTeam(teamId);
    }

    public List<Map<String, Object>> getLiveScoreApiLiveByTeam(int teamId) {
        return liveScoreApiClient.getLiveScoresByTeam(teamId);
    }

    public Map<String, Object> getLiveScoreApiTeamMatches(int teamId) {
        return liveScoreApiClient.getTeamLastMatches(teamId);
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
    //
    // KEY FIX — status demotion guard:
    //
    //   A FINISHED match must never be overwritten with LIVE or UPCOMING by a
    //   stale poll event.  A LIVE match must never be overwritten with UPCOMING
    //   (e.g. by a NOT STARTED event that slipped through the poller filter).
    //
    //   isPermittedTransition(existing, incoming) encodes the allowed moves:
    //     UPCOMING  → LIVE      ✓
    //     UPCOMING  → FINISHED  ✓
    //     LIVE      → FINISHED  ✓
    //     LIVE      → UPCOMING  ✗
    //     FINISHED  → *         ✗  (terminal — never demoted)
    //
    //   If a transition is rejected the existing record is returned unchanged.
    //
    //   warnedDemotions ensures only the first rejection per externalId emits
    //   a WARN — subsequent rejections are logged at DEBUG to avoid flooding
    //   logs when the upstream API is stuck on stale data across many cycles.
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    @CacheEvict(value = {"matches", "featuredMatches", "todayMatches", "futureMatches"}, allEntries = true)
    public Match saveOrUpdate(Match match) {
        if (match.getExternalId() == null || match.getExternalId().isBlank())
            return matchRepo.save(match);

        return matchRepo.findByExternalId(match.getExternalId())
                .map(existing -> {

                    // ── Status guard ─────────────────────────────────────
                    if (match.getStatus() != null) {
                        if (isPermittedTransition(existing.getStatus(), match.getStatus())) {
                            existing.setStatus(match.getStatus());
                        } else {
                            // Warn only on the first blocked demotion for this externalId.
                            // Subsequent attempts (e.g. upstream API stuck in a stale LIVE
                            // loop) are demoted to DEBUG to keep logs clean.
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
                            // Return early — nothing else should be written from
                            // an event whose status has already been rejected.
                            return matchRepo.save(existing);
                        }
                    }

                    // ── Score / metadata — always update when present ─────
                    if (match.getScoreHome() != null) existing.setScoreHome(match.getScoreHome());
                    if (match.getScoreAway() != null) existing.setScoreAway(match.getScoreAway());
                    if (match.getMetadata()  != null) existing.setMetadata(match.getMetadata());

                    // ── League — always overwrite with resolved name ──────
                    if (!isMissing(match.getLeague())) existing.setLeague(match.getLeague());

                    // ── Sparse fields — fill in only when missing ─────────
                    if (isMissing(existing.getHomeTeam())   && !isMissing(match.getHomeTeam()))   existing.setHomeTeam(match.getHomeTeam());
                    if (isMissing(existing.getAwayTeam())   && !isMissing(match.getAwayTeam()))   existing.setAwayTeam(match.getAwayTeam());
                    if (isMissing(existing.getSport())      && !isMissing(match.getSport()))      existing.setSport(match.getSport());
                    if (isMissing(existing.getHomeLogo())   && !isMissing(match.getHomeLogo()))   existing.setHomeLogo(match.getHomeLogo());
                    if (isMissing(existing.getAwayLogo())   && !isMissing(match.getAwayLogo()))   existing.setAwayLogo(match.getAwayLogo());
                    if (isMissing(existing.getLeagueLogo()) && !isMissing(match.getLeagueLogo())) existing.setLeagueLogo(match.getLeagueLogo());
                    if (existing.getSource() == null && match.getSource() != null) existing.setSource(match.getSource());

                    // ── Kickoff healing — only upgrade to a real timestamp ─
                    // A "real" kickoff has nanoseconds == 0 (it came from the
                    // API's date+time fields rather than a synthetic placeholder).
                    // Never overwrite a good kickoff with a null or fake one.
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
        List<Match> stale = matchRepo.findStaleLive(cutoff);
        if (stale.isEmpty()) return 0;
        log.info("finishStaleLiveMatches: force-finishing {} stale match(es)", stale.size());
        for (Match m : stale) { m.setStatus("FINISHED"); matchRepo.save(m); }
        return stale.size();
    }

    public List<Match> getUnsettledFinished() { return matchRepo.findUnsettledFinished(); }

    @Transactional
    public void markSettled(String id) {
        matchRepo.findById(toUuid(id)).ifPresent(m -> {
            m.setSettledAt(Instant.now());
            matchRepo.save(m);
        });
    }
}
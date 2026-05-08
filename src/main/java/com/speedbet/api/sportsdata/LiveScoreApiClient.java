package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/**
 * Client for the LiveScore API (livescore-api.com) — PRIMARY data source.
 *
 * ── Competition selection ────────────────────────────────────────────────
 *
 *   ALL competition-specific methods now accept enum constants directly
 *   instead of raw strings or integers.  This acts as a type-safe
 *   "dropdown": the caller picks from the enum — the client resolves the ID.
 *
 *   Entry points:
 *
 *     getLiveScoresByLeague(CompetitionIds.Top6League league)
 *     getLiveScoresByCup(CompetitionIds.CupCompetition cup)
 *     getLiveScoresByLeagueComp(CompetitionIds.LeagueCompetition league)
 *     getFixturesByLeague(CompetitionIds.Top6League league)
 *     getFixturesByCup(CompetitionIds.CupCompetition cup)
 *     getFixturesByLeagueComp(CompetitionIds.LeagueCompetition league)
 *     getStandingsByLeague(CompetitionIds.Top6League league)
 *     getTopScorersByLeague(CompetitionIds.Top6League league)
 *
 *   No caller should ever pass a raw competition ID integer or a plain
 *   String league name.  Use the enum constants; they are the "dropdown".
 *
 * ── Response shape differences ───────────────────────────────────────────
 *
 *   LIVE endpoint  (matches/live.json):
 *     home.name / away.name     — nested objects
 *     scores.score              — "1 - 0"
 *     time                      — match clock "45", "HT", "FT"
 *     list key chain: data → match
 *
 *   COMPETITION fixture endpoints (fixtures/matches.json?competition_id=X):
 *     home.name / away.name     — nested objects
 *     scheduled                 — ISO string "2026-05-01T10:00:00"
 *     fixture_id                — fixture identifier
 *     list key chain: data → fixture
 *
 *   GENERAL fixture endpoint (fixtures/matches.json no competition_id):
 *     home_name / away_name     — flat strings
 *     home_image / away_image   — flat logo URLs
 *     date + time               — separate fields
 *     id                        — fixture identifier (no fixture_id)
 *     list key chain: data → fixtures
 *
 *   All extractor methods handle ALL shapes via fallback chains.
 */
@Slf4j
@Component
public class LiveScoreApiClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String BASE_URL            = "https://livescore-api.com";
    private static final long   KEY_ROTATE_DELAY_MS = 150;
    private static final int    TRANSIENT_RETRIES   = 1;
    private static final long   KEY_COOLDOWN_MS     = 5 * 60_000L;
    private static final long   CACHE_TTL_MINUTES   = 5;

    // ── Derived convenience maps (built from CompetitionIds enums) ─────────
    public static final Map<String, Integer> TOP_6_COMPETITION_IDS =
            Collections.unmodifiableMap(CompetitionIds.Top6League.asNameToIdMap());

    public static final Map<String, Integer> TOP_6_CUP_IDS =
            Collections.unmodifiableMap(CompetitionIds.CupCompetition.asNameToIdMap());

    public static final Map<String, Integer> ALL_COMPETITION_IDS = buildAllCompetitionIds();

    private static Map<String, Integer> buildAllCompetitionIds() {
        Map<String, Integer> merged = new LinkedHashMap<>();
        merged.putAll(CompetitionIds.Top6League.asNameToIdMap());
        merged.putAll(CompetitionIds.LeagueCompetition.asNameToIdMap());
        merged.putAll(CompetitionIds.CupCompetition.asNameToIdMap());
        return Collections.unmodifiableMap(merged);
    }

    // ── API key pairs ──────────────────────────────────────────────────────
    private final List<String[]> apiCredentials = List.of(
            new String[]{"045qVcNAO4mk94Uk", "gZBmVHlFYLDUcguckACgMpRAtdUDiPYy"},
            new String[]{"fxHrdM0AerFzWyjw", "xTzXyrTqNQwKNpX3XFubsvLGWqgTZAqw"}
    );

    private final WebClient    client;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<String, CacheEntry> cache        = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>       keyCooldowns = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ── Constructor ────────────────────────────────────────────────────────
    public LiveScoreApiClient(WebClient.Builder builder,
                              @Value("${app.platform.demo-mode:false}") boolean demoMode) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        if (demoMode) log.info("LiveScoreApiClient: demo-mode flag detected.");
    }

    // ── Key cooldown helpers ───────────────────────────────────────────────
    private boolean isCredentialCoolingDown(String key) {
        Long until = keyCooldowns.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            keyCooldowns.remove(key);
            log.debug("LiveScoreAPI key ...{} cooldown expired.", tail(key));
            return false;
        }
        return true;
    }

    private void coolDownCredential(String key, long ms) {
        keyCooldowns.put(key, System.currentTimeMillis() + ms);
        log.warn("LiveScoreAPI key ...{} placed on {}s cooldown.", tail(key), ms / 1000);
    }

    // ── Core caller with key rotation ──────────────────────────────────────
    private Map<String, Object> callWithFallback(String path) {
        int usableKeys = 0;
        for (String[] cred : apiCredentials) {
            String key    = cred[0];
            String secret = cred[1];

            if (key.contains("FALLBACK") || key.isBlank()) continue;
            if (isCredentialCoolingDown(key)) {
                log.debug("LiveScoreAPI [{}] key ...{} cooling down, skipping.", path, tail(key));
                continue;
            }
            usableKeys++;

            for (int attempt = 0; attempt <= TRANSIENT_RETRIES; attempt++) {
                final int currentAttempt = attempt;
                try {
                    String fullPath = path + (path.contains("?") ? "&" : "?")
                            + "key=" + key + "&secret=" + secret;

                    String raw = client.get()
                            .uri("/api-client/" + fullPath)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(12))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                int status = extractStatusCode(e);
                                if (status == 401 || status == 403) {
                                    log.warn("LiveScoreAPI [{}] key ...{} → HTTP {} (auth failure)", path, tail(key), status);
                                    return Mono.empty();
                                }
                                if (status == 402 || status == 429) {
                                    log.warn("LiveScoreAPI [{}] key ...{} → HTTP {} (rate limited)", path, tail(key), status);
                                    coolDownCredential(key, KEY_COOLDOWN_MS);
                                    return Mono.error(new SkipKeyException("Rate limited: HTTP " + status));
                                }
                                if (status >= 500) {
                                    log.warn("LiveScoreAPI [{}] key ...{} → HTTP {} (server error)", path, tail(key), status);
                                    return Mono.error(new SkipKeyException("Server error: HTTP " + status));
                                }
                                log.warn("LiveScoreAPI [{}] key ...{} attempt={} network error: {}",
                                        path, tail(key), currentAttempt, e.getMessage());
                                return Mono.empty();
                            })
                            .block();

                    if (raw == null || raw.isBlank()) {
                        log.debug("LiveScoreAPI [{}] key ...{} attempt={} → blank response", path, tail(key), currentAttempt);
                        continue;
                    }

                    Map<String, Object> result = mapper.readValue(raw, MAP_TYPE);
                    Object success = result.get("success");
                    if (success != null && "false".equals(success.toString())) {
                        log.warn("LiveScoreAPI [{}] key ...{} → success=false: {}", path, tail(key), result.get("error"));
                        break;
                    }

                    log.info("LiveScoreAPI [{}] key ...{} → OK ({} bytes)", path, tail(key), raw.length());
                    return result;

                } catch (SkipKeyException e) {
                    log.debug("LiveScoreAPI [{}] key ...{} → {}, skipping", path, tail(key), e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warn("LiveScoreAPI [{}] key ...{} attempt={} → threw: {}", path, tail(key), currentAttempt, e.getMessage());
                    if (currentAttempt == TRANSIENT_RETRIES) break;
                }
            }
            sleepQuietly(KEY_ROTATE_DELAY_MS);
        }

        if (usableKeys == 0) log.warn("LiveScoreAPI [{}] → ALL keys on cooldown", path);
        else log.error("LiveScoreAPI [{}] → ALL {} usable keys exhausted", path, usableKeys);
        return null;
    }

    /**
     * Package-visible alias for {@link #callWithFallback(String)} used by
     * {@link com.speedbet.api.livescore.LiveScorePoller} when it needs to
     * call an ad-hoc path (e.g. history with combined query params) that is
     * not covered by the typed enum methods.
     */
    public Map<String, Object> callWithFallbackPublic(String path) {
        return callWithFallback(path);
    }

    // ── Cache helper ───────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private <T> T cached(String cacheKey, java.util.function.Supplier<T> loader) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            log.debug("LiveScoreAPI cache HIT: '{}'", cacheKey);
            return (T) entry.data();
        }
        T result = loader.get();
        if (result != null) {
            long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CACHE_TTL_MINUTES);
            cache.put(cacheKey, new CacheEntry(result, expiresAt));
        }
        return result;
    }

    public void invalidateCache(String key) { cache.remove(key); }
    public void clearCache() { cache.clear(); }

    // ═════════════════════════════════════════════════════════════════════
    //  PUBLIC API — ENUM-DRIVEN (the "dropdown" interface)
    // ═════════════════════════════════════════════════════════════════════

    // ── Live Scores — enum-driven (CACHED — for non-live-poll callers) ─────

    public List<Map<String, Object>> getLiveScoresByLeague(CompetitionIds.Top6League league) {
        return cached("live:top6league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "matches/live.json?competition_id=" + league.id());
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> matches = extractByPath(result, "data", "match");
            log.info("getLiveScoresByLeague({}): {} live match(es)", league.displayName(), matches.size());
            return matches;
        });
    }

    public List<Map<String, Object>> getLiveScoresByCup(CompetitionIds.CupCompetition cup) {
        return cached("live:cup:" + cup.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "matches/live.json?competition_id=" + cup.id());
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> matches = extractByPath(result, "data", "match");
            log.info("getLiveScoresByCup({}): {} live match(es)", cup.displayName(), matches.size());
            return matches;
        });
    }

    public List<Map<String, Object>> getLiveScoresByLeagueComp(CompetitionIds.LeagueCompetition league) {
        return cached("live:league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "matches/live.json?competition_id=" + league.id());
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> matches = extractByPath(result, "data", "match");
            log.info("getLiveScoresByLeagueComp({}): {} live match(es)", league.displayName(), matches.size());
            return matches;
        });
    }

    // ── Live Scores — FRESH (no cache) — used exclusively by LiveScorePoller ──
    // FIX #2: Live poll runs every 30s — caching defeats the entire purpose.
    // These methods always hit the API directly.

    /**
     * Fresh (uncached) live scores for a Top-6 league.
     * Use ONLY from LiveScorePoller — every other caller should use the cached variant.
     */
    public List<Map<String, Object>> getLiveScoresByLeagueFresh(CompetitionIds.Top6League league) {
        Map<String, Object> result = callWithFallback(
                "matches/live.json?competition_id=" + league.id());
        if (result == null) return Collections.emptyList();
        List<Map<String, Object>> matches = extractByPath(result, "data", "match");
        log.info("getLiveScoresByLeagueFresh({}): {} live match(es)", league.displayName(), matches.size());
        return matches;
    }

    /**
     * Fresh (uncached) live scores for a cup competition.
     * Use ONLY from LiveScorePoller — every other caller should use the cached variant.
     */
    public List<Map<String, Object>> getLiveScoresByCupFresh(CompetitionIds.CupCompetition cup) {
        Map<String, Object> result = callWithFallback(
                "matches/live.json?competition_id=" + cup.id());
        if (result == null) return Collections.emptyList();
        List<Map<String, Object>> matches = extractByPath(result, "data", "match");
        log.info("getLiveScoresByCupFresh({}): {} live match(es)", cup.displayName(), matches.size());
        return matches;
    }

    // ── Fixtures — enum-driven ─────────────────────────────────────────────

    public List<Map<String, Object>> getFixturesByLeague(CompetitionIds.Top6League league) {
        return cached("fixtures:top6league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "fixtures/matches.json?competition_id=" + league.id());
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> fixtures = extractByPath(result, "data", "fixture");
            log.info("getFixturesByLeague({}): {} fixture(s)", league.displayName(), fixtures.size());
            return fixtures;
        });
    }

    public List<Map<String, Object>> getFixturesByCup(CompetitionIds.CupCompetition cup) {
        return cached("fixtures:cup:" + cup.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "fixtures/matches.json?competition_id=" + cup.id());
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> fixtures = extractByPath(result, "data", "fixture");
            log.info("getFixturesByCup({}): {} fixture(s)", cup.displayName(), fixtures.size());
            return fixtures;
        });
    }

    public List<Map<String, Object>> getFixturesByLeagueComp(CompetitionIds.LeagueCompetition league) {
        return cached("fixtures:league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "fixtures/matches.json?competition_id=" + league.id());
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> fixtures = extractByPath(result, "data", "fixture");
            log.info("getFixturesByLeagueComp({}): {} fixture(s)", league.displayName(), fixtures.size());
            return fixtures;
        });
    }

    // ── Standings — enum-driven ────────────────────────────────────────────

    public Map<String, Object> getStandingsByLeague(CompetitionIds.Top6League league) {
        return cached("standings:top6league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "standings/table.json?competition_id=" + league.id());
            if (result == null) return Map.of();
            log.info("getStandingsByLeague({}): fetched", league.displayName());
            return result;
        });
    }

    public Map<String, Object> getStandingsByCup(CompetitionIds.CupCompetition cup) {
        return cached("standings:cup:" + cup.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "standings/table.json?competition_id=" + cup.id());
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getStandingsByLeagueComp(CompetitionIds.LeagueCompetition league) {
        return cached("standings:league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "standings/table.json?competition_id=" + league.id());
            return result != null ? result : Map.of();
        });
    }

    // ── Top Scorers — enum-driven ──────────────────────────────────────────

    public Map<String, Object> getTopScorersByLeague(CompetitionIds.Top6League league) {
        return cached("topscorers:top6league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "competitions/topscorers.json?competition_id=" + league.id());
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getTopScorersByLeagueComp(CompetitionIds.LeagueCompetition league) {
        return cached("topscorers:league:" + league.name(), () -> {
            Map<String, Object> result = callWithFallback(
                    "competitions/topscorers.json?competition_id=" + league.id());
            return result != null ? result : Map.of();
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  BULK HELPERS — iterate all enum values automatically
    // ═════════════════════════════════════════════════════════════════════

    public boolean verifyCredentials() {
        Map<String, Object> result = callWithFallback("users/pair.json");
        if (result == null) return false;
        Object success = result.get("success");
        return success != null && !"false".equals(success.toString());
    }

    public List<Map<String, Object>> getTop6LiveScores() {
        return cached("live:top6:all", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                all.addAll(getLiveScoresByLeague(league));
                sleepQuietly(200);
            }
            log.info("getTop6LiveScores: {} total live match(es)", all.size());
            return all;
        });
    }

    public List<Map<String, Object>> getTop6CupsLiveScores() {
        return cached("live:top6cups:all", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (CompetitionIds.CupCompetition cup : CompetitionIds.CupCompetition.top6Related()) {
                all.addAll(getLiveScoresByCup(cup));
                sleepQuietly(200);
            }
            log.info("getTop6CupsLiveScores: {} total live cup match(es)", all.size());
            return all;
        });
    }

    public List<Map<String, Object>> getTop6Fixtures() {
        return cached("fixtures:top6:all", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                all.addAll(getFixturesByLeague(league));
                sleepQuietly(200);
            }
            log.info("getTop6Fixtures: {} total fixture(s)", all.size());
            return all;
        });
    }

    public List<Map<String, Object>> getTop6CupFixtures() {
        return cached("fixtures:top6cups:all", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (CompetitionIds.CupCompetition cup : CompetitionIds.CupCompetition.top6Related()) {
                all.addAll(getFixturesByCup(cup));
                sleepQuietly(200);
            }
            log.info("getTop6CupFixtures: {} total cup fixture(s)", all.size());
            return all;
        });
    }

    public List<Map<String, Object>> getTop6AndCupFixtures() {
        return cached("fixtures:top6andcups:all", () -> {
            List<Map<String, Object>> leagues = getTop6Fixtures();
            List<Map<String, Object>> cups    = getTop6CupFixtures();

            Set<String> seen   = new HashSet<>();
            List<Map<String, Object>> merged = new ArrayList<>();
            for (Map<String, Object> f : leagues) {
                String id = extractFixtureId(f);
                if (!id.isEmpty() && seen.add(id)) merged.add(f);
            }
            for (Map<String, Object> f : cups) {
                String id = extractFixtureId(f);
                if (!id.isEmpty() && seen.add(id)) merged.add(f);
            }
            log.info("getTop6AndCupFixtures: {} deduplicated (leagues={} cups={})",
                    merged.size(), leagues.size(), cups.size());
            return merged;
        });
    }

    public Map<String, Map<String, Object>> getAllTop6Standings() {
        return cached("standings:top6:all", () -> {
            Map<String, Map<String, Object>> all = new LinkedHashMap<>();
            for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                Map<String, Object> standings = getStandingsByLeague(league);
                if (!standings.isEmpty()) all.put(league.displayName(), standings);
                sleepQuietly(200);
            }
            return all;
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  TODAY'S MATCHES
    // ═════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getTodayMatches() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return cached("today:" + today, () -> {
            Map<String, Object> result = callWithFallback(
                    "matches/history.json?from=" + today + "&to=" + today);
            if (result == null) return Collections.emptyList();
            return extractByPath(result, "data", "match");
        });
    }

    public List<Map<String, Object>> getTodayTop6Matches() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return cached("today:top6:" + today, () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (CompetitionIds.Top6League league : CompetitionIds.Top6League.values()) {
                Map<String, Object> result = callWithFallback(
                        "matches/history.json?from=" + today + "&to=" + today
                                + "&competition_id=" + league.id());
                if (result != null) all.addAll(extractByPath(result, "data", "match"));
                sleepQuietly(200);
            }
            return all;
        });
    }

    public List<Map<String, Object>> getTodayTop6CupMatches() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return cached("today:top6cups:" + today, () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (CompetitionIds.CupCompetition cup : CompetitionIds.CupCompetition.top6Related()) {
                Map<String, Object> result = callWithFallback(
                        "matches/history.json?from=" + today + "&to=" + today
                                + "&competition_id=" + cup.id());
                if (result != null) all.addAll(extractByPath(result, "data", "match"));
                sleepQuietly(200);
            }
            return all;
        });
    }

    public List<Map<String, Object>> getMatchesByDate(String date) {
        return cached("history:" + date, () -> {
            Map<String, Object> result = callWithFallback(
                    "matches/history.json?from=" + date + "&to=" + date);
            if (result == null) return Collections.emptyList();
            return extractByPath(result, "data", "match");
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  GENERAL (ALL COMPETITIONS) ENDPOINTS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * All live matches across every competition — single API call, no cache.
     * Used by LiveScorePoller as the primary live-data source (FIX #3).
     */
    public List<Map<String, Object>> getLiveScores() {
        Map<String, Object> result = callWithFallback("matches/live.json");
        if (result == null) { log.warn("getLiveScores: null response"); return Collections.emptyList(); }
        return extractByPath(result, "data", "match");
    }

    public List<Map<String, Object>> getUpcomingFixtures() {
        return cached("fixtures:all", () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json");
            if (result == null) return Collections.emptyList();
            return extractGeneralFixtures(result);
        });
    }

    public List<Map<String, Object>> getFixturesByDate(String date) {
        return cached("fixtures:" + date, () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json?date=" + date);
            if (result == null) return Collections.emptyList();
            return extractGeneralFixtures(result);
        });
    }

    public List<Map<String, Object>> getLiveScoresByTeam(int teamId) {
        Map<String, Object> result = callWithFallback("matches/live.json?team_id=" + teamId);
        if (result == null) return Collections.emptyList();
        return extractByPath(result, "data", "match");
    }

    public List<Map<String, Object>> getLiveScoresByCountry(int countryId) {
        Map<String, Object> result = callWithFallback("matches/live.json?country_id=" + countryId);
        if (result == null) return Collections.emptyList();
        return extractByPath(result, "data", "match");
    }

    public List<Map<String, Object>> getFixturesByTeam(int teamId) {
        return cached("fixtures:team:" + teamId, () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json?team_id=" + teamId);
            if (result == null) return Collections.emptyList();
            return extractGeneralFixtures(result);
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MATCH DETAILS
    // ═════════════════════════════════════════════════════════════════════

    public Map<String, Object> getMatchStats(int matchId) {
        Map<String, Object> result = callWithFallback("matches/stats.json?match_id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getMatchLineup(int matchId) {
        Map<String, Object> result = callWithFallback("matches/lineups.json?match_id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getMatchEvents(int matchId) {
        Map<String, Object> result = callWithFallback("scores/events.json?id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getMatchCommentary(int matchId) {
        Map<String, Object> result = callWithFallback("matches/commentary.json?match_id=" + matchId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getFullMatchDetails(int matchId) {
        Map<String, Object> details = new HashMap<>();
        details.put("matchId", matchId);
        details.put("stats",   getMatchStats(matchId));
        details.put("events",  getMatchEvents(matchId));
        details.put("lineups", getMatchLineup(matchId));
        return Collections.unmodifiableMap(details);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  TEAMS / H2H / COUNTRIES / SEASONS
    // ═════════════════════════════════════════════════════════════════════

    public Map<String, Object> getTeamsByCompetition(int competitionId) {
        return cached("teams:comp:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback("teams/list.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getTeamLastMatches(int teamId) {
        Map<String, Object> result = callWithFallback("teams/matches.json?team_id=" + teamId);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getHeadToHead(int team1Id, int team2Id) {
        Map<String, Object> result = callWithFallback(
                "teams/head2head.json?team1_id=" + team1Id + "&team2_id=" + team2Id);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getTopDisciplinary(int competitionId) {
        return cached("topdisciplinary:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "competitions/disciplinary.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getAllCompetitions() {
        return cached("competitions:all", () -> {
            Map<String, Object> result = callWithFallback("competitions/list.json");
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getCountries() {
        return cached("countries:all", () -> {
            Map<String, Object> result = callWithFallback("countries/list.json");
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getSeasons() {
        return cached("seasons:all", () -> {
            Map<String, Object> result = callWithFallback("seasons/list.json");
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getStandings(int competitionId) {
        return cached("standings:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "standings/table.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    public Map<String, Object> getTopScorers(int competitionId) {
        return cached("topscorers:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback(
                    "competitions/topscorers.json?competition_id=" + competitionId);
            return result != null ? result : Map.of();
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FIELD EXTRACTORS — handle ALL three response shapes
    // ═════════════════════════════════════════════════════════════════════

    public static String extractMatchId(Map<String, Object> match) {
        Object v = match.get("id");
        return v != null ? v.toString() : "";
    }

    public static String extractFixtureId(Map<String, Object> match) {
        Object fixtureId = match.get("fixture_id");
        if (fixtureId != null && !fixtureId.toString().isBlank()) return fixtureId.toString();
        Object id = match.get("id");
        return id != null ? id.toString() : "";
    }

    public static String extractHomeName(Map<String, Object> match) {
        Object home = match.get("home");
        if (home instanceof Map<?, ?> homeMap) {
            Object name = homeMap.get("name");
            if (name != null && !name.toString().isBlank()) return name.toString();
        }
        Object flat = match.get("home_name");
        return flat != null ? flat.toString() : "";
    }

    public static String extractAwayName(Map<String, Object> match) {
        Object away = match.get("away");
        if (away instanceof Map<?, ?> awayMap) {
            Object name = awayMap.get("name");
            if (name != null && !name.toString().isBlank()) return name.toString();
        }
        Object flat = match.get("away_name");
        return flat != null ? flat.toString() : "";
    }

    public static String extractHomeLogo(Map<String, Object> match) {
        Object home = match.get("home");
        if (home instanceof Map<?, ?> homeMap) {
            Object logo = homeMap.get("logo");
            if (logo != null && !logo.toString().isBlank()) return logo.toString();
            Object image = homeMap.get("image");
            if (image != null && !image.toString().isBlank()) return image.toString();
        }
        Object homeImage = match.get("home_image");
        if (homeImage != null && !homeImage.toString().isBlank()) return homeImage.toString();
        Object homeLogo  = match.get("home_logo");
        if (homeLogo  != null && !homeLogo.toString().isBlank())  return homeLogo.toString();
        return "";
    }

    public static String extractAwayLogo(Map<String, Object> match) {
        Object away = match.get("away");
        if (away instanceof Map<?, ?> awayMap) {
            Object logo = awayMap.get("logo");
            if (logo != null && !logo.toString().isBlank()) return logo.toString();
            Object image = awayMap.get("image");
            if (image != null && !image.toString().isBlank()) return image.toString();
        }
        Object awayImage = match.get("away_image");
        if (awayImage != null && !awayImage.toString().isBlank()) return awayImage.toString();
        Object awayLogo  = match.get("away_logo");
        if (awayLogo  != null && !awayLogo.toString().isBlank())  return awayLogo.toString();
        return "";
    }

    public static String extractScore(Map<String, Object> match) {
        Object scores = match.get("scores");
        if (scores instanceof Map<?, ?> scoresMap) {
            Object score = scoresMap.get("score");
            if (score != null) {
                String s = score.toString().replace(" ", "");
                return s.isBlank() ? "" : s;
            }
        }
        return "";
    }

    public static String extractHalfTimeScore(Map<String, Object> match) {
        Object scores = match.get("scores");
        if (scores instanceof Map<?, ?> scoresMap) {
            Object ht = scoresMap.get("ht_score");
            if (ht != null) {
                String s = ht.toString().replace(" ", "");
                return s.isBlank() ? "" : s;
            }
        }
        return "";
    }

    public static String extractStatus(Map<String, Object> match) {
        Object status = match.get("status");
        return status != null ? status.toString() : "";
    }

    public static String extractMatchTime(Map<String, Object> match) {
        Object time = match.get("time");
        return time != null ? time.toString() : "";
    }

    public static String extractMatchDate(Map<String, Object> match) {
        Object date = match.get("date");
        return date != null ? date.toString() : "";
    }

    public static String extractScheduledTime(Map<String, Object> match) {
        Object scheduled = match.get("scheduled");
        if (scheduled != null && !scheduled.toString().isBlank()) return scheduled.toString();
        Object time = match.get("time");
        return time != null ? time.toString() : "";
    }

    public static String extractCompetitionName(Map<String, Object> match) {
        Object comp = match.get("competition");
        if (comp instanceof Map<?, ?> compMap) {
            Object name = compMap.get("name");
            if (name != null) return name.toString();
        }
        Object compName = match.get("competition_name");
        if (compName != null && !compName.toString().isBlank()) return compName.toString();
        return "";
    }

    public static String extractLeagueLogo(Map<String, Object> match) {
        Object comp = match.get("competition");
        if (comp instanceof Map<?, ?> compMap) {
            Object logo = compMap.get("logo");
            if (logo != null && !logo.toString().isBlank()) return logo.toString();
            Object image = compMap.get("image");
            if (image != null && !image.toString().isBlank()) return image.toString();
        }
        Object flat  = match.get("competition_logo");
        if (flat  != null && !flat.toString().isBlank())  return flat.toString();
        Object flat2 = match.get("league_logo");
        if (flat2 != null && !flat2.toString().isBlank()) return flat2.toString();
        return "";
    }

    public static Instant buildKickoffInstant(Map<String, Object> match) {
        Object scheduledObj = match.get("scheduled");
        if (scheduledObj != null && !scheduledObj.toString().isBlank()) {
            String scheduled = scheduledObj.toString().trim();
            if (scheduled.contains("T")) {
                try { return LocalDateTime.parse(scheduled).toInstant(ZoneOffset.UTC); } catch (DateTimeParseException ignored) {}
                try { return OffsetDateTime.parse(scheduled).toInstant(); }              catch (DateTimeParseException ignored) {}
            }
        }

        String date    = extractMatchDate(match);
        String timeStr = "";

        Object timeObj = match.get("time");
        if (timeObj != null && !timeObj.toString().isBlank()) {
            String raw = timeObj.toString().trim();
            if (raw.contains(":")) timeStr = raw;
        }

        if (timeStr.isBlank() && scheduledObj != null && !scheduledObj.toString().isBlank())
            timeStr = scheduledObj.toString().trim();

        if (date.isBlank() || timeStr.isBlank()) return null;

        try {
            LocalDate ld = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalTime lt = timeStr.length() > 5
                    ? LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm:ss"))
                    : LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"));
            return LocalDateTime.of(ld, lt).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STATUS DETECTION — FIX #1 & #4
    //
    //  isLive() was incorrectly treating any numeric clock value (e.g. "90",
    //  "45") as a live signal. This caused finished matches to be flagged as
    //  live, then immediately demoted to FINISHED by the stale-guard, so
    //  nothing ever surfaced as genuinely LIVE.
    //
    //  Fix: only treat numeric clock as live if status is explicitly "LIVE",
    //  or if status is blank AND the clock is a valid in-play minute (1–130).
    //
    //  isFinished() fix: also catches FULL_TIME / ENDED provider status values.
    // ═════════════════════════════════════════════════════════════════════

    /**
     * FIX #1: Corrected live detection.
     *
     * Priority:
     *   1. If status is explicitly FINISHED or time is "FT" → NOT live.
     *   2. If status is explicitly "LIVE" → live.
     *   3. If status is blank AND time is a numeric minute 1–130 → live.
     *   4. Otherwise → not live.
     *
     * The old logic treated ANY non-FT/HT time string as live, which caused
     * finished matches with clock="90" to appear as live then get demoted.
     */
    public static boolean isLive(Map<String, Object> match) {
        String status = extractStatus(match);
        String time   = extractMatchTime(match);

        // Finished always wins — check this first
        if ("FINISHED".equalsIgnoreCase(status)
                || "FULL_TIME".equalsIgnoreCase(status)
                || "ENDED".equalsIgnoreCase(status)
                || "FT".equals(time)) {
            return false;
        }

        // Explicit live status from the provider
        if ("LIVE".equalsIgnoreCase(status)) return true;

        // Numeric clock only counts as live if the provider hasn't set a status yet
        // (some providers omit status and only set the clock during a match)
        if (status.isBlank() && !time.isBlank() && !"HT".equals(time) && !"POSTP".equals(time)) {
            try {
                int minute = Integer.parseInt(time.trim());
                return minute >= 1 && minute <= 130; // sane in-play range
            } catch (NumberFormatException ignored) {}
        }

        return false;
    }

    /**
     * FIX #4: Corrected finished detection.
     * Now catches FULL_TIME and ENDED in addition to FINISHED / FT.
     */
    public static boolean isFinished(Map<String, Object> match) {
        String status = extractStatus(match);
        String time   = extractMatchTime(match);
        return "FINISHED".equalsIgnoreCase(status)
                || "FULL_TIME".equalsIgnoreCase(status)
                || "ENDED".equalsIgnoreCase(status)
                || "FT".equals(time);
    }

    public static boolean isScheduled(Map<String, Object> match) {
        String status = extractStatus(match);
        return "SCHEDULED".equalsIgnoreCase(status) || status.isEmpty();
    }

    // ── Odds helpers ───────────────────────────────────────────────────────
    public Map<String, Object> extractOdds(Map<String, Object> matchData) {
        Object odds = matchData.get("odds");
        if (odds instanceof Map<?, ?> oddsMap) return new HashMap<>((Map<String, Object>) oddsMap);
        return Map.of();
    }

    public String extractPreOddsHome(Map<String, Object> m)  { return extractNestedOdds(m, "pre",  "1"); }
    public String extractPreOddsDraw(Map<String, Object> m)  { return extractNestedOdds(m, "pre",  "X"); }
    public String extractPreOddsAway(Map<String, Object> m)  { return extractNestedOdds(m, "pre",  "2"); }
    public String extractLiveOddsHome(Map<String, Object> m) { return extractNestedOdds(m, "live", "1"); }
    public String extractLiveOddsDraw(Map<String, Object> m) { return extractNestedOdds(m, "live", "X"); }
    public String extractLiveOddsAway(Map<String, Object> m) { return extractNestedOdds(m, "live", "2"); }

    @SuppressWarnings("unchecked")
    private String extractNestedOdds(Map<String, Object> matchData, String type, String outcome) {
        try {
            Object odds = matchData.get("odds");
            if (odds instanceof Map<?, ?> oddsMap) {
                Object typeOdds = ((Map<String, Object>) oddsMap).get(type);
                if (typeOdds instanceof Map<?, ?> typeMap) {
                    Object val = ((Map<String, Object>) typeMap).get(outcome);
                    return val != null ? val.toString() : "";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ── Key status ─────────────────────────────────────────────────────────
    public Map<String, Object> getKeyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String[] cred : apiCredentials) {
            String key  = cred[0];
            Long until  = keyCooldowns.get(key);
            if (until == null || now >= until) status.put("..." + tail(key), "ACTIVE");
            else status.put("..." + tail(key), "COOLDOWN (" + (until - now) / 1000 + "s remaining)");
        }
        return status;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CORE EXTRACTION HELPERS
    // ═════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractByPath(Map<String, Object> response, String... keys) {
        if (response == null || keys.length == 0) return Collections.emptyList();

        Object current = response;
        for (int i = 0; i < keys.length - 1; i++) {
            if (!(current instanceof Map<?, ?> map)) return Collections.emptyList();
            current = map.get(keys[i]);
        }

        String lastKey = keys[keys.length - 1];
        if (current instanceof Map<?, ?> map) {
            Object val = map.get(lastKey);
            if (val instanceof List<?> list && !list.isEmpty())
                return (List<Map<String, Object>>) list;

            for (Object innerVal : ((Map<?, ?>) map).values()) {
                if (innerVal instanceof List<?> innerList && !innerList.isEmpty())
                    return (List<Map<String, Object>>) innerList;
            }
        }

        Object topLevel = response.get(lastKey);
        if (topLevel instanceof List<?> list && !list.isEmpty())
            return (List<Map<String, Object>>) list;

        return Collections.emptyList();
    }

    public List<Map<String, Object>> extractByPathPublic(Map<String, Object> response, String... keys) {
        return extractByPath(response, keys);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractGeneralFixtures(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        List<Map<String, Object>> fromFixtures = extractByPath(response, "data", "fixtures");
        if (!fromFixtures.isEmpty()) return fromFixtures;
        List<Map<String, Object>> fromFixture  = extractByPath(response, "data", "fixture");
        if (!fromFixture.isEmpty()) return fromFixture;
        return Collections.emptyList();
    }

    // ── Internal utilities ─────────────────────────────────────────────────
    private int extractStatusCode(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return 0;
        if (msg.contains("401")) return 401;
        if (msg.contains("403")) return 403;
        if (msg.contains("402")) return 402;
        if (msg.contains("429")) return 429;
        if (msg.contains("500")) return 500;
        if (msg.contains("502")) return 502;
        if (msg.contains("503")) return 503;
        return 0;
    }

    private static String tail(String key) {
        return key.length() > 4 ? key.substring(key.length() - 4) : key;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static class SkipKeyException extends RuntimeException {
        SkipKeyException(String msg) { super(msg); }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  DEPRECATED — kept for backward compatibility only
    // ═════════════════════════════════════════════════════════════════════

    @Deprecated(since = "enum-migration", forRemoval = true)
    public List<Map<String, Object>> getLiveScoresByLeagueName(String leagueName) {
        int id = CompetitionIds.resolveId(leagueName);
        if (id == -1) { log.warn("getLiveScoresByLeagueName: unknown '{}', returning empty", leagueName); return Collections.emptyList(); }
        Map<String, Object> result = callWithFallback("matches/live.json?competition_id=" + id);
        if (result == null) return Collections.emptyList();
        return extractByPath(result, "data", "match");
    }

    @Deprecated(since = "enum-migration", forRemoval = true)
    public List<Map<String, Object>> getFixturesByLeagueName(String leagueName) {
        int id = CompetitionIds.resolveId(leagueName);
        if (id == -1) { log.warn("getFixturesByLeagueName: unknown '{}', returning empty", leagueName); return Collections.emptyList(); }
        Map<String, Object> result = callWithFallback("fixtures/matches.json?competition_id=" + id);
        if (result == null) return Collections.emptyList();
        return extractByPath(result, "data", "fixture");
    }

    @Deprecated(since = "enum-migration", forRemoval = true)
    public Integer resolveCompetitionId(String leagueName) {
        int id = CompetitionIds.resolveId(leagueName);
        return id == -1 ? null : id;
    }

    @Deprecated(since = "enum-migration", forRemoval = true)
    public Map<String, Integer> getAllKnownCompetitionIds() {
        return ALL_COMPETITION_IDS;
    }

    @Deprecated(since = "enum-migration", forRemoval = true)
    public List<Map<String, Object>> getLiveScoresByCompetition(int competitionId) {
        Map<String, Object> result = callWithFallback("matches/live.json?competition_id=" + competitionId);
        if (result == null) return Collections.emptyList();
        return extractByPath(result, "data", "match");
    }

    @Deprecated(since = "enum-migration", forRemoval = true)
    public List<Map<String, Object>> getFixturesByCompetition(int competitionId) {
        return cached("fixtures:comp:" + competitionId, () -> {
            Map<String, Object> result = callWithFallback("fixtures/matches.json?competition_id=" + competitionId);
            if (result == null) return Collections.emptyList();
            return extractByPath(result, "data", "fixture");
        });
    }

    @Deprecated(since = "enum-migration", forRemoval = true)
    public Map<String, Object> getLiveStandings(int competitionId) {
        Map<String, Object> result = callWithFallback("standings/live.json?competition_id=" + competitionId);
        return result != null ? result : Map.of();
    }

    @Deprecated(since = "enum-migration", forRemoval = true)
    public Map<String, Object> getCompetitionsByCountry(int countryId) {
        return cached("competitions:country:" + countryId, () -> {
            Map<String, Object> result = callWithFallback("competitions/list.json?country_id=" + countryId);
            return result != null ? result : Map.of();
        });
    }
}
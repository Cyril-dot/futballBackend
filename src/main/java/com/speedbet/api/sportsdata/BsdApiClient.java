package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Client for the BSD (Bzzoiro Sports Data) Public API.
 *
 * Base URL : https://sports.bzzoiro.com/api/
 * Auth     : Token-based — Authorization: Token YOUR_API_KEY
 * Plan     : Free, no rate limits, no credit card required
 *
 * NOTE: Demo/fallback data is DISABLED. When the API returns no data or
 * all keys are exhausted, empty collections are returned so that the
 * LiveScore API client (primary source) is used exclusively.
 *
 * KEY ROTATION FIX:
 *   - 401 / 403 → auth failure, rotate to next key (no cooldown)
 *   - 429       → rate limited, put key on cooldown, try next key
 *   - 5xx       → server error, skip key, try next key
 *   - Network   → transient, retry same key once, then move on
 *
 *   Only returns fallback when ALL keys are exhausted.
 */
@Slf4j
@Component
public class BsdApiClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String BASE_URL          = "https://sports.bzzoiro.com/api/";
    private static final long   KEY_ROTATE_DELAY  = 150L;
    private static final int    TRANSIENT_RETRIES = 1;
    private static final long   CACHE_TTL_MINUTES = 5L;
    private static final long   LIVE_CACHE_MS     = 30_000L;
    private static final int    MAX_PAGES         = 10;

    /** Keys on this cooldown cannot be used until the timer expires. */
    private static final long KEY_COOLDOWN_MS = 5 * 60_000L; // 5 minutes

    private final List<String> apiKeys = List.of(
            "7a06f920ecd1f2076cf4e842b2e1b1d40f915824",
            "2eb97ffad2958f6a1bd49442adc8d0024e513c75",
            "67628774db97406d72117afa8e8da49691696597",
            "59c69ed4cd2c4f1ba6c7a80d4223b28d76005a8d"
    );

    public static final List<Integer> TOP_6_LEAGUE_IDS = List.of(1, 2, 3, 4, 5, 6);

    private final WebClient    client;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<String, CacheEntry>  cache        = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>        keyCooldowns = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public BsdApiClient(WebClient.Builder builder,
                        @Value("${app.platform.demo-mode:false}") boolean demoMode) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        // demoMode parameter kept for constructor compatibility but no longer used —
        // demo data is permanently disabled so only real API data is returned.
        if (demoMode) {
            log.info("BsdApiClient: demo-mode flag detected but demo data is disabled. " +
                    "Real API calls will be made; empty results returned on failure.");
        }
    }

    // ── Key cooldown helpers ──────────────────────────────────────────────

    private boolean isKeyCoolingDown(String key) {
        Long until = keyCooldowns.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            keyCooldowns.remove(key);
            log.debug("BSD key ...{} cooldown expired, re-enabling.", tail(key));
            return false;
        }
        return true;
    }

    private void coolDownKey(String key, long ms) {
        keyCooldowns.put(key, System.currentTimeMillis() + ms);
        log.warn("BSD key ...{} placed on {}s cooldown.", tail(key), ms / 1000);
    }

    // ── Core HTTP caller ──────────────────────────────────────────────────

    private String callRaw(String path, int timeoutSeconds) {
        int usableKeys = 0;
        for (String key : apiKeys) {
            if (key.startsWith("BSD_KEY_")) {
                log.warn("BSD [{}] skipping placeholder key '{}'", path, key);
                continue;
            }
            if (isKeyCoolingDown(key)) {
                log.debug("BSD [{}] key ...{} is cooling down, skipping.", path, tail(key));
                continue;
            }
            usableKeys++;

            for (int attempt = 0; attempt <= TRANSIENT_RETRIES; attempt++) {
                final int currentAttempt = attempt;
                try {
                    String raw = client.get()
                            .uri(path)
                            .header("Authorization", "Token " + key)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                if (e instanceof WebClientResponseException wcre) {
                                    int status = wcre.getStatusCode().value();
                                    if (status == 401 || status == 403) {
                                        log.warn("BSD [{}] key ...{} HTTP {} (auth failure) → rotating",
                                                path, tail(key), status);
                                        return Mono.empty();
                                    }
                                    if (status == 429) {
                                        log.warn("BSD [{}] key ...{} HTTP 429 (rate limited) → cooling down",
                                                path, tail(key));
                                        coolDownKey(key, KEY_COOLDOWN_MS);
                                        return Mono.error(new SkipKeyException("HTTP 429"));
                                    }
                                    log.warn("BSD [{}] key ...{} HTTP {} (error) → trying next key",
                                            path, tail(key), status);
                                    return Mono.error(new SkipKeyException("HTTP " + status));
                                }
                                log.warn("BSD [{}] key ...{} attempt={} network error: {}",
                                        path, tail(key), currentAttempt, e.getMessage());
                                return Mono.empty();
                            })
                            .block();

                    if (raw == null || raw.isBlank()) {
                        log.debug("BSD [{}] key ...{} attempt={} → blank response", path, tail(key), currentAttempt);
                        continue;
                    }
                    if (isErrorBody(raw)) {
                        log.warn("BSD [{}] key ...{} → error body detected, rotating to next key",
                                path, tail(key));
                        break;
                    }
                    log.info("BSD [{}] key ...{} OK ({} bytes)", path, tail(key), raw.length());
                    return raw;

                } catch (SkipKeyException e) {
                    log.debug("BSD [{}] key ...{} → {}, skipping to next key", path, tail(key), e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warn("BSD [{}] key ...{} attempt={} threw: {}",
                            path, tail(key), currentAttempt, e.getMessage());
                    if (currentAttempt == TRANSIENT_RETRIES) break;
                }
            }

            sleepQuietly(KEY_ROTATE_DELAY);
        }

        if (usableKeys == 0) {
            log.warn("BSD [{}] → ALL keys currently on cooldown", path);
        } else {
            log.error("BSD [{}] all {} usable keys exhausted", path, usableKeys);
        }
        return null;
    }

    /** Signals: skip this key, try the next one immediately. */
    private static class SkipKeyException extends RuntimeException {
        SkipKeyException(String msg) { super(msg); }
    }

    // ── Deserialise helpers ────────────────────────────────────────────────

    private Map<String, Object> getMap(String path) {
        String raw = callRaw(path, 12);
        if (raw == null) return Collections.emptyMap();
        try {
            return mapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            log.error("BSD getMap [{}] parse error: {}", path, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> getPagedList(String firstPath) {
        List<Map<String, Object>> all = new ArrayList<>();
        String path = firstPath;
        int page = 0;
        while (path != null && page < MAX_PAGES) {
            String raw = callRaw(path, 15);
            if (raw == null) break;
            try {
                JsonNode root = mapper.readTree(raw);
                JsonNode results = root.has("results") ? root.get("results") : root;
                if (results.isArray()) {
                    for (JsonNode item : results) {
                        all.add(mapper.convertValue(item, MAP_TYPE));
                    }
                }
                JsonNode nextNode = root.get("next");
                if (nextNode == null || nextNode.isNull()) break;
                String nextUrl = nextNode.asText();
                path = nextUrl.startsWith(BASE_URL) ? nextUrl.substring(BASE_URL.length()) : nextUrl;
                page++;
            } catch (Exception e) {
                log.error("BSD pagination parse [{}]: {}", path, e.getMessage());
                break;
            }
        }
        return all;
    }

    // ── Cache helpers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, java.util.function.Supplier<T> loader) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("BSD cache HIT: {}", key);
            return (T) entry.data();
        }
        T result = loader.get();
        if (result != null) {
            long exp = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CACHE_TTL_MINUTES);
            cache.put(key, new CacheEntry(result, exp));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T cachedLive(java.util.function.Supplier<T> loader) {
        CacheEntry entry = cache.get("bsd:live");
        if (entry != null && !entry.isExpired()) return (T) entry.data();
        T result = loader.get();
        if (result != null) {
            cache.put("bsd:live", new CacheEntry(result,
                    System.currentTimeMillis() + LIVE_CACHE_MS));
        }
        return result;
    }

    // ── Leagues ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getLeagues() {
        return cached("bsd:leagues", () -> getPagedList("leagues/"));
    }

    public Map<String, Object> getLeagueStandings(int leagueId) {
        return cached("bsd:standings:" + leagueId, () -> getMap("leagues/" + leagueId + "/standings/"));
    }

    // ── Teams ─────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getTeamsByLeague(int leagueId) {
        return cached("bsd:teams:league:" + leagueId, () -> getPagedList("teams/?league=" + leagueId));
    }

    // ── Matches / Events ──────────────────────────────────────────────────

    public List<Map<String, Object>> getEventsByLeague(int leagueId) {
        return cached("bsd:events:league:" + leagueId, () -> getPagedList("events/?league=" + leagueId));
    }

    public List<Map<String, Object>> getTop6Events() {
        return cached("bsd:events:top6", () -> {
            Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
            for (int lid : TOP_6_LEAGUE_IDS) {
                try {
                    List<Map<String, Object>> events = getEventsByLeague(lid);
                    for (Map<String, Object> e : events) {
                        Object idObj = e.get("id");
                        String id = (idObj != null) ? idObj.toString() : "";
                        if (!id.isBlank()) deduped.put(id, e);
                    }
                    sleepQuietly(300);
                } catch (Exception ex) {
                    log.warn("BSD top6 events league {} failed: {}", lid, ex.getMessage());
                }
            }
            return new ArrayList<>(deduped.values());
        });
    }

    // ── Live Scores ───────────────────────────────────────────────────────

    /**
     * Returns real live events from the BSD API.
     * If the API returns empty or fails, an empty list is returned.
     * Demo data is NOT used — the LiveScoreApiClient is the primary source.
     */
    public List<Map<String, Object>> getLiveEvents() {
        return cachedLive(() -> {
            String raw = callRaw("live/", 30);
            if (raw == null) {
                log.warn("BSD live: null response, returning empty list (LiveScoreApi is primary source)");
                return Collections.emptyList();
            }
            try {
                JsonNode root = mapper.readTree(raw);
                JsonNode results = root.has("results") ? root.get("results") : root;
                List<Map<String, Object>> list = new ArrayList<>();
                if (results.isArray()) {
                    for (JsonNode item : results) {
                        list.add(mapper.convertValue(item, MAP_TYPE));
                    }
                }
                if (list.isEmpty()) {
                    log.info("BSD live: API returned 0 events ({} bytes), returning empty list", raw.length());
                    return Collections.emptyList();
                }
                log.info("BSD live: {} real live events returned", list.size());
                return list;
            } catch (Exception e) {
                log.error("BSD live parse error: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    public List<Map<String, Object>> getLiveEventsFull() {
        String raw = callRaw("live/?full=true", 30);
        if (raw == null) return Collections.emptyList();
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode results = root.has("results") ? root.get("results") : root;
            List<Map<String, Object>> list = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode item : results) list.add(mapper.convertValue(item, MAP_TYPE));
            }
            return list;
        } catch (Exception e) {
            log.error("BSD live-full parse error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Predictions ───────────────────────────────────────────────────────

    public List<Map<String, Object>> getPredictions() {
        return cached("bsd:predictions:upcoming", () -> getPagedList("predictions/"));
    }

    public List<Map<String, Object>> getPredictionsByLeague(int leagueId) {
        return cached("bsd:predictions:league:" + leagueId,
                () -> getPagedList("predictions/?league=" + leagueId));
    }

    public Map<String, Map<String, Object>> getPredictionsIndex() {
        return getPredictions().stream()
                .filter(p -> p.get("event") instanceof Map)
                .collect(Collectors.toMap(
                        p -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ev = (Map<String, Object>) p.get("event");
                            Object id = ev.get("id");
                            return (id != null) ? id.toString() : "";
                        },
                        p -> p,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    // ── Players ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getPlayersByTeam(int teamId) {
        return cached("bsd:players:team:" + teamId, () -> getPagedList("players/?team=" + teamId));
    }

    public List<Map<String, Object>> getPlayerStatsByEvent(int eventId) {
        return getPagedList("player-stats/?event=" + eventId);
    }

    public List<Map<String, Object>> getPlayerStatHistory(int playerId) {
        return cached("bsd:playerstats:" + playerId,
                () -> getPagedList("player-stats/?player=" + playerId));
    }

    // ── Odds ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getOdds(int eventId, String market) {
        UriComponentsBuilder ub = UriComponentsBuilder.newInstance()
                .path("odds/")
                .queryParam("event", eventId);
        if (market != null && !market.isBlank()) ub.queryParam("market", market);
        String path = ub.build().toUriString();
        String raw = callRaw(path, 12);
        if (raw == null) return Collections.emptyList();
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode oddsNode = root.has("odds") ? root.get("odds") : root.get("results");
            if (oddsNode != null && oddsNode.isArray()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (JsonNode item : oddsNode) list.add(mapper.convertValue(item, MAP_TYPE));
                return list;
            }
        } catch (Exception e) {
            log.error("BSD getOdds [event={}, market={}] parse error: {}", eventId, market, e.getMessage());
        }
        return Collections.emptyList();
    }

    public Map<String, Object> getOddsCompare(int eventId) {
        return getMap("odds/compare/?event=" + eventId);
    }

    public List<Map<String, Object>> getBestOdds(String market, int days, int leagueId) {
        UriComponentsBuilder ub = UriComponentsBuilder.newInstance()
                .path("odds/best/")
                .queryParam("market", market)
                .queryParam("days", days);
        if (leagueId > 0) ub.queryParam("league", leagueId);
        return getPagedList(ub.build().toUriString());
    }

    public List<Map<String, Object>> getPolymarketOdds(int leagueId) {
        String path = (leagueId > 0) ? "odds/polymarket/?league=" + leagueId : "odds/polymarket/";
        return cached("bsd:polymarket:" + leagueId, () -> getPagedList(path));
    }

    public Map<String, Object> getFullOddsForEvent(int eventId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("raw",     getOdds(eventId, "1x2"));
        result.put("compare", getOddsCompare(eventId));
        return result;
    }

    // ── Manager / Coach ───────────────────────────────────────────────────

    public Map<String, Object> getManagerByTeam(int teamId) {
        return cached("bsd:manager:team:" + teamId, () -> getMap("managers/?team_id=" + teamId));
    }

    // ── Key status (for health/diagnostics) ──────────────────────────────

    public Map<String, Object> getKeyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String key : apiKeys) {
            String tail = tail(key);
            Long until = keyCooldowns.get(key);
            if (until == null || now >= until) {
                status.put("..." + tail, "ACTIVE");
            } else {
                long secondsLeft = (until - now) / 1000;
                status.put("..." + tail, "COOLDOWN (" + secondsLeft + "s remaining)");
            }
        }
        return status;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean isErrorBody(String raw) {
        if (raw == null) return false;
        String t = raw.stripLeading();
        if (t.startsWith("[")) return false;
        if (!t.startsWith("{")) return false;
        try {
            JsonNode node = mapper.readTree(t);
            JsonNode err = node.get("error");
            if (err != null && !err.isNull() && !err.asText().isBlank()) return true;
            JsonNode detail = node.get("detail");
            if (detail != null && !detail.isNull() && !detail.asText().isBlank()) return true;
            JsonNode s = node.get("status");
            if (s != null && s.isInt() && s.asInt() >= 400) {
                boolean hasData = node.has("results") || node.has("data")
                        || node.has("count") || node.has("id");
                return !hasData;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String tail(String key) {
        return (key.length() > 4) ? key.substring(key.length() - 4) : key;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
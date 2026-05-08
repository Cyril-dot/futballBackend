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
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Client for the SportDB.dev Flashscore API.
 *
 * Base URL  : https://api.sportdb.dev
 * Auth      : X-API-Key header
 *
 * KEY ROTATION FIX:
 *   Previously, ANY non-2xx response (including 402 Payment Required, 429 Rate
 *   Limited, 500 Server Error) triggered AbortRotationException, which caused the
 *   client to return the fallback immediately without trying remaining keys.
 *
 *   Now only 401/403 (auth failures) abort rotation. For 402/429/5xx errors the
 *   current key is blacklisted for a short cooldown and the NEXT key is tried.
 *   This means if one key is rate-limited, the others will still be attempted.
 *
 * ── Endpoints used ──────────────────────────────────────────────────────────
 *   GET /api/flashscore/football/live                              → live scores
 *   GET /api/flashscore/football/live/odds                         → live odds
 *   GET /api/flashscore/match/{eventId}/details?with_events=true   → match detail
 *   GET /api/flashscore/match/{eventId}/stats                      → match stats
 *   GET /api/flashscore/{leaguePath}/{season}/fixtures             → fixtures
 *   GET /api/flashscore/{leaguePath}/{season}/results              → results
 *   GET /api/flashscore/{leaguePath}/{season}/standings            → standings
 *   GET /api/flashscore/search?q=...&type=...                      → search
 */
@Slf4j
@Component
public class SportDbClient {

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private static final String FLASHSCORE_IMAGE_BASE =
            "https://static.flashscore.com/res/image/data/";

    /** Inter-key delay (ms) — small breathing room between keys. */
    private static final long KEY_ROTATE_DELAY_MS = 150;

    /** How many times to retry the SAME key on a transient network error before moving on. */
    private static final int TRANSIENT_RETRIES = 1;

    /** Cache TTL for fixture/standings data (minutes). */
    private static final long CACHE_TTL_MINUTES = 5;

    /**
     * Keys blacklisted due to 402/429 — blocked for this many ms before being tried again.
     * Prevents hammering a rate-limited key on the next poll cycle.
     */
    private static final long KEY_COOLDOWN_MS = 5 * 60_000L; // 5 minutes

    public static final String CURRENT_SEASON = "2025-2026";

    private final WebClient    client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean      demoMode;

    // ── In-process cache ──────────────────────────────────────────────────
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Object data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ── Key cooldown tracking ─────────────────────────────────────────────
    /** Maps key → timestamp after which it is safe to use again. */
    private final ConcurrentHashMap<String, Long> keyCooldowns = new ConcurrentHashMap<>();

    // ── API keys ──────────────────────────────────────────────────────────
    private final List<String> apiKeys = List.of(
            "8ZoQBJcgvemcyVVMNQ2u82zsVJTfF8nltOcRoOuE",
            "S0BGjprvLuknMEH88Ba1yGkzXoTKKH5soOoFU2dw",
            "8KwyAq0EuknP4RGrT1T7vwmOFRuh6oiWAcw134EI",
            "T58V5A0Ph7hSc1VlCUPskJ1oEvjXfoddTUdoYk7U",
            "4AOiX1hjqMipBtPyPYqLPEMwyL8zkvn8KtbmyNph",
            "zxYVeFKbdVzBIVa3JerEUNhHipvJcw3nXQPN5zwW",
            "93ylKNAmCetco5OlRU4egoq1O7tZhFuxuAJR8Xfv"
    );

    // ── League path constants ─────────────────────────────────────────────

    public static final List<String> TOP_6_LEAGUE_PATHS = List.of(
            "football/england:198/premier-league:dYlOSQOD",
            "football/spain:206/laliga:NM2kKlSH",
            "football/germany:81/bundesliga:W6BOzpK2",
            "football/italy:115/serie-a:WXael8bg",
            "football/france:74/ligue-1:lL3HT39B",
            "football/europe:6/champions-league:jLsL0992"
    );

    public static final List<String> ALL_LEAGUE_PATHS = List.of(
            // England
            "football/england:198/premier-league:dYlOSQOD",
            "football/england:198/championship:tqCuEYSH",
            "football/england:198/league-one:DKJP2aBd",
            "football/england:198/fa-cup:po4dSCjH",
            "football/england:198/efl-cup:QRnwFnJn",
            // Spain
            "football/spain:206/laliga:NM2kKlSH",
            "football/spain:206/laliga2:OaYvfzN4",
            "football/spain:206/copa-del-rey:p0pBbkTp",
            // Germany
            "football/germany:81/bundesliga:W6BOzpK2",
            "football/germany:81/2-bundesliga:tscKMX6d",
            "football/germany:81/dfb-pokal:YJxhUEBk",
            // Italy
            "football/italy:115/serie-a:WXael8bg",
            "football/italy:115/serie-b:WStpfOLN",
            "football/italy:115/coppa-italia:XKdxHjBj",
            // France
            "football/france:74/ligue-1:lL3HT39B",
            "football/france:74/ligue-2:wXyYxlCK",
            "football/france:74/coupe-de-france:lDKtAvGs",
            // Netherlands
            "football/netherlands:150/eredivisie:nUHt6Pjn",
            "football/netherlands:150/eerste-divisie:QBmBvfnk",
            // Portugal
            "football/portugal:177/liga-portugal:DutchPtr",
            // Belgium
            "football/belgium:33/jupiler-pro-league:E3yvxwKq",
            // Turkey
            "football/turkey:215/super-lig:M1Zq1Omy",
            // Scotland
            "football/scotland:186/premiership:rDppCrEP",
            // Mexico
            "football/mexico:140/liga-mx:tWCvkHtZ",
            // Australia
            "football/australia:15/a-league:hTlEgKb2",
            // Africa
            "football/ghana:80/premier-league:kfNjbXzQ",
            "football/nigeria:155/npfl:qrBnCdEf",
            // Europe club competitions
            "football/europe:6/champions-league:jLsL0992",
            "football/europe:6/europa-league:KNUF7pzn",
            "football/europe:6/conference-league:U57WqTPm"
    );

    // ── Constructor ───────────────────────────────────────────────────────

    public SportDbClient(WebClient.Builder builder,
                         @Value("${app.platform.demo-mode:false}") boolean demoMode) {
        this.demoMode = demoMode;
        this.client   = builder
                .baseUrl("https://api.sportdb.dev")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // ── Key cooldown helpers ──────────────────────────────────────────────

    private boolean isKeyCoolingDown(String key) {
        Long until = keyCooldowns.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            keyCooldowns.remove(key);
            log.debug("SportDB key ...{} cooldown expired, re-enabling.", tail(key));
            return false;
        }
        return true;
    }

    private void coolDownKey(String key, long ms) {
        keyCooldowns.put(key, System.currentTimeMillis() + ms);
        log.warn("SportDB key ...{} placed on {}s cooldown.", tail(key), ms / 1000);
    }

    // ── Core caller (standard endpoints — fixtures, standings, search) ────

    /**
     * KEY ROTATION FIX:
     *   - 401 / 403 → key is bad, move to next key immediately (no cooldown).
     *   - 402 / 429 → key is rate-limited, put on cooldown, try next key.
     *   - 5xx        → server error, try next key after brief delay.
     *   - Network    → transient, retry same key once then move on.
     *
     * Only returns the fallback when ALL keys are exhausted.
     */
    private <T> T callWithFallback(String path, TypeReference<T> typeRef, T fallback) {
        int usableKeys = 0;
        for (String key : apiKeys) {
            if (key.contains("_HERE")) continue;
            if (isKeyCoolingDown(key)) {
                log.debug("SportDB [{}] key ...{} is cooling down, skipping.", path, tail(key));
                continue;
            }
            usableKeys++;

            for (int attempt = 0; attempt <= TRANSIENT_RETRIES; attempt++) {
                final int currentAttempt = attempt;
                try {
                    String raw = client.get()
                            .uri("/api/flashscore/" + path)
                            .header("X-API-Key", key)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(12))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                if (e instanceof WebClientResponseException wcre) {
                                    int status = wcre.getStatusCode().value();
                                    // 401/403: auth failure → rotate immediately, no cooldown
                                    if (status == 401 || status == 403) {
                                        log.warn("SportDB [{}] key ...{} → HTTP {} (auth failure), rotating key",
                                                path, tail(key), status);
                                        return Mono.empty(); // signals: try next key
                                    }
                                    // 402/429: rate limited → cooldown this key
                                    if (status == 402 || status == 429) {
                                        log.warn("SportDB [{}] key ...{} → HTTP {} (rate limited), cooling down",
                                                path, tail(key), status);
                                        coolDownKey(key, KEY_COOLDOWN_MS);
                                        return Mono.error(new SkipKeyException("Rate limited: HTTP " + status));
                                    }
                                    // 5xx or other: skip key, try next
                                    log.warn("SportDB [{}] key ...{} → HTTP {} (server error), trying next key",
                                            path, tail(key), status);
                                    return Mono.error(new SkipKeyException("Server error: HTTP " + status));
                                }
                                log.warn("SportDB [{}] key ...{} attempt={} network error: {}",
                                        path, tail(key), currentAttempt, e.getMessage());
                                return Mono.empty(); // retry same key (transient)
                            })
                            .block();

                    if (raw == null || raw.isBlank()) {
                        log.debug("SportDB [{}] key ...{} attempt={} → blank/null response, retrying",
                                path, tail(key), currentAttempt);
                        continue;
                    }

                    if (isErrorBody(raw)) {
                        log.warn("SportDB [{}] key ...{} → error body detected, rotating to next key", path, tail(key));
                        break; // try next key
                    }

                    T result = mapper.readValue(raw, typeRef);
                    if (result != null) {
                        log.info("SportDB [{}] key ...{} → OK ({} bytes)", path, tail(key), raw.length());
                        return result;
                    }
                    log.warn("SportDB [{}] key ...{} → deserialized to null, rotating", path, tail(key));

                } catch (SkipKeyException e) {
                    log.debug("SportDB [{}] key ...{} → {}, skipping to next key", path, tail(key), e.getMessage());
                    break; // move to next key immediately
                } catch (Exception e) {
                    log.warn("SportDB [{}] key ...{} attempt={} → threw: {}",
                            path, tail(key), currentAttempt, e.getMessage());
                    if (currentAttempt == TRANSIENT_RETRIES) break;
                }
            }

            sleepQuietly(KEY_ROTATE_DELAY_MS);
        }

        if (usableKeys == 0) {
            log.warn("SportDB [{}] → ALL keys currently on cooldown, returning fallback", path);
        } else {
            log.error("SportDB [{}] → ALL {} usable keys exhausted, returning fallback", path, usableKeys);
        }
        return fallback;
    }

    /**
     * Variant for live endpoints — uses a shorter timeout and exchangeToMono
     * for better response-code handling without consuming the error body.
     */
    private <T> T callLiveWithFallback(String path, TypeReference<T> typeRef, T fallback) {
        int usableKeys = 0;
        for (String key : apiKeys) {
            if (key.contains("_HERE")) continue;
            if (isKeyCoolingDown(key)) {
                log.debug("SportDB live [{}] key ...{} is cooling down, skipping.", path, tail(key));
                continue;
            }
            usableKeys++;

            for (int attempt = 0; attempt <= TRANSIENT_RETRIES; attempt++) {
                final int currentAttempt = attempt;
                try {
                    String raw = client.get()
                            .uri("/api/flashscore/" + path)
                            .header("X-API-Key", key)
                            .exchangeToMono(response -> {
                                int status = response.statusCode().value();
                                if (response.statusCode().is2xxSuccessful()) {
                                    return response.bodyToMono(String.class);
                                }
                                if (status == 401 || status == 403) {
                                    log.warn("SportDB live [{}] key ...{} → HTTP {} (auth), rotating",
                                            path, tail(key), status);
                                    return response.releaseBody().then(Mono.empty());
                                }
                                if (status == 402 || status == 429) {
                                    log.warn("SportDB live [{}] key ...{} → HTTP {} (rate limited), cooling down",
                                            path, tail(key), status);
                                    coolDownKey(key, KEY_COOLDOWN_MS);
                                    return response.releaseBody()
                                            .then(Mono.error(new SkipKeyException("Rate limited: HTTP " + status)));
                                }
                                log.warn("SportDB live [{}] key ...{} → HTTP {} (error), trying next",
                                        path, tail(key), status);
                                return response.releaseBody()
                                        .then(Mono.error(new SkipKeyException("HTTP " + status)));
                            })
                            .timeout(Duration.ofSeconds(20))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                if (e instanceof SkipKeyException) return Mono.error(e);
                                log.warn("SportDB live [{}] key ...{} attempt={} exchange error: {}",
                                        path, tail(key), currentAttempt, e.getMessage());
                                return Mono.empty();
                            })
                            .block();

                    if (raw == null || raw.isBlank()) {
                        break;
                    }

                    if (isErrorBody(raw)) {
                        log.warn("SportDB live [{}] key ...{} → error body, rotating", path, tail(key));
                        break;
                    }

                    T result = mapper.readValue(raw, typeRef);
                    if (result != null) {
                        log.info("SportDB live [{}] key ...{} → OK ({} bytes)", path, tail(key), raw.length());
                        return result;
                    }
                    log.warn("SportDB live [{}] key ...{} → deserialized to null", path, tail(key));

                } catch (SkipKeyException e) {
                    log.debug("SportDB live [{}] key ...{} → {}, skipping", path, tail(key), e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warn("SportDB live [{}] key ...{} attempt={} → threw: {}",
                            path, tail(key), currentAttempt, e.getMessage());
                    if (currentAttempt == TRANSIENT_RETRIES) break;
                }
            }

            sleepQuietly(KEY_ROTATE_DELAY_MS);
        }

        if (usableKeys == 0) {
            log.warn("SportDB live [{}] → ALL keys on cooldown, returning fallback", path);
        } else {
            log.error("SportDB live [{}] → ALL {} usable keys exhausted, returning fallback", path, usableKeys);
        }
        return fallback;
    }

    // ── Exception types ───────────────────────────────────────────────────

    /** Signals: skip this key, try the next one immediately. */
    private static class SkipKeyException extends RuntimeException {
        SkipKeyException(String msg) { super(msg); }
    }

    // ── isErrorBody — FIXED: narrower detection to avoid false positives ──

    /**
     * Returns true only when the JSON body is clearly an API error response.
     *
     * FIX: previously matched ANY JSON with a non-blank "message" field, which
     * caused false positives on valid responses that include a "message" key
     * (e.g. fixture lists with metadata). Now only triggers on bodies that ALSO
     * lack any list/array content and have an error-indicating status field.
     */
    private boolean isErrorBody(String raw) {
        if (raw == null) return false;
        String trimmed = raw.stripLeading();
        // Arrays are never error bodies
        if (trimmed.startsWith("[")) return false;
        if (!trimmed.startsWith("{")) return false;
        try {
            JsonNode node = mapper.readTree(trimmed);
            // Explicit error field
            JsonNode errorNode = node.get("error");
            if (errorNode != null && !errorNode.isNull() && !errorNode.asText().isBlank()) {
                return true;
            }
            // Explicit detail field (DRF-style errors)
            JsonNode detailNode = node.get("detail");
            if (detailNode != null && !detailNode.isNull() && !detailNode.asText().isBlank()) {
                return true;
            }
            // Status >= 400 with no data payload
            JsonNode statusNode = node.get("status");
            if (statusNode != null && statusNode.isInt() && statusNode.asInt() >= 400) {
                // Only treat as error if there's no data/results field
                boolean hasData = node.has("data") || node.has("results")
                        || node.has("fixtures") || node.has("standings");
                return !hasData;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Cache helpers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T cached(String cacheKey, java.util.function.Supplier<T> loader) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            log.debug("SportDB cache HIT: '{}'", cacheKey);
            return (T) entry.data();
        }
        T result = loader.get();
        if (result != null) {
            long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CACHE_TTL_MINUTES);
            cache.put(cacheKey, new CacheEntry(result, expiresAt));
        }
        return result;
    }

    public void invalidateCache(String cacheKey) { cache.remove(cacheKey); }
    public void clearCache() { cache.clear(); }

    // ── Logo URL helpers ──────────────────────────────────────────────────

    public static String buildLogoUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;
        return FLASHSCORE_IMAGE_BASE + raw;
    }

    public String extractHomeLogo(Map<String, Object> event) {
        for (var key : List.of("homeLogo", "homeLogoUrl", "homeImage",
                "homeTeamLogo", "homeTeamIcon", "homeIcon")) {
            Object val = event.get(key);
            if (val != null && !val.toString().isBlank()) return buildLogoUrl(val.toString());
        }
        return buildLogoUrl(extractNestedLogo(event, "home"));
    }

    public String extractAwayLogo(Map<String, Object> event) {
        for (var key : List.of("awayLogo", "awayLogoUrl", "awayImage",
                "awayTeamLogo", "awayTeamIcon", "awayIcon")) {
            Object val = event.get(key);
            if (val != null && !val.toString().isBlank()) return buildLogoUrl(val.toString());
        }
        return buildLogoUrl(extractNestedLogo(event, "away"));
    }

    public String extractLeagueLogo(Map<String, Object> event) {
        for (var key : List.of("tournamentLogo", "leagueLogo", "competitionLogo",
                "tournamentImage", "leagueImage", "logo")) {
            Object val = event.get(key);
            if (val != null && !val.toString().isBlank()) return buildLogoUrl(val.toString());
        }
        return null;
    }

    private String extractNestedLogo(Map<String, Object> event, String side) {
        try {
            Object participants = event.get("participants");
            if (participants instanceof List<?> list) {
                for (Object p : list) {
                    if (p instanceof Map<?, ?> participant) {
                        Object type = participant.get("type");
                        if (type != null && (type.toString().equalsIgnoreCase(side)
                                || type.toString().equalsIgnoreCase(side + "Team"))) {
                            Object logo  = participant.get("logo");
                            if (logo  != null) return logo.toString();
                            Object image = participant.get("image");
                            if (image != null) return image.toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Field extraction helpers ──────────────────────────────────────────

    public static String extractHomeName(Map<String, Object> event) {
        Object v = event.get("homeName");
        if (v != null && !v.toString().isBlank()) return v.toString();
        v = event.get("homeFirstName");
        return v != null ? v.toString() : "";
    }

    public static String extractAwayName(Map<String, Object> event) {
        Object v = event.get("awayName");
        if (v != null && !v.toString().isBlank()) return v.toString();
        v = event.get("awayFirstName");
        return v != null ? v.toString() : "";
    }

    public static String extractHomeScore(Map<String, Object> event) {
        for (String key : List.of("homeScore", "homeFullTimeScore")) {
            Object v = event.get(key);
            if (v != null && !v.toString().isBlank() && !v.toString().equals("-1"))
                return v.toString();
        }
        return "";
    }

    public static String extractAwayScore(Map<String, Object> event) {
        for (String key : List.of("awayScore", "awayFullTimeScore")) {
            Object v = event.get(key);
            if (v != null && !v.toString().isBlank() && !v.toString().equals("-1"))
                return v.toString();
        }
        return "";
    }

    public static String extractTournamentName(Map<String, Object> event) {
        Object v = event.get("tournamentName");
        if (v == null) return "";
        String name = v.toString();
        int colon = name.indexOf(':');
        if (colon >= 0 && colon < name.length() - 2)
            return name.substring(colon + 2).trim();
        return name.trim();
    }

    public static String extractTournamentNameRaw(Map<String, Object> event) {
        Object v = event.get("tournamentName");
        return v != null ? v.toString() : "";
    }

    public static String extractKickoff(Map<String, Object> event) {
        Object v = event.get("startDateTimeUtc");
        return v != null ? v.toString() : "";
    }

    public static String extractKickoffEpoch(Map<String, Object> event) {
        Object v = event.get("startUtime");
        if (v != null && !v.toString().isBlank()) return v.toString();
        v = event.get("startTime");
        return v != null ? v.toString() : "";
    }

    public static String extractRound(Map<String, Object> event) {
        Object v = event.get("round");
        return v != null ? v.toString() : "";
    }

    public static String extractGameTime(Map<String, Object> event) {
        Object v = event.get("gameTime");
        return v != null ? v.toString() : "-1";
    }

    public static String extractEventId(Map<String, Object> event) {
        Object v = event.get("eventId");
        return v != null ? v.toString() : "";
    }

    public static boolean isLive(Map<String, Object> event) {
        Object stage = event.get("eventStage");
        if (stage != null && "LIVE".equalsIgnoreCase(stage.toString())) return true;
        Object stageId = event.get("eventStageId");
        if (stageId != null && "2".equals(stageId.toString())) return true;
        String gt = extractGameTime(event);
        if (!gt.equals("-1") && !gt.isBlank() && !gt.equalsIgnoreCase("HT")) {
            try { return Integer.parseInt(gt.replaceAll("[^0-9]", "")) >= 0; }
            catch (NumberFormatException ignored) {}
        }
        return false;
    }

    public static boolean isScheduled(Map<String, Object> event) {
        Object stage = event.get("eventStage");
        if (stage != null && "SCHEDULED".equalsIgnoreCase(stage.toString())) return true;
        Object stageId = event.get("eventStageId");
        return stageId != null && "1".equals(stageId.toString());
    }

    public static boolean isFinished(Map<String, Object> event) {
        Object stage = event.get("eventStage");
        if (stage != null && "FINISHED".equalsIgnoreCase(stage.toString())) return true;
        Object stageId = event.get("eventStageId");
        return stageId != null && "3".equals(stageId.toString());
    }

    public static String extractWinner(Map<String, Object> event) {
        Object v = event.get("ftWinner");
        if (v == null) v = event.get("winner");
        if (v == null) return "";
        String s = v.toString().trim();
        if (s.equals("1")) return "1";
        if (s.equals("2")) return "2";
        if (s.contains("0")) return "draw";
        return "";
    }

    public static boolean homeHasRedCard(Map<String, Object> event) {
        Object v = event.get("homeRedCardCount");
        if (v == null) return false;
        try { return Integer.parseInt(v.toString()) > 0; }
        catch (NumberFormatException e) { return false; }
    }

    public static boolean awayHasRedCard(Map<String, Object> event) {
        Object v = event.get("awayRedCardCount");
        if (v == null) return false;
        try { return Integer.parseInt(v.toString()) > 0; }
        catch (NumberFormatException e) { return false; }
    }

    public static boolean hasLiveBetting(Map<String, Object> event) {
        Object v = event.get("hasLiveBetting");
        return v != null && "y".equalsIgnoreCase(v.toString());
    }

    public static String extractTvOrLivestreamingRaw(Map<String, Object> event) {
        Object v = event.get("hasTvOrLivestreaming");
        return v != null ? v.toString() : null;
    }

    // ── Live odds field helpers ───────────────────────────────────────────

    public static String extractLiveOddsHome(Map<String, Object> oddsRecord) {
        Object v = oddsRecord.get("odds0");
        return v != null ? v.toString() : "";
    }

    public static String extractLiveOddsDraw(Map<String, Object> oddsRecord) {
        Object v = oddsRecord.get("odds1");
        return v != null ? v.toString() : "";
    }

    public static String extractLiveOddsAway(Map<String, Object> oddsRecord) {
        Object v = oddsRecord.get("odds2");
        return v != null ? v.toString() : "";
    }

    public static Map<String, List<Map<String, Object>>> buildLiveOddsIndex(
            List<Map<String, Object>> liveOdds) {
        if (liveOdds == null || liveOdds.isEmpty()) return Map.of();
        return liveOdds.stream()
                .filter(o -> o.get("eventId") != null)
                .collect(Collectors.groupingBy(o -> o.get("eventId").toString()));
    }

    public static List<Map<String, Object>> getLiveOddsForEvent(
            List<Map<String, Object>> liveOdds, String eventId) {
        if (liveOdds == null || liveOdds.isEmpty() || eventId == null) return List.of();
        return liveOdds.stream()
                .filter(o -> eventId.equals(
                        o.get("eventId") != null ? o.get("eventId").toString() : null))
                .collect(Collectors.toList());
    }

    // ── Live scores ───────────────────────────────────────────────────────

    public List<Map<String, Object>> getLiveScores() {
        if (demoMode) return getDemoLiveScores();
        List<Map<String, Object>> result =
                callLiveWithFallback("football/live", LIST_MAP_TYPE, null);
        if (result != null && !result.isEmpty()) {
            log.info("SportDB live scores → {} matches returned", result.size());
            return result;
        }
        log.warn("SportDB live scores → no real data, falling back to demo");
        return getDemoLiveScores();
    }

    public List<Map<String, Object>> getTop6LiveScores() {
        if (demoMode) return getDemoLiveScores();
        Set<String> top6Leagues = Set.of(
                "Premier League", "LaLiga", "La Liga",
                "Bundesliga", "Serie A", "Ligue 1",
                "Champions League", "UEFA Champions League");
        return getLiveScores().stream()
                .filter(m -> {
                    String league = extractTournamentName(m);
                    return top6Leagues.stream().anyMatch(l ->
                            league.toLowerCase().contains(l.toLowerCase()));
                })
                .collect(Collectors.toList());
    }

    // ── Live odds ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> getLiveOdds() {
        if (demoMode) return Collections.emptyList();
        List<Map<String, Object>> result =
                callLiveWithFallback("football/live/odds", LIST_MAP_TYPE, null);
        return result != null ? result : Collections.emptyList();
    }

    // ── Match odds (disabled — requires paid plan) ────────────────────────

    /**
     * The /match/{id}/odds endpoint requires a paid SportDB plan.
     * Returns empty immediately — use BsdApiClient.getOdds() instead.
     */
    public List<Map<String, Object>> getMatchOdds(String eventId) {
        log.debug("getMatchOdds [{}]: skipped — requires paid plan. Use BsdApiClient instead.", eventId);
        return Collections.emptyList();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> getAllUpcomingFixtures() {
        if (demoMode) return getDemoFixtures();
        return cached("fixtures:all", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (String path : ALL_LEAGUE_PATHS) {
                try {
                    List<Map<String, Object>> result = callWithFallback(
                            path + "/" + CURRENT_SEASON + "/fixtures?page=1",
                            LIST_MAP_TYPE, null);
                    if (result != null) all.addAll(result);
                    sleepQuietly(400);
                } catch (Exception e) {
                    log.warn("Failed to fetch fixtures for {}: {}", path, e.getMessage());
                }
            }
            log.info("Fetched {} total fixtures across all leagues", all.size());
            return all;
        });
    }

    public List<Map<String, Object>> getTop6Fixtures() {
        if (demoMode) return getDemoFixtures();
        return cached("fixtures:top6", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (String path : TOP_6_LEAGUE_PATHS) {
                try {
                    List<Map<String, Object>> result = callWithFallback(
                            path + "/" + CURRENT_SEASON + "/fixtures?page=1",
                            LIST_MAP_TYPE, null);
                    if (result != null) all.addAll(result);
                    sleepQuietly(400);
                } catch (Exception e) {
                    log.warn("Failed to fetch top6 fixtures for {}: {}", path, e.getMessage());
                }
            }
            return all;
        });
    }

    // ── Results ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getAllRecentResults() {
        if (demoMode) return getDemoResults();
        return cached("results:top6", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (String path : TOP_6_LEAGUE_PATHS) {
                try {
                    List<Map<String, Object>> result = callWithFallback(
                            path + "/" + CURRENT_SEASON + "/results?page=1",
                            LIST_MAP_TYPE, null);
                    if (result != null) all.addAll(result);
                    sleepQuietly(400);
                } catch (Exception e) {
                    log.warn("Failed to fetch results for {}: {}", path, e.getMessage());
                }
            }
            return all;
        });
    }

    // ── Match detail ──────────────────────────────────────────────────────

    public Map<String, Object> getMatchDetail(String eventId) {
        if (demoMode) return getDemoMatchDetail(eventId);
        Map<String, Object> result = callWithFallback(
                "match/" + eventId + "/details?with_events=true", MAP_TYPE, null);
        return result != null ? result : getDemoMatchDetail(eventId);
    }

    public List<Map<String, Object>> getMatchStats(String eventId) {
        if (demoMode) return getDemoMatchStats();
        List<Map<String, Object>> result =
                callWithFallback("match/" + eventId + "/stats", LIST_MAP_TYPE, null);
        return result != null ? result : getDemoMatchStats();
    }

    // ── Standings ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> getStandings(String leaguePath) {
        if (demoMode) return Collections.emptyList();
        return cached("standings:" + leaguePath, () -> {
            List<Map<String, Object>> result = callWithFallback(
                    leaguePath + "/" + CURRENT_SEASON + "/standings",
                    LIST_MAP_TYPE, null);
            return result != null ? result : Collections.emptyList();
        });
    }

    // ── Search ────────────────────────────────────────────────────────────

    public Map<String, Object> search(String query, String type) {
        if (demoMode) return Map.of("results", List.of());
        String path = UriComponentsBuilder.newInstance()
                .path("search")
                .queryParam("q", query)
                .queryParamIfPresent("type", Optional.ofNullable(type))
                .build()
                .toUriString();
        Map<String, Object> result = callWithFallback(path, MAP_TYPE, null);
        return result != null ? result : Map.of("results", List.of());
    }

    // ── Team & player detail ──────────────────────────────────────────────

    public Map<String, Object> getTeamDetails(String teamSlug, String teamId) {
        if (demoMode) return Map.of();
        Map<String, Object> result = callWithFallback(
                "team/" + teamSlug + "/" + teamId, MAP_TYPE, null);
        return result != null ? result : Map.of();
    }

    public Map<String, Object> getPlayerDetails(String playerSlug, String playerId) {
        if (demoMode) return Map.of();
        Map<String, Object> result = callWithFallback(
                "player/" + playerSlug + "/" + playerId, MAP_TYPE, null);
        return result != null ? result : Map.of();
    }

    // ── Key status (for debugging / health endpoints) ─────────────────────

    /**
     * Returns a summary of which keys are currently cooling down.
     * Useful for a /actuator/sportdb-keys diagnostic endpoint.
     */
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

    private static String tail(String key) {
        return key.length() > 4 ? key.substring(key.length() - 4) : key;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Demo / fallback data ──────────────────────────────────────────────

    private static final String EPL_LOGO        = "https://static.flashscore.com/res/image/data/dYlOSQOD.png";
    private static final String LL_LOGO         = "https://static.flashscore.com/res/image/data/NM2kKlSH.png";
    private static final String BL_LOGO         = "https://static.flashscore.com/res/image/data/W6BOzpK2.png";
    private static final String SA_LOGO         = "https://static.flashscore.com/res/image/data/WXael8bg.png";
    private static final String L1_LOGO         = "https://static.flashscore.com/res/image/data/lL3HT39B.png";

    private static final String ARSENAL_LOGO    = "https://static.flashscore.com/res/image/data/0n1ffK6k-vcNAdtF9.png";
    private static final String CHELSEA_LOGO    = "https://static.flashscore.com/res/image/data/lMxEQ8me-IROrZEJb.png";
    private static final String LIVERPOOL_LOGO  = "https://static.flashscore.com/res/image/data/nBClzyne-KCp4zq5F.png";
    private static final String MANCITY_LOGO    = "https://static.flashscore.com/res/image/data/0vgscFU0-lQuhqN8N.png";
    private static final String MANUTD_LOGO     = "https://static.flashscore.com/res/image/data/GhGV3qjT-h2pPXz3k.png";
    private static final String BARCA_LOGO      = "https://static.flashscore.com/res/image/data/SKbpVP5K.png";
    private static final String REALMADRID_LOGO = "https://static.flashscore.com/res/image/data/lMpedABo.png";
    private static final String JUVENTUS_LOGO   = "https://static.flashscore.com/res/image/data/WXael8bg.png";
    private static final String PSG_LOGO        = "https://static.flashscore.com/res/image/data/lDKtAvGs.png";
    private static final String ACMILAN_LOGO    = "https://static.flashscore.com/res/image/data/XKdxHjBj.png";
    private static final String ATLETICO_LOGO   = "https://static.flashscore.com/res/image/data/NM2kKlSH.png";
    private static final String LYON_LOGO       = "https://static.flashscore.com/res/image/data/wXyYxlCK.png";

    private static Map<String, Object> liveEvent(String eventId, String home, String away,
                                                 String league, String hs, String as,
                                                 String gameTime, String stageId,
                                                 String homeLogo, String awayLogo,
                                                 String leagueLogo) {
        var m = new HashMap<String, Object>();
        m.put("eventId",     eventId);
        m.put("homeName",    home);
        m.put("homeFirstName", home);
        m.put("awayName",    away);
        m.put("awayFirstName", away);
        m.put("tournamentName", league);
        m.put("homeScore",   hs);
        m.put("awayScore",   as);
        m.put("gameTime",    gameTime);
        m.put("eventStageId", stageId);
        m.put("eventStage",  "2".equals(stageId) ? "LIVE" : "SCHEDULED");
        m.put("homeLogo",    homeLogo);
        m.put("awayLogo",    awayLogo);
        m.put("tournamentLogo", leagueLogo);
        m.put("hasLiveBetting", "y");
        m.put("bookmakerListLiveInOffer", "16|5|417|28");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> fixture(String eventId, String home, String away,
                                               String league, String kickoff,
                                               String homeLogo, String awayLogo,
                                               String leagueLogo) {
        var m = new HashMap<String, Object>();
        m.put("eventId",          eventId);
        m.put("homeName",         home);
        m.put("homeFirstName",    home);
        m.put("awayName",         away);
        m.put("awayFirstName",    away);
        m.put("tournamentName",   league);
        m.put("startDateTimeUtc", kickoff);
        m.put("startUtime",       "0");
        m.put("gameTime",         "-1");
        m.put("eventStage",       "SCHEDULED");
        m.put("eventStageId",     "1");
        m.put("homeLogo",         homeLogo);
        m.put("awayLogo",         awayLogo);
        m.put("tournamentLogo",   leagueLogo);
        m.put("hasLiveBetting",   "n");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> result(String eventId, String home, String away,
                                              String league, String hs, String as,
                                              String homeLogo, String awayLogo,
                                              String leagueLogo) {
        var m = new HashMap<String, Object>();
        m.put("eventId",           eventId);
        m.put("homeName",          home);
        m.put("homeFirstName",     home);
        m.put("awayName",          away);
        m.put("awayFirstName",     away);
        m.put("tournamentName",    league);
        m.put("homeScore",         hs);
        m.put("homeFullTimeScore", hs);
        m.put("awayScore",         as);
        m.put("awayFullTimeScore", as);
        m.put("eventStage",        "FINISHED");
        m.put("eventStageId",      "3");
        m.put("gameTime",          "-1");
        m.put("homeLogo",          homeLogo);
        m.put("awayLogo",          awayLogo);
        m.put("tournamentLogo",    leagueLogo);
        int h = Integer.parseInt(hs), a = Integer.parseInt(as);
        Object winner = h > a ? "1" : (a > h ? "2" : List.of("0", "0"));
        m.put("ftWinner", winner);
        m.put("winner",   winner);
        return Collections.unmodifiableMap(m);
    }

    private List<Map<String, Object>> getDemoLiveScores() {
        return List.of(
                liveEvent("live-001", "Barcelona", "Real Madrid",
                        "SPAIN: LaLiga", "1", "0", "55", "2",
                        BARCA_LOGO, REALMADRID_LOGO, LL_LOGO),
                liveEvent("live-002", "Arsenal", "Chelsea",
                        "ENGLAND: Premier League", "0", "0", "23", "2",
                        ARSENAL_LOGO, CHELSEA_LOGO, EPL_LOGO),
                liveEvent("live-003", "Bayern Munich", "Dortmund",
                        "GERMANY: Bundesliga", "2", "1", "67", "2",
                        "https://static.flashscore.com/res/image/data/lvWjpNBo.png",
                        "https://static.flashscore.com/res/image/data/QBmBvfnk.png", BL_LOGO)
        );
    }

    private List<Map<String, Object>> getDemoFixtures() {
        return List.of(
                fixture("fix-001", "Manchester City", "Liverpool",
                        "ENGLAND: Premier League", "2026-05-01T14:00:00Z",
                        MANCITY_LOGO, LIVERPOOL_LOGO, EPL_LOGO),
                fixture("fix-002", "PSG", "Lyon",
                        "FRANCE: Ligue 1", "2026-05-02T18:00:00Z",
                        PSG_LOGO, LYON_LOGO, L1_LOGO),
                fixture("fix-003", "Juventus", "AC Milan",
                        "ITALY: Serie A", "2026-05-03T19:45:00Z",
                        JUVENTUS_LOGO, ACMILAN_LOGO, SA_LOGO),
                fixture("fix-004", "Atletico Madrid", "Sevilla",
                        "SPAIN: LaLiga", "2026-05-03T20:00:00Z",
                        ATLETICO_LOGO,
                        "https://static.flashscore.com/res/image/data/OaYvfzN4.png", LL_LOGO),
                fixture("fix-005", "Accra Hearts", "Kotoko",
                        "GHANA: Premier League", "2026-05-04T15:00:00Z", "", "", "")
        );
    }

    private List<Map<String, Object>> getDemoResults() {
        return List.of(
                result("res-001", "Real Madrid", "Barcelona",
                        "SPAIN: LaLiga", "2", "1", REALMADRID_LOGO, BARCA_LOGO, LL_LOGO),
                result("res-002", "Liverpool", "Man United",
                        "ENGLAND: Premier League", "3", "0", LIVERPOOL_LOGO, MANUTD_LOGO, EPL_LOGO),
                result("res-003", "Inter Milan", "Napoli",
                        "ITALY: Serie A", "1", "1",
                        "https://static.flashscore.com/res/image/data/WXael8bg.png",
                        "https://static.flashscore.com/res/image/data/WStpfOLN.png", SA_LOGO)
        );
    }

    private Map<String, Object> getDemoMatchDetail(String id) {
        var m = new HashMap<String, Object>();
        m.put("eventId",        id);
        m.put("homeName",       "Arsenal");
        m.put("homeFirstName",  "Arsenal");
        m.put("awayName",       "Chelsea");
        m.put("awayFirstName",  "Chelsea");
        m.put("homeScore",      "1");
        m.put("awayScore",      "0");
        m.put("tournamentName", "ENGLAND: Premier League");
        m.put("eventStageId",   "3");
        m.put("eventStage",     "FINISHED");
        m.put("homeLogo",       ARSENAL_LOGO);
        m.put("awayLogo",       CHELSEA_LOGO);
        m.put("tournamentLogo", EPL_LOGO);
        m.put("events", List.of(
                Map.of("type", "goal",       "player", "Saka",    "minute", "55", "team", "home"),
                Map.of("type", "yellowCard", "player", "Caicedo", "minute", "42", "team", "away")
        ));
        return Collections.unmodifiableMap(m);
    }

    private List<Map<String, Object>> getDemoMatchStats() {
        return List.of(Map.of("period", "Match", "stats", List.of(
                Map.of("statName", "Expected goals (xG)", "homeValue", "1.42",  "awayValue", "0.87", "statId", "432"),
                Map.of("statName", "Ball possession",     "homeValue", "55%",   "awayValue", "45%",  "statId", "12"),
                Map.of("statName", "Total shots",         "homeValue", "12",    "awayValue", "8",    "statId", "34"),
                Map.of("statName", "Shots on target",     "homeValue", "5",     "awayValue", "3",    "statId", "13"),
                Map.of("statName", "Big chances",         "homeValue", "3",     "awayValue", "1",    "statId", "459"),
                Map.of("statName", "Corner kicks",        "homeValue", "6",     "awayValue", "3",    "statId", "16"),
                Map.of("statName", "Fouls",               "homeValue", "11",    "awayValue", "13",   "statId", "21"),
                Map.of("statName", "Goalkeeper saves",    "homeValue", "3",     "awayValue", "4",    "statId", "19")
        )));
    }
}
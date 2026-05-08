package com.speedbet.api.sportsdata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client for the SportSRC V2.5 API (Free Tier).
 *
 * Base URL : https://api.sportsrc.org/v2/
 * Auth     : X-API-KEY header
 *
 * Supported Free Endpoints:
 *   - matches  (Schedules)
 *   - scores   (Live Scores)
 *   - detail   (Match Info & Streaming)
 *   - account  (Plan Status)
 *
 * KEY ROTATION FIX:
 *   Previously used a naive loop that called all keys but would silently skip
 *   errors without distinguishing transient failures from auth failures.
 *   Now implements the same cooldown-based rotation used by SportDbClient and
 *   BsdApiClient:
 *     - 401/403 → auth failure, rotate immediately
 *     - 429     → rate limited, cooldown this key, try next
 *     - 5xx     → server error, skip key, try next
 *     - Network → transient, retry same key once then rotate
 */
@Slf4j
@Component
public class SportSrcClient {

    private static final String BASE_URL = "https://api.sportsrc.org/v2/";

    /** Keys on cooldown cannot be used until the timer expires. */
    private static final long KEY_COOLDOWN_MS     = 5 * 60_000L; // 5 minutes
    private static final long KEY_ROTATE_DELAY_MS = 150L;
    private static final int  TRANSIENT_RETRIES   = 1;

    private final WebClient client;
    private final boolean   demoMode;

    private final List<String> apiKeys = List.of(
            "712c025bda60cd2fd4b532c1c004a4be",
            "92854d3f6a8b55dd82d9e60304f9d1d3",
            "be6effc376d16e1152bee16ff4b4df66"
    );

    /** Key → timestamp after which it is safe to use again. */
    private final ConcurrentHashMap<String, Long> keyCooldowns = new ConcurrentHashMap<>();

    public SportSrcClient(WebClient.Builder builder,
                          @Value("${app.platform.demo-mode:false}") boolean demoMode) {
        this.demoMode = demoMode;
        this.client = builder
                .baseUrl(BASE_URL)
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    // ── Key cooldown helpers ──────────────────────────────────────────────

    private boolean isKeyCoolingDown(String key) {
        Long until = keyCooldowns.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            keyCooldowns.remove(key);
            log.debug("SportSRC key ...{} cooldown expired, re-enabling.", tail(key));
            return false;
        }
        return true;
    }

    private void coolDownKey(String key, long ms) {
        keyCooldowns.put(key, System.currentTimeMillis() + ms);
        log.warn("SportSRC key ...{} placed on {}s cooldown.", tail(key), ms / 1000);
    }

    // ── Core caller (Map response) ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callMap(String type, String matchId,
                                        Map<String, Object> fallback) {
        if (demoMode) return fallback != null ? fallback : Map.of();

        int usableKeys = 0;
        for (String key : apiKeys) {
            if (isKeyCoolingDown(key)) {
                log.debug("SportSRC [{}] key ...{} is cooling down, skipping.", type, tail(key));
                continue;
            }
            usableKeys++;

            for (int attempt = 0; attempt <= TRANSIENT_RETRIES; attempt++) {
                final int currentAttempt = attempt;
                try {
                    Map result = client.get()
                            .uri(u -> {
                                var b = u.queryParam("type", type);
                                if (matchId != null) b = b.queryParam("id", matchId);
                                return b.build();
                            })
                            .header("X-API-KEY", key)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .timeout(Duration.ofSeconds(10))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                if (e instanceof WebClientResponseException wcre) {
                                    int status = wcre.getStatusCode().value();
                                    if (status == 401 || status == 403) {
                                        log.warn("SportSRC [{}] key ...{} HTTP {} (auth failure), rotating",
                                                type, tail(key), status);
                                        return Mono.empty(); // rotate immediately
                                    }
                                    if (status == 429) {
                                        log.warn("SportSRC [{}] key ...{} HTTP 429 (rate limited), cooling down",
                                                type, tail(key));
                                        coolDownKey(key, KEY_COOLDOWN_MS);
                                        return Mono.error(new SkipKeyException("HTTP 429"));
                                    }
                                    log.warn("SportSRC [{}] key ...{} HTTP {} (error), trying next key",
                                            type, tail(key), status);
                                    return Mono.error(new SkipKeyException("HTTP " + status));
                                }
                                log.warn("SportSRC [{}] key ...{} attempt={} network error: {}",
                                        type, tail(key), currentAttempt, e.getMessage());
                                return Mono.empty(); // transient — retry same key
                            })
                            .block();

                    if (result == null || result.isEmpty()) {
                        log.debug("SportSRC [{}] key ...{} attempt={} → empty response, retrying",
                                type, tail(key), currentAttempt);
                        continue;
                    }

                    // Check for API-level success=false
                    Object success = result.get("success");
                    if (Boolean.FALSE.equals(success)) {
                        log.warn("SportSRC [{}] key ...{} → success=false: {}, rotating",
                                type, tail(key), result.get("message"));
                        break; // try next key
                    }

                    log.info("SportSRC [{}] key ...{} → OK", type, tail(key));
                    return (Map<String, Object>) result;

                } catch (SkipKeyException e) {
                    log.debug("SportSRC [{}] key ...{} → {}, skipping to next key",
                            type, tail(key), e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warn("SportSRC [{}] key ...{} attempt={} threw: {}",
                            type, tail(key), currentAttempt, e.getMessage());
                    if (currentAttempt == TRANSIENT_RETRIES) break;
                }
            }

            sleepQuietly(KEY_ROTATE_DELAY_MS);
        }

        if (usableKeys == 0) {
            log.warn("SportSRC [{}] → ALL keys on cooldown, using fallback", type);
        } else {
            log.error("SportSRC [{}] → all {} usable keys exhausted, using fallback", type, usableKeys);
        }
        return fallback != null ? fallback : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callList(String type, String matchId,
                                               String listKey,
                                               List<Map<String, Object>> fallback) {
        Map<String, Object> raw = callMap(type, matchId, null);
        if (raw == null || raw.isEmpty()) return fallback != null ? fallback : List.of();

        Object data = raw.get("data");
        Map<String, Object> payload = (data instanceof Map) ? (Map<String, Object>) data : raw;

        Object list = payload.get(listKey);
        if (list instanceof List) return (List<Map<String, Object>>) list;

        list = raw.get(listKey);
        if (list instanceof List) return (List<Map<String, Object>>) list;

        log.warn("SportSRC [{}] key '{}' not found in response", type, listKey);
        return fallback != null ? fallback : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> raw,
                                            Map<String, Object> fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        Object data = raw.get("data");
        return (data instanceof Map) ? (Map<String, Object>) data : fallback;
    }

    /** Signals: skip this key, try the next one immediately. */
    private static class SkipKeyException extends RuntimeException {
        SkipKeyException(String msg) { super(msg); }
    }

    // ── Public API — Free Tier ────────────────────────────────────────────

    /**
     * Fetch matches by sport, status and date.
     * @param sport  e.g. "soccer", "basketball"
     * @param status e.g. "live", "upcoming", "finished"
     * @param date   YYYY-MM-DD, or null for today
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMatches(String sport, String status, String date) {
        if (demoMode) return Map.of("data", List.of());

        int usableKeys = 0;
        for (String key : apiKeys) {
            if (isKeyCoolingDown(key)) continue;
            usableKeys++;
            try {
                Map result = client.get()
                        .uri(u -> u
                                .queryParam("type",   "matches")
                                .queryParam("sport",  sport)
                                .queryParam("status", status)
                                .queryParam("date",   date)
                                .build())
                        .header("X-API-KEY", key)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofSeconds(10))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(e -> {
                            if (e instanceof WebClientResponseException wcre) {
                                int s = wcre.getStatusCode().value();
                                if (s == 401 || s == 403) return Mono.empty();
                                if (s == 429) { coolDownKey(key, KEY_COOLDOWN_MS); }
                                return Mono.error(new SkipKeyException("HTTP " + s));
                            }
                            return Mono.empty();
                        })
                        .block();

                if (result != null && !result.isEmpty()
                        && !Boolean.FALSE.equals(result.get("success"))) {
                    log.info("SportSRC [matches] key ...{} → OK", tail(key));
                    return (Map<String, Object>) result;
                }
            } catch (SkipKeyException e) {
                log.debug("SportSRC [matches] key ...{} → {}, skipping", tail(key), e.getMessage());
            } catch (Exception e) {
                log.warn("SportSRC [matches] key ...{} threw: {}", tail(key), e.getMessage());
            }
            sleepQuietly(KEY_ROTATE_DELAY_MS);
        }

        log.error("SportSRC [matches] all {} usable keys failed", usableKeys);
        return Map.of("data", List.of());
    }

    /**
     * Fetch live scores for a specific date.
     * @param date YYYY-MM-DD, or null for today
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getScores(String date) {
        if (demoMode) return List.of();

        int usableKeys = 0;
        for (String key : apiKeys) {
            if (isKeyCoolingDown(key)) continue;
            usableKeys++;
            try {
                Map result = client.get()
                        .uri(u -> u
                                .queryParam("type", "scores")
                                .queryParam("date", date)
                                .build())
                        .header("X-API-KEY", key)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofSeconds(10))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(e -> {
                            if (e instanceof WebClientResponseException wcre) {
                                int s = wcre.getStatusCode().value();
                                if (s == 401 || s == 403) return Mono.empty();
                                if (s == 429) { coolDownKey(key, KEY_COOLDOWN_MS); }
                                return Mono.error(new SkipKeyException("HTTP " + s));
                            }
                            return Mono.empty();
                        })
                        .block();

                if (result != null && !Boolean.FALSE.equals(result.get("success"))) {
                    Object data = result.get("data");
                    if (data instanceof List) {
                        log.info("SportSRC [scores] key ...{} → {} scores", tail(key),
                                ((List<?>) data).size());
                        return (List<Map<String, Object>>) data;
                    }
                }
            } catch (SkipKeyException e) {
                log.debug("SportSRC [scores] key ...{} → {}, skipping", tail(key), e.getMessage());
            } catch (Exception e) {
                log.warn("SportSRC [scores] key ...{} threw: {}", tail(key), e.getMessage());
            }
            sleepQuietly(KEY_ROTATE_DELAY_MS);
        }

        log.error("SportSRC [scores] all {} usable keys failed", usableKeys);
        return List.of();
    }

    /**
     * Fetch live scores for today (convenience).
     */
    public List<Map<String, Object>> getLiveScores() {
        return getScores(java.time.LocalDate.now().toString());
    }

    /**
     * Fetch match detail + stream sources.
     */
    public Map<String, Object> getDetail(String matchId) {
        if (demoMode) return getDemoDetail(matchId);
        Map<String, Object> raw = callMap("detail", matchId, null);
        return extractData(raw, getDemoDetail(matchId));
    }

    /**
     * Fetch available stream sources for a match.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getStreamSources(String matchId) {
        if (demoMode) return List.of();
        Map<String, Object> detail = getDetail(matchId);
        Object sources = detail.get("sources");
        return (sources instanceof List) ? (List<Map<String, Object>>) sources : List.of();
    }

    /**
     * Fetch account / plan status (useful for verifying keys are active).
     */
    public Map<String, Object> getAccountInfo() {
        Map<String, Object> raw = callMap("account", null, Map.of());
        return extractData(raw, Map.of());
    }

    // ── Key status (for health/diagnostics) ──────────────────────────────

    public Map<String, Object> getKeyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String key : apiKeys) {
            String t = tail(key);
            Long until = keyCooldowns.get(key);
            if (until == null || now >= until) {
                status.put("..." + t, "ACTIVE");
            } else {
                long secondsLeft = (until - now) / 1000;
                status.put("..." + t, "COOLDOWN (" + secondsLeft + "s remaining)");
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

    private Map<String, Object> getDemoDetail(String matchId) {
        return Map.of(
                "match_info", Map.of(
                        "id",     matchId,
                        "title",  "Arsenal vs Chelsea",
                        "status", "finished",
                        "status_detail", "Ended",
                        "league", Map.of("name", "Premier League", "country", "England"),
                        "teams",  Map.of(
                                "home", Map.of("name", "Arsenal",
                                        "badge", "https://static.flashscore.com/res/image/data/SKbpVP5K.png"),
                                "away", Map.of("name", "Chelsea",
                                        "badge", "https://static.flashscore.com/res/image/data/tKrId2wB.png")),
                        "score",  Map.of("display", "1 - 0",
                                "current", Map.of("home", 1, "away", 0))
                ),
                "sources", List.of(),
                "info", Map.of(
                        "venue",   Map.of("stadium", "Emirates Stadium", "city", "London"),
                        "referee", Map.of("name", "Michael Oliver", "country", "England")
                )
        );
    }
}
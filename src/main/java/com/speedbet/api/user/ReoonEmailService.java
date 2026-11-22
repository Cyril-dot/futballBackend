package com.speedbet.api.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedbet.api.superadmin.ReoonSettingsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reoon Email Verifier (https://emailverifier.reoon.com) — single-email
 * check used by the MSport email-verification flow.
 *
 * Docs (reoon.com/articles/api-documentation-of-reoon-email-verifier):
 * GET {base}/verify?email=&lt;email&gt;&amp;key=&lt;key&gt;&amp;mode=quick
 * returns {email, status, is_valid_syntax, is_disposable, is_role_account,
 * mx_accepts_mail, is_spamtrap, is_free_email, mx_records,
 * verification_mode}. QUICK mode is used deliberately: it answers in
 * under 0.5s and the docs call it "best for: during user registration"
 * — POWER mode can take a minute, which is unusable on a signup call.
 * Quick-mode statuses are "valid", "invalid", "disposable", "spamtrap".
 *
 * Mapping: "valid" → valid; "invalid" / "disposable" (or
 * is_disposable=true) → invalid; every other status — and any Reoon
 * outage, timeout or unexpected response — FAILS OPEN (valid=true), so
 * the verification-link flow decides instead of a third-party blip
 * locking a real user out.
 *
 * The key resolves stored-value-else-env via ReoonSettingsService; the
 * switch must be on AND a key present before any call is made.
 * No auth is needed on this service itself — callers decide who may
 * trigger a check (note: POST /api/auth/validate-email is public by
 * design, so it burns one Reoon credit per call; anyone can call it).
 *
 * POWER mode ({@code checkPower}) is the deep check: it verifies the
 * individual inbox actually exists, which quick mode does not (the
 * docs say non-existing inboxes from a good domain read "valid" in
 * quick mode). It is used only by POST /api/auth/verify-email-now,
 * where the mail is verified with NO link sent — only "safe" proves
 * the mailbox. Power can take a few seconds to over a minute, so its
 * timeout is ~90s; an outage, timeout or non-200 is INCONCLUSIVE,
 * never an error, and changes no state.
 */
@Service
@RequiredArgsConstructor
public class ReoonEmailService {

    private static final Logger log = LoggerFactory.getLogger(ReoonEmailService.class);

    /** One Reoon credit per call — fail open fast rather than hang a signup on it. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Power mode can take a few seconds to over a minute (docs) — allow ~90s; a timeout is inconclusive, never an error. */
    private static final Duration POWER_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POWER_REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private final ReoonSettingsService settingsService;
    private final ObjectMapper objectMapper;

    @Value("${app.reoon.base-url:https://emailverifier.reoon.com/api/v1}")
    private String baseUrl;

    /**
     * True only when the switch is on and a key resolves (stored or env).
     * NEVER throws: resolving the settings reads the DB and may decrypt a
     * stored key — either can fail (missing table, undecryptable key) and
     * a failure here must never 500 a login. Any resolution failure reads
     * as "not configured" (fail open); the next login retries.
     */
    public boolean configured() {
        try {
            var resolved = settingsService.resolved();
            return resolved.enabled() && resolved.apiKey() != null && !resolved.apiKey().isBlank();
        } catch (Exception e) {
            log.warn("reoon: settings could not be resolved: {} — treating as not configured", e.getMessage());
            return false;
        }
    }

    /** Outcome of one Reoon check. {@code reason} is a user-facing line, present only when invalid. */
    public record Result(boolean valid, String status, String reason) {
    }

    /** Calls Reoon (quick mode). Never throws for a Reoon-side failure — those fail open. */
    public Result check(String email) {
        var resolved = settingsService.resolved();
        String apiKey = resolved.apiKey();
        if (!resolved.enabled() || apiKey == null || apiKey.isBlank()) {
            // Unconfigured is a fail-open, not an error: callers guard with
            // configured(), but the guard and this call resolve the settings
            // separately — a change between the two must never 500 a login.
            log.warn("reoon: check called while not configured for email='{}' — failing open", email);
            return new Result(true, "unknown", null);
        }
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://emailverifier.reoon.com/api/v1" : baseUrl.replaceAll("/+$", "");
        try {
            String url = base + "/verify?email="
                    + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&mode=quick";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("reoon: non-200 status={} for email='{}' — failing open", response.statusCode(), email);
                return new Result(true, "unknown", null);
            }
            return map(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            log.warn("reoon: check failed for email='{}': {} — failing open", email, e.getMessage());
            return new Result(true, "unknown", null);
        }
    }

    /** Outcome of one Reoon POWER check. {@code reason} is a short user-facing line, present only when not safe. */
    public record PowerResult(Outcome outcome, String status, String reason) {
        public enum Outcome { SAFE, INVALID, INCONCLUSIVE }
    }

    /** Calls Reoon (power mode). Never throws for a Reoon-side failure — those are INCONCLUSIVE, never an error. */
    public PowerResult checkPower(String email) {
        var resolved = settingsService.resolved();
        String apiKey = resolved.apiKey();
        if (!resolved.enabled() || apiKey == null || apiKey.isBlank()) {
            // Unconfigured is inconclusive, not an error — same fail-open
            // reasoning as check() (see above).
            log.warn("reoon: power check called while not configured for email='{}' — inconclusive", email);
            return new PowerResult(PowerResult.Outcome.INCONCLUSIVE, "unknown",
                    "The verification service didn't respond — please try again or use the verification link instead.");
        }
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://emailverifier.reoon.com/api/v1" : baseUrl.replaceAll("/+$\", \"\");
        try {
            String url = base + "/verify?email="
                    + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&mode=power";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(POWER_REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(POWER_CONNECT_TIMEOUT)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("reoon: power non-200 status={} for email='{}' — inconclusive", response.statusCode(), email);
                return new PowerResult(PowerResult.Outcome.INCONCLUSIVE, "unknown",
                        "The verification service didn't respond — please try again or use the verification link instead.");
            }
            return mapPower(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            log.warn("reoon: power check failed for email='{}': {} — inconclusive", email, e.getMessage());
            return new PowerResult(PowerResult.Outcome.INCONCLUSIVE, "unknown",
                    "The verification service didn't respond — please try again or use the verification link instead.");
        }
    }

    /**
     * Maps a Reoon power-mode response. Only the documented fields are
     * read. Power statuses are "safe", "invalid", "disabled",
     * "disposable", "inbox_full", "catch_all", "role_account",
     * "spamtrap", "unknown". Only "safe" proves the mailbox exists.
     */
    private PowerResult mapPower(JsonNode body) {
        if (body == null || !body.hasNonNull("status")) {
            return new PowerResult(PowerResult.Outcome.INCONCLUSIVE, "unknown",
                    "We couldn't confirm this email address — please use the verification link instead.");
        }
        String status = body.path("status").asText("unknown").toLowerCase();
        boolean disposable = body.path("is_disposable").asBoolean(false);
        if ("safe".equals(status) && !disposable) {
            return new PowerResult(PowerResult.Outcome.SAFE, "safe", null);
        }
        if ("invalid".equals(status)) {
            return new PowerResult(PowerResult.Outcome.INVALID, "invalid", "This email address is invalid.");
        }
        if ("disabled".equals(status)) {
            return new PowerResult(PowerResult.Outcome.INVALID, "disabled", "This email address is disabled.");
        }
        if ("disposable".equals(status) || disposable) {
            return new PowerResult(PowerResult.Outcome.INVALID, "disposable", "Disposable email addresses are not allowed.");
        }
        if ("inbox_full".equals(status)) {
            return new PowerResult(PowerResult.Outcome.INVALID, "inbox_full", "This email inbox is full.");
        }
        // catch_all, role_account, spamtrap, unknown and anything else:
        // power could not prove the mailbox — inconclusive, no state change.
        return new PowerResult(PowerResult.Outcome.INCONCLUSIVE, status,
                "We couldn't confirm this email address — please use the verification link instead.");
    }

    /** Maps a Reoon quick-mode response onto valid/invalid. Only the documented fields are read. */
    private Result map(JsonNode body) {
        if (body == null || !body.hasNonNull("status")) {
            return new Result(true, "unknown", null);
        }
        String status = body.path("status").asText("unknown").toLowerCase();
        boolean disposable = body.path("is_disposable").asBoolean(false);
        if ("valid".equals(status) && !disposable) {
            return new Result(true, "valid", null);
        }
        if ("invalid".equals(status)) {
            return new Result(false, "invalid", "This email address is invalid.");
        }
        if ("disposable".equals(status) || disposable) {
            return new Result(false, "disposable", "Disposable email addresses are not allowed.");
        }
        // spamtrap and anything else the docs list: fail open.
        return new Result(true, status, null);
    }
}

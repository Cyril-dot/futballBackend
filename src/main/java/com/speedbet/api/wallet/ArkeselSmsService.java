package com.speedbet.api.wallet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.speedbet.api.config.ArkeselSmsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Arkesel SMS client (V2 API).
 *
 * Moved off V1 because V1 puts the API key in the query string, which meant the
 * key was written to the application log on every send. V2 takes it in an
 * `api-key` header and returns structured JSON instead of a bare string, so
 * failures can be told apart properly.
 *
 * Sending never throws. A failed text is not a reason to fail the operation
 * that triggered it — callers get a boolean if they want to know.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArkeselSmsService {

    private static final String SEND_PATH = "/api/v2/sms/send";

    /** Max length Arkesel accepts for an alphanumeric sender ID. */
    private static final int SENDER_ID_MAX_LENGTH = 11;

    /**
     * Values that have shown up in this project's env vars as stand-ins for a
     * real key. Calling Arkesel with one of these just burns a request and
     * returns "Authentication Failed", so we skip and say why.
     */
    private static final Set<String> PLACEHOLDER_KEYS = Set.of(
            "active", "changeme", "todo", "placeholder", "your-api-key",
            "j888888888888888"
    );

    private final ArkeselSmsConfig smsConfig;
    private final RestTemplate     restTemplate;

    /**
     * @return true when Arkesel accepted the message, false on any skip or failure.
     */
    public boolean sendSms(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("SMS skipped — no phone number provided");
            return false;
        }
        if (message == null || message.isBlank()) {
            log.warn("SMS skipped — empty message body");
            return false;
        }

        final String recipient = normaliseGhanaNumber(phoneNumber);

        if (smsConfig.isSandbox()) {
            log.info("[SANDBOX] SMS to {} ({} chars): {}", recipient, message.length(), message);
            return true;
        }

        if (!configIsUsable()) {
            return false;
        }

        final String senderId = smsConfig.getSenderId();
        final String url = trimTrailingSlash(smsConfig.getBaseUrl()) + SEND_PATH;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("api-key", smsConfig.getApiKey());

        Map<String, Object> body = Map.of(
                "sender",     senderId,
                "message",    message,
                "recipients", List.of(recipient)
        );

        log.info("Arkesel send → to={} from={} chars={} url={}",
                recipient, senderId, message.length(), url);

        try {
            ResponseEntity<ArkeselResponse> res = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), ArkeselResponse.class);

            ArkeselResponse payload = res.getBody();

            if (payload != null && payload.isSuccess()) {
                log.info("SMS delivered to Arkesel for {}", recipient);
                return true;
            }

            log.error("Arkesel rejected the message for {} — status='{}' message='{}'",
                    recipient,
                    payload != null ? payload.status() : "<no body>",
                    payload != null ? payload.message() : "<no body>");
            return false;

        } catch (HttpStatusCodeException e) {
            // Arkesel returns 4xx with a JSON body explaining the problem —
            // surface that rather than just the status line.
            log.error("Arkesel returned {} for {} — body: {}",
                    e.getStatusCode(), recipient, describeError(e.getResponseBodyAsString()));
            return false;

        } catch (Exception e) {
            log.error("Arkesel request failed for {}: {}", recipient, e.getMessage(), e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Config validation
    // ─────────────────────────────────────────────────────────────────────────

    /** Checks the credentials look real before spending a network round trip. */
    private boolean configIsUsable() {
        String key = smsConfig.getApiKey();

        if (key == null || key.isBlank()) {
            log.error("SMS not sent — arkesel.api-key is not set. "
                    + "Set the ARKESEL_API_KEY environment variable to the key from "
                    + "Arkesel Dashboard → SMS API.");
            return false;
        }

        if (PLACEHOLDER_KEYS.contains(key.trim().toLowerCase())) {
            log.error("SMS not sent — arkesel.api-key is set to the placeholder '{}'. "
                    + "Arkesel will reject this with 'Authentication Failed' (code 102). "
                    + "Set ARKESEL_API_KEY to a real key.", key);
            return false;
        }

        String sender = smsConfig.getSenderId();
        if (sender == null || sender.isBlank()) {
            log.error("SMS not sent — arkesel.sender-id is not set.");
            return false;
        }
        if (sender.length() > SENDER_ID_MAX_LENGTH) {
            log.error("SMS not sent — sender ID '{}' is {} characters; Arkesel allows at most {}.",
                    sender, sender.length(), SENDER_ID_MAX_LENGTH);
            return false;
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Arkesel wants recipients in international format without a leading plus:
     * 233XXXXXXXXX. Accepts 0XXXXXXXXX, +233XXXXXXXXX, 233XXXXXXXXX and
     * anything with spaces, dashes or brackets in it.
     */
    private String normaliseGhanaNumber(String phone) {
        String digits = phone.trim().replaceAll("[\\s\\-()+]", "");

        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.startsWith("0")) {
            digits = "233" + digits.substring(1);
        }
        return digits;
    }

    private String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Defensive scrub — the API key travels in a header on V2, but if a future
     * error body ever echoes a URL back at us it must not reach the log intact.
     */
    private String describeError(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        return body.replaceAll("(?i)(api[-_]?key=)[^&\\s\"]*", "$1***");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response shape
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * V2 replies with {"status":"success","data":{...}} on acceptance and
     * {"status":"error","message":"..."} on rejection. `data` is ignored — the
     * per-message ids are only useful for delivery-report polling, which we
     * don't do yet.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArkeselResponse(String status, String message, Object data) {
        boolean isSuccess() {
            return "success".equalsIgnoreCase(status);
        }
    }
}
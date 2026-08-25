package com.speedbet.api.wallet.jetsms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * JestSMS client.
 *
 * Structured the same way as ArkeselSmsService: sandbox short-circuit,
 * placeholder-key guard so a misconfigured deploy fails loudly in logs
 * instead of silently burning a request, non-throwing sendSms() so a
 * failed send never fails the operation that triggered it, and scrubbed
 * error logging.
 *
 * IMPORTANT — this class does not restrict WHO can call it. Access control
 * (only specific approved sites/services may trigger a send) is enforced
 * one layer up by SmsAccessGuard, which should run in the controller before
 * sendSms() is ever invoked. Keeping that check out of this class means the
 * transport logic here stays reusable (e.g. for internal/background jobs
 * that don't go through the public controller at all).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JestSmsService {

    private static final String SEND_PATH = "/send-sms";

    /** JestSMS doesn't document a sender-id length cap; Arkesel's 11-character
     *  alphanumeric limit is the common GSM sender-ID convention, so it's
     *  kept here as a sanity check rather than a confirmed JestSMS rule. */
    private static final int SENDER_ID_MAX_LENGTH = 11;

    private static final Set<String> PLACEHOLDER_KEYS = Set.of(
            "active", "changeme", "todo", "placeholder", "your-api-key",
            "no_whatsapp_api_key_found"
    );

    private final JestSmsConfig   smsConfig;
    private final SmsAccessConfig accessConfig;
    private final RestTemplate    restTemplate;

    /**
     * Renders a named template (from jestsms.templates.*) and sends it.
     *
     * {site_name} is always filled in automatically from
     * SMS_ALLOWED_SITE_NAME (via SmsAccessConfig) — callers don't need to
     * pass it. Any other placeholders in the template (e.g. {name},
     * {amount}, {code}, {minutes}) must be supplied in `vars`.
     *
     * Example:
     *   sendTemplatedSms("0541709799", "otp", Map.of("code", "123456", "minutes", "5"));
     *
     * Template: "Your {site_name} verification code is {code}. It expires in {minutes} minutes."
     * Result:   "Your SpeedBet Main Site verification code is 123456. It expires in 5 minutes."
     */
    public boolean sendTemplatedSms(String phoneNumber, String templateKey, Map<String, String> vars) {
        String template = smsConfig.getTemplates().get(templateKey);
        if (template == null || template.isBlank()) {
            log.error("SMS not sent — no template configured for key '{}' "
                    + "(expected property jestsms.templates.{})", templateKey, templateKey);
            return false;
        }

        String rendered = renderTemplate(template, vars);
        return sendSms(phoneNumber, rendered);
    }

    private String renderTemplate(String template, Map<String, String> vars) {
        String result = template.replace("{site_name}", accessConfig.getAllowedSiteName());

        if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    /**
     * @return true when JestSMS accepted the message, false on any skip or failure.
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
        // JestSMS docs show the key accepted three interchangeable ways;
        // sending all three keeps this working regardless of which one
        // their gateway actually checks.
        headers.setBearerAuth(smsConfig.getApiKey());
        headers.set("X-API-KEY", smsConfig.getApiKey());
        headers.set("Api-Key", smsConfig.getApiKey());

        Map<String, Object> body = Map.of(
                "sender",     senderId,
                "message",    message,
                "recipients", recipient
        );

        log.info("JestSMS send → to={} from={} chars={} url={}",
                recipient, senderId, message.length(), url);

        try {
            ResponseEntity<JestSmsResponse> res = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), JestSmsResponse.class);

            JestSmsResponse payload = res.getBody();

            if (payload != null && payload.isSuccess()) {
                log.info("SMS delivered to JestSMS for {} (sent={}, failed={}, balance={})",
                        recipient, payload.sent(), payload.failed(), payload.remainingBalance());
                return true;
            }

            log.error("JestSMS rejected the message for {} — status='{}' message='{}'",
                    recipient,
                    payload != null ? payload.status() : "<no body>",
                    payload != null ? payload.message() : "<no body>");
            return false;

        } catch (HttpStatusCodeException e) {
            log.error("JestSMS returned {} for {} — body: {}",
                    e.getStatusCode(), recipient, describeError(e.getResponseBodyAsString()));
            return false;

        } catch (Exception e) {
            log.error("JestSMS request failed for {}: {}", recipient, e.getMessage(), e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Config validation
    // ─────────────────────────────────────────────────────────────────────────

    private boolean configIsUsable() {
        String key = smsConfig.getApiKey();

        if (key == null || key.isBlank()) {
            log.error("SMS not sent — jestsms.api-key is not set. "
                    + "Set the JESTSMS_API_KEY environment variable to the SMS API key "
                    + "shown on the JestSMS API Documentation page.");
            return false;
        }

        if (PLACEHOLDER_KEYS.contains(key.trim().toLowerCase())) {
            log.error("SMS not sent — jestsms.api-key is set to the placeholder '{}'. "
                    + "Set JESTSMS_API_KEY to a real key.", key);
            return false;
        }

        String sender = smsConfig.getSenderId();
        if (sender == null || sender.isBlank()) {
            log.error("SMS not sent — jestsms.sender-id is not set.");
            return false;
        }
        if (sender.length() > SENDER_ID_MAX_LENGTH) {
            log.error("SMS not sent — sender ID '{}' is {} characters; keeping it at or under {} "
                    + "avoids sender-ID rejection.", sender, sender.length(), SENDER_ID_MAX_LENGTH);
            return false;
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JestSMS's own examples use local format (0XXXXXXXXX) directly, but
     * international format (233XXXXXXXXX) is safer/more portable, so this
     * normalises to that the same way ArkeselSmsService does.
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

    private String describeError(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        return body.replaceAll("(?i)(api[-_]?key=)[^&\\s\"]*", "$1***");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response shape
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JestSMS /send-sms replies with:
     * {"status":"success","message":"...","recipients":2,"sent":2,
     *  "failed":0,"units_deducted":2,"remaining_balance":"98.00"}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JestSmsResponse(
            String status,
            String message,
            Integer recipients,
            Integer sent,
            Integer failed,
            @com.fasterxml.jackson.annotation.JsonProperty("units_deducted") Integer unitsDeducted,
            @com.fasterxml.jackson.annotation.JsonProperty("remaining_balance") String remainingBalance
    ) {
        boolean isSuccess() {
            return "success".equalsIgnoreCase(status);
        }
    }
}
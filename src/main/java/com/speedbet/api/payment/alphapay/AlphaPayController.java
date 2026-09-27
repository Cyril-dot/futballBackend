package com.speedbet.api.payment.alphapay;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AlphaPay mobile money integration.
 *
 * Base URL  : https://api.edibytes.online/api
 * Domain    : superrbett.com  (whitelisted in AlphaPay dashboard)
 * Secret key: injected via ${app.alphapay.secret-key}
 *
 * Key differences from Paystack
 * ─────────────────────────────
 * 1. Amounts are GHS major units (e.g. "50.00"), NOT pesewas — do NOT multiply by 100.
 * 2. Every initialize call must include "domain" matching a whitelisted dashboard entry.
 * 3. Pass "phone" to skip the hosted redirect and dispatch MoMo prompt directly.
 * 4. No webhook push yet — poll /payments/verify/:reference/ to detect completion.
 * 5. Trailing slash is required on both /payments/initialize/ and /payments/verify/:ref/.
 *
 * Reference format (FIXED)
 * ─────────────────────────
 * Previous format "DEP-<full-uuid>-<suffix>" was ~54 chars. AlphaPay silently accepted
 * the initialize call but never dispatched the MoMo prompt — almost certainly because
 * their system rejected the overly long/hyphenated reference internally.
 *
 * New format: "SB" + 18 random hex chars = 20 chars total. Short, URL-safe, unique.
 * Example: SB3f9a1c2e8b4d7f0a2c
 *
 * Because the userId is no longer embedded in the reference, ownership in verifyCharge
 * is checked via the in-memory pendingDeposits map (populated at charge time) rather
 * than by parsing the reference string. The webhook handler also uses this map; if the
 * entry has already been removed (e.g. verify ran first) it falls back to a log warning
 * and skips crediting (idempotency handled by walletService 409 check).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AlphaPayController {

    private static final Set<String> CREDITABLE_STATUSES =
            Set.of("success", "succeeded", "completed", "paid");

    private final Duration alphaPayTimeout       = Duration.ofSeconds(10);
    private final long     alphaPayRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    /**
     * In-memory store: reference → (userId, amount).
     * Populated when /charge is called; removed after successful crediting.
     * Note: this is lost on restart. For production resilience, back this
     * with Redis or a pending_deposits DB table.
     */
    private final Map<String, PendingDeposit> pendingDeposits = new ConcurrentHashMap<>();

    private static final class PendingDeposit {
        private final UUID       userId;
        private final BigDecimal amount;
        PendingDeposit(UUID userId, BigDecimal amount) {
            this.userId = userId;
            this.amount = amount;
        }
        UUID       userId() { return userId; }
        BigDecimal amount() { return amount; }
    }

    /**
     * Thrown when AlphaPay itself responds with an error.
     * upstreamUnavailable=true  → network/outage  → map to 502
     * upstreamUnavailable=false → AlphaPay said no (bad input etc.) → map to 400
     */
    private static final class AlphaPayApiException extends RuntimeException {
        final boolean upstreamUnavailable;
        AlphaPayApiException(String message, boolean upstreamUnavailable) {
            super(message);
            this.upstreamUnavailable = upstreamUnavailable;
        }
    }

    @Value("${app.alphapay.secret-key}")               private String     secretKey;
    @Value("${app.alphapay.base-url}")                 private String     baseUrl;
    @Value("${app.alphapay.domain}")                   private String     whitelistedDomain;
    @Value("${app.platform.min-deposit-amount-ghs:1}") private BigDecimal minDeposit;

    // ─── Step 1a — Initialize (hosted redirect) ────────────────────────────────

    @PostMapping("/api/wallet/deposit/alphapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[AlphaPay][init] START — userId='{}'", user.getId());

        var amount    = extractValidAmount(req, user.getId());
        var reference = buildReference();

        requireConfigured();

        Map<String, Object> response;
        try {
            response = alphaPayInitialize(amount, reference, null, user.getEmail());
        } catch (AlphaPayApiException e) {
            return alphaPayErrorResponse(e, "init");
        }

        // Store so verify can ownership-check without parsing the reference
        pendingDeposits.put(reference, new PendingDeposit(user.getId(), amount));

        log.info("[AlphaPay][init] DONE — userId='{}' ref='{}' hasCheckoutUrl={}",
                user.getId(), reference, response.get("checkout_url") != null);

        var result = new java.util.HashMap<String, Object>();
        result.put("reference", reference);
        result.put("checkoutUrl", response.get("checkout_url"));
        result.put("status", response.getOrDefault("status", "pending"));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Step 1b — Initialize (direct charge, MoMo prompt to phone) ────────────

    @PostMapping("/api/wallet/deposit/alphapay/charge")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDirectCharge(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[AlphaPay][charge] START — userId='{}'", user.getId());

        var amount   = extractValidAmount(req, user.getId());
        var rawPhone = req.get("phone") == null ? "" : String.valueOf(req.get("phone")).trim();
        if (rawPhone.isBlank() || rawPhone.equals("null"))
            throw ApiException.badRequest("Phone number is required.");

        var phone     = normalizeGhanaPhone(rawPhone);
        var reference = buildReference();

        requireConfigured();

        log.info("[AlphaPay][charge] Calling AlphaPay initialize — ref='{}' amountGHS={} phone='{}'",
                reference, amount, phone);

        Map<String, Object> response;
        try {
            response = alphaPayInitialize(amount, reference, phone, user.getEmail());
        } catch (AlphaPayApiException e) {
            return alphaPayErrorResponse(e, "charge");
        }

        // Must be stored AFTER a successful initialize so verify can find it
        pendingDeposits.put(reference, new PendingDeposit(user.getId(), amount));

        log.info("[AlphaPay][charge] DONE — userId='{}' ref='{}' status='{}'",
                user.getId(), reference, response.get("status"));

        var result = new java.util.HashMap<String, Object>();
        result.put("reference", reference);
        result.put("status", response.getOrDefault("status", "pending"));
        result.put("message", response.get("message") == null
                ? "Check your phone to approve the payment."
                : response.get("message"));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Step 2 — Verify / poll ─────────────────────────────────────────────────

    @GetMapping("/api/wallet/deposit/alphapay/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        if (reference == null || reference.isBlank())
            throw ApiException.badRequest("reference is required.");

        // Ownership check via pendingDeposits — no need to parse the reference string
        var pending = pendingDeposits.get(reference);
        if (pending == null) {
            // May have already been credited (e.g. a previous successful verify call).
            // Allow the call through so the frontend can read the final status from
            // AlphaPay and show the correct state; just don't credit again.
            log.warn("[AlphaPay][verify] No pending deposit for ref='{}' userId='{}' — may already be credited",
                    reference, user.getId());
        } else if (!user.getId().equals(pending.userId())) {
            throw ApiException.badRequest("This payment does not belong to the signed-in user.");
        }

        log.info("[AlphaPay][verify] userId='{}' ref='{}'", user.getId(), reference);

        Map<String, Object> response;
        try {
            response = alphaPayVerify(reference);
        } catch (AlphaPayApiException e) {
            return alphaPayErrorResponse(e, "verify");
        }

        var status   = paymentStatus(response);
        var credited = false;

        if (CREDITABLE_STATUSES.contains(status)) {
            var amount = extractAmount(response);
            if (amount == null && pending != null) amount = pending.amount();
            if (amount == null || amount.signum() <= 0)
                throw ApiException.badRequest("AlphaPay did not return a valid payment amount.");
            if (pending != null && amount.compareTo(pending.amount()) != 0)
                throw ApiException.badRequest("The verified amount does not match the requested deposit.");
            handleDeposit(user.getId(), reference, amount);
            pendingDeposits.remove(reference);
            credited = true;
        }

        log.info("[AlphaPay][verify] DONE — ref='{}' status='{}' credited={}", reference, status, credited);

        var result = new java.util.HashMap<String, Object>(response);
        result.put("status", status);
        result.put("credited", credited);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ────────────────────────────────────────────────────────────────
    //
    // AlphaPay does not yet push webhooks — this endpoint is here for when
    // they add the feature. It must be permit-all in SecurityConfig, e.g.:
    //   .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
    //
    // Signature: HMAC-SHA512 of raw body, hex-encoded, in X-AlphaPay-Signature.
    // Amount arrives in GHS major units — no division needed.

    @PostMapping("/api/webhooks/alphapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AlphaPay-Signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[AlphaPay][Webhook] HIT — remote='{}'", request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[AlphaPay][Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        log.info("[AlphaPay][Webhook] Body length={} bytes", rawBody.length);

        if (signature == null || signature.isBlank()) {
            log.warn("[AlphaPay][Webhook] REJECTED — missing X-AlphaPay-Signature");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("[AlphaPay][Webhook] REJECTED — HMAC mismatch");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("[AlphaPay][Webhook] Signature OK");

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event") != null ? event.get("event").toString() : "unknown";
            log.info("[AlphaPay][Webhook] event='{}'", eventType);

            if (!"payment.succeeded".equals(eventType)) {
                log.info("[AlphaPay][Webhook] Ignored event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("[AlphaPay][Webhook] payment.succeeded has null data");
                return ResponseEntity.status(400).body("Missing data");
            }

            var ref = data.get("reference") != null ? data.get("reference").toString() : "";
            if (ref.isBlank()) {
                log.error("[AlphaPay][Webhook] Missing reference in data");
                return ResponseEntity.status(400).body("Missing reference");
            }

            // Resolve userId from pendingDeposits (reference is now opaque — no userId embedded)
            var pendingEntry = pendingDeposits.get(ref);
            if (pendingEntry == null) {
                log.warn("[AlphaPay][Webhook] No pending deposit for ref='{}' — may already be credited, skipping", ref);
                return ResponseEntity.ok("OK-ALREADY-CREDITED");
            }

            var rawAmount = data.get("amount");
            if (rawAmount == null) {
                log.error("[AlphaPay][Webhook] Missing amount — ref='{}'", ref);
                return ResponseEntity.status(400).body("Missing amount");
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(rawAmount.toString());
            } catch (NumberFormatException e) {
                log.error("[AlphaPay][Webhook] Unparseable amount='{}' — ref='{}'", rawAmount, ref);
                return ResponseEntity.status(400).body("Invalid amount");
            }

            log.info("[AlphaPay][Webhook] Crediting — userId='{}' ref='{}' amountGHS={}",
                    pendingEntry.userId(), ref, amount);

            handleDeposit(pendingEntry.userId(), ref, amount);
            pendingDeposits.remove(ref);

        } catch (ApiException e) {
            log.error("[AlphaPay][Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[AlphaPay][Webhook] Unexpected error — AlphaPay will retry: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[AlphaPay][Webhook] COMPLETE — 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    private void handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("[AlphaPay][handleDeposit] START — userId='{}' amountGHS={} ref='{}'",
                userId, amount, ref);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "alphapay", "channel", "mobile_money", "reference", ref));
            log.info("[AlphaPay][handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'",
                    amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[AlphaPay][handleDeposit] Duplicate ref='{}' — already credited, skipping", ref);
                return;
            }
            log.error("[AlphaPay][handleDeposit] credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("[AlphaPay][handleDeposit] Commission attributed — userId='{}'", userId);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back the deposit
            log.error("[AlphaPay][handleDeposit] Commission FAILED (non-blocking) — userId='{}' — {}",
                    userId, ex.getMessage(), ex);
        }

        log.info("[AlphaPay][handleDeposit] COMPLETE — userId='{}' ref='{}'", userId, ref);
    }

    // ─── Status / amount helpers ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String paymentStatus(Map<String, Object> response) {
        Object raw = response.get("status");
        if (raw == null) raw = response.get("payment_status");
        Object nestedRaw = response.get("data");
        if (raw == null && nestedRaw instanceof Map) {
            var nested = (Map<String, Object>) nestedRaw;
            raw = nested.get("status");
            if (raw == null) raw = nested.get("payment_status");
        }
        return raw == null ? "unknown" : raw.toString().trim().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private BigDecimal extractAmount(Map<String, Object> response) {
        Object raw = response.get("amount");
        Object nestedRaw = response.get("data");
        if (raw == null && nestedRaw instanceof Map)
            raw = ((Map<String, Object>) nestedRaw).get("amount");
        if (raw == null) return null;
        try { return new BigDecimal(raw.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    // ─── Reference builder ─────────────────────────────────────────────────────

    /**
     * Builds a short, URL-safe, unique payment reference.
     *
     * Format: "SB" + 18 random hex chars = 20 chars total.
     * Example: SB3f9a1c2e8b4d7f0a2c
     *
     * Previous format ("DEP-<full-uuid>-<suffix>", ~54 chars) caused AlphaPay
     * to silently accept the initialize call but never dispatch the MoMo prompt.
     * Keeping it short and clean fixes that.
     *
     * The userId is NO LONGER embedded in the reference. Ownership is tracked
     * via the pendingDeposits ConcurrentHashMap instead.
     */
    private String buildReference() {
        String hex = UUID.randomUUID().toString().replace("-", ""); // 32 hex chars
        return "SB" + hex.substring(0, 18);                        // 20 chars total
    }

    // ─── AlphaPay API calls ─────────────────────────────────────────────────────

    private Map<String, Object> alphaPayInitialize(
            BigDecimal amountGhs, String reference, String phoneOrNull, String emailOrNull) {

        var body = new java.util.HashMap<String, Object>();
        // AlphaPay expects amount as a decimal STRING (e.g. "50.00"), not a bare number.
        body.put("amount",    amountGhs.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        body.put("currency",  "GHS");
        body.put("reference", reference);
        body.put("domain",    whitelistedDomain);
        if (phoneOrNull != null && !phoneOrNull.isBlank())
            body.put("phone", phoneOrNull);
        if (emailOrNull != null && !emailOrNull.isBlank())
            body.put("customer_email", emailOrNull);

        log.debug("[AlphaPay][alphaPayInitialize] body={}", body);

        // Trailing slash required — documented path is /payments/initialize/
        return postToAlphaPay("/payments/initialize/", body, "alphaPayInitialize");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> alphaPayVerify(String reference) {
        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder.build()
                    // Trailing slash required — documented path is /payments/verify/:reference/
                    .get().uri(baseUrl + "/payments/verify/" + reference + "/")
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                        log.error("[alphaPayVerify] HTTP error — status={} body='{}' ref='{}'",
                                r.statusCode(), respBody, reference);
                        return new AlphaPayApiException(
                                extractErrorMessage(respBody, "AlphaPay returned " + r.statusCode()),
                                false);
                    }))
                    .bodyToMono(Map.class)
                    .timeout(alphaPayTimeout)
                    .retryWhen(Retry.max(alphaPayRetryAttempts)
                            .filter(ex -> !(ex instanceof AlphaPayApiException)))
                    .onErrorMap(ex -> !(ex instanceof AlphaPayApiException),
                            ex -> new AlphaPayApiException(
                                    "AlphaPay is currently unavailable. Please try again.", true))
                    .block();
        } catch (AlphaPayApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[alphaPayVerify] Unexpected failure — ref='{}'", reference, e);
            throw new AlphaPayApiException("AlphaPay is currently unavailable. Please try again.", true);
        }

        if (result == null)
            throw new AlphaPayApiException("AlphaPay returned empty response.", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postToAlphaPay(
            String path, Map<String, Object> body, String tag) {

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder.build()
                    .post().uri(baseUrl + path)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                        log.error("[{}] HTTP error — path='{}' status={} body='{}'",
                                tag, path, r.statusCode(), respBody);
                        return new AlphaPayApiException(
                                extractErrorMessage(respBody, "AlphaPay returned " + r.statusCode()),
                                false);
                    }))
                    .bodyToMono(Map.class)
                    .timeout(alphaPayTimeout)
                    .retryWhen(Retry.max(alphaPayRetryAttempts)
                            .filter(ex -> !(ex instanceof AlphaPayApiException)))
                    .onErrorMap(ex -> !(ex instanceof AlphaPayApiException),
                            ex -> new AlphaPayApiException(
                                    "AlphaPay is currently unavailable. Please try again.", true))
                    .block();
        } catch (AlphaPayApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] Unexpected failure — path='{}'", tag, path, e);
            throw new AlphaPayApiException("AlphaPay is currently unavailable. Please try again.", true);
        }

        if (result == null)
            throw new AlphaPayApiException("AlphaPay returned empty response.", true);

        // AlphaPay documented error shape: { "error": { "type": "...", "message": "..." } }
        if (result.get("error") != null) {
            var error = (Map<String, Object>) result.get("error");
            var msg   = error.getOrDefault("message", "AlphaPay declined the request").toString();
            log.error("[{}] error — path='{}' msg='{}'", tag, path, msg);
            throw new AlphaPayApiException(msg, false);
        }

        return result;
    }

    /**
     * Pulls a human-readable message out of AlphaPay's error shape.
     * Falls back to a generic status-based message so we never leak
     * a raw stack trace or unparsed body to the client.
     */
    private String extractErrorMessage(String rawBody, String fallback) {
        if (rawBody == null || rawBody.isBlank()) return fallback;
        try {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
            Object errObj = parsed.get("error");
            if (errObj instanceof Map) {
                var err = (Map<?, ?>) errObj;
                if (err.get("message") != null) return err.get("message").toString();
            }
            if (parsed.get("message") != null) return parsed.get("message").toString();
        } catch (Exception ignored) {
            // Body wasn't JSON in the shape we expected — fall through to fallback.
        }
        return fallback;
    }

    /**
     * Maps an AlphaPayApiException to a proper HTTP response.
     * 400 = AlphaPay explicitly rejected the request (bad input, etc.)
     * 502 = AlphaPay unreachable / misbehaving
     */
    private ResponseEntity<ApiResponse<Map<String, Object>>> alphaPayErrorResponse(
            AlphaPayApiException e, String action) {
        var status = e.upstreamUnavailable ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
        log.error("[AlphaPay][{}] returning {} to client — {}", action, status.value(), e.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
    }

    private void requireConfigured() {
        if (secretKey == null || secretKey.isBlank()
                || baseUrl == null || baseUrl.isBlank()
                || whitelistedDomain == null || whitelistedDomain.isBlank()) {
            log.error("[AlphaPay] Missing configuration — secretKeySet={} baseUrlSet={} domainSet={}",
                    secretKey != null && !secretKey.isBlank(),
                    baseUrl  != null && !baseUrl.isBlank(),
                    whitelistedDomain != null && !whitelistedDomain.isBlank());
            throw new RuntimeException("AlphaPay is not configured. Please contact support.");
        }
    }

    // ─── Small helpers ─────────────────────────────────────────────────────────

    private BigDecimal extractValidAmount(Map<String, Object> req, UUID userId) {
        var rawAmount = req.get("amount");
        if (rawAmount == null) throw ApiException.badRequest("amount is required.");
        BigDecimal amount;
        try { amount = new BigDecimal(rawAmount.toString()); }
        catch (NumberFormatException e) { throw ApiException.badRequest("amount must be a valid number."); }
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        return amount;
    }

    private String normalizeGhanaPhone(String raw) {
        var digits = raw.replaceAll("[\\s\\-]", "");
        if (digits.startsWith("+233"))                               digits = "0" + digits.substring(4);
        else if (digits.startsWith("233") && digits.length() == 12) digits = "0" + digits.substring(3);
        if (!digits.matches("^0\\d{9}$"))
            throw ApiException.badRequest(
                    "Invalid Ghana phone number. Use 0XXXXXXXXX or +233XXXXXXXXX.");
        return digits;
    }

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            var matches  = computed.equals(signature);
            if (!matches)
                log.warn("[verifySignature] HMAC mismatch — computed='{}...' received='{}...'",
                        computed.substring(0, 8),
                        signature.substring(0, Math.min(8, signature.length())));
            return matches;
        } catch (Exception e) {
            log.error("[verifySignature] HMAC error", e);
            return false;
        }
    }
}
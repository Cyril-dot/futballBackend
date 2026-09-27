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

/**
 * AlphaPay mobile money integration.
 *
 * Flow differs from Paystack in a few important ways:
 *   1. No OTP / birthday step — a single "initialize" call either returns a
 *      checkout_url (hosted redirect flow) or, if "phone" is supplied,
 *      dispatches the charge directly and the customer approves on their handset.
 *   2. Amounts are sent in GHS major units (e.g. 50.00), NOT pesewas/minor units.
 *      This is the opposite of Paystack — do not multiply by 100 here.
 *      Confirmed against both the written API docs and the live docs page
 *      (the dashboard's own quick-start snippet shows "500000", which looks
 *      like a stale/wrong example — the prose spec is unambiguous and is
 *      what this controller follows).
 *   3. Every initialize call must include a "domain" that has already been
 *      whitelisted on the AlphaPay dashboard, or the request is rejected with 403.
 *   4. Webhook signature header is X-AlphaPay-Signature. AlphaPay's docs don't
 *      publish the algorithm; this implementation assumes HMAC-SHA512 hex
 *      (matching Paystack's scheme) per your confirmation — verify against a
 *      real webhook payload before relying on it in production.
 *   5. IMPORTANT — as of now, AlphaPay's dashboard has no self-serve field to
 *      register your webhook URL. Per their own docs: "In this demo the
 *      forwarding destination is set via an environment variable rather than
 *      a dashboard field — a per-business settings UI is next." Until that
 *      ships, you must send AlphaPay support your webhook URL directly
 *      (https://t.me/sitesupport10) to have it configured on their end.
 *      Do NOT block launch on the webhook working — this controller's verify
 *      endpoint is fully sufficient on its own; treat the webhook purely as
 *      a later optimization for near-real-time crediting.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AlphaPayController {

    private static final Set<String> CREDITABLE_STATUSES = Set.of("success");

    private final Duration alphaPayTimeout       = Duration.ofSeconds(10);
    private final long     alphaPayRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.alphapay.secret-key}")               private String     secretKey;
    @Value("${app.alphapay.base-url}")                 private String     baseUrl;
    @Value("${app.alphapay.domain}")                   private String     whitelistedDomain;
    @Value("${app.platform.min-deposit-amount:1}")     private BigDecimal minDeposit;

    // ─── Step 1a — Initialize (hosted redirect) ────────────────────────────────

    @PostMapping("/api/wallet/deposit/alphapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[AlphaPay][init] START — userId='{}'", user.getId());

        var amount    = extractValidAmount(req, user.getId());
        var reference = buildReference(user.getId());

        var response = alphaPayInitialize(amount, reference, null);

        log.info("[AlphaPay][init] DONE — userId='{}' ref='{}' hasCheckoutUrl={}",
                user.getId(), reference, response.get("checkout_url") != null);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "reference", reference,
                "checkoutUrl", response.getOrDefault("checkout_url", null),
                "status", response.getOrDefault("status", "pending")
        )));
    }

    // ─── Step 1b — Initialize (direct charge, no redirect) ─────────────────────

    @PostMapping("/api/wallet/deposit/alphapay/charge")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDirectCharge(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[AlphaPay][charge] START — userId='{}'", user.getId());

        var amount    = extractValidAmount(req, user.getId());
        var rawPhone  = req.get("phone") == null ? "" : String.valueOf(req.get("phone")).trim();
        if (rawPhone.isBlank() || rawPhone.equals("null"))
            throw ApiException.badRequest("Phone number is required.");

        var phone     = normalizeGhanaPhone(rawPhone);
        var reference = buildReference(user.getId());

        var response = alphaPayInitialize(amount, reference, phone);

        log.info("[AlphaPay][charge] DONE — userId='{}' ref='{}' status='{}'",
                user.getId(), reference, response.get("status"));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "reference", reference,
                "status", response.getOrDefault("status", "pending"),
                "message", response.getOrDefault("message", "Check your phone to approve the payment.")
        )));
    }

    // ─── Verify ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/wallet/deposit/alphapay/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        if (reference == null || reference.isBlank())
            throw ApiException.badRequest("reference is required.");

        log.info("[AlphaPay][verify] userId='{}' ref='{}'", user.getId(), reference);
        var response = alphaPayVerify(reference);
        log.info("[AlphaPay][verify] DONE — ref='{}' status='{}'", reference, response.get("status"));

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/alphapay
    //
    // Must be added to SecurityConfig alongside the Paystack rule, e.g.:
    //   .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
    // (already covers this path if your Paystack rule uses the /** wildcard).
    //
    // Identity is proven by HMAC-SHA512 over the raw body, sent in
    // X-AlphaPay-Signature. Unlike Paystack, AlphaPay's docs don't show a
    // "metadata" field on payment objects — the reference you chose at
    // initialize time is the only correlation key available. This
    // implementation therefore expects the reference to embed the userId
    // (see buildReference below) rather than reading it out of a metadata map.
    // ═══════════════════════════════════════════════════════════════════════════
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
            log.warn("[AlphaPay][Webhook] REJECTED — HMAC mismatch (wrong secret key?)");
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

            var rawUserId = extractUserIdFromReference(ref);
            if (rawUserId == null) {
                log.error("[AlphaPay][Webhook] UNRESOLVABLE userId — ref='{}'", ref);
                return ResponseEntity.ok("OK-NO-USER");
            }

            var rawAmount = data.get("amount");
            if (rawAmount == null) {
                log.error("[AlphaPay][Webhook] Missing amount — ref='{}'", ref);
                return ResponseEntity.status(400).body("Missing amount");
            }

            // Amount arrives in GHS major units already — no /100 division needed.
            BigDecimal amount;
            try {
                amount = new BigDecimal(rawAmount.toString());
            } catch (NumberFormatException e) {
                log.error("[AlphaPay][Webhook] Unparseable amount='{}' — ref='{}'", rawAmount, ref);
                return ResponseEntity.status(400).body("Invalid amount");
            }

            UUID userId;
            try {
                userId = UUID.fromString(rawUserId);
            } catch (IllegalArgumentException e) {
                log.error("[AlphaPay][Webhook] Invalid userId='{}' — ref='{}'", rawUserId, ref);
                return ResponseEntity.ok("OK-BAD-UUID");
            }

            log.info("[AlphaPay][Webhook] Crediting — userId='{}' ref='{}' amountGHS={}",
                    userId, ref, amount);

            handleDeposit(userId, ref, amount);

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
            log.info("[AlphaPay][handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'", amount, userId, ref);

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

    // ─── Reference / user correlation ──────────────────────────────────────────

    /**
     * AlphaPay's payment payload (per their published docs) has no metadata
     * field, so the userId can't be echoed back the way Paystack does it.
     * Instead we embed it directly in the reference we send at initialize
     * time: "DEP-<userId>-<random>". This is parsed back out on webhook
     * receipt. If AlphaPay later adds metadata support, prefer that over
     * parsing the reference.
     */
    private String buildReference(UUID userId) {
        return "DEP-" + userId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String extractUserIdFromReference(String ref) {
        // Expected shape: DEP-<uuid>-<suffix>
        var parts = ref.split("-", 2);
        if (parts.length < 2 || !"DEP".equals(parts[0])) {
            log.error("[extractUserId] reference does not match DEP-<userId>-<suffix> pattern — ref='{}'", ref);
            return null;
        }
        // UUID itself contains hyphens, so re-join everything except the trailing
        // random suffix we appended (last 9 chars: "-XXXXXXXX").
        var remainder = parts[1];
        if (remainder.length() <= 9) {
            log.error("[extractUserId] reference too short to contain userId — ref='{}'", ref);
            return null;
        }
        return remainder.substring(0, remainder.length() - 9);
    }

    // ─── AlphaPay API calls ─────────────────────────────────────────────────────

    private Map<String, Object> alphaPayInitialize(BigDecimal amountGhs, String reference, String phoneOrNull) {
        var body = new java.util.HashMap<String, Object>();
        body.put("amount", amountGhs);       // GHS major units — do NOT convert to minor units
        body.put("currency", "GHS");
        body.put("reference", reference);
        body.put("domain", whitelistedDomain);
        if (phoneOrNull != null) body.put("phone", phoneOrNull);

        return postToAlphaPay("/payments/initialize", body, "alphaPayInitialize");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> alphaPayVerify(String reference) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payments/verify/" + reference)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[alphaPayVerify] HTTP error — status={} body='{}' ref='{}'",
                            r.statusCode(), respBody, reference);
                    return new RuntimeException("AlphaPay returned " + r.statusCode() + ": " + respBody);
                }))
                .bodyToMono(Map.class)
                .timeout(alphaPayTimeout)
                .retryWhen(Retry.max(alphaPayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("AlphaPay is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("AlphaPay returned empty response.");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postToAlphaPay(String path, Map<String, Object> body, String tag) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + path)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] HTTP error — path='{}' status={} body='{}'",
                            tag, path, r.statusCode(), respBody);
                    return new RuntimeException("AlphaPay returned " + r.statusCode() + ": " + respBody);
                }))
                .bodyToMono(Map.class)
                .timeout(alphaPayTimeout)
                .retryWhen(Retry.max(alphaPayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("AlphaPay is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("AlphaPay returned empty response.");

        // AlphaPay's documented error shape is { "error": { "type", "message" } }
        // rather than Paystack's { "status": false, "message" }.
        if (result.get("error") != null) {
            @SuppressWarnings("unchecked")
            var error = (Map<String, Object>) result.get("error");
            var msg = error.getOrDefault("message", "AlphaPay declined the request").toString();
            log.error("[{}] error — path='{}' msg='{}'", tag, path, msg);
            throw new RuntimeException("AlphaPay error: " + msg);
        }

        return result;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

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
        if (digits.startsWith("+233"))                              digits = "0" + digits.substring(4);
        else if (digits.startsWith("233") && digits.length() == 12) digits = "0" + digits.substring(3);
        if (!digits.matches("^0\\d{9}$"))
            throw ApiException.badRequest("Invalid Ghana phone. Use 0XXXXXXXXX or +233XXXXXXXXX.");
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
                        computed.substring(0, 8), signature.substring(0, Math.min(8, signature.length())));
            return matches;
        } catch (Exception e) {
            log.error("[verifySignature] HMAC error", e);
            return false;
        }
    }
}
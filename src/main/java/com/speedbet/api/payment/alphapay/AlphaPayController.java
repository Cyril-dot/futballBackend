package com.speedbet.api.payment.alphapay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AlphaPay (https://alphapay.edibytes.online/docs) mobile-money deposit integration.
 *
 * Flow:
 *  1. POST /api/wallet/deposit/alphapay/init        -> returns checkout_url (redirect the customer there)
 *  2. (optional) POST .../submit-otp                -> only if you build your own OTP form
 *  3. Customer approves on phone; AlphaPay POSTs webhook to /api/webhooks/alphapay
 *  4. GET  /api/wallet/deposit/alphapay/verify/{ref} -> frontend poll / fallback; also credits (idempotent)
 *
 * Security model:
 *  - Webhook identity = HMAC-SHA256 over raw body, keyed with secret key (X-AlphaPay-Signature).
 *  - Even after the signature passes, we NEVER trust the payload: we call AlphaPay's verify API
 *    and credit only the amount/status AlphaPay returns server-to-server.
 *  - userId is recovered from our own reference format and, on the verify endpoint, must match
 *    the authenticated user, so one user can't trigger/inspect another user's deposit.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AlphaPayController {

    // DEP_<32 hex userId without dashes>_<12 hex random>
    private static final Pattern REF_PATTERN = Pattern.compile("^DEP_([0-9a-f]{32})_[0-9a-f]{12}$");

    private static final Set<String> MTN_GH_PREFIXES = Set.of("024", "025", "053", "054", "055", "059");
    private static final Set<String> ATL_GH_PREFIXES = Set.of("026", "027", "056", "057");
    private static final Set<String> VOD_GH_PREFIXES = Set.of("020", "050");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.alphapay.secret-key}")           private String     secretKey;
    @Value("${app.alphapay.base-url}")             private String     baseUrl;          // https://api.edibytes.online/api
    @Value("${app.alphapay.domain}")               private String     domain;           // luckysttake.site (must be whitelisted)
    @Value("${app.alphapay.callback-url}")         private String     callbackUrl;      // https://<your-api>/api/webhooks/alphapay
    @Value("${app.alphapay.return-url}")           private String     returnUrl;        // https://www.luckysttake.site/wallet/deposit/complete
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 1 — Initialize payment
    // Body: { "amount": 50, "phone": "0241234567" }   (phone optional)
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/wallet/deposit/alphapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> init(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[AlphaPay][init] START — userId='{}'", user.getId());

        var amount = extractValidAmount(req);

        String phone = null;
        var rawPhone = req.get("phone");
        if (rawPhone != null && !rawPhone.toString().isBlank()) {
            phone = normalizeGhanaPhone(rawPhone.toString().trim());
            warnIfUnknownPrefix(phone);
        }

        var reference = buildReference(user.getId());

        var body = new LinkedHashMap<String, Object>();
        body.put("amount",       amount);              // BigDecimal, GHS, 2dp — NOT pesewas
        body.put("currency",     "GHS");
        body.put("reference",    reference);
        body.put("domain",       domain);
        body.put("callback_url", callbackUrl);
        if (phone != null) body.put("phone_number", phone);

        log.info("[AlphaPay][init] Calling initialize — userId='{}' ref='{}' amountGHS={} phone='{}'",
                user.getId(), reference, amount, phone == null ? "-" : maskPhone(phone));

        var response = callAlphaPay(HttpMethod.POST, "/payments/initialize/", body, true, "init");

        log.info("[AlphaPay][init] DONE — userId='{}' ref='{}' status='{}' hasCheckoutUrl={}",
                user.getId(), reference, response.get("status"), response.get("checkout_url") != null);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 2 (optional) — Submit OTP from YOUR OWN form.
    // Not needed if you redirect to checkout_url (hosted page does this itself).
    // Body: { "reference": "DEP_...", "code": "1234" }
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/wallet/deposit/alphapay/submit-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var reference = requireNonBlank(req, "reference");
        var code      = requireNonBlank(req, "code");
        assertReferenceBelongsTo(user, reference);

        log.info("[AlphaPay][submitOtp] userId='{}' ref='{}'", user.getId(), reference);

        // verify-otp is unauthenticated on AlphaPay's side (scoped by reference)
        var response = callAlphaPay(HttpMethod.POST, "/payments/" + reference + "/verify-otp/",
                Map.of("code", code), false, "submitOtp");

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Verify — frontend polling + fallback (webhooks have NO retries on AlphaPay).
    // Credits the wallet if AlphaPay says success. Safe to call repeatedly:
    // walletService.credit() throws 409 on duplicate reference, which we swallow.
    // ═══════════════════════════════════════════════════════════════════════════
    @GetMapping("/api/wallet/deposit/alphapay/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        assertReferenceBelongsTo(user, reference);
        log.info("[AlphaPay][verify] userId='{}' ref='{}'", user.getId(), reference);

        var result = alphaPayVerify(reference);
        var status = String.valueOf(result.get("status"));

        if ("success".equals(status)) {
            creditFromVerified(user.getId(), reference, result);
        }

        log.info("[AlphaPay][verify] DONE — ref='{}' status='{}'", reference, status);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/alphapay   (covered by POST /api/webhooks/** permitAll)
    // Header: X-AlphaPay-Signature = hex(HMAC_SHA256(rawBody, secretKey))
    // Payload: { "event": "payment.succeeded", "data": { "reference": "...", "amount": "50.00", ... } }
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/webhooks/alphapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AlphaPay-Signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[AlphaPay][Webhook] HIT — remote='{}'", request.getRemoteAddr());

        // 1. Raw body (before any parsing)
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[AlphaPay][Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        // 2. Signature
        if (signature == null || signature.isBlank()) {
            log.warn("[AlphaPay][Webhook] REJECTED — missing X-AlphaPay-Signature");
            return ResponseEntity.status(400).body("Missing signature");
        }
        if (!verifySignature(rawBody, signature.trim())) {
            log.warn("[AlphaPay][Webhook] REJECTED — HMAC mismatch (wrong secret key?)");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        // 3. Parse + dispatch
        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = String.valueOf(event.get("event"));
            if (!"payment.succeeded".equals(eventType)) {
                log.info("[AlphaPay][Webhook] Ignored event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null || data.get("reference") == null || data.get("reference").toString().isBlank()) {
                log.error("[AlphaPay][Webhook] payment.succeeded without data/reference");
                return ResponseEntity.status(400).body("Missing reference");
            }

            var ref    = data.get("reference").toString().trim();
            var userId = parseUserIdFromReference(ref);
            if (userId == null) {
                // Not one of ours (or malformed). 200 so it isn't treated as a delivery failure.
                log.error("[AlphaPay][Webhook] UNRESOLVABLE userId from ref='{}'", ref);
                return ResponseEntity.ok("OK-NO-USER");
            }

            // Never credit from the webhook body — confirm with AlphaPay server-to-server.
            var verified = alphaPayVerify(ref);
            var status   = String.valueOf(verified.get("status"));
            if (!"success".equals(status)) {
                log.warn("[AlphaPay][Webhook] Verify says status='{}' for ref='{}' — NOT crediting", status, ref);
                return ResponseEntity.ok("OK-NOT-SUCCESS");
            }

            creditFromVerified(userId, ref, verified);

        } catch (ApiException e) {
            log.error("[AlphaPay][Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[AlphaPay][Webhook] Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[AlphaPay][Webhook] COMPLETE — 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Browser return — AlphaPay also redirects the customer's browser to callback_url (GET).
    // Bounce them to the frontend, which then polls /verify/{ref}.
    // Needs: .requestMatchers(HttpMethod.GET, "/api/webhooks/alphapay").permitAll()
    // ═══════════════════════════════════════════════════════════════════════════
    @GetMapping("/api/webhooks/alphapay")
    public ResponseEntity<Void> browserReturn(@RequestParam(value = "reference", required = false) String reference) {
        var target = returnUrl;
        if (reference != null && REF_PATTERN.matcher(reference).matches()) {
            target = returnUrl + (returnUrl.contains("?") ? "&" : "?") + "reference=" + reference;
        }
        return ResponseEntity.status(302).location(URI.create(target)).build();
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    private void creditFromVerified(UUID userId, String ref, Map<String, Object> verified) {
        var currency = String.valueOf(verified.get("currency"));
        if (!"GHS".equalsIgnoreCase(currency)) {
            log.error("[AlphaPay][credit] Unexpected currency='{}' ref='{}' — NOT crediting", currency, ref);
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(verified.get("amount"))).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.error("[AlphaPay][credit] Unparseable amount='{}' ref='{}'", verified.get("amount"), ref);
            throw ApiException.badRequest("Invalid amount from AlphaPay.");
        }
        if (amount.signum() <= 0) {
            log.error("[AlphaPay][credit] Non-positive amount={} ref='{}'", amount, ref);
            return;
        }

        var channel = verified.get("channel") != null ? verified.get("channel").toString() : "mobile_money";
        handleDeposit(userId, ref, amount, channel);
    }

    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String channel) {
        log.info("[AlphaPay][handleDeposit] START — userId='{}' amountGHS={} ref='{}'", userId, amount, ref);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "alphapay", "channel", channel, "reference", ref));
            log.info("[AlphaPay][handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'", amount, userId, ref);

        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.info("[AlphaPay][handleDeposit] Duplicate ref='{}' — already credited, skipping", ref);
                return;
            }
            log.error("[AlphaPay][handleDeposit] credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back the deposit
            log.error("[AlphaPay][handleDeposit] Commission FAILED (non-blocking) — userId='{}' — {}",
                    userId, ex.getMessage(), ex);
        }
    }

    // ─── AlphaPay API calls ────────────────────────────────────────────────────

    private Map<String, Object> alphaPayVerify(String reference) {
        if (!REF_PATTERN.matcher(reference).matches())
            throw ApiException.badRequest("Invalid reference.");
        return callAlphaPay(HttpMethod.GET, "/payments/verify/" + reference + "/", null, true, "verify");
    }

    private static class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("AlphaPay returned " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callAlphaPay(HttpMethod method, String path, Object body,
                                             boolean auth, String tag) {
        var spec = webClientBuilder.build()
                .method(method)
                .uri(baseUrl + path)
                .accept(MediaType.APPLICATION_JSON);

        if (auth) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey);
        var withBody = body != null
                ? spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                : spec;

        var mono = withBody.retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> {
                            log.error("[AlphaPay][{}] HTTP error — path='{}' status={} body='{}'",
                                    tag, path, r.statusCode().value(), b);
                            return (Throwable) new UpstreamException(r.statusCode().value(), b);
                        }))
                .bodyToMono(Map.class)
                .timeout(TIMEOUT);

        // Retry only idempotent GETs, and only on network/timeout/5xx. Never retry POSTs.
        if (method == HttpMethod.GET) {
            mono = mono.retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                    .filter(ex -> ex instanceof TimeoutException
                            || ex instanceof WebClientRequestException
                            || (ex instanceof UpstreamException u && u.status >= 500))
                    .onRetryExhaustedThrow((s, sig) -> sig.failure()));
        }

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) mono.block();
        } catch (UpstreamException e) {
            if (e.status == 400) throw ApiException.badRequest(extractErrorMessage(e.body));
            throw new RuntimeException("AlphaPay is currently unavailable. Please try again.", e);
        } catch (Exception e) {
            log.error("[AlphaPay][{}] call failed — {}", tag, e.getMessage(), e);
            throw new RuntimeException("AlphaPay is currently unavailable. Please try again.", e);
        }

        if (result == null) throw new RuntimeException("AlphaPay returned empty response.");
        return result;
    }

    /** AlphaPay errors look like { "error": { "type": "...", "message": "..." } } */
    @SuppressWarnings("unchecked")
    private String extractErrorMessage(String rawBody) {
        try {
            var parsed = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
            if (parsed.get("error") instanceof Map<?, ?> err && err.get("message") != null)
                return err.get("message").toString();
        } catch (Exception ignored) { }
        return "Payment request was rejected.";
    }

    // ─── Reference helpers ─────────────────────────────────────────────────────

    private String buildReference(UUID userId) {
        var rand = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "DEP_" + userId.toString().replace("-", "") + "_" + rand;
    }

    private UUID parseUserIdFromReference(String ref) {
        Matcher m = REF_PATTERN.matcher(ref);
        if (!m.matches()) return null;
        var h = m.group(1);
        try {
            return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
                    + "-" + h.substring(16, 20) + "-" + h.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void assertReferenceBelongsTo(User user, String reference) {
        var owner = parseUserIdFromReference(reference);
        if (owner == null || !owner.equals(user.getId()))
            throw ApiException.badRequest("Unknown reference.");
    }

    // ─── Validation helpers ────────────────────────────────────────────────────

    private BigDecimal extractValidAmount(Map<String, Object> req) {
        var raw = req.get("amount");
        if (raw == null) throw ApiException.badRequest("amount is required.");
        BigDecimal amount;
        try { amount = new BigDecimal(raw.toString()); }
        catch (NumberFormatException e) { throw ApiException.badRequest("amount must be a valid number."); }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        return amount;
    }

    private String requireNonBlank(Map<String, Object> req, String field) {
        var raw = req.get(field);
        if (raw == null || raw.toString().isBlank())
            throw ApiException.badRequest(field + " is required.");
        return raw.toString().trim();
    }

    private String normalizeGhanaPhone(String raw) {
        var digits = raw.replaceAll("[\\s\\-]", "");
        if (digits.startsWith("+233"))                              digits = "0" + digits.substring(4);
        else if (digits.startsWith("233") && digits.length() == 12) digits = "0" + digits.substring(3);
        if (!digits.matches("^0\\d{9}$"))
            throw ApiException.badRequest("Invalid Ghana phone. Use 0XXXXXXXXX or +233XXXXXXXXX.");
        return digits;
    }

    private void warnIfUnknownPrefix(String phone) {
        var p = phone.substring(0, 3);
        if (!MTN_GH_PREFIXES.contains(p) && !ATL_GH_PREFIXES.contains(p) && !VOD_GH_PREFIXES.contains(p))
            log.warn("[AlphaPay] phone prefix='{}' not in known Ghana MoMo ranges", p);
    }

    // ─── Signature ─────────────────────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            // constant-time compare
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[AlphaPay][verifySignature] HMAC error", e);
            return false;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}

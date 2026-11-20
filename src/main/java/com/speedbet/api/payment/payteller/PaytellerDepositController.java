package com.speedbet.api.payment.payteller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.superadmin.PaytellerSettingsService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payteller (https://paytelller.com/docs.html) hosted-checkout deposit integration.
 *
 * Flow:
 *  1. POST /api/wallet/deposit/payteller/init      -> creates a Payteller payment, returns checkout_url
 *  2. Customer pays on Payteller's hosted checkout page, then returns to return_url (?payteller_ref=MSP_...)
 *  3. Payteller POSTs webhook to /api/webhooks/payteller (HMAC-signed, at-least-once)
 *  4. GET  /api/wallet/deposit/payteller/verify/{ref} -> frontend poll / fallback; also credits (idempotent)
 *
 * There is NO in-page OTP step: the hosted checkout page handles payment.
 * Payteller's default charge is 10% of the deposit (merchant keeps 90%) —
 * that is Payteller's cut, reported in merchant_net; we never do fee math
 * here and we always credit the full requested amount, as with AlphaPay.
 *
 * Security model:
 *  - Webhook identity = HMAC-SHA256 over "<timestamp>.<raw body>", keyed with
 *    the webhook signing secret (X-Payteller-Signature: t=...,v1=...), 300s tolerance.
 *  - Even after the signature passes, we NEVER trust the payload: we call
 *    Payteller's status API and credit only the amount/status Payteller
 *    returns server-to-server.
 *  - userId is recovered from our own reference format and, on the verify
 *    endpoint, must match the authenticated user, so one user can't
 *    trigger/inspect another user's deposit.
 *  - The rail is OFF by default: a super admin must turn it on in the panel
 *    (enabled), and credentials must resolve, before any payment is created.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaytellerDepositController {

    // MSP_<32 hex userId without dashes>_<12 hex random>
    private static final Pattern REF_PATTERN = Pattern.compile("^MSP_([0-9a-f]{32})_[0-9a-f]{12}$");

    // X-Payteller-Signature: t=<unix seconds>,v1=<64 hex chars>
    private static final Pattern SIGNATURE_PATTERN =
            Pattern.compile("^t=(\\d+),v1=([a-fA-F0-9]{64})$");

    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;
    private final PaytellerSettingsService paytellerSettings;

    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 1 — Create payment
    // Body: { "amount": 100 }   (GHS; Payteller takes a decimal string "100.00")
    // Returns: { checkoutUrl, reference, paytellerId, status }
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/wallet/deposit/payteller/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> init(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Payteller][init] START — userId='{}'", user.getId());

        var amount = extractValidAmount(req);

        // Fail closed before any Payteller call: the rail must be switched on
        // in the panel, and all three credentials must resolve.
        var cfg = paytellerSettings.resolved();
        if (!cfg.enabled()) {
            throw ApiException.badRequest("Payteller is not available right now.");
        }
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()
                || cfg.merchantId() == null || cfg.merchantId().isBlank()
                || cfg.username() == null || cfg.username().isBlank()) {
            throw ApiException.badRequest("Payteller is not available right now.");
        }

        // Payteller requires a customer email for the receipt.
        var email = user.getEmail();
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("Add an email to your account to use Payteller.");
        }

        var reference = buildReference(user.getId());
        var returnUrl = cfg.returnUrl() + (cfg.returnUrl().contains("?") ? "&" : "?")
                + "payteller_ref=" + reference;

        var body = new LinkedHashMap<String, Object>();
        body.put("amount",         amount.toPlainString());  // decimal STRING, 2dp — NOT a JSON number
        body.put("currency",       "GHS");
        body.put("reference",      reference);
        body.put("customer_email", email.trim());
        body.put("description",    "MSport wallet deposit");
        body.put("return_url",     returnUrl);

        log.info("[Payteller][init] Calling create payment — userId='{}' ref='{}' amountGHS={}",
                user.getId(), reference, amount);

        var response = callPayteller(HttpMethod.POST, "/payments", body, "init");

        var checkoutUrl = response.get("checkout_url");
        if (checkoutUrl == null || checkoutUrl.toString().isBlank()) {
            log.error("[Payteller][init] No checkout_url in response — ref='{}' body='{}'", reference, response);
            throw new RuntimeException("Payteller is currently unavailable. Please try again.");
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("checkoutUrl", checkoutUrl.toString());
        out.put("reference",   reference);
        out.put("paytellerId", response.get("id"));
        out.put("status",      response.get("status"));

        log.info("[Payteller][init] DONE — userId='{}' ref='{}' status='{}'",
                user.getId(), reference, response.get("status"));

        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Verify — frontend polling + fallback (webhooks are at-least-once, but a
    // missed or delayed webhook must not strand a paid deposit).
    // Credits the wallet if Payteller says successful. Safe to call repeatedly:
    // walletService.credit() throws 409 on duplicate reference, which we swallow.
    // ═══════════════════════════════════════════════════════════════════════════
    @GetMapping("/api/wallet/deposit/payteller/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        assertReferenceBelongsTo(user, reference);
        log.info("[Payteller][verify] userId='{}' ref='{}'", user.getId(), reference);

        var result = paytellerStatus(reference);
        var status = String.valueOf(result.get("status"));

        if ("successful".equals(status)) {
            creditFromVerified(user.getId(), reference, result);
        }

        log.info("[Payteller][verify] DONE — ref='{}' status='{}'", reference, status);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/payteller   (covered by POST /api/webhooks/** permitAll)
    // Headers: X-Payteller-Event, X-Payteller-Signature = t=<ts>,v1=HMAC_SHA256("<ts>.<raw body>", webhookSecret)
    // Payload: { "id": "...", "type": "payment.successful" | "payment.failed",
    //            "data": { "merchant_reference": "MSP_...", "amount": "100.00", ... } }
    // Delivery is at-least-once (8 attempts) — crediting is idempotent on the
    // reference, so a redelivered event can never double-credit.
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/webhooks/payteller")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-Payteller-Signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[Payteller][Webhook] HIT — remote='{}'", request.getRemoteAddr());

        // 1. Raw body (before any parsing)
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[Payteller][Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        // 2. Signature
        if (signature == null || signature.isBlank()) {
            log.warn("[Payteller][Webhook] REJECTED — missing X-Payteller-Signature");
            return ResponseEntity.status(401).body("Missing signature");
        }
        if (!verifySignature(rawBody, signature.trim())) {
            log.warn("[Payteller][Webhook] REJECTED — HMAC mismatch / stale timestamp (wrong webhook secret?)");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // 3. Parse + dispatch
        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = String.valueOf(event.get("type"));
            if ("payment.failed".equals(eventType)) {
                log.info("[Payteller][Webhook] payment.failed — nothing to credit");
                return ResponseEntity.ok("OK-FAILED");
            }
            if (!"payment.successful".equals(eventType)) {
                log.info("[Payteller][Webhook] Ignored event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            String ref = null;
            if (data != null) {
                if (data.get("merchant_reference") != null
                        && !data.get("merchant_reference").toString().isBlank()) {
                    ref = data.get("merchant_reference").toString().trim();
                } else if (data.get("reference") != null && !data.get("reference").toString().isBlank()) {
                    ref = data.get("reference").toString().trim();
                }
            }
            if (ref == null) {
                log.error("[Payteller][Webhook] payment.successful without data/merchant_reference");
                return ResponseEntity.status(400).body("Missing reference");
            }

            var userId = parseUserIdFromReference(ref);
            if (userId == null) {
                // Not one of ours (or malformed). 200 so it isn't treated as a delivery failure.
                log.error("[Payteller][Webhook] UNRESOLVABLE userId from ref='{}'", ref);
                return ResponseEntity.ok("OK-NO-USER");
            }

            // Never credit from the webhook body — confirm with Payteller server-to-server.
            var verified = paytellerStatus(ref);
            var status   = String.valueOf(verified.get("status"));
            if (!"successful".equals(status)) {
                log.warn("[Payteller][Webhook] Status API says status='{}' for ref='{}' — NOT crediting", status, ref);
                return ResponseEntity.ok("OK-NOT-SUCCESS");
            }

            creditFromVerified(userId, ref, verified);

        } catch (ApiException e) {
            log.error("[Payteller][Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Payteller][Webhook] Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[Payteller][Webhook] COMPLETE — 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    private void creditFromVerified(UUID userId, String ref, Map<String, Object> verified) {
        var currency = String.valueOf(verified.get("currency"));
        if (!"GHS".equalsIgnoreCase(currency)) {
            log.error("[Payteller][credit] Unexpected currency='{}' ref='{}' — NOT crediting", currency, ref);
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(verified.get("amount"))).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.error("[Payteller][credit] Unparseable amount='{}' ref='{}'", verified.get("amount"), ref);
            throw ApiException.badRequest("Invalid amount from Payteller.");
        }
        if (amount.signum() <= 0) {
            log.error("[Payteller][credit] Non-positive amount={} ref='{}'", amount, ref);
            return;
        }

        // Credit the FULL requested amount. Payteller's 10% charge is taken
        // from the merchant's share (merchant_net) — never deducted here.
        handleDeposit(userId, ref, amount, "payteller");
    }

    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String channel) {
        log.info("[Payteller][handleDeposit] START — userId='{}' amountGHS={} ref='{}'", userId, amount, ref);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "payteller", "channel", channel, "reference", ref));
            log.info("[Payteller][handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'", amount, userId, ref);

        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.info("[Payteller][handleDeposit] Duplicate ref='{}' — already credited, skipping", ref);
                // 409 one-shot fix: the wallet was already credited on a
                // previous attempt — attribute now (idempotent per ref) so
                // the commission is not lost if the first attempt's
                // attribution failed or was skipped.
                try {
                    referralService.attributeCommission(userId, amount, ref);
                } catch (Exception attributionEx) {
                    log.error("[Payteller][handleDeposit] Commission attribution on duplicate FAILED (non-blocking) — userId='{}' — {}",
                            userId, attributionEx.getMessage(), attributionEx);
                }
                return;
            }
            log.error("[Payteller][handleDeposit] credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount, ref);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back the deposit
            log.error("[Payteller][handleDeposit] Commission FAILED (non-blocking) — userId='{}' — {}",
                    userId, ex.getMessage(), ex);
        }
    }

    // ─── Payteller API calls ───────────────────────────────────────────────────

    private Map<String, Object> paytellerStatus(String reference) {
        if (!REF_PATTERN.matcher(reference).matches())
            throw ApiException.badRequest("Invalid reference.");
        return callPayteller(HttpMethod.GET, "/payments/" + reference, null, "status");
    }

    private static class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("Payteller returned " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callPayteller(HttpMethod method, String path, Object body, String tag) {
        // Resolve fresh on every call: a key saved in the panel minutes
        // ago applies immediately, with no restart. Never call Payteller
        // with missing credentials — fail closed instead.
        var cfg = paytellerSettings.resolved();
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()
                || cfg.merchantId() == null || cfg.merchantId().isBlank()
                || cfg.username() == null || cfg.username().isBlank()) {
            throw ApiException.badRequest("Payteller is not available right now.");
        }
        var spec = webClientBuilder.build()
                .method(method)
                .uri(cfg.baseUrl() + path)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                .header("X-Payteller-Merchant-ID", cfg.merchantId())
                .header("X-Payteller-Username", cfg.username());
        var withBody = body != null
                ? spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                : spec;

        var mono = withBody.retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> {
                            log.error("[Payteller][{}] HTTP error — path='{}' status={} body='{}'",
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
            throw new RuntimeException("Payteller is currently unavailable. Please try again.", e);
        } catch (Exception e) {
            log.error("[Payteller][{}] call failed — {}", tag, e.getMessage(), e);
            throw new RuntimeException("Payteller is currently unavailable. Please try again.", e);
        }

        if (result == null) throw new RuntimeException("Payteller returned empty response.");
        return result;
    }

    /** Payteller errors look like { "error": "human-readable explanation" } (a plain string). */
    @SuppressWarnings("unchecked")
    private String extractErrorMessage(String rawBody) {
        try {
            var parsed = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
            if (parsed.get("error") instanceof String errStr && !errStr.isBlank()) return errStr;
            if (parsed.get("error") instanceof Map<?, ?> err && err.get("message") != null)
                return err.get("message").toString();
        } catch (Exception ignored) { }
        return "Payment request was rejected.";
    }

    // ─── Reference helpers ─────────────────────────────────────────────────────

    private String buildReference(UUID userId) {
        var rand = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "MSP_" + userId.toString().replace("-", "") + "_" + rand;
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

    // ─── Signature ─────────────────────────────────────────────────────────────

    /**
     * Verifies X-Payteller-Signature: t=&lt;unix seconds&gt;,v1=&lt;hex HMAC-SHA256&gt;
     * over "&lt;timestamp&gt;.&lt;raw body&gt;", keyed with the resolved webhook
     * signing secret. 300s tolerance, timing-safe compare. An empty secret
     * can never validate a signature — fail closed.
     */
    private boolean verifySignature(byte[] rawBody, String signature) {
        Matcher m = SIGNATURE_PATTERN.matcher(signature);
        if (!m.matches()) return false;
        long timestamp;
        try {
            timestamp = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return false;
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSeconds - timestamp) > SIGNATURE_TOLERANCE_SECONDS) return false;

        String secret = paytellerSettings.resolved().webhookSecret();
        if (secret == null || secret.isBlank()) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((m.group(1) + ".").getBytes(StandardCharsets.UTF_8));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            // constant-time compare
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    m.group(2).toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[Payteller][verifySignature] HMAC error", e);
            return false;
        }
    }
}

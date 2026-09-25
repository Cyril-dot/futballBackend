package com.speedbet.api.payment.webrabbit;

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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WebRabbitPaymentController {

    // Web Rabbit's documented MoMo network codes (docs/collect-momo §3.2).
    // Legacy codes VDF/ATL/TGO are mapped by Web Rabbit itself, not by us.
    private static final Set<String> VALID_NETWORKS = Set.of("MTN", "TELECEL", "AT", "GMONEY");

    private static final Set<String> MTN_GH_PREFIXES     = Set.of("024", "025", "053", "054", "055", "059");
    private static final Set<String> AT_GH_PREFIXES      = Set.of("026", "027", "056", "057");
    private static final Set<String> TELECEL_GH_PREFIXES = Set.of("020", "050");

    private final Duration webRabbitTimeout       = Duration.ofSeconds(10);
    private final long     webRabbitRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    // FIX #1 (root cause of "no MoMo prompt received"):
    // The old default fallback here was "https://api.example.com/v1" — a
    // placeholder domain that resolves to nothing. If APP_WEBRABBIT_BASE_URL
    // was ever unset/unloaded in the actual runtime environment, every call
    // silently went to api.example.com, failed at the network layer, and got
    // swallowed by onErrorMap into a generic "unavailable" error — no prompt
    // ever left the server. The fallback now points at the real, documented
    // Web Rabbit host (guide §"Base URL"), so a missing env var still works
    // correctly instead of failing silently. Still: explicitly set
    // APP_WEBRABBIT_BASE_URL=https://api.webrabbitmedia.com/v1 in your real
    // deployment env — don't rely on this fallback long-term.
    @Value("${app.webrabbit.secret-key}")
    private String secretKey;

    @Value("${app.webrabbit.base-url:https://api.webrabbitmedia.com/v1}")
    private String baseUrl;

    @Value("${app.webrabbit.webhook-secret}")
    private String webhookSecret;

    @Value("${app.platform.min-deposit-amount:1}")
    private BigDecimal minDeposit;

    @Value("${app.platform.name}")
    private String appName; // sent as X-Webrabbitmedia-Title

    @Value("${app.platform.site-url}")
    private String siteUrl; // sent as HTTP-Referer

    // FIX #3: minimal in-memory pending-deposit store so the webhook can
    // resolve transaction_id -> userId (Web Rabbit's /collect/momo request
    // has no metadata field to carry this through). Swap for a real
    // repository/table backed by your DB before production — this map is
    // lost on restart and won't work across multiple app instances.
    private final Map<String, UUID> pendingDeposits = new ConcurrentHashMap<>();

    // ─── MoMo: Step 1 — initiate charge ────────────────────────────────────────
    @PostMapping("/api/wallet/deposit/webrabbit-momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initMomoDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[WR-MoMo][init] START — userId='{}' baseUrl='{}'", user.getId(), baseUrl);

        var amount   = extractValidAmount(req, user.getId());
        var rawPhone = req.get("phone") == null ? "" : String.valueOf(req.get("phone")).trim();
        if (rawPhone.isBlank() || rawPhone.equals("null"))
            throw ApiException.badRequest("Phone number is required.");

        var phone = normalizeGhanaPhone(rawPhone);

        var rawNetwork = req.get("network");
        if (rawNetwork == null)
            throw ApiException.badRequest("network is required. Use one of: MTN, TELECEL, AT, GMONEY.");

        var network = rawNetwork.toString().trim().toUpperCase();
        if (!VALID_NETWORKS.contains(network))
            throw ApiException.badRequest("Unsupported network '" + network + "'.");

        validateNetworkPrefix(phone, network);

        // Web Rabbit takes decimal GHS directly — no pesewa conversion needed.
        var idempotencyKey = "wr-momo-" + user.getId() + "-" + UUID.randomUUID();

        log.info("[WR-MoMo][init] Calling Web Rabbit POST /collect/momo — userId='{}' amountGHS={} phone='{}' network='{}' idempotencyKey='{}'",
                user.getId(), amount, maskPhone(phone), network, idempotencyKey);

        // FIX #4: "desc" was previously built with an em dash (—), which Web
        // Rabbit's validation rejects with reason_code=failed, reason=
        // "Reference should not contain any special characters." Plain ASCII
        // only here — ideally alphanumeric plus space/hyphen/underscore.
        var response = webRabbitChargeMomo(amount, phone, network,
                "Deposit - user " + user.getId(), user.getEmail(), idempotencyKey);

        // FIX #3 (part 1): remember which user this transaction_id belongs to,
        // so the webhook (or a reconciliation job) can resolve it later.
        var txId = response.get("transaction_id");
        if (txId != null) {
            pendingDeposits.put(txId.toString(), user.getId());
        } else {
            log.warn("[WR-MoMo][init] Web Rabbit response had no transaction_id — cannot register pending deposit. userId='{}'", user.getId());
        }

        log.info("[WR-MoMo][init] DONE — userId='{}' transactionId='{}' status='{}' reasonCode='{}'",
                user.getId(), response.get("transaction_id"), response.get("status"), response.get("reason_code"));

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── MoMo: verify fallback ──────────────────────────────────────────────────
    @GetMapping("/api/wallet/deposit/webrabbit-momo/verify/{transactionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyMomoCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String transactionId) {

        if (transactionId == null || transactionId.isBlank())
            throw ApiException.badRequest("transactionId is required.");

        log.info("[WR-MoMo][verify] userId='{}' transactionId='{}'", user.getId(), transactionId);
        var response = webRabbitGetTransaction(transactionId);

        log.info("[WR-MoMo][verify] DONE — transactionId='{}' status='{}' reasonCode='{}' settledAt='{}'",
                transactionId, response.get("status"), response.get("reason_code"), response.get("settled_at"));

        // Client polling is the documented, reliable path (guide §4.6) — if the
        // webhook is still misconfigured or hasn't fired yet, still credit here
        // once the transaction is genuinely approved, so deposits aren't stuck
        // solely on the webhook. handleDeposit() is idempotent (409-safe).
        var status     = String.valueOf(response.get("status"));
        var reasonCode = String.valueOf(response.get("reason_code"));
        if ("approved".equals(status) && "approved".equals(reasonCode)) {
            var userId = pendingDeposits.getOrDefault(transactionId, user.getId());
            var rawAmount = response.get("gross_amount");
            if (rawAmount != null) {
                try {
                    var amount = new BigDecimal(rawAmount.toString());
                    handleDeposit(userId, transactionId, amount, "mobile_money");
                } catch (NumberFormatException e) {
                    log.error("[WR-MoMo][verify] Unparseable gross_amount='{}' — transactionId='{}'", rawAmount, transactionId);
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/webrabbit-momo
    //
    // Must be covered by .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
    // in SecurityConfig, so Web Rabbit's server-to-server POST reaches this
    // method without a JWT. Identity is proven by an HMAC-SHA256 signature
    // over the raw body.
    //
    // ⚠ STILL UNCONFIRMED — CONFIRM AGAINST THE REAL WEBHOOKS DOC PAGE:
    //   The integration guide you supplied confirms webhooks are "signed
    //   HMAC-SHA256" but never names the signature header or the event name
    //   for a MoMo *collection* reaching a terminal state — only the
    //   *disbursement* events (payout.queued / payout.completed /
    //   payout.failed) are confirmed in writing. This is why, even after
    //   fixing the base URL, crediting should not depend solely on this
    //   webhook firing correctly — verifyMomoCharge() above now also credits
    //   on a successful poll, and handleDeposit() is idempotent, so whichever
    //   path fires first wins and the other is a safe no-op.
    //   Update WEBHOOK_SIGNATURE_HEADER and the event-name check below once
    //   you have the real Webhooks doc page or a reply from
    //   support@webrabbitmedia.com.
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String WEBHOOK_SIGNATURE_HEADER = "x-webrabbit-signature"; // TODO confirm exact header name with Web Rabbit support

    @PostMapping("/api/webhooks/webrabbit-momo")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = WEBHOOK_SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        log.info("[WR-Webhook] HIT — remote='{}'", request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[WR-Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        log.info("[WR-Webhook] Body length={} bytes", rawBody.length);

        if (signature == null || signature.isBlank()) {
            log.warn("[WR-Webhook] REJECTED — missing {} header (confirm real header name with Web Rabbit)", WEBHOOK_SIGNATURE_HEADER);
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("[WR-Webhook] REJECTED — HMAC mismatch (wrong webhook secret?)");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("[WR-Webhook] Signature OK");

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event") != null ? event.get("event").toString() : "unknown";
            log.info("[WR-Webhook] event='{}'", eventType);

            // Broadened match: accept any event name containing "approved" so
            // we're not solely dependent on guessing the exact string
            // ("collection.approved" vs whatever Web Rabbit actually sends).
            // This is a stopgap until the real event name is confirmed.
            if (!eventType.toLowerCase().contains("approved")) {
                log.info("[WR-Webhook] Ignored event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            if (data == null) {
                log.error("[WR-Webhook] event='{}' has null data", eventType);
                return ResponseEntity.status(400).body("Missing data");
            }

            var transactionId = data.get("transaction_id") != null ? data.get("transaction_id").toString() : "";
            var status         = data.get("status") != null ? data.get("status").toString() : "";
            var reasonCode     = data.get("reason_code") != null ? data.get("reason_code").toString() : "";

            log.info("[WR-Webhook] transactionId='{}' status='{}' reasonCode='{}'", transactionId, status, reasonCode);

            if (transactionId.isBlank()) {
                log.error("[WR-Webhook] Missing transaction_id in data");
                return ResponseEntity.status(400).body("Missing transaction_id");
            }

            // Per guide §4.4: status and reason_code are the fields to trust.
            // A mere "prompt_sent" / "pending" must NEVER credit.
            if (!"approved".equals(status) || !"approved".equals(reasonCode)) {
                log.info("[WR-Webhook] Not a terminal approval — status='{}' reasonCode='{}' transactionId='{}' — skipping credit",
                        status, reasonCode, transactionId);
                return ResponseEntity.ok("OK-NOT-APPROVED");
            }

            UUID userId = lookupUserIdByTransactionId(transactionId);
            if (userId == null) {
                log.error("[WR-Webhook] UNRESOLVABLE userId — transactionId='{}' — no local record found", transactionId);
                return ResponseEntity.ok("OK-NO-USER");
            }

            var rawAmount = data.get("gross_amount");
            if (rawAmount == null) {
                log.error("[WR-Webhook] Missing gross_amount — transactionId='{}'", transactionId);
                return ResponseEntity.status(400).body("Missing gross_amount");
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(rawAmount.toString());
            } catch (NumberFormatException e) {
                log.error("[WR-Webhook] Unparseable gross_amount='{}' — transactionId='{}'", rawAmount, transactionId);
                return ResponseEntity.status(400).body("Invalid gross_amount");
            }

            log.info("[WR-Webhook] Crediting — userId='{}' transactionId='{}' amountGHS={}",
                    userId, transactionId, amount);

            handleDeposit(userId, transactionId, amount, "mobile_money");

        } catch (ApiException e) {
            log.error("[WR-Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[WR-Webhook] Unexpected error — Web Rabbit will retry: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[WR-Webhook] COMPLETE — 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String channel) {
        log.info("[WR-handleDeposit] START — userId='{}' amountGHS={} ref='{}' channel='{}'",
                userId, amount, ref, channel);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "webrabbit", "channel", channel, "reference", ref));
            log.info("[WR-handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'", amount, userId, ref);

        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[WR-handleDeposit] Duplicate ref='{}' — already credited, skipping", ref);
                return;
            }
            log.error("[WR-handleDeposit] credit FAILED — userId='{}' ref='{}' — {}", userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("[WR-handleDeposit] Commission attributed — userId='{}'", userId);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back the deposit
            log.error("[WR-handleDeposit] Commission FAILED (non-blocking) — userId='{}' — {}", userId, ex.getMessage(), ex);
        }

        // Once credited, this pending-deposit record has served its purpose.
        pendingDeposits.remove(ref);

        log.info("[WR-handleDeposit] COMPLETE — userId='{}' ref='{}'", userId, ref);
    }

    /**
     * FIX #3: resolves which platform user a Web Rabbit transaction_id
     * belongs to, using the in-memory map populated at /init time. Replace
     * with a real repository-backed pending_deposits table before production
     * — this in-memory map does not survive a restart and does not work
     * across multiple app instances behind a load balancer.
     */
    private UUID lookupUserIdByTransactionId(String transactionId) {
        var userId = pendingDeposits.get(transactionId);
        if (userId == null) {
            log.warn("[WR-lookupUserIdByTransactionId] No pending deposit found — transactionId='{}'", transactionId);
        }
        return userId;
    }

    // ─── Web Rabbit API calls ───────────────────────────────────────────────────

    private Map<String, Object> webRabbitChargeMomo(BigDecimal amountGhs, String phone, String network,
                                                      String desc, String customerEmail,
                                                      String idempotencyKey) {
        var body = new java.util.HashMap<String, Object>();
        body.put("amount", amountGhs);
        body.put("subscriber_number", phone);
        body.put("network", network);
        var safeDesc = sanitizeForWebRabbit(desc, 100);
        if (safeDesc != null && !safeDesc.isBlank()) body.put("desc", safeDesc);
        if (customerEmail != null && !customerEmail.isBlank()) body.put("customer_email", customerEmail);

        return postToWebRabbit("/collect/momo", body, idempotencyKey, "webRabbitChargeMomo");
    }

    /**
     * FIX #4: Web Rabbit rejects "desc" (and likely Idempotency-Key) values
     * containing special characters, e.g. an em dash, with
     * reason_code=failed / reason="Reference should not contain any special
     * characters." Strip everything except letters, digits, spaces, hyphens
     * and underscores, and cap length. Applied defensively here rather than
     * trusting every caller to pass clean input.
     */
    private String sanitizeForWebRabbit(String value, int maxLen) {
        if (value == null) return null;
        var cleaned = value.replaceAll("[^A-Za-z0-9 _-]", "");
        if (cleaned.length() > maxLen) cleaned = cleaned.substring(0, maxLen);
        return cleaned.trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> webRabbitGetTransaction(String transactionId) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/transactions/" + transactionId)
                .header("Authorization", "Bearer " + secretKey)
                .header("HTTP-Referer", siteUrl)
                .header("X-Webrabbitmedia-Title", appName)
                .retrieve()
                .onStatus(status -> status.value() == 404, r -> {
                    log.warn("[webRabbitGetTransaction] 404 — transactionId='{}' not found for this key's mode", transactionId);
                    return r.bodyToMono(String.class)
                            .map(body -> new RuntimeException("Transaction not found: " + transactionId));
                })
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(body -> {
                    log.error("[webRabbitGetTransaction] HTTP error — transactionId='{}' status={} body='{}'",
                            transactionId, r.statusCode(), body);
                    return new RuntimeException("Web Rabbit returned " + r.statusCode() + ": " + body);
                }))
                .bodyToMono(Map.class)
                .timeout(webRabbitTimeout)
                .retryWhen(Retry.max(webRabbitRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("Web Rabbit is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("Web Rabbit returned empty response.");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postToWebRabbit(String path, Map<String, Object> body,
                                                 String idempotencyKey, String tag) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + path)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("HTTP-Referer", siteUrl)
                .header("X-Webrabbitmedia-Title", appName)
                .bodyValue(body)
                .retrieve()
                // 422 = account_not_found (name-verification failure) — surface distinctly
                .onStatus(status -> status.value() == 422, r -> r.bodyToMono(String.class).map(respBody -> {
                    log.warn("[{}] 422 account_not_found — path='{}' body='{}'", tag, path, respBody);
                    return ApiException.badRequest("We couldn't verify that mobile money account. Please check the number.");
                }))
                // 502 = upstream provider rejected the charge — safe to retry with same Idempotency-Key
                .onStatus(status -> status.value() == 502, r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] 502 upstream rejection — path='{}' body='{}'", tag, path, respBody);
                    return new RuntimeException("Payment provider declined the charge: " + respBody);
                }))
                // 403 = key/scope/attribution problem — surface distinctly so it's
                // not confused with a generic outage (guide §1.4, §2)
                .onStatus(status -> status.value() == 403, r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] 403 forbidden — path='{}' body='{}' (check key scope is read+write, and business/domain approval)", tag, path, respBody);
                    return new RuntimeException("Web Rabbit rejected the request (403): " + respBody);
                }))
                // 401 = bad/missing secret key
                .onStatus(status -> status.value() == 401, r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] 401 unauthorized — path='{}' body='{}' (check app.webrabbit.secret-key)", tag, path, respBody);
                    return new RuntimeException("Web Rabbit rejected the API key (401): " + respBody);
                }))
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] HTTP error — path='{}' status={} body='{}'",
                            tag, path, r.statusCode(), respBody);
                    return new RuntimeException("Web Rabbit returned " + r.statusCode() + ": " + respBody);
                }))
                .bodyToMono(Map.class)
                .timeout(webRabbitTimeout)
                .retryWhen(Retry.max(webRabbitRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("Web Rabbit is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("Web Rabbit returned empty response.");
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
        if (digits.startsWith("+233"))                          digits = "0" + digits.substring(4);
        else if (digits.startsWith("233") && digits.length() == 12) digits = "0" + digits.substring(3);
        if (!digits.matches("^0\\d{9}$"))
            throw ApiException.badRequest("Invalid Ghana phone. Use 0XXXXXXXXX or +233XXXXXXXXX.");
        return digits;
        // Web Rabbit's docs (§3.2) confirm subscriber_number accepts either
        // local (0XXXXXXXXX) or international (233XXXXXXXXX) format and
        // normalises it server-side.
    }

    private void validateNetworkPrefix(String phone, String network) {
        var prefix   = phone.substring(0, 3);
        var mismatch = switch (network) {
            case "MTN"     -> !MTN_GH_PREFIXES.contains(prefix);
            case "AT"      -> !AT_GH_PREFIXES.contains(prefix);
            case "TELECEL" -> !TELECEL_GH_PREFIXES.contains(prefix);
            case "GMONEY"  -> false; // no published prefix range for G-Money — skip the check
            default        -> false;
        };
        if (mismatch)
            log.warn("[validateNetworkPrefix] prefix='{}' may not match network='{}'", prefix, network);
    }

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            var matches  = computed.equals(signature);
            if (!matches)
                log.warn("[WR-verifySignature] HMAC mismatch — computed='{}...' received='{}...'",
                        computed.substring(0, 8), signature.substring(0, Math.min(8, signature.length())));
            return matches;
        } catch (Exception e) {
            log.error("[WR-verifySignature] HMAC error", e);
            return false;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}

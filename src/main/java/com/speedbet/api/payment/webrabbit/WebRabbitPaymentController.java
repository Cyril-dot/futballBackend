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
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WebRabbitPaymentController {

    // Web Rabbit's documented MoMo network codes (docs/collect-momo). Legacy
    // codes VDF/ATL/TGO are mapped by Web Rabbit itself, not by us, so we only
    // need to accept and forward the current ones.
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

    @Value("${app.webrabbit.secret-key}")             private String     secretKey;
    @Value("${app.webrabbit.base-url}")                private String     baseUrl; // e.g. https://api.webrabbitmedia.com/v1
    @Value("${app.webrabbit.webhook-secret}")          private String     webhookSecret; // HMAC-SHA256 signing secret — see note below
    @Value("${app.platform.min-deposit-amount:1}")     private BigDecimal minDeposit;
    @Value("${app.platform.name}")                     private String     appName;      // sent as X-Webrabbitmedia-Title
    @Value("${app.platform.site-url}")                 private String     siteUrl;      // sent as HTTP-Referer

    // ─── MoMo: Step 1 — initiate charge ────────────────────────────────────────
    //
    // Web Rabbit's MoMo flow is single-step server-side: POST /v1/collect/momo
    // sends the prompt to the customer's phone and returns 202 pending
    // immediately. There is no OTP-submit step (unlike Paystack) — the customer
    // approves on-handset and we find out via webhook or by polling
    // GET /v1/transactions/{id}.

    @PostMapping("/api/wallet/deposit/webrabbit-momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initMomoDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[WR-MoMo][init] START — userId='{}'", user.getId());

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

        // Web Rabbit takes decimal GHS directly — no pesewa conversion needed
        // on the request (contrast with Paystack, which wants amount in pesewas).
        var idempotencyKey = "wr-momo-" + user.getId() + "-" + UUID.randomUUID();

        log.info("[WR-MoMo][init] Calling Web Rabbit POST /collect/momo — userId='{}' amountGHS={} phone='{}' network='{}' idempotencyKey='{}'",
                user.getId(), amount, maskPhone(phone), network, idempotencyKey);

        var response = webRabbitChargeMomo(amount, phone, network,
                "Deposit — user " + user.getId(), user.getEmail(), idempotencyKey);

        log.info("[WR-MoMo][init] DONE — userId='{}' transactionId='{}' status='{}' reasonCode='{}'",
                user.getId(), response.get("transaction_id"), response.get("status"), response.get("reason_code"));

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── MoMo: verify fallback ──────────────────────────────────────────────────
    //
    // Mirrors Paystack's verify fallback. Docs recommend polling every ~3s for
    // up to 60s, then slowing to ~10s — that cadence belongs on the client or a
    // scheduled job, not in this single-shot endpoint.

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

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/webrabbit-momo
    //
    // Must be covered by .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
    // in SecurityConfig (same rule already used for the Paystack webhook), so
    // Web Rabbit's server-to-server POST reaches this method without a JWT.
    // Identity is proven by an HMAC-SHA256 signature over the raw body.
    //
    // ⚠ CONFIRM AGAINST THE WEBHOOKS DOC PAGE BEFORE GOING LIVE:
    //   The pages you supplied confirm webhooks are "signed HMAC-SHA256" but the
    //   exact header name and payload event names for MoMo collections were on
    //   the separate Webhooks doc page, which wasn't included in what was pasted.
    //   This controller assumes:
    //     - signature arrives in header  x-webrabbit-signature
    //     - event name for a MoMo collection reaching a terminal state is
    //       something like "collection.approved" / "collection.failed"
    //       (by analogy with the confirmed disbursement events
    //       payout.queued / payout.completed / payout.failed)
    //   Update WEBHOOK_HEADER and the event-name checks below to match the
    //   real Webhooks page once you have it in front of you.
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String WEBHOOK_SIGNATURE_HEADER = "x-webrabbit-signature"; // TODO confirm exact header name

    @PostMapping("/api/webhooks/webrabbit-momo")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = WEBHOOK_SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) {

        // ── 1. Read raw body (MUST be before any parsing) ─────────────────────
        log.info("[WR-Webhook] HIT — remote='{}'", request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[WR-Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        log.info("[WR-Webhook] Body length={} bytes", rawBody.length);

        // ── 2. Signature check ─────────────────────────────────────────────────
        if (signature == null || signature.isBlank()) {
            log.warn("[WR-Webhook] REJECTED — missing {} header", WEBHOOK_SIGNATURE_HEADER);
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("[WR-Webhook] REJECTED — HMAC mismatch (wrong webhook secret?)");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("[WR-Webhook] Signature OK");

        // ── 3. Parse + dispatch ────────────────────────────────────────────────
        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event") != null ? event.get("event").toString() : "unknown";
            log.info("[WR-Webhook] event='{}'", eventType);

            // Only act on a MoMo collection reaching a terminal, successful state.
            // Confirm the real event name against the Webhooks doc page — see
            // the block comment above this method.
            if (!eventType.equals("collection.approved") && !eventType.contains("approved")) {
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

            // Per docs/transactions-retrieve: status and resolved_status are the
            // ledger truth, but reason_code is the field to switch application
            // logic on. A mere "prompt_sent" / "pending" must NEVER credit.
            if (!"approved".equals(status) || !"approved".equals(reasonCode)) {
                log.info("[WR-Webhook] Not a terminal approval — status='{}' reasonCode='{}' transactionId='{}' — skipping credit",
                        status, reasonCode, transactionId);
                return ResponseEntity.ok("OK-NOT-APPROVED");
            }

            // ── Resolve which user this transaction belongs to ─────────────────
            //
            // Unlike Paystack, the Web Rabbit /collect/momo request body in the
            // docs you supplied has no free-form metadata field — only amount,
            // subscriber_number, network, desc and customer_email. So userId
            // cannot ride along in provider metadata the way it does with
            // Paystack. This controller resolves the user by looking up the
            // transaction_id we stored locally against the idempotency key we
            // generated at /init time (see initMomoDeposit). Swap in your own
            // lookup (e.g. a pending_deposits table keyed by transaction_id)
            // in place of the placeholder below.
            UUID userId = lookupUserIdByTransactionId(transactionId);
            if (userId == null) {
                log.error("[WR-Webhook] UNRESOLVABLE userId — transactionId='{}' — no local record found", transactionId);
                return ResponseEntity.ok("OK-NO-USER");
            }

            // ── Parse amount — gross_amount is decimal GHS already ─────────────
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

        log.info("[WR-handleDeposit] COMPLETE — userId='{}' ref='{}'", userId, ref);
    }

    /**
     * Placeholder for resolving which platform user a Web Rabbit transaction_id
     * belongs to. Wire this to whatever you persist at /init time (e.g. save
     * userId + transaction_id together right after webRabbitChargeMomo returns,
     * in a pending_deposits table), since Web Rabbit's /collect/momo request
     * has no metadata field to carry the userId through for us.
     */
    private UUID lookupUserIdByTransactionId(String transactionId) {
        // TODO: replace with a real repository lookup, e.g.
        // return pendingDepositRepository.findUserIdByTransactionId(transactionId).orElse(null);
        log.warn("[WR-lookupUserIdByTransactionId] Not implemented — transactionId='{}'", transactionId);
        return null;
    }

    // ─── Web Rabbit API calls ───────────────────────────────────────────────────

    private Map<String, Object> webRabbitChargeMomo(BigDecimal amountGhs, String phone, String network,
                                                      String desc, String customerEmail,
                                                      String idempotencyKey) {
        var body = new java.util.HashMap<String, Object>();
        body.put("amount", amountGhs);
        body.put("subscriber_number", phone);
        body.put("network", network);
        if (desc != null && !desc.isBlank()) body.put("desc", desc);
        if (customerEmail != null && !customerEmail.isBlank()) body.put("customer_email", customerEmail);

        return postToWebRabbit("/collect/momo", body, idempotencyKey, "webRabbitChargeMomo");
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
        // Note: Web Rabbit's own docs say subscriber_number accepts either
        // local (0XXXXXXXXX) or international (233XXXXXXXXX) format and that
        // they normalise it server-side — so sending local format here is a
        // safe, valid choice, not just a Paystack habit carried over.
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
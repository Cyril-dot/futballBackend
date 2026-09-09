package com.speedbet.api.payment.akwapay;

import com.speedbet.api.chat.AdminUpgradeChatService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AkwaPay payment integration — updated for NaloPay gateway (September 2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * GATEWAY CHANGE (2026-09-09) — NALOPAY INTEGRATION
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * AkwaPay now routes through NaloPay as the primary gateway (priority 1).
 * The API contract this controller talks to is unchanged — same
 * /v1/payment_intents endpoint, same auth, same webhook shape. But the
 * underlying gateway behaviour changes:
 *
 *   1. PRIMARY FLOW: method="mobile_money" + customer.phone + network
 *      → NaloPay sends a MoMo push prompt directly to the customer's phone.
 *      next_action.type = "await_prompt"
 *      next_action.ussdFallback = USSD code (e.g. "*920*1*486#") — show this
 *      if the push doesn't arrive within ~30 seconds.
 *
 *   2. FALLBACK FLOW: method="card" (no phone required)
 *      → NaloPay creates a hosted checkout session.
 *      next_action.type = "redirect"
 *      next_action.url = AkwaPay checkout page URL (NOT Nalo's page directly)
 *      The AkwaPay checkout page handles MoMo form + USSD + card.
 *
 *   3. THE OTP FLOW ({@link #submitOtp}) IS KEPT for legacy compatibility
 *      but NaloPay does not use it. NaloPay uses USSD for fallback.
 *
 *   4. USSD FALLBACK: Always surface next_action.ussdFallback to the
 *      frontend. NaloPay push prompts may not arrive (account activation
 *      pending). The USSD code always works.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REFERENCE FORMAT — 2026-09-09 FIX: SHORT + OPAQUE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * PREVIOUS bug: the reference embedded the full 32-hex user UUID, producing
 * a 47-character string (e.g. "sbdep-2ccd7eba0d704e9aa573207a00758763-2a694733").
 * NaloPay rejected these with "Invalid reference" — MoMo-routed references get
 * forwarded to the telco (MTN/Telecel/AirtelTigo) side, which typically caps
 * transaction reference fields well below 47 characters.
 *
 * FIX: references are now short random tokens, NOT encoded userId+intent.
 * All identity resolution (which user, deposit vs admin upgrade, amount) now
 * comes from looking up the AkwaPayPendingIntent row by its reference (the
 * @Id), which we already persist before calling AkwaPay. See resolvePending().
 *
 *     sbdep-<16 random alphanumeric>     wallet deposit   (22 chars total)
 *     sbadm-<16 random alphanumeric>     admin upgrade    (22 chars total)
 *
 * Only alphanumeric characters and hyphens — no underscores — per NaloPay's
 * accepted charset.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY customer.email IS SYNTHETIC (PER-ATTEMPT)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * AkwaPay deduplicates customers by email on some gateway paths. We use
 * plus-addressing to make the email unique per attempt:
 * kojo@gmail.com → kojo+1724449830123@gmail.com
 * The real email is preserved in metadata for audit.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RELIABILITY GUARANTEE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Webhooks may not always fire. The reconciliation sweep
 * ({@link #reconcilePendingIntents}) is the primary credit mechanism.
 * Both paths dedupe via WalletService.credit() (409 on duplicate reference)
 * so no double-credit is possible whichever wins the race.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WEBHOOK PAYLOAD
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   {
 *     "id":         "evt_<chargePublicId>",
 *     "type":       "payment_intent.succeeded",
 *     "sequence":   <int>,
 *     "created_at": "<ISO-8601>",
 *     "data": {
 *       "intent_id": "<pi_...>",
 *       "amount":    <integer pesewas>,
 *       "currency":  "GHS",
 *       "reference": "<your reference string>",
 *       "status":    "succeeded"
 *     }
 *   }
 *
 * Delivery is at-least-once. Deduped inside WalletService.credit() via
 * the reference (409 → skip).
 */
@Slf4j
@EnableScheduling
@RestController
@RequiredArgsConstructor
public class AkwaPayController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // Hyphens only — NaloPay/AkwaPay reject underscores in references.
    private static final String REF_PREFIX_DEPOSIT = "sbdep-";
    private static final String REF_PREFIX_ADMIN   = "sbadm-";

    // Length of the random opaque token appended after the prefix.
    // Total reference length = prefix(6) + token(16) = 22 chars, well under
    // any MoMo-scheme reference cap we've seen documented (typically 18-35).
    private static final int    REF_TOKEN_LENGTH = 16;
    private static final String REF_TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom REF_RANDOM = new SecureRandom();

    private final Duration akwapayTimeout      = Duration.ofSeconds(15);
    private final long     akwapayRetryAttempts = 2;

    private static final long     SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final Duration SWEEP_HEAD_START            = Duration.ofSeconds(5);
    private static final long     SWEEP_INTERVAL_MS           = 5_000;

    private static final Duration TIER_HOT_UNTIL  = Duration.ofMinutes(2);
    private static final Duration TIER_WARM_UNTIL = Duration.ofMinutes(10);
    private static final Duration TIER_COOL_UNTIL = Duration.ofMinutes(60);

    private static final Duration POLL_EVERY_HOT  = Duration.ofSeconds(5);
    private static final Duration POLL_EVERY_WARM = Duration.ofSeconds(30);
    private static final Duration POLL_EVERY_COOL = Duration.ofMinutes(2);
    private static final Duration POLL_EVERY_COLD = Duration.ofMinutes(10);

    private static final Duration ABANDON_AFTER = Duration.ofHours(24);

    // Ghana MNO prefix → AkwaPay network code
    private static final Map<String, String> GH_NETWORK_PREFIXES = new LinkedHashMap<>();
    static {
        for (var p : new String[]{"024", "025", "053", "054", "055", "059"}) GH_NETWORK_PREFIXES.put(p, "MTN");
        for (var p : new String[]{"020", "050"})                             GH_NETWORK_PREFIXES.put(p, "TELECEL");
        for (var p : new String[]{"026", "027", "056", "057"})               GH_NETWORK_PREFIXES.put(p, "AIRTELTIGO");
    }

    private final WalletService                  walletService;
    private final UserService                    userService;
    private final AdminUpgradeChatService        adminUpgradeChatService;
    private final ReferralService                referralService;
    private final AkwaPayPendingIntentRepository pendingIntents;
    private final WebClient.Builder              webClientBuilder;
    private final ObjectMapper                   objectMapper;

    @Value("${app.akwapay.secret-key}")              private String     secretKey;
    @Value("${app.akwapay.webhook-secret}")          private String     webhookSecret;
    @Value("${app.akwapay.base-url}")                private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")           private String     frontendUrl;

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    /**
     * Initiates a deposit via AkwaPay/NaloPay.
     *
     * PRIMARY PATH (phone provided):
     *   method="mobile_money" → NaloPay sends MoMo push to customer's phone.
     *   Response includes next_action.type="await_prompt" and
     *   next_action.ussdFallback for when the push doesn't arrive.
     *
     * FALLBACK PATH (no phone):
     *   method="card" → AkwaPay creates a hosted checkout session.
     *   Response includes checkout_url — redirect the customer there.
     *   The AkwaPay checkout page handles MoMo form + USSD + card.
     *
     * Frontend should:
     *   1. If next_action.type == "await_prompt": show "Check your phone"
     *      spinner + ussdFallback USSD code as tap-to-dial.
     *   2. If next_action.type == "redirect": redirect to checkout_url.
     *   3. Poll GET /api/wallet/deposit/akwapay/status/{intentId} or listen
     *      for the webhook to confirm success.
     */
    @PostMapping("/api/wallet/deposit/akwapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        var reference = buildReference(REF_PREFIX_DEPOSIT);

        var phone        = req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req.get("network") == null ? null : req.get("network").toString();

        // Determine payment method:
        // - phone provided → direct MoMo push (primary, NaloPay)
        // - no phone       → hosted checkout fallback
        boolean useMomoPush = phone != null && !phone.isBlank();
        String  network     = useMomoPush ? resolveNetwork(requestedNet, phone) : null;
        String  method      = useMomoPush ? "mobile_money" : "card";

        log.info("initDeposit: userId='{}' amount={} pesewas={} ref='{}' (len={}) method='{}' network='{}'",
                user.getId(), amount, amountPesewas, reference, reference.length(), method, network);

        // Persist BEFORE calling AkwaPay: recordPending is the only place the
        // userId is stored against this reference. If AkwaPay call fails after
        // this point, the sweep never sees a status="succeeded" for an intent
        // that was never created, so this is safe to do first.
        recordPending(reference, null, user.getId(), amount, false);

        var response = akwapayCreateIntent(
                amountPesewas,
                reference,
                user.getEmail(),
                phone,
                network,
                method,
                frontendUrl + "/wallet?payment=success",
                Map.of("userId", user.getId().toString(), "purpose", "deposit")
        );

        var intentId       = String.valueOf(response.get("id"));
        var nextActionType = nextActionType(response);

        attachIntentId(reference, intentId);

        log.info("initDeposit: intent='{}' status='{}' next_action='{}' ussdFallback='{}' for userId='{}'",
                intentId,
                response.get("status"),
                nextActionType,
                nextActionUssd(response),
                user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    @PostMapping("/api/user/upgrade-to-admin/akwapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var reference = buildReference(REF_PREFIX_ADMIN);

        var phone        = req == null || req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req == null || req.get("network") == null ? null : req.get("network").toString();

        boolean useMomoPush = phone != null && !phone.isBlank();
        String  network     = useMomoPush ? resolveNetwork(requestedNet, phone) : null;
        String  method      = useMomoPush ? "mobile_money" : "card";

        log.info("initAdminUpgrade: userId='{}' email='{}' ref='{}' (len={}) method='{}' network='{}'",
                user.getId(), user.getEmail(), reference, reference.length(), method, network);

        var upgradeAmountGhs = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_PESEWAS)
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

        recordPending(reference, null, user.getId(), upgradeAmountGhs, true);

        var response = akwapayCreateIntent(
                ADMIN_UPGRADE_FEE_PESEWAS,
                reference,
                user.getEmail(),
                phone,
                network,
                method,
                frontendUrl + "/app/upgrade?payment=success",
                Map.of(
                        "userId",        user.getId().toString(),
                        "upgradeIntent", UPGRADE_INTENT_ADMIN
                )
        );

        var intentId = String.valueOf(response.get("id"));
        attachIntentId(reference, intentId);

        log.info("initAdminUpgrade: intent='{}' status='{}' next_action='{}' for userId='{}'",
                intentId, response.get("status"), nextActionType(response), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Persists the pending intent row. Called BEFORE the AkwaPay API call so
     * that the (userId, amount, adminUpgrade) tuple is recoverable purely from
     * the reference string even if the process crashes mid-request. intentId
     * may be null initially — see attachIntentId().
     */
    private void recordPending(String reference, String intentId, UUID userId,
                               BigDecimal amountGhs, boolean adminUpgrade) {
        try {
            pendingIntents.save(new AkwaPayPendingIntent(
                    reference, intentId, userId, amountGhs, adminUpgrade, Instant.now(), 0, null));
            log.info("recordPending: ref='{}' intent='{}' persisted — sweep will reconcile if webhook is lost",
                    reference, intentId);
        } catch (Exception e) {
            log.error("recordPending: FAILED to persist ref='{}' intent='{}' userId='{}' amount={} — " +
                            "this payment can only be credited by webhook or by hand. Investigate.",
                    reference, intentId, userId, amountGhs, e);
        }
    }

    /** Fills in the AkwaPay intent id once it's known, after the pending row already exists. */
    private void attachIntentId(String reference, String intentId) {
        try {
            var existing = pendingIntents.findById(reference).orElse(null);
            if (existing == null) {
                log.error("attachIntentId: no pending row for ref='{}' (intent='{}') — " +
                                "was recordPending() called first? This intent cannot be reconciled by the sweep.",
                        reference, intentId);
                return;
            }
            existing.setIntentId(intentId);
            pendingIntents.save(existing);
            log.info("attachIntentId: ref='{}' now linked to intent='{}'", reference, intentId);
        } catch (Exception e) {
            log.error("attachIntentId: FAILED for ref='{}' intent='{}' — sweep may not find this by intentId " +
                    "but reconciliation still works via reference lookup on webhook.", reference, intentId, e);
        }
    }

    // ─── Hosted Checkout Init (explicit fallback) ─────────────────────────────

    /**
     * Explicitly creates a hosted checkout session when the frontend has no
     * phone number (e.g. the customer skips the phone form).
     *
     * Returns checkout_url — redirect the customer to it.
     * The AkwaPay checkout page collects the phone, sends the MoMo push,
     * and shows the USSD fallback if push doesn't arrive.
     */
    @PostMapping("/api/wallet/deposit/akwapay/checkout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initCheckout(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> req) {

        var amount = req == null || req.get("amount") == null
                ? minDeposit
                : new BigDecimal(req.get("amount").toString());

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        var reference = buildReference(REF_PREFIX_DEPOSIT);

        log.info("initCheckout: userId='{}' amount={} ref='{}' (len={}) — hosted checkout fallback",
                user.getId(), amount, reference, reference.length());

        recordPending(reference, null, user.getId(), amount, false);

        var response = akwapayCreateIntent(
                amountPesewas,
                reference,
                user.getEmail(),
                null,     // no phone — checkout page collects it
                null,     // no network
                "card",   // triggers hosted checkout
                frontendUrl + "/wallet?payment=success",
                Map.of("userId", user.getId().toString(), "purpose", "deposit")
        );

        var intentId = String.valueOf(response.get("id"));
        attachIntentId(reference, intentId);

        log.info("initCheckout: intent='{}' checkoutUrl='{}' for userId='{}'",
                intentId, response.get("checkout_url"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Status probe (read only) ─────────────────────────────────────────────

    @GetMapping("/api/wallet/deposit/akwapay/status/{intentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user,
            @PathVariable String intentId) {

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intentId)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("AkwaPay status probe error: status={} body={}", r.statusCode(), body);
                            return new RuntimeException("AkwaPay returned " + r.statusCode());
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");

        log.info("status: userId='{}' intent='{}' status='{}' next_action='{}'",
                user.getId(), intentId, result.get("status"), nextActionType(result));

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── OTP submission (legacy — kept for non-NaloPay gateway fallback) ──────

    /**
     * NaloPay does not use OTP — it uses USSD fallback instead.
     * This endpoint is kept for backward compatibility if AkwaPay routes
     * to a different gateway that still uses the submit_otp flow.
     *
     * For NaloPay: surface next_action.ussdFallback to the user instead.
     */
    @PostMapping("/api/wallet/deposit/akwapay/otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var intentId     = req.get("intentId")     == null ? "" : req.get("intentId").toString().trim();
        var clientSecret = req.get("clientSecret") == null ? "" : req.get("clientSecret").toString().trim();
        var otp          = req.get("otp")          == null ? "" : req.get("otp").toString().trim();

        if (intentId.isBlank() || clientSecret.isBlank() || otp.isBlank())
            throw ApiException.badRequest("intentId, clientSecret and otp are all required");

        log.info("submitOtp: userId='{}' intent='{}' (NaloPay uses USSD not OTP — kept for legacy)",
                user.getId(), intentId);

        var akwapayRoot = baseUrl.replaceAll("/v1.*$", "");

        URI validateUri = UriComponentsBuilder
                .fromUriString(akwapayRoot + "/v1/checkout/" + intentId + "/validate")
                .queryParam("cs", clientSecret)
                .build()
                .toUri();

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .post()
                .uri(validateUri)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("otp", otp))
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("submitOtp: AkwaPay error status={} body={} intent='{}'",
                                    r.statusCode(), body, intentId);
                            return new RuntimeException(
                                    "AkwaPay returned " + r.statusCode() + ": " + body);
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response for OTP validation.");

        log.info("submitOtp: intent='{}' result='{}'", intentId, result);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/akwapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature",   required = false) String signature,
            @RequestHeader(value = "X-AkwaPay-Event-Type",  required = false) String headerEventType,
            @RequestHeader(value = "X-AkwaPay-Delivery-Id", required = false) String deliveryId,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("AkwaPay webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("AkwaPay webhook: missing X-AkwaPay-Signature header, delivery='{}'", deliveryId);
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("AkwaPay webhook: invalid signature, delivery='{}' eventType='{}' — " +
                            "check AKWAPAY_WEBHOOK_SECRET matches the whsec_ for this endpoint",
                    deliveryId, headerEventType);
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventId   = String.valueOf(event.get("id"));
            var eventType = String.valueOf(event.get("type"));
            var sequence  = event.get("sequence");

            log.info("AkwaPay webhook: event='{}' type='{}' sequence={} delivery='{}'",
                    eventId, eventType, sequence, deliveryId);

            if (!"payment_intent.succeeded".equals(eventType)) {
                log.info("AkwaPay webhook: ignoring event type='{}' (event='{}')", eventType, eventId);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("AkwaPay webhook: no data block on event='{}'", eventId);
                return ResponseEntity.status(400).body("Missing data");
            }

            var reference = data.get("reference") == null ? null : data.get("reference").toString();
            var intentId  = data.get("intent_id") == null ? "" : data.get("intent_id").toString();

            if (reference == null || reference.isBlank()) {
                log.error("AkwaPay webhook: no reference on event='{}' intent='{}'", eventId, intentId);
                return ResponseEntity.status(400).body("Missing reference");
            }

            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            var pending = resolvePending(reference);
            if (pending == null) {
                log.warn("AkwaPay webhook: unrecognised reference '{}' on event='{}' intent='{}' " +
                                "amount={} — returning 200 so AkwaPay stops retrying. " +
                                "If this is a real customer payment, credit it manually.",
                        reference, eventId, intentId, amount);
                return ResponseEntity.ok("Ignored: unknown reference");
            }

            if (pending.isAdminUpgrade()) {
                handleAdminUpgrade(pending.getUserId(), reference, amount, intentId);
            } else {
                handleDeposit(pending.getUserId(), reference, amount, intentId);
            }

            deletePending(reference, "settled by webhook");

        } catch (ApiException e) {
            log.error("AkwaPay webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("AkwaPay webhook: unexpected error — will be retried", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Reconciliation sweep ─────────────────────────────────────────────────

    @Scheduled(fixedDelay = 5_000)
    public void reconcilePendingIntents() {
        var cutoff = Instant.now().minus(SWEEP_HEAD_START);
        var stale  = pendingIntents.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        if (stale.isEmpty()) return;

        var due = stale.stream().filter(i -> isDue(i, Instant.now())).toList();
        if (due.isEmpty()) return;

        log.info("reconcile: {} of {} pending intent(s) due this tick", due.size(), stale.size());

        for (var intent : due) {
            try {
                reconcileOne(intent);
            } catch (Exception e) {
                log.error("reconcile: unexpected error for ref='{}' intent='{}' — will retry next tick",
                        intent.getReference(), intent.getIntentId(), e);
            }
        }
    }

    private boolean isDue(AkwaPayPendingIntent intent, Instant now) {
        // No AkwaPay intent id yet means the create-intent call never finished
        // (crashed between recordPending and attachIntentId) — nothing to poll.
        if (intent.getIntentId() == null || intent.getIntentId().isBlank()) return false;
        var last = intent.getLastCheckedAt();
        if (last == null) return true;
        return last.plus(pollIntervalFor(intent, now)).isBefore(now);
    }

    private Duration pollIntervalFor(AkwaPayPendingIntent intent, Instant now) {
        var age = Duration.between(intent.getCreatedAt(), now);
        if (age.compareTo(TIER_HOT_UNTIL)  < 0) return POLL_EVERY_HOT;
        if (age.compareTo(TIER_WARM_UNTIL) < 0) return POLL_EVERY_WARM;
        if (age.compareTo(TIER_COOL_UNTIL) < 0) return POLL_EVERY_COOL;
        return POLL_EVERY_COLD;
    }

    private void reconcileOne(AkwaPayPendingIntent intent) {
        var ref = intent.getReference();

        if (intent.getCreatedAt().isBefore(Instant.now().minus(ABANDON_AFTER))) {
            log.warn("reconcile: abandoning ref='{}' intent='{}' after {}h with no settlement",
                    ref, intent.getIntentId(), ABANDON_AFTER.toHours());
            deletePending(ref, "abandoned after " + ABANDON_AFTER.toHours() + "h");
            return;
        }

        try {
            intent.markChecked(Instant.now());
            pendingIntents.save(intent);
        } catch (Exception e) {
            log.warn("reconcile: could not stamp lastCheckedAt for ref='{}': {}", ref, e.getMessage());
        }

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intent.getIntentId())
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("reconcile: AkwaPay status error for ref='{}' status={} body={}",
                                    ref, r.statusCode(), body);
                            return new RuntimeException("AkwaPay returned " + r.statusCode());
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .onErrorResume(e -> {
                    log.warn("reconcile: status check failed for ref='{}' intent='{}' — retry next sweep: {}",
                            ref, intent.getIntentId(), e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (result == null) return;

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase();
        log.info("reconcile: ref='{}' intent='{}' akwapayStatus='{}' attempt={}",
                ref, intent.getIntentId(), akwapayStatus, intent.getAttempts());

        switch (akwapayStatus) {
            case "succeeded" -> {
                log.info("reconcile: ref='{}' succeeded on sweep — applying credit", ref);
                if (intent.isAdminUpgrade()) {
                    handleAdminUpgrade(intent.getUserId(), ref, intent.getAmountGhs(), intent.getIntentId());
                } else {
                    handleDeposit(intent.getUserId(), ref, intent.getAmountGhs(), intent.getIntentId());
                }
                deletePending(ref, "settled by sweep");
            }

            case "failed", "declined", "cancelled", "expired" -> {
                log.warn("reconcile: ref='{}' intent='{}' terminal status='{}' — no credit applied",
                        ref, intent.getIntentId(), akwapayStatus);
                deletePending(ref, "terminal status " + akwapayStatus);
            }

            default ->
                    log.info("reconcile: ref='{}' status='{}' — still in flight, next check in {}s",
                            ref, akwapayStatus, pollIntervalFor(intent, Instant.now()).toSeconds());
        }
    }

    private void deletePending(String reference, String why) {
        try {
            if (pendingIntents.existsById(reference)) {
                pendingIntents.deleteById(reference);
                log.info("deletePending: ref='{}' removed from pending ledger ({})", reference, why);
            }
        } catch (Exception e) {
            log.warn("deletePending: could not remove ref='{}' ({}) — sweep will re-check and skip: {}",
                    reference, why, e.getMessage());
        }
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String intentId) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}' intent='{}'",
                userId, amount, ref, intentId);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "akwapay/nalopay", "reference", ref, "intentId", intentId));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'", amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return;
            }
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit={}", userId, amount);
        } catch (Exception ex) {
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate", userId, ex);
        }
    }

    private void handleAdminUpgrade(UUID userId, String ref, BigDecimal amount, String intentId) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}' intent='{}'",
                userId, amount, ref, intentId);

        if (amount.compareTo(BigDecimal.valueOf(200)) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'",
                    amount, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            userService.upgradeToAdmin(userId, ref);
            log.info("handleAdminUpgrade: userId='{}' promoted to ADMIN ref='{}'", userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleAdminUpgrade: duplicate ref='{}' — skipping", ref);
                return;
            }
            throw ex;
        }

        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "akwapay/nalopay", "reference", ref, "intentId", intentId));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── Network resolution ───────────────────────────────────────────────────

    private String resolveNetwork(String requested, String phone) {
        if (requested != null && !requested.isBlank()) {
            var normalized = requested.trim().toUpperCase();
            log.info("resolveNetwork: using client-selected network '{}'", normalized);
            return normalized;
        }

        var detected = detectNetworkFromPhone(phone);
        if (detected.isPresent()) {
            log.info("resolveNetwork: auto-detected network '{}' from phone prefix", detected.get());
            return detected.get();
        }

        log.warn("resolveNetwork: could not resolve network from phone='{}' and no network was selected",
                phone == null ? "null" : "<redacted>");
        throw ApiException.badRequest(
                "We couldn't detect which network that number is on. Please select MTN, Telecel, or AirtelTigo.");
    }

    private Optional<String> detectNetworkFromPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();

        var digits = phone.replaceAll("[^\\d]", "");

        String local;
        if (digits.startsWith("233") && digits.length() == 12) {
            local = "0" + digits.substring(3);
        } else if (digits.length() == 10 && digits.startsWith("0")) {
            local = digits;
        } else {
            return Optional.empty();
        }

        return Optional.ofNullable(GH_NETWORK_PREFIXES.get(local.substring(0, 3)));
    }

    // ─── AkwaPay API helper ───────────────────────────────────────────────────

    /**
     * Creates a payment intent on AkwaPay.
     *
     * For NaloPay (primary gateway):
     *   - method="mobile_money" + phone + network → direct MoMo push
     *     Response: next_action.type="await_prompt", next_action.ussdFallback="*920*1*xxx#"
     *   - method="card" (no phone) → hosted checkout session
     *     Response: next_action.type="redirect", checkout_url="https://akwapay.vercel.app/checkout/..."
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> akwapayCreateIntent(int amountPesewas,
                                                    String reference,
                                                    String email,
                                                    String phone,
                                                    String network,
                                                    String method,
                                                    String returnUrl,
                                                    Map<String, Object> metadata) {

        // Synthetic email per-attempt to avoid customer deduplication issues
        String syntheticEmail;
        if (email != null && email.contains("@")) {
            int atIdx = email.indexOf("@");
            syntheticEmail = email.substring(0, atIdx)
                    + "+" + System.currentTimeMillis()
                    + email.substring(atIdx);
        } else {
            syntheticEmail = reference.replaceAll("[^a-zA-Z0-9]", "")
                    + System.currentTimeMillis()
                    + "@customers.akwapay.com";
        }

        var customer = new HashMap<String, Object>();
        customer.put("email", syntheticEmail);
        if (phone != null && !phone.isBlank()) customer.put("phone", phone);

        var body = new HashMap<String, Object>();
        body.put("amount",     amountPesewas);
        body.put("currency",   "GHS");
        body.put("reference",  reference);
        body.put("return_url", returnUrl);
        body.put("metadata",   metadata);
        body.put("customer",   customer);
        body.put("method",     method);

        // Only set network for mobile_money — card doesn't need it
        if ("mobile_money".equals(method) && network != null) {
            body.put("network", network.toUpperCase());
        }

        var idempotencyKey = UUID.randomUUID().toString();

        log.info("akwapayCreateIntent: ref='{}' (len={}) method='{}' network='{}' amountPesewas={} idempotencyKey='{}'",
                reference, reference.length(), method, network, amountPesewas, idempotencyKey);
        log.debug("akwapayCreateIntent: full request body for ref='{}': {}", reference, body);

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/payment_intents")
                .header("Authorization",   "Bearer " + secretKey)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type",    "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(errBody -> {
                                    // Log EVERYTHING about the failing request so a rejected
                                    // reference (or any other field AkwaPay/NaloPay dislikes)
                                    // is diagnosable from logs alone, without reproducing locally.
                                    log.error("AkwaPay API error: status={} ref='{}' (len={}) method='{}' " +
                                                    "network='{}' amountPesewas={} idempotencyKey='{}' body={}",
                                            clientResponse.statusCode(), reference, reference.length(),
                                            method, network, amountPesewas, idempotencyKey, errBody);

                                    int code = clientResponse.statusCode().value();
                                    if (code >= 400 && code < 500) {
                                        String userMessage;
                                        try {
                                            @SuppressWarnings("unchecked")
                                            var parsed = (Map<String, Object>)
                                                    objectMapper.readValue(errBody, Map.class);
                                            @SuppressWarnings("unchecked")
                                            var error = (Map<String, Object>) parsed.get("error");
                                            var msg = error != null ? (String) error.get("message") : null;
                                            var errCode = error != null ? (String) error.get("code") : null;
                                            log.error("AkwaPay API error detail: ref='{}' errorCode='{}' errorMessage='{}'",
                                                    reference, errCode, msg);
                                            userMessage = (msg != null && !msg.isBlank())
                                                    ? msg
                                                    : "Payment was rejected. Please check your details and try again.";
                                        } catch (Exception parseEx) {
                                            log.error("AkwaPay API error: could not parse error body as JSON for ref='{}': {}",
                                                    reference, errBody);
                                            userMessage = "Payment was rejected. Please check your details and try again.";
                                        }
                                        return (Throwable) ApiException.badRequest(userMessage);
                                    }

                                    return new RuntimeException(
                                            "AkwaPay returned " + clientResponse.statusCode() + ": " + errBody);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .retryWhen(Retry.max(akwapayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("AkwaPay API unreachable after {} retries, ref='{}'",
                                    akwapayRetryAttempts, reference, ex);
                            return new RuntimeException("AkwaPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            log.error("akwapayCreateIntent: empty response body for ref='{}'", reference);
            throw new RuntimeException("AkwaPay returned an empty response.");
        }

        var status = String.valueOf(result.get("status"));
        log.info("akwapayCreateIntent: intent='{}' status='{}' next_action='{}' ussdFallback='{}' ref='{}'",
                result.get("id"), status, nextActionType(result), nextActionUssd(result), reference);

        if (result.get("error") != null) {
            log.error("akwapayCreateIntent: error on ref='{}' — {}", reference, result.get("error"));
            throw new RuntimeException("AkwaPay error: " + result.get("error"));
        }

        if ("failed".equals(status)) {
            log.error("akwapayCreateIntent: intent created but already failed, ref='{}'", reference);
            throw new RuntimeException("Payment could not be started. Please try again.");
        }

        return result;
    }

    private String nextActionType(Map<String, Object> response) {
        var na = response.get("next_action");
        if (!(na instanceof Map<?, ?> m)) return "none";
        return String.valueOf(m.get("type"));
    }

    /**
     * Extracts the USSD fallback code from next_action.
     * NaloPay returns this as ussdFallback (e.g. "*920*1*486#").
     * Surface this to the frontend — always show it, always.
     */
    private String nextActionUssd(Map<String, Object> response) {
        var na = response.get("next_action");
        if (!(na instanceof Map<?, ?> m)) return null;
        var ussd = m.get("ussdFallback");
        return ussd == null ? null : String.valueOf(ussd);
    }

    // ─── Reference generation / resolution ────────────────────────────────────

    /**
     * Builds a short, opaque reference: prefix + 16 random lowercase
     * alphanumeric characters. 22 characters total, hyphens only, well under
     * any MoMo-scheme reference length limit we've seen.
     *
     * IMPORTANT: unlike the old scheme, this reference does NOT encode the
     * userId or anything else. All lookups go through resolvePending(), which
     * reads the AkwaPayPendingIntent row keyed by this exact string. The row
     * MUST be saved via recordPending() before this reference is sent to
     * AkwaPay, or the webhook/sweep will have nothing to resolve it against.
     */
    private String buildReference(String prefix) {
        var sb = new StringBuilder(prefix.length() + REF_TOKEN_LENGTH);
        sb.append(prefix);
        for (int i = 0; i < REF_TOKEN_LENGTH; i++) {
            sb.append(REF_TOKEN_ALPHABET.charAt(REF_RANDOM.nextInt(REF_TOKEN_ALPHABET.length())));
        }
        var ref = sb.toString();
        // Belt-and-suspenders: collisions are astronomically unlikely
        // (36^16 keyspace) but a random generator is still a random
        // generator, and this table is small — check is cheap.
        if (pendingIntents.existsById(ref)) {
            log.warn("buildReference: collision on '{}' — regenerating", ref);
            return buildReference(prefix);
        }
        return ref;
    }

    /**
     * Resolves a reference back to its pending intent row. Replaces the old
     * parseReference() which tried to decode the userId out of the string
     * itself — that only worked because the string used to embed the UUID,
     * which is exactly what made references too long for NaloPay to accept.
     */
    private AkwaPayPendingIntent resolvePending(String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (!reference.startsWith(REF_PREFIX_DEPOSIT) && !reference.startsWith(REF_PREFIX_ADMIN)) {
            log.warn("resolvePending: reference '{}' does not match any known prefix", reference);
            return null;
        }
        return pendingIntents.findById(reference).orElse(null);
    }

    // ─── Signature verification ───────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String header) {
        try {
            String t = null, v1 = null;
            for (var part : header.split(",")) {
                var kv = part.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0]))       t  = kv[1].trim();
                else if ("v1".equals(kv[0])) v1 = kv[1].trim();
            }

            if (t == null || v1 == null) {
                log.warn("AkwaPay webhook: malformed signature header — missing 't' or 'v1' part");
                return false;
            }

            long timestamp;
            try {
                timestamp = Long.parseLong(t);
            } catch (NumberFormatException e) {
                log.warn("AkwaPay webhook: non-numeric timestamp in signature header");
                return false;
            }

            var age = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (age > SIGNATURE_TOLERANCE_SECONDS) {
                log.warn("AkwaPay webhook: signature timestamp {}s old (tolerance {}s) — replay rejected",
                        age, SIGNATURE_TOLERANCE_SECONDS);
                return false;
            }

            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);

            var expected = HexFormat.of().formatHex(mac.doFinal());

            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("AkwaPay webhook: signature verification threw unexpectedly", e);
            return false;
        }
    }
}
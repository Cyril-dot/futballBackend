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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AkwaPay payment integration.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * HOW THIS DIFFERS FROM {@code PaystackController} — READ BEFORE EDITING
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1. NO METADATA ON THE WEBHOOK.
 *    Paystack echoes back whatever metadata you sent at init, which is how the
 *    Paystack controller recovers the userId. AkwaPay does NOT. Its merchant
 *    event payload is exactly:
 *
 *        { id, type, sequence, created_at,
 *          data: { intent_id, amount, currency, reference, status } }
 *
 *    You may send `metadata` when creating the intent and it is stored, but it
 *    never comes back on the webhook. So the userId is encoded INTO the
 *    `reference` and parsed back out here. See {@link #buildReference} and
 *    {@link #parseReference}. Do not "simplify" the reference format — the
 *    webhook becomes unroutable if you do.
 *
 * 2. TWO SEPARATE SECRETS.
 *    Paystack signs webhooks with the same secret key you call the API with.
 *    AkwaPay does not:
 *
 *        sk_live_...   calls the API          (app.akwapay.secret-key)
 *        whsec_...     signs webhooks to you  (app.akwapay.webhook-secret)
 *
 *    The whsec is minted by POST /v1/webhook_endpoints and shown ONCE. It is
 *    encrypted at rest on their side, not hashed, but they will not show it
 *    again — losing it means re-registering the endpoint.
 *
 * 3. SIGNATURE SCHEME IS HMAC-SHA256 OVER "{timestamp}.{rawBody}", NOT SHA512
 *    OVER THE BODY ALONE, and it carries a replay window:
 *
 *        X-AkwaPay-Signature: t=1754049600,v1=5f3c9a...
 *
 *    Timestamps older than 5 minutes are rejected. See {@link #verifySignature}.
 *
 * 4. EVERY MUTATING CALL NEEDS AN Idempotency-Key HEADER.
 *    Same key + same body replays the stored response. Same key + DIFFERENT
 *    body returns 409. We send a fresh UUID per attempt and let the unique
 *    `reference` provide the real dedupe.
 *
 * 5. next_action IS NOT ALWAYS A PUSH PROMPT.
 *    Observed live on a mobile_money/MTN charge:
 *
 *        "next_action": { "type": "redirect",
 *                         "url": "https://checkout.flutterwave.com/captcha/..." }
 *
 *    AkwaPay routes across gateways and fails over, so two identical requests
 *    can return different next_action types. The frontend MUST branch on
 *    `next_action.type` (await_prompt | redirect | submit_otp | none) or just
 *    send the user to `checkout_url`, which handles all four.
 *
 * 6. AMOUNTS ARE INTEGER PESEWAS. GHS 50.00 is 5000. Sending 50.00 is a 400.
 *    Identical to Paystack in practice, but AkwaPay enforces it strictly —
 *    there is no decimal tolerance anywhere in the API.
 *
 * 7. `unknown` IS A REAL STATUS AND IS NOT A FAILURE.
 *    It means AkwaPay asked a gateway to move money and got no clear answer.
 *    They poll until it resolves and then fire the webhook. Never re-charge on
 *    it, never fail the deposit on it — you will double-debit real people.
 *
 * 8. `method` AND `network` ARE NOW BOTH REQUIRED ON EVERY payment_intents
 *    CALL WHEN THE METHOD IS mobile_money.
 *    AkwaPay used to accept a request with neither `method` nor `network` and
 *    let the hosted checkout collect both. That stopped being true in two
 *    steps, both observed live:
 *      a) omitting `method` → 400 invalid_method
 *         ("method must be one of: mobile_money, card, bank_transfer")
 *      b) sending method=mobile_money with no `network` → 400 invalid_network
 *         ("mobile_money requires network: MTN, TELECEL or AIRTELTIGO")
 *    This controller only ever originates GHS mobile-money deposits, so
 *    `method` is hardcoded unconditionally in {@link #akwapayCreateIntent}.
 *    `network` is resolved with a two-step fallback in
 *    {@link #resolveNetwork}: use what the client explicitly sent, and if
 *    that's blank, guess it from the Ghana MoMo number prefix (see
 *    {@link #detectNetworkFromPhone}). If neither works we fail fast with a
 *    400 telling the user to pick a network — better than letting AkwaPay
 *    reject it and surfacing a generic 500 to the frontend.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WEBHOOK PAYLOAD — CONFIRMED FROM OFFICIAL AKWAPAY SOURCE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * The exact shape AkwaPay sends on payment_intent.succeeded (from
 * packages/worker/src/webhook-sender.ts → paymentIntentEvent()):
 *
 *   {
 *     "id":         "evt_<chargePublicId>",
 *     "type":       "payment_intent.succeeded",
 *     "sequence":   <int — increases per intent; discard lower than seen>,
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
 * IMPORTANT — data.status is derived as eventType.split('.')[1], so:
 *   payment_intent.succeeded  → data.status = "succeeded"
 *   payment_intent.failed     → data.status = "failed"
 *   payment_intent.processing → data.status = "processing"
 *
 * IDEMPOTENCY — delivery is at-least-once. The same event WILL arrive twice.
 * Key on event.id (not data.reference alone) to no-op on replay. This
 * controller delegates that to WalletService.credit() which returns 409 on a
 * duplicate reference — we catch that 409 and skip silently.
 *
 * SEQUENCE — each event carries `sequence` that increases per intent. A lower
 * sequence than already processed should be discarded. We do not track sequence
 * here because our only terminal action is on `succeeded`, which is idempotent
 * via the reference dedup in WalletService. If you add non-idempotent handlers
 * for other event types, store and check the sequence.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AkwaPayController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * Admins earn 70% of the configured platform commission on each referred
     * deposit. The actual per-admin rate lives on the Referral entity (set
     * during upgradeToAdmin) and is resolved inside
     * ReferralService.attributeCommission(). This constant is for
     * logging/documentation only — do not branch on it.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // ─── Reference encoding ───────────────────────────────────────────────────
    //
    // The webhook gives us back only `reference` inside data{}, so it has to
    // carry the routing information itself. Format:
    //
    //     sbdep_<32-hex userId>_<8-hex nonce>     wallet deposit
    //     sbadm_<32-hex userId>_<8-hex nonce>     admin upgrade
    //
    // The nonce makes the reference unique per attempt (AkwaPay requires
    // account-wide uniqueness on `reference` and returns 409 duplicate_reference
    // otherwise), while the userId segment stays at a fixed offset so parsing
    // never depends on splitting a UUID that contains dashes.
    //
    // CRITICAL: references created outside this controller (e.g. test charges
    // made directly in the AkwaPay dashboard) will not match either prefix.
    // parseReference() returns null for those, and the webhook handler returns
    // 200 "Ignored: foreign reference" so AkwaPay stops retrying something we
    // will never be able to route.

    private static final String REF_PREFIX_DEPOSIT = "sbdep_";
    private static final String REF_PREFIX_ADMIN   = "sbadm_";

    /** How long to wait for AkwaPay to respond before timing out. */
    private final Duration akwapayTimeout = Duration.ofSeconds(15);

    /**
     * Retries on transient network failures only (connection reset, TCP
     * timeout). AkwaPay 4xx/5xx are mapped to RuntimeException by the onStatus
     * handler and are excluded from the retry predicate — retrying a 400 just
     * burns time, and retrying a 402 would be actively wrong.
     */
    private final long akwapayRetryAttempts = 2;

    /** Replay window for webhook signatures, per AkwaPay docs (5 minutes). */
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;

    /**
     * Ghana MoMo number → network, by leading digits after normalising to the
     * local 0XXXXXXXXX shape. This is a best-effort fallback ONLY — Ghana's
     * NCA reassigns/ports ranges occasionally, so this table can drift. It
     * exists purely to skip an extra tap for the common case; it is never the
     * only way through (see {@link #resolveNetwork}), and it is intentionally
     * a flat prefix table rather than a "smart" parser so it's a one-line diff
     * to fix when a range changes.
     *
     * Sources: publicly documented NCA numbering plan ranges as of last
     * verification. If AkwaPay starts rejecting a detected network as wrong,
     * that prefix's mapping is stale — fix it here, not in the frontend.
     */
    private static final Map<String, String> GH_NETWORK_PREFIXES = new LinkedHashMap<>();
    static {
        for (var p : new String[]{"024", "025", "053", "054", "055", "059"}) GH_NETWORK_PREFIXES.put(p, "MTN");
        for (var p : new String[]{"020", "050"})                             GH_NETWORK_PREFIXES.put(p, "TELECEL");
        for (var p : new String[]{"026", "027", "056", "057"})               GH_NETWORK_PREFIXES.put(p, "AIRTELTIGO");
    }

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    @Value("${app.akwapay.secret-key}")              private String     secretKey;
    @Value("${app.akwapay.webhook-secret}")          private String     webhookSecret;
    @Value("${app.akwapay.base-url}")                private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")           private String     frontendUrl;

    // ─── Deposit Init ─────────────────────────────────────────────────────────

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

        var reference = buildReference(REF_PREFIX_DEPOSIT, user.getId());

        var phone        = req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req.get("network") == null ? null : req.get("network").toString();
        var network      = resolveNetwork(requestedNet, phone);

        log.info("initDeposit: userId='{}' amount={} pesewas={} ref='{}' requestedNetwork='{}' resolvedNetwork='{}'",
                user.getId(), amount, amountPesewas, reference, requestedNet, network);

        var response = akwapayCreateIntent(
                amountPesewas,
                reference,
                user.getEmail(),
                phone,
                network,
                frontendUrl + "/app/wallet?payment=success",
                Map.of("userId", user.getId().toString(), "purpose", "deposit")
        );

        log.info("initDeposit: intent='{}' status='{}' next_action='{}' for userId='{}'",
                response.get("id"), response.get("status"),
                nextActionType(response), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    @PostMapping("/api/user/upgrade-to-admin/akwapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var reference = buildReference(REF_PREFIX_ADMIN, user.getId());

        var phone        = req == null || req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req == null || req.get("network") == null ? null : req.get("network").toString();
        var network      = resolveNetwork(requestedNet, phone);

        log.info("initAdminUpgrade: userId='{}' email='{}' ref='{}' requestedNetwork='{}' resolvedNetwork='{}'",
                user.getId(), user.getEmail(), reference, requestedNet, network);

        var response = akwapayCreateIntent(
                ADMIN_UPGRADE_FEE_PESEWAS,
                reference,
                user.getEmail(),
                phone,
                network,
                frontendUrl + "/app/upgrade?payment=success",
                Map.of(
                        "userId",        user.getId().toString(),
                        "upgradeIntent", UPGRADE_INTENT_ADMIN
                )
        );

        log.info("initAdminUpgrade: intent='{}' status='{}' for userId='{}'",
                response.get("id"), response.get("status"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Status probe (fallback only) ─────────────────────────────────────────

    /**
     * Lets the frontend poll while it waits, purely so the UI can say something
     * better than a spinner.
     *
     * THIS MUST NOT CREDIT ANYTHING. Money moves on the webhook and only on the
     * webhook. A user returning to your return_url proves their browser
     * finished a redirect, nothing more, and anyone reading your JS can hit
     * that URL directly.
     */
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

        log.info("status: userId='{}' intent='{}' status='{}'",
                user.getId(), intentId, result.get("status"));

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Receives AkwaPay merchant events.
     *
     * CONFIRMED PAYLOAD SHAPE (from official AkwaPay worker source):
     *
     *   Top level:  id, type, sequence, created_at, data{}
     *   data{}:     intent_id, amount (integer pesewas), currency, reference, status
     *
     * The `data.reference` field is exactly the string we sent at intent-creation
     * time, which is how we recover the userId. There is no `metadata` field
     * on the webhook — metadata is stored server-side only.
     *
     * IDEMPOTENCY: delivery is at-least-once. We return 200 on every path that
     * is not a hard error so AkwaPay stops retrying. Duplicate `succeeded`
     * events are deduped inside WalletService.credit() via the reference (409 →
     * we catch and skip). Foreign references (not minted by this controller)
     * return 200 "Ignored" — we do NOT return 400, because that would put them
     * back into AkwaPay's retry queue forever for something we will never handle.
     */
    @PostMapping("/api/webhooks/akwapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature",   required = false) String signature,
            @RequestHeader(value = "X-AkwaPay-Event-Type",  required = false) String headerEventType,
            @RequestHeader(value = "X-AkwaPay-Delivery-Id", required = false) String deliveryId,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            // MUST be the raw bytes — re-serialising parsed JSON changes key
            // order and whitespace and the HMAC will never match.
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
            log.warn("AkwaPay webhook: invalid signature, delivery='{}' eventType='{}'",
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

            // ── Only act on succeeded ──────────────────────────────────────────
            //
            // Delivery is at-least-once — the same event WILL arrive twice (on
            // their retry, on a network blip, on our own 500). Everything except
            // `payment_intent.succeeded` is a no-op here. `succeeded` is deduped
            // on `reference` inside WalletService (409 → skip), so replays are
            // harmless.
            //
            // We return 200 (not 4xx) for every non-succeeded type so AkwaPay
            // marks the delivery done and does not retry it. Returning 4xx on an
            // event we simply don't handle would put it in the retry queue
            // forever, burning their retry budget and generating noise alerts.
            if (!"payment_intent.succeeded".equals(eventType)) {
                log.info("AkwaPay webhook: ignoring event type='{}' (event='{}')", eventType, eventId);
                return ResponseEntity.ok("Ignored");
            }

            // ── Parse data{} block ────────────────────────────────────────────
            //
            // Confirmed shape from paymentIntentEvent() in webhook-sender.ts:
            //   data.intent_id  — the pi_... public ID
            //   data.amount     — integer pesewas
            //   data.currency   — "GHS"
            //   data.reference  — exactly what we sent at intent-creation time
            //   data.status     — "succeeded" (eventType.split('.')[1])
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("AkwaPay webhook: no data block on event='{}'", eventId);
                return ResponseEntity.status(400).body("Missing data");
            }

            // data.reference is the ONLY field that lets us route this event to
            // a user. It is set by us at intent-creation time and echoed back
            // verbatim. If it is absent or blank, this event is unroutable.
            var reference = data.get("reference") == null ? null : data.get("reference").toString();
            var intentId  = data.get("intent_id") == null ? "" : data.get("intent_id").toString();

            if (reference == null || reference.isBlank()) {
                log.error("AkwaPay webhook: no reference on event='{}' intent='{}'", eventId, intentId);
                return ResponseEntity.status(400).body("Missing reference");
            }

            // data.amount is integer pesewas — convert to GHS for our wallet.
            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            // ── Route by reference prefix ──────────────────────────────────────
            //
            // References NOT minted by this controller (e.g. test charges created
            // directly in the AkwaPay dashboard, or charges from another service
            // sharing this merchant account) will not match our prefixes.
            //
            // We return 200 "Ignored" — NOT 400 — because a 4xx would put the
            // event back in AkwaPay's retry queue (10s, 1m, 5m, 30m, 2h, 6h,
            // 12h, 24h → dead-letter). Since we will never be able to handle a
            // foreign reference, retrying is pure noise. 200 tells AkwaPay
            // "received, nothing to do" and they stop.
            var parsed = parseReference(reference);
            if (parsed == null) {
                log.warn("AkwaPay webhook: unrecognised reference '{}' on event='{}' intent='{}' " +
                                "amount={} — returning 200 so AkwaPay stops retrying an unroutable event. " +
                                "If this is a real customer payment, credit it manually.",
                        reference, eventId, intentId, amount);
                return ResponseEntity.ok("Ignored: foreign reference");
            }

            if (parsed.adminUpgrade()) {
                handleAdminUpgrade(parsed.userId(), reference, amount, intentId);
            } else {
                handleDeposit(parsed.userId(), reference, amount, intentId);
            }

        } catch (ApiException e) {
            log.error("AkwaPay webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            // Non-2xx puts us back in AkwaPay's retry schedule
            // (10s, 1m, 5m, 30m, 2h, 6h, 12h, 24h → dead-letter), which is what
            // we want for a transient DB failure. A payment that failed to credit
            // due to a DB hiccup WILL be retried and credited on the next attempt.
            log.error("AkwaPay webhook: unexpected error — will be retried", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission to their
     * referrer (if they were referred).
     *
     * Commission structure:
     *   The referring admin earns a percentage of every deposit made by users
     *   they referred. The rate is stored on the Referral entity and defaults
     *   to 70% of the platform commission. Resolution happens entirely inside
     *   ReferralService.attributeCommission(). This method just triggers it.
     *
     * Flow:
     *   deposit amount → walletService.credit          (user wallet)
     *                  → referralService.attribute…    (admin affiliate wallet)
     */
    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String intentId) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}' intent='{}'",
                userId, amount, ref, intentId);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "akwapay", "reference", ref, "intentId", intentId));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'", amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return;
            }
            throw ex;
        }

        // ── Attribute commission to referring admin ──
        // The admin's rate (default 70%) is resolved from the Referral entity
        // inside ReferralService. No rate logic lives here.
        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit={} adminRate={}",
                    userId, amount, ADMIN_COMMISSION_RATE);
        } catch (Exception ex) {
            // Never block a deposit because of a commission failure.
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate",
                    userId, ex);
        }
    }

    /**
     * Handles an admin upgrade payment.
     *
     * Steps:
     *   1. Validate amount >= GHS 200
     *   2. Promote user to ADMIN + initialise their referral link at 70%
     *   3. Record an audit transaction (AkwaPay collected the funds externally)
     *   4. Create onboarding chat with Super Admin for commission confirmation
     *
     * Commission structure note:
     *   The new admin's default rate is set to 70% inside
     *   UserService.upgradeToAdmin(). Super Admin can adjust it via the
     *   onboarding chat created in step 4.
     */
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
            // upgradeToAdmin sets the new admin's commission rate to 70% on the
            // Referral entity.
            userService.upgradeToAdmin(userId, ref);
            log.info("handleAdminUpgrade: userId='{}' promoted to ADMIN with {}% commission ref='{}'",
                    userId,
                    ADMIN_COMMISSION_RATE.multiply(BigDecimal.valueOf(100)).toPlainString(),
                    ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleAdminUpgrade: duplicate ref='{}' — skipping", ref);
                return;
            }
            throw ex;
        }

        // Audit record — AkwaPay collected GHS 200 externally, no wallet debit.
        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "akwapay", "reference", ref, "intentId", intentId));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        // Onboarding chat so Super Admin can confirm/adjust the 70% rate.
        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── Network resolution ────────────────────────────────────────────────────

    /**
     * Decides which network string ("MTN" | "TELECEL" | "AIRTELTIGO") to send
     * to AkwaPay, since it's now mandatory whenever method=mobile_money.
     *
     * Order of preference:
     *   1. Whatever the client explicitly sent — the user picked it, trust it.
     *   2. A best-effort guess from the phone number's prefix.
     *   3. Neither worked → 400, ask the user to pick one. This is
     *      deliberately a 400 raised HERE, before we ever call AkwaPay, so the
     *      frontend gets a specific, actionable message instead of a generic
     *      500 bubbled up from AkwaPay's own rejection.
     */
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

        log.warn("resolveNetwork: could not resolve a network from phone='{}' and no network was selected",
                phone == null ? "null" : "<redacted>");
        throw ApiException.badRequest(
                "We couldn't tell which network that number is on. Please choose MTN, Telecel, or AirtelTigo.");
    }

    /**
     * Best-effort guess of a Ghana MoMo number's network from its prefix.
     * Returns empty when the number doesn't normalize to a recognisable Ghana
     * mobile shape, or its prefix isn't in {@link #GH_NETWORK_PREFIXES}.
     *
     * This is a courtesy shortcut, not a source of truth — ranges get
     * reassigned/ported occasionally, and it will never be as reliable as the
     * user just picking their own network. {@link #resolveNetwork} always
     * prefers an explicit client selection over this.
     */
    private Optional<String> detectNetworkFromPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();

        var digits = phone.replaceAll("[^\\d]", "");

        // Normalise +233XXXXXXXXX / 233XXXXXXXXX / 0XXXXXXXXX to 0XXXXXXXXX.
        String local;
        if (digits.startsWith("233") && digits.length() == 12) {
            local = "0" + digits.substring(3);
        } else if (digits.length() == 10 && digits.startsWith("0")) {
            local = digits;
        } else {
            return Optional.empty();
        }

        var prefix = local.substring(0, 3);
        var network = GH_NETWORK_PREFIXES.get(prefix);
        return Optional.ofNullable(network);
    }

    // ─── AkwaPay API helper ───────────────────────────────────────────────────

    /**
     * Calls POST /v1/payment_intents and returns the FULL response map.
     *
     * The response is returned whole and unmodified. The frontend decides
     * between `checkout_url` (handles every branch) and reading
     * `next_action.type` itself. Do not flatten it here — AkwaPay adds
     * next_action variants without warning, and a flattening layer silently
     * drops the ones it does not know about.
     *
     * Resilience:
     *   - Times out after 15s so the caller thread is never held indefinitely.
     *   - Retries up to 2 times on transient network errors only.
     *   - Idempotency-Key is a fresh UUID per attempt; the unique `reference`
     *     is what actually prevents a double charge.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> akwapayCreateIntent(int amountPesewas,
                                                    String reference,
                                                    String email,
                                                    String phone,
                                                    String network,
                                                    String returnUrl,
                                                    Map<String, Object> metadata) {

        var customer = new HashMap<String, Object>();
        if (email != null && !email.isBlank()) customer.put("email", email);
        if (phone != null && !phone.isBlank()) customer.put("phone", phone);

        var body = new HashMap<String, Object>();
        body.put("amount",     amountPesewas);   // integer pesewas — never a decimal
        body.put("currency",   "GHS");
        body.put("reference",  reference);
        body.put("return_url", returnUrl);
        body.put("metadata",   metadata);        // stored server-side; NOT echoed on the webhook
        if (!customer.isEmpty()) body.put("customer", customer);

        // `method` AND `network` are both required — see class note #8.
        // `network` arrives here already resolved by resolveNetwork(), which
        // throws a 400 before we ever get this far if it couldn't determine one.
        body.put("method",  "mobile_money");
        body.put("network", network.toUpperCase());

        var idempotencyKey = UUID.randomUUID().toString();

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
                                    log.error("AkwaPay API error: status={} ref='{}' body={}",
                                            clientResponse.statusCode(), reference, errBody);
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
                            log.error("AkwaPay API unreachable after {} retries", akwapayRetryAttempts, ex);
                            return new RuntimeException("AkwaPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("AkwaPay returned an empty response.");
        }

        var status = String.valueOf(result.get("status"));
        log.info("akwapayCreateIntent: intent='{}' status='{}' ref='{}'",
                result.get("id"), status, reference);

        // AkwaPay signals failure with an `error` object, not a status boolean.
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

    // ─── Reference encoding / decoding ────────────────────────────────────────

    /**
     * Builds a reference string that encodes the userId and intent type so the
     * webhook handler can recover both with no other context.
     *
     * Format:  sbdep_<32-hex-userId>_<8-hex-nonce>
     *          sbadm_<32-hex-userId>_<8-hex-nonce>
     *
     * The 32-hex userId is the UUID with dashes stripped. The 8-hex nonce is the
     * low 32 bits of System.nanoTime(), zero-padded. The nonce ensures uniqueness
     * across retries (AkwaPay returns 409 on a duplicate reference).
     *
     * NEVER change the prefix strings or the layout — the webhook parser depends
     * on fixed offsets. A format change makes all in-flight intents unroutable.
     */
    private String buildReference(String prefix, UUID userId) {
        var nonce = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        return prefix
                + userId.toString().replace("-", "")
                + "_"
                + String.format("%8s", nonce).replace(' ', '0');
    }

    /**
     * Parses a reference string back into userId + intent type.
     *
     * Returns null when the reference was not minted by this controller — which
     * means the webhook cannot be routed and should be acknowledged (200) without
     * taking any action.
     *
     * The most common cause of a null return is a charge created directly in the
     * AkwaPay dashboard (for testing or manual top-ups) rather than through
     * /api/wallet/deposit/akwapay/init. Those charges have arbitrary references
     * like "test-002" that don't follow our prefix+userId format.
     */
    private ParsedRef parseReference(String reference) {
        boolean adminUpgrade;
        if (reference.startsWith(REF_PREFIX_DEPOSIT))      adminUpgrade = false;
        else if (reference.startsWith(REF_PREFIX_ADMIN))   adminUpgrade = true;
        else return null;   // foreign reference — not minted by us

        // Both prefixes are 6 characters, so we strip the same length regardless.
        var rest = reference.substring(REF_PREFIX_DEPOSIT.length());
        if (rest.length() < 32) return null;

        var hex = rest.substring(0, 32);
        try {
            var userId = UUID.fromString(
                    hex.substring(0, 8)  + "-" +
                            hex.substring(8, 12) + "-" +
                            hex.substring(12, 16) + "-" +
                            hex.substring(16, 20) + "-" +
                            hex.substring(20, 32));
            return new ParsedRef(userId, adminUpgrade);
        } catch (IllegalArgumentException e) {
            log.warn("parseReference: malformed userId segment in ref='{}'", reference);
            return null;
        }
    }

    private record ParsedRef(UUID userId, boolean adminUpgrade) {}

    // ─── Signature verification ───────────────────────────────────────────────

    /**
     * Verifies the X-AkwaPay-Signature header on an inbound webhook.
     *
     * Header format:  X-AkwaPay-Signature: t=1754049600,v1=5f3c9a...
     * Signed value:   HMAC-SHA256( "{timestamp}.{rawBody}", whsec )  hex-encoded
     *
     * Three things that are easy to get wrong, all handled here:
     *
     *   1. HMAC is over the RAW bytes, before any JSON parse/re-serialise.
     *      Re-serialising changes key order and whitespace; the HMAC will not
     *      match. This is why rawBody is read from the InputStream directly in
     *      the webhook() handler and passed here unchanged.
     *
     *   2. Timestamps outside ±5 min are rejected. This stops a replay attack
     *      where someone captures a real `succeeded` event and re-sends it later
     *      to credit a user a second time.
     *
     *   3. Constant-time compare via MessageDigest.isEqual(). String.equals()
     *      leaks signature bytes one comparison at a time (timing side-channel).
     *
     * This implementation matches the reference verifier in the official AkwaPay
     * merchant-api.md documentation exactly.
     */
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

            // Signed string is "{timestamp}.{rawBody}" — matches AkwaPay docs exactly.
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);

            var expected = HexFormat.of().formatHex(mac.doFinal());

            // Constant-time comparison — must not short-circuit on first mismatch.
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("AkwaPay webhook: signature verification threw unexpectedly", e);
            return false;
        }
    }
}
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
import reactor.core.publisher.Mono;
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
 * RELIABILITY GUARANTEE — READ THIS FIRST
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Webhooks are the fast path but are NOT the only path, and in this deployment
 * they have never once fired. Every credit applied in production so far has
 * come from the sweep below. Treat the sweep as the primary mechanism and the
 * webhook as an optimisation, not the other way round.
 *
 * Every intent this controller creates is written to the
 * `akwapay_pending_intents` table. A scheduled sweep
 * ({@link #reconcilePendingIntents}) polls AkwaPay directly every 2 minutes for
 * any row older than 3 minutes and applies the credit if AkwaPay says
 * `succeeded`. So as long as AkwaPay collected the money, the user WILL be
 * credited — even if:
 *   - the webhook never arrives at all (the current reality)
 *   - the webhook secret is wrong and we reject every delivery
 *   - this pod restarts mid-payment (the table survives; a map did not)
 *   - AkwaPay's own webhook worker is down
 *
 * The two paths are safe to run concurrently:
 *   - {@link WalletService#credit} dedupes on `reference` and returns 409 on a
 *     duplicate. Both the webhook handler and the sweep catch that 409 and skip
 *     silently. No double-credit is possible, whichever wins the race.
 *   - Whichever settles first deletes the row; the other's delete is a no-op.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THE PENDING LEDGER IS A TABLE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * It was a ConcurrentHashMap. That is fine until the process restarts, which on
 * a PaaS is constantly — the 2026-08-12 deploy log shows three restarts inside
 * ninety seconds. Two facts make an in-memory ledger unsafe here:
 *
 *   1. AkwaPay has NO endpoint that lists your payment intents. You can only
 *      GET one by id. An intent whose id we forget can never be asked about
 *      again — there is no way to rediscover it.
 *   2. The webhook does not fire. The fallback that justified holding this in
 *      memory is not operating.
 *
 * Together: intent created → deploy 30s later → map empties → no webhook ever
 * comes → AkwaPay collected the customer's money and this service holds no
 * record that it owes them anything. Silent and permanent.
 *
 * See {@link AkwaPayPendingIntent}.
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
 *    The whsec is minted by POST /v1/webhook_endpoints and shown ONCE.
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
 *
 * 7. `unknown` IS A REAL STATUS AND IS NOT A FAILURE.
 *    It means AkwaPay asked a gateway to move money and got no clear answer.
 *    They poll until it resolves and then fire the webhook. Never re-charge on
 *    it, never fail the deposit on it — you will double-debit real people.
 *
 * 8. `method` AND `network` ARE BOTH REQUIRED WHEN THE METHOD IS mobile_money.
 *      a) omitting `method` → 400 invalid_method
 *      b) method=mobile_money with no `network` → 400 invalid_network
 *    This controller only originates GHS mobile-money deposits, so `method` is
 *    hardcoded in {@link #akwapayCreateIntent}. `network` is resolved with a
 *    two-step fallback in {@link #resolveNetwork}.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WEBHOOK PAYLOAD — CONFIRMED FROM OFFICIAL AKWAPAY SOURCE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * From packages/worker/src/webhook-sender.ts → paymentIntentEvent():
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
 * IDEMPOTENCY — delivery is at-least-once. The same event WILL arrive twice.
 * Deduped inside WalletService.credit() via the reference (409 → skip).
 */
@Slf4j
@EnableScheduling
@RestController
@RequiredArgsConstructor
public class AkwaPayController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * The actual per-admin rate lives on the Referral entity and is resolved
     * inside ReferralService.attributeCommission(). This constant is for
     * logging only — do not branch on it.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // ─── Reference encoding ───────────────────────────────────────────────────
    //
    //     sbdep_<32-hex userId>_<8-hex nonce>     wallet deposit
    //     sbadm_<32-hex userId>_<8-hex nonce>     admin upgrade
    //
    // The nonce makes the reference unique per attempt (AkwaPay requires
    // account-wide uniqueness and returns 409 duplicate_reference otherwise),
    // while the userId sits at a fixed offset so parsing never depends on
    // splitting a UUID that contains dashes.
    //
    // References created outside this controller (e.g. dashboard test charges)
    // match neither prefix. parseReference() returns null for those and the
    // webhook handler returns 200 "Ignored: foreign reference", so AkwaPay
    // stops retrying something we can never route.

    private static final String REF_PREFIX_DEPOSIT = "sbdep_";
    private static final String REF_PREFIX_ADMIN   = "sbadm_";

    /** How long to wait for AkwaPay to respond before timing out. */
    private final Duration akwapayTimeout = Duration.ofSeconds(15);

    /**
     * Retries on transient network failures only. AkwaPay 4xx/5xx are mapped to
     * RuntimeException by the onStatus handler and excluded from the retry
     * predicate — retrying a 400 burns time, retrying a 402 would be wrong.
     */
    private final long akwapayRetryAttempts = 2;

    /** Replay window for webhook signatures, per AkwaPay docs (5 minutes). */
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;

    /**
     * How long the webhook gets to settle an intent before the sweep touches it.
     *
     * Five seconds. Short enough that a customer who approves quickly is
     * credited almost immediately; long enough that we are not polling an
     * intent that AkwaPay only just created.
     */
    private static final Duration SWEEP_HEAD_START = Duration.ofSeconds(5);

    /**
     * How often the sweep ticks. This is NOT how often any one intent is
     * polled — see {@link #pollIntervalFor}.
     *
     * Documentation only: the @Scheduled annotation below needs a literal and
     * cannot read this. Change both together.
     *
     * An idle tick is one indexed query returning nothing and no network calls
     * at all, so a fast tick costs essentially nothing when there are no
     * payments in flight.
     */
    private static final long SWEEP_INTERVAL_MS = 5_000;

    /**
     * TIERED POLLING — the reason a deposit settles in seconds rather than
     * minutes.
     *
     * A flat interval forces a bad trade: fast enough for a good checkout
     * experience means hammering AkwaPay for hours on intents the customer
     * abandoned. So the poll rate decays with the intent's age instead.
     *
     * The shape follows how people actually pay. Almost every real payment
     * resolves in the first two minutes — that is someone with the prompt in
     * front of them. Past ten minutes they have walked away, and the only
     * reason to keep asking is the small chance a stuck gateway resolves late.
     *
     *     age < 2 min    poll every tick (5s)    → the common case, near-instant
     *     2–10 min       every 30s               → slow approvals, gateway lag
     *     10–60 min      every 2 min             → probably abandoned
     *     > 60 min       every 10 min            → long-tail gateway recovery
     *
     * Cost for one abandoned intent over the full 24h window is ~24 + 16 + 25 +
     * 138 ≈ 200 calls, against ~2,880 at a flat 30s. Twelve times cheaper AND
     * six times faster in the case that actually matters.
     */
    private static final Duration TIER_HOT_UNTIL    = Duration.ofMinutes(2);
    private static final Duration TIER_WARM_UNTIL   = Duration.ofMinutes(10);
    private static final Duration TIER_COOL_UNTIL   = Duration.ofMinutes(60);

    private static final Duration POLL_EVERY_HOT    = Duration.ofSeconds(5);
    private static final Duration POLL_EVERY_WARM   = Duration.ofSeconds(30);
    private static final Duration POLL_EVERY_COOL   = Duration.ofMinutes(2);
    private static final Duration POLL_EVERY_COLD   = Duration.ofMinutes(10);

    /** After this long with no resolution, stop polling and give up on a row. */
    private static final Duration ABANDON_AFTER = Duration.ofHours(24);

    /**
     * Ghana MoMo number → network, by leading digits after normalising to the
     * local 0XXXXXXXXX shape. Best-effort fallback ONLY — the NCA reassigns and
     * ports ranges, so this drifts. It exists to skip a tap in the common case;
     * it is never the only way through (see {@link #resolveNetwork}), and it is
     * a flat prefix table rather than a "smart" parser so fixing a stale range
     * is a one-line diff.
     */
    private static final Map<String, String> GH_NETWORK_PREFIXES = new LinkedHashMap<>();
    static {
        for (var p : new String[]{"024", "025", "053", "054", "055", "059"}) GH_NETWORK_PREFIXES.put(p, "MTN");
        for (var p : new String[]{"020", "050"})                             GH_NETWORK_PREFIXES.put(p, "TELECEL");
        for (var p : new String[]{"026", "027", "056", "057"})               GH_NETWORK_PREFIXES.put(p, "AIRTELTIGO");
    }

    private final WalletService                    walletService;
    private final UserService                      userService;
    private final AdminUpgradeChatService          adminUpgradeChatService;
    private final ReferralService                  referralService;
    private final AkwaPayPendingIntentRepository   pendingIntents;
    private final WebClient.Builder                webClientBuilder;
    private final ObjectMapper                     objectMapper;

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
                frontendUrl + "/wallet?payment=success",
                Map.of("userId", user.getId().toString(), "purpose", "deposit")
        );

        var intentId = String.valueOf(response.get("id"));
        recordPending(reference, intentId, user.getId(), amount, false);

        log.info("initDeposit: intent='{}' status='{}' next_action='{}' for userId='{}'",
                intentId, response.get("status"), nextActionType(response), user.getId());

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

        // GHS, not pesewas — the sweep credits this value directly, and
        // handleAdminUpgrade takes GHS the same way the webhook path does.
        var upgradeAmountGhs = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_PESEWAS)
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

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

        var intentId = String.valueOf(response.get("id"));
        recordPending(reference, intentId, user.getId(), upgradeAmountGhs, true);

        log.info("initAdminUpgrade: intent='{}' status='{}' for userId='{}'",
                intentId, response.get("status"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Writes the pending row.
     *
     * Deliberately swallows persistence failures. The intent already exists at
     * AkwaPay by the time we get here — throwing now would return an error to a
     * customer whose payment is genuinely in flight, and they would try again
     * and pay twice. A failure here degrades us to webhook-only for this one
     * payment, which is logged at ERROR so it can be credited by hand.
     */
    private void recordPending(String reference, String intentId, UUID userId,
                               BigDecimal amountGhs, boolean adminUpgrade) {
        try {
            // lastCheckedAt starts null — "never polled", which the sweep treats
            // as due the moment the head start elapses.
            pendingIntents.save(new AkwaPayPendingIntent(
                    reference, intentId, userId, amountGhs, adminUpgrade, Instant.now(), 0, null));
            log.info("recordPending: ref='{}' intent='{}' persisted — sweep will reconcile if the webhook is lost",
                    reference, intentId);
        } catch (Exception e) {
            log.error("recordPending: FAILED to persist ref='{}' intent='{}' userId='{}' amount={} — " +
                            "this payment can now only be credited by webhook or by hand. Investigate.",
                    reference, intentId, userId, amountGhs, e);
        }
    }

    // ─── Status probe (read only) ─────────────────────────────────────────────

    /**
     * Lets the frontend poll while it waits, purely so the UI can say something
     * better than a spinner.
     *
     * THIS MUST NOT CREDIT ANYTHING. A user arriving at return_url proves their
     * browser finished a redirect and nothing more, and anyone reading the JS
     * can call this endpoint directly. Money moves in the webhook handler and
     * the sweep, both of which verify against AkwaPay first.
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



    @PostMapping("/api/wallet/deposit/akwapay/otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var intentId     = String.valueOf(req.get("intentId"));
        var clientSecret = String.valueOf(req.get("clientSecret"));
        var otp          = String.valueOf(req.get("otp"));

        log.info("submitOtp: userId='{}' intent='{}'", user.getId(), intentId);

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .post()
                .uri(baseUrl + "/v1/checkout/" + intentId + "/validate?cs=" + clientSecret)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("otp", otp))
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("submitOtp: AkwaPay error status={} body={}", r.statusCode(), body);
                            return new RuntimeException("AkwaPay returned " + r.statusCode() + ": " + body);
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        log.info("submitOtp: intent='{}' result='{}'", intentId, result);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Receives AkwaPay merchant events.
     *
     * NOTE: as of this writing no delivery has ever been observed in
     * production. If you are debugging a missing credit, check the sweep logs
     * first — that is where settlement actually happens today. If you never see
     * a line starting "AkwaPay webhook: event=", the endpoint is not registered
     * (POST /v1/webhook_endpoints) or AKWAPAY_WEBHOOK_SECRET is wrong.
     *
     * We return 200 on every path that is not a hard error, so AkwaPay stops
     * retrying. Foreign references get 200 "Ignored" rather than 400 — a 400
     * would put them back in the retry queue forever for something we will
     * never handle.
     */
    @PostMapping("/api/webhooks/akwapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature",   required = false) String signature,
            @RequestHeader(value = "X-AkwaPay-Event-Type",  required = false) String headerEventType,
            @RequestHeader(value = "X-AkwaPay-Delivery-Id", required = false) String deliveryId,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            // MUST be the raw bytes. Re-serialising parsed JSON changes key
            // order and whitespace, and the HMAC will never match.
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

            // Webhook beat the sweep. Drop the row so the sweep doesn't re-poll
            // AkwaPay for something already settled.
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

    /**
     * Ticks every {@link #SWEEP_INTERVAL_MS}ms. Each pending row is polled only
     * when it is due for its age band ({@link #pollIntervalFor}), so a
     * just-created intent is checked every 5 seconds while an hour-old one is
     * checked every 10 minutes.
     *
     * Worst case from the customer approving to being credited is one tick plus
     * the status call — around 5 seconds.
     *
     * This is what actually settles payments in this deployment. Because the
     * rows are in Postgres rather than a field on this bean, a restart mid-sweep
     * loses nothing: the next cycle on any instance picks the same rows up.
     *
     * Safety:
     *   - Both paths call the SAME handleDeposit / handleAdminUpgrade, which
     *     delegate to WalletService.credit(), which returns 409 on a duplicate
     *     reference. No double-credit is possible.
     *   - `processing` / `unknown` / `requires_action` rows are left in place
     *     and re-checked next cycle. `unknown` in particular is NOT a failure.
     *   - Rows older than {@link #ABANDON_AFTER} are deleted; those are intents
     *     the customer never completed.
     *   - Poll rate decays with age, so an abandoned intent costs ~200 calls
     *     across the full 24h window rather than thousands.
     */
    //
    // NOTE: this literal must stay in step with SWEEP_INTERVAL_MS above.
    //
    // It cannot reference the constant. @Scheduled needs a compile-time
    // constant or a property placeholder; a SpEL bean lookup like
    // "#{@akwaPayController.sweepIntervalMs}" makes this bean depend on
    // itself while it is still being constructed, and Spring rejects the
    // whole context with "Expression parsing failed" plus a one-node
    // dependency cycle. The application will not start at all.
    //
    // If you want it configurable, use a property instead:
    //     @Scheduled(fixedDelayString = "${app.akwapay.sweep-interval-ms:5000}")
    @Scheduled(fixedDelay = 5_000)
    public void reconcilePendingIntents() {
        var cutoff = Instant.now().minus(SWEEP_HEAD_START);

        var stale = pendingIntents.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        if (stale.isEmpty()) return;

        // Only the rows actually due this tick. Logging the count of DUE rows
        // rather than of all pending rows keeps the log readable — at a 5s tick
        // the alternative is the same line twelve times a minute saying nothing
        // happened.
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

    /**
     * Whether this intent is due to be polled now, per its age band.
     *
     * Never polled before → always due. That is the first check after the head
     * start elapses, and it is the one that settles the overwhelming majority
     * of real payments.
     */
    private boolean isDue(AkwaPayPendingIntent intent, Instant now) {
        var last = intent.getLastCheckedAt();
        if (last == null) return true;
        return last.plus(pollIntervalFor(intent, now)).isBefore(now);
    }

    /**
     * How often to poll an intent of this age. See the TIER_* constants for the
     * reasoning behind the shape.
     */
    private Duration pollIntervalFor(AkwaPayPendingIntent intent, Instant now) {
        var age = Duration.between(intent.getCreatedAt(), now);
        if (age.compareTo(TIER_HOT_UNTIL)  < 0) return POLL_EVERY_HOT;
        if (age.compareTo(TIER_WARM_UNTIL) < 0) return POLL_EVERY_WARM;
        if (age.compareTo(TIER_COOL_UNTIL) < 0) return POLL_EVERY_COOL;
        return POLL_EVERY_COLD;
    }

    /**
     * Polls AkwaPay for one pending row and acts on the result.
     *
     * Terminal (row deleted):
     *   succeeded                                → credit the user
     *   failed / declined / cancelled / expired  → nothing to credit
     *   older than ABANDON_AFTER                 → customer never completed it
     *
     * Non-terminal (row kept, retried next cycle):
     *   processing / unknown / requires_action   → still in flight
     *   network error / timeout                  → AkwaPay unreachable
     */
    private void reconcileOne(AkwaPayPendingIntent intent) {
        var ref = intent.getReference();

        if (intent.getCreatedAt().isBefore(Instant.now().minus(ABANDON_AFTER))) {
            log.warn("reconcile: abandoning ref='{}' intent='{}' after {}h with no settlement",
                    ref, intent.getIntentId(), ABANDON_AFTER.toHours());
            deletePending(ref, "abandoned after " + ABANDON_AFTER.toHours() + "h");
            return;
        }

        // Mark BEFORE the network call. If the call hangs or the pod dies
        // mid-flight, the row is already stamped and will not be re-polled on
        // the very next tick — which at a 5s cadence would otherwise pile up
        // duplicate in-flight requests against AkwaPay for the same intent.
        try {
            intent.markChecked(Instant.now());
            pendingIntents.save(intent);
        } catch (Exception e) {
            // Diagnostics only — never let a bookkeeping write stop a settlement.
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
                            log.error("reconcile: AkwaPay status check error for ref='{}' status={} body={}",
                                    ref, r.statusCode(), body);
                            return new RuntimeException("AkwaPay returned " + r.statusCode());
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .onErrorResume(e -> {
                    log.warn("reconcile: status check failed for ref='{}' intent='{}' — will retry next sweep: {}",
                            ref, intent.getIntentId(), e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (result == null) {
            // Network error or timeout — already logged. Leave the row alone.
            return;
        }

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase();
        log.info("reconcile: ref='{}' intent='{}' akwapayStatus='{}' attempt={}",
                ref, intent.getIntentId(), akwapayStatus, intent.getAttempts());

        switch (akwapayStatus) {
            case "succeeded" -> {
                // Same code path the webhook uses. credit() dedupes on
                // reference, so this stays safe even if a webhook lands
                // seconds later.
                log.info("reconcile: ref='{}' succeeded on sweep — applying credit now", ref);
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
                // processing / unknown / requires_action / anything unfamiliar.
                // Still in flight; the safe direction is always "keep waiting",
                // never "tell them it failed".
                    log.info("reconcile: ref='{}' status='{}' — still in flight, next check in {}s",
                            ref, akwapayStatus, pollIntervalFor(intent, Instant.now()).toSeconds());
        }
    }

    /**
     * Removes a settled or abandoned row.
     *
     * Failures are logged, not thrown. By the time this is called the credit has
     * already been applied; a stuck row only causes a redundant status poll next
     * cycle, and that poll finds a duplicate reference and skips. Throwing here
     * would roll back a successful credit, which is far worse.
     */
    private void deletePending(String reference, String why) {
        try {
            if (pendingIntents.existsById(reference)) {
                pendingIntents.deleteById(reference);
                log.info("deletePending: ref='{}' removed from pending ledger ({})", reference, why);
            }
        } catch (Exception e) {
            log.warn("deletePending: could not remove ref='{}' ({}) — harmless, sweep will re-check and skip: {}",
                    reference, why, e.getMessage());
        }
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission to their
     * referrer (if they were referred).
     *
     * Called by BOTH the webhook handler AND the sweep. Safe concurrently —
     * WalletService.credit() returns 409 on a duplicate reference; we catch it
     * and skip, so no double-credit is possible whichever wins the race.
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

        // ── Attribute commission to the referring admin ──
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
     * Handles an admin upgrade payment. Called by BOTH the webhook handler and
     * the sweep; upgradeToAdmin() returns 409 on a duplicate reference, which
     * we catch and skip.
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

        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "akwapay", "reference", ref, "intentId", intentId));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── Network resolution ────────────────────────────────────────────────────

    /**
     * Decides which network string ("MTN" | "TELECEL" | "AIRTELTIGO") to send,
     * since it is mandatory whenever method=mobile_money.
     *
     *   1. Whatever the client sent — the user picked it, trust it.
     *   2. A best-effort guess from the phone number's prefix.
     *   3. Neither → 400, ask the user to pick one. Better than letting AkwaPay
     *      reject it and surfacing a generic 500.
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
     * Empty when the number doesn't normalise to a recognisable Ghana mobile
     * shape, or its prefix isn't in {@link #GH_NETWORK_PREFIXES}.
     */
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
     * Calls POST /v1/payment_intents and returns the FULL response map,
     * unmodified. The frontend decides between `checkout_url` (handles every
     * branch) and reading `next_action.type` itself. Do not flatten it here —
     * AkwaPay adds next_action variants without warning and a flattening layer
     * silently drops the ones it does not know about.
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
        body.put("metadata",   metadata);        // stored, but NOT echoed on the webhook
        if (!customer.isEmpty()) body.put("customer", customer);

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
     * Builds a reference encoding the userId and intent type, so the webhook
     * handler can recover both with no other context.
     *
     * NEVER change the prefixes or the layout — the parser depends on fixed
     * offsets, and a format change makes every in-flight intent unroutable.
     */
    private String buildReference(String prefix, UUID userId) {
        var nonce = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        return prefix
                + userId.toString().replace("-", "")
                + "_"
                + String.format("%8s", nonce).replace(' ', '0');
    }

    /**
     * Parses a reference back into userId + intent type. Null when the
     * reference was not minted by this controller, which means the webhook
     * cannot be routed and should be acknowledged without action.
     */
    private ParsedRef parseReference(String reference) {
        boolean adminUpgrade;
        if (reference.startsWith(REF_PREFIX_DEPOSIT))      adminUpgrade = false;
        else if (reference.startsWith(REF_PREFIX_ADMIN))   adminUpgrade = true;
        else return null;

        var rest = reference.substring(REF_PREFIX_DEPOSIT.length()); // both prefixes are 6 chars
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
     * Header format:  X-AkwaPay-Signature: t=1754049600,v1=5f3c9a...
     * Signed value:   HMAC-SHA256( "{timestamp}.{rawBody}", whsec )  hex-encoded
     *
     * Three things that are easy to get wrong, all handled here:
     *   1. HMAC over the RAW bytes, before any parse/re-serialise.
     *   2. Reject timestamps outside ±5 min — this is what stops someone
     *      replaying a captured `succeeded` event.
     *   3. Constant-time compare. equals() on a signature leaks it a byte at a
     *      time.
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
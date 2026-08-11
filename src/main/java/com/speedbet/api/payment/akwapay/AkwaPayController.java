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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AkwaPay payment integration.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RELIABILITY GUARANTEE — READ THIS FIRST
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Webhooks are the fast path but are NOT the only path. This controller
 * maintains an in-memory ledger ({@link #pendingIntents}) of every intent it
 * creates, and a scheduled sweep ({@link #reconcilePendingIntents}) polls
 * AkwaPay directly every 2 minutes for any intent that is still PENDING after
 * 3 minutes. This means: as long as AkwaPay successfully collected the money,
 * the user WILL be credited — even if:
 *   - the webhook never arrived (worker crash on our side or theirs)
 *   - the webhook secret is wrong and we rejected the delivery
 *   - the return_url redirect fired but the webhook was delayed
 *   - anything else that silences the webhook channel
 *
 * The two paths are fully safe to run concurrently:
 *   - {@link WalletService#credit} dedupes on `reference` and returns 409 on
 *     a duplicate. Both the webhook handler and the sweep catch that 409 and
 *     skip silently. No double-credit is possible.
 *   - The sweep marks intents SETTLED so it does not keep polling after one
 *     path has already applied the credit.
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
@EnableScheduling
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

    // ─── In-memory pending intent ledger ──────────────────────────────────────
    //
    // Keyed by reference (our own string, globally unique per attempt).
    // Written the moment we create an intent; removed when it settles or is
    // abandoned. The sweep reads this map every 2 minutes — no DB needed.
    //
    // This survives pod restarts only as long as the JVM is running. On restart,
    // the ledger starts empty and any in-flight intents from before the restart
    // will settle via the webhook (which AkwaPay retries for 24 h) rather than
    // via the sweep. That is acceptable — the webhook is the primary path. The
    // sweep is the safety net for the case where the webhook never arrives at all
    // (e.g. the pod crashed during the very delivery window).
    //
    // If you need cross-restart durability, replace this map with a lightweight
    // DB-backed store (see the companion AkwaPayIntent entity in the design doc).
    // The sweep logic below is identical either way.

    /**
     * Internal record held in {@link #pendingIntents} for each in-flight intent.
     *
     * @param intentId      The pi_... public ID returned by AkwaPay.
     * @param userId        The user who initiated the payment.
     * @param amount        GHS amount (already converted from pesewas).
     * @param adminUpgrade  True for admin upgrade payments; false for deposits.
     * @param createdAt     Wall clock at intent creation — used to decide when
     *                      to abandon after 24 h.
     * @param attempts      How many sweep cycles have already polled AkwaPay for
     *                      this intent.
     */
    private record PendingIntent(
            String    intentId,
            UUID      userId,
            BigDecimal amount,
            boolean   adminUpgrade,
            Instant   createdAt,
            int       attempts
    ) {
        PendingIntent withAttempt() {
            return new PendingIntent(intentId, userId, amount, adminUpgrade, createdAt, attempts + 1);
        }
    }

    /**
     * Live ledger of every intent this controller has created that has not yet
     * settled. Thread-safe; written on every init call, read+removed by the
     * scheduled sweep and by the webhook handler.
     */
    private final ConcurrentHashMap<String, PendingIntent> pendingIntents = new ConcurrentHashMap<>();

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
                frontendUrl + "/wallet?payment=success",
                Map.of("userId", user.getId().toString(), "purpose", "deposit")
        );

        // ── Register in the pending ledger immediately ──────────────────────────
        // The webhook is the fast path. The scheduled sweep is the guarantee.
        // We record the intent NOW so that if the webhook never arrives, the
        // sweep will poll AkwaPay directly and apply the credit itself.
        var intentId = String.valueOf(response.get("id"));
        pendingIntents.put(reference, new PendingIntent(
                intentId, user.getId(), amount, false, Instant.now(), 0));
        log.info("initDeposit: intent='{}' registered in pending ledger for userId='{}' — sweep will reconcile if webhook is lost",
                intentId, user.getId());

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

        // GHS 200 in GHS (not pesewas) — we store this for the sweep, which
        // calls handleAdminUpgrade with the GHS amount, same as the webhook path.
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

        // ── Register in the pending ledger immediately ──────────────────────────
        var intentId = String.valueOf(response.get("id"));
        pendingIntents.put(reference, new PendingIntent(
                intentId, user.getId(), upgradeAmountGhs, true, Instant.now(), 0));
        log.info("initAdminUpgrade: intent='{}' registered in pending ledger for userId='{}' — sweep will reconcile if webhook is lost",
                intentId, user.getId());

        log.info("initAdminUpgrade: intent='{}' status='{}' for userId='{}'",
                intentId, response.get("status"), user.getId());

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

            // ── Remove from pending ledger — webhook beat the sweep ─────────────
            // The sweep checks this map; removing here means it won't re-poll
            // AkwaPay for an intent that just settled cleanly via the webhook.
            var removed = pendingIntents.remove(reference);
            if (removed != null) {
                log.info("AkwaPay webhook: ref='{}' removed from pending ledger after webhook settlement", reference);
            }

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
     * Runs every 2 minutes. For every intent in {@link #pendingIntents} that is
     * older than 3 minutes (giving the webhook a head start), polls AkwaPay
     * directly and applies the credit if AkwaPay says `succeeded`.
     *
     * This is the guarantee that makes the webhook optional rather than a single
     * point of failure. As long as AkwaPay collected the money, the credit will
     * happen — even if:
     *   - the webhook delivery window was missed entirely (pod restart, crash)
     *   - the webhook secret was wrong and we rejected every delivery
     *   - AkwaPay's webhook worker had an outage
     *
     * Safety:
     *   - Both paths call the SAME {@link #handleDeposit} /
     *     {@link #handleAdminUpgrade} methods, which delegate to
     *     {@link WalletService#credit}, which returns 409 on a duplicate
     *     reference. Both callers catch 409 and skip. No double-credit possible.
     *   - Intents still `processing` / `unknown` / `requires_action` are left
     *     PENDING and re-checked next cycle.
     *   - Intents older than 24 h are abandoned — these are almost always
     *     customers who approved the MoMo prompt but the payment expired on the
     *     gateway side. AkwaPay will have already fired `payment_intent.failed`
     *     for them.
     */
    @Scheduled(fixedDelay = 120_000) // every 2 minutes
    public void reconcilePendingIntents() {
        // Give the webhook a 3-minute head start. Most intents settle almost
        // instantly via the webhook; don't poll AkwaPay for those.
        var cutoff = Instant.now().minus(Duration.ofMinutes(3));

        var stale = pendingIntents.entrySet().stream()
                .filter(e -> e.getValue().createdAt().isBefore(cutoff))
                .toList();

        if (stale.isEmpty()) return;

        log.info("reconcile: {} stale pending intent(s) to check", stale.size());

        for (var entry : stale) {
            var ref    = entry.getKey();
            var intent = entry.getValue();
            try {
                reconcileOne(ref, intent);
            } catch (Exception e) {
                log.error("reconcile: unexpected error for ref='{}' intent='{}' — will retry next sweep",
                        ref, intent.intentId(), e);
            }
        }
    }

    /**
     * Polls AkwaPay for a single pending intent and acts on the result.
     *
     * Terminal outcomes:
     *   succeeded → credit the user, remove from ledger
     *   failed / declined / cancelled / expired → log, remove from ledger
     *   > 24 h old → abandon, remove from ledger (payment expired on gateway)
     *
     * Non-terminal outcomes (leave in ledger, retry next cycle):
     *   processing / unknown / requires_action → still in flight, do nothing
     *   network error / timeout → AkwaPay unreachable, try again in 2 min
     */
    private void reconcileOne(String ref, PendingIntent intent) {
        // Abandon after 24 h — these are almost certainly expired/failed intents
        // whose payment_intent.failed webhook we already processed (or will
        // process). Keeping them longer just burns AkwaPay API quota.
        if (intent.createdAt().isBefore(Instant.now().minus(Duration.ofHours(24)))) {
            log.warn("reconcile: abandoning ref='{}' intent='{}' after 24 h with no settlement",
                    ref, intent.intentId());
            pendingIntents.remove(ref);
            return;
        }

        // Bump attempt counter before the network call so a crash mid-call still
        // increments it on the next sweep.
        pendingIntents.put(ref, intent.withAttempt());

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intent.intentId())
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
                            ref, intent.intentId(), e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (result == null) {
            // Network error or timeout — already logged above. Leave in ledger.
            return;
        }

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase();
        log.info("reconcile: ref='{}' intent='{}' akwapayStatus='{}' attempt={}",
                ref, intent.intentId(), akwapayStatus, intent.attempts() + 1);

        switch (akwapayStatus) {
            case "succeeded" -> {
                // Same code path the webhook uses. WalletService.credit() dedupes
                // on reference (409 on repeat), so this is safe even if the real
                // webhook fires seconds later — no double credit either way.
                log.info("reconcile: ref='{}' succeeded on sweep — applying credit now", ref);
                if (intent.adminUpgrade()) {
                    handleAdminUpgrade(intent.userId(), ref, intent.amount(), intent.intentId());
                } else {
                    handleDeposit(intent.userId(), ref, intent.amount(), intent.intentId());
                }
                pendingIntents.remove(ref);
                log.info("reconcile: ref='{}' settled and removed from pending ledger", ref);
            }

            case "failed", "declined", "cancelled", "expired" -> {
                // Payment definitively failed on AkwaPay's side. Nothing to credit.
                // The webhook for payment_intent.failed may or may not have arrived;
                // either way we don't need to act. Log and clear.
                log.warn("reconcile: ref='{}' intent='{}' terminal status='{}' — removing from ledger, no credit applied",
                        ref, intent.intentId(), akwapayStatus);
                pendingIntents.remove(ref);
            }

            default ->
                // processing / unknown / requires_action / anything else —
                // still in flight. AkwaPay will resolve it and fire the webhook,
                // OR the next sweep will catch it. Leave in the ledger.
                    log.info("reconcile: ref='{}' status='{}' — still in flight, will re-check next sweep",
                            ref, akwapayStatus);
        }
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission to their
     * referrer (if they were referred).
     *
     * Called by BOTH the webhook handler AND the reconciliation sweep. Both paths
     * are safe to call concurrently — WalletService.credit() returns 409 on a
     * duplicate reference; we catch that and skip silently, so no double-credit
     * is possible regardless of which path wins the race.
     *
     * Commission structure:
     *   The referring admin earns a percentage of every deposit made by users
     *   they referred. The rate is stored on the Referral entity and defaults
     *   to 70% of the platform commission. Resolution happens entirely inside
     *   ReferralService.attributeCommission(). This method just triggers it.
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
     * Called by BOTH the webhook handler AND the reconciliation sweep. Both paths
     * are safe to call concurrently — UserService.upgradeToAdmin() returns 409
     * on a duplicate reference; we catch that and skip silently.
     *
     * Steps:
     *   1. Validate amount >= GHS 200
     *   2. Promote user to ADMIN + initialise their referral link at 70%
     *   3. Record an audit transaction (AkwaPay collected the funds externally)
     *   4. Create onboarding chat with Super Admin for commission confirmation
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
     * Decides which network string ("MTN" | "TELECEL" | "AIRTELTIGO") to send
     * to AkwaPay, since it's now mandatory whenever method=mobile_money.
     *
     * Order of preference:
     *   1. Whatever the client explicitly sent — the user picked it, trust it.
     *   2. A best-effort guess from the phone number's prefix.
     *   3. Neither worked → 400, ask the user to pick one.
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
        body.put("amount",     amountPesewas);
        body.put("currency",   "GHS");
        body.put("reference",  reference);
        body.put("return_url", returnUrl);
        body.put("metadata",   metadata);
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
     * Builds a reference string that encodes the userId and intent type so the
     * webhook handler can recover both with no other context.
     *
     * Format:  sbdep_<32-hex-userId>_<8-hex-nonce>
     *          sbadm_<32-hex-userId>_<8-hex-nonce>
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
     */
    private ParsedRef parseReference(String reference) {
        boolean adminUpgrade;
        if (reference.startsWith(REF_PREFIX_DEPOSIT))      adminUpgrade = false;
        else if (reference.startsWith(REF_PREFIX_ADMIN))   adminUpgrade = true;
        else return null;

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
     *   2. Timestamps outside ±5 min are rejected (replay attack prevention).
     *   3. Constant-time compare via MessageDigest.isEqual().
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
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * REFERENCE FORMAT — HYPHENS NOT UNDERSCORES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * NaloPay (via AkwaPay) uses our reference as the transaction reference.
 * Only alphanumeric characters and hyphens are accepted — no underscores:
 *
 *     sbdep-<32-hex userId>-<8-hex nonce>     wallet deposit
 *     sbadm-<32-hex userId>-<8-hex nonce>     admin upgrade
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX (2026-09-09) — DO NOT REUSE A REFERENCE ACROSS RETRY ATTEMPTS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * NaloPay appears to persist a payment_intent record for a reference even
 * when the create call ultimately errors back to us (e.g. MoMo push
 * rejected due to account restriction/activation). If we retry the SAME
 * reference against the hosted-checkout fallback, NaloPay rejects the
 * second call with "You have already created a payment intent with this
 * reference." Every call to AkwaPay — including same-request fallback
 * retries — must use a freshly generated reference. See
 * {@link #initDeposit} and {@link #initAdminUpgrade}.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX (2026-09-09) — REFERENCE FORMAT: SHORT AND OPAQUE, NO EMBEDDED userId
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * NaloPay rejected our previous reference shape outright — every attempt,
 * not just retries — with "Invalid value for reference". That shape was
 * "sbdep-<32-hex userId><hyphen><12-hex nonce>": 51 characters, 2 hyphens.
 * AkwaPay's own docs and test suite only ever use short, single-segment,
 * single-hyphen references such as "order-4471" or "REF123" — nothing
 * close to what we were sending survives Nalo's validation.
 *
 * The reference is now a short opaque token — prefix + 16 random
 * alphanumeric characters, ONE hyphen total — and no longer encodes the
 * userId. Encoding structured data into a merchant reference was never
 * something AkwaPay's contract promised to preserve; it's meant to be
 * opaque, matching their own examples.
 *
 * Because the userId is no longer recoverable from the string, the
 * webhook handler and the reconciliation sweep resolve userId (and
 * whether the charge was an admin upgrade) by looking up the
 * AkwaPayPendingIntent row keyed on the reference — see
 * {@link #resolvePending} — rather than decoding it. This is strictly
 * more robust: we already persist that row on every successful intent
 * creation, so there is no new failure mode, and a reference doesn't need
 * to describe itself to be looked up.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY customer.email IS SYNTHETIC (PER-ATTEMPT) — CARD/CHECKOUT ONLY
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * AkwaPay deduplicates customers by email on some gateway paths. For the
 * card/hosted-checkout path we use plus-addressing to make the email
 * unique per attempt: kojo@gmail.com → kojo+1724449830123@gmail.com. The
 * real email is preserved in metadata for audit.
 *
 * FIX (2026-09-09): customer.email is NEVER sent for method="mobile_money".
 * Confirmed via a controlled live test against AkwaPay's API directly —
 * the identical request that succeeds with customer={"phone":"..."} only
 * fails with 402 "NALOPAY collection rejected: Failed to create
 * collection" the moment an email field is added alongside the phone. This
 * was the root cause of every production MoMo-push failure up to
 * 2026-09-09: the synthetic email was being attached unconditionally on
 * every call, so every real mobile_money attempt was silently rejected by
 * NaloPay and fell back to hosted checkout without ever reaching a phone.
 * See {@link #akwapayCreateIntent} for where this is now suppressed.
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
 * FIX (2026-09-10) — SETTLE-ON-READ: AkwaPay/NaloPay dashboard sync gap
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Confirmed in production by comparing the AkwaPay dashboard against the
 * underlying NaloPay collection report for the SAME reference at the SAME
 * moment:
 *
 *   AkwaPay dashboard    pi_f96d3a47843d4a05  sbdep-7sv4na556zk2ndz0  "awaiting customer"
 *   NaloPay collections  jUxNhv8j34A5qx6GbusCCC sbdep-7sv4na556zk2ndz0 "Successful"
 *
 * NaloPay had already collected the money; AkwaPay's own
 * /payment_intents/{id} record for that intent never advanced past
 * requires_action. This is a sync gap between AkwaPay and its NaloPay
 * channel, not a bug in this controller — but it means the webhook (which
 * fires off AkwaPay's own internal state) and the sweep (which polls that
 * same internal state) can both wait forever on a payment that has, in
 * reality, already succeeded.
 *
 * Fix: stop treating "the webhook fired AND a pending row exists" as the
 * only path to a credit. GET /status/{intentId} — the one endpoint the
 * customer's own browser is actively polling every few seconds while a
 * payment is in flight — now credits immediately the moment AkwaPay's own
 * read of the intent EVER reports "succeeded", via {@link #settle}. This
 * closes the gap the instant the customer (or their background poll) asks,
 * rather than waiting on AkwaPay's internal sync with NaloPay to catch up
 * on its own schedule — which, per the evidence above, may not happen for
 * some intents.
 *
 * settle() is also the credit path used by the webhook and the sweep, so
 * whichever of the three notices "succeeded" first wins; the other two are
 * harmless no-ops via WalletService.credit()'s reference-based 409 dedupe.
 * settle() additionally recovers the owning user from AkwaPay's own
 * intent-metadata when the local pending row is missing (see the
 * "recordPending failure" note below) — before this fix, a missing row
 * meant an unrecoverable silent drop; now it is instead a same-request
 * recovery.
 *
 * CAVEAT: if AkwaPay's own API read of an intent NEVER reports "succeeded"
 * — i.e. their dashboard stays on "awaiting customer" indefinitely even
 * though NaloPay's own collection report already says "Successful" for
 * that reference — this fix cannot help, because it still only acts on
 * what AkwaPay's API itself returns. That specific mismatch is on AkwaPay's
 * side (their sync with the NaloPay channel is broken for that intent) and
 * needs (a) a manual WalletService credit for the affected references,
 * confirmed against the NaloPay collection report, and (b) an AkwaPay
 * support ticket citing the pi_... IDs against their own matching NaloPay
 * transaction IDs.
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
     * IMPORTANT: if the primary MoMo push attempt fails and we fall back to
     * hosted checkout, the fallback call uses a NEWLY GENERATED reference,
     * not the one that just failed. NaloPay can persist a payment_intent
     * record for a reference even when it errors the create call back to
     * us, so reusing that reference on the fallback gets rejected with
     * "You have already created a payment intent with this reference."
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

        log.info("initDeposit: userId='{}' amount={} pesewas={} ref='{}' method='{}' network='{}'",
                user.getId(), amount, amountPesewas, reference, method, network);

        // Validate MSISDN before calling AkwaPay — non-Ghana numbers (e.g. 070xx = Nigeria)
        // will be rejected by NaloPay with "Not a valid Ghanaian MSISDN". Catch it early
        // so we fall back to checkout gracefully rather than surfacing a confusing error.
        if (useMomoPush) {
            var detected = detectNetworkFromPhone(phone);
            if (detected.isEmpty()) {
                log.warn("initDeposit: phone='{}' is not a recognised Ghana number — switching to hosted checkout fallback",
                        phone.length() > 4 ? phone.substring(0, 3) + "***" + phone.substring(phone.length() - 2) : "***");
                useMomoPush = false;
                network     = null;
                method      = "card";
            }
        }

        // NOTE (2026-09-09): persist-before-call is reverted for now — the
        // live "akwapay_pending_intents" table still has intent_id as
        // NOT NULL, so inserting a row with intentId=null throws a
        // DataIntegrityViolationException before we ever call AkwaPay
        // (see prod log, SQLState 23502). The entity javadoc and the
        // nullable=false-less @Column annotation both say intent_id SHOULD
        // be nullable — the DB schema hasn't been migrated to match yet.
        // Once `ALTER TABLE akwapay_pending_intents ALTER COLUMN intent_id
        // DROP NOT NULL;` has been run, switch this back to persist-before-
        // call (recordPending(reference, null, ...) here, attachIntentId()
        // after success) to restore the crash-safety described there.
        Map<String, Object> response;
        try {
            response = akwapayCreateIntent(
                    amountPesewas,
                    reference,
                    user.getEmail(),
                    useMomoPush ? phone : null,
                    useMomoPush ? network : null,
                    method,
                    frontendUrl + "/wallet?payment=success",
                    Map.of("userId", user.getId().toString(), "purpose", "deposit")
            );
        } catch (Exception ex) {
            // NaloPay direct MoMo push failed (PAY-FAIL-0070: push not activated,
            // or account restriction). Auto-fallback to hosted checkout.
            if (useMomoPush) {
                log.warn("initDeposit: MoMo push FAILED for ref='{}' userId='{}' — auto-falling back to hosted checkout. cause: {}",
                        reference, user.getId(), ex.getMessage());

                // IMPORTANT: generate a FRESH reference for the fallback attempt.
                // NaloPay may have already persisted a payment_intent record under
                // the original reference even though it returned an error to us —
                // retrying that same reference gets rejected as a duplicate.
                var fallbackReference = buildReference(REF_PREFIX_DEPOSIT);
                log.info("initDeposit: retrying with fresh ref='{}' (was '{}') for userId='{}'",
                        fallbackReference, reference, user.getId());

                try {
                    response = akwapayCreateIntent(
                            amountPesewas,
                            fallbackReference,
                            user.getEmail(),
                            null,
                            null,
                            "card",
                            frontendUrl + "/wallet?payment=success",
                            Map.of("userId", user.getId().toString(), "purpose", "deposit", "momo_fallback", "true")
                    );
                    reference = fallbackReference; // downstream logging/persistence must use the ref that actually succeeded
                    log.info("initDeposit: fallback checkout OK for ref='{}' userId='{}'", reference, user.getId());
                } catch (Exception fallbackEx) {
                    log.error("initDeposit: both MoMo push and checkout fallback failed for userId='{}' (attempted refs '{}' then '{}')",
                            user.getId(), reference, fallbackReference, fallbackEx);
                    throw fallbackEx;
                }
            } else {
                throw ex;
            }
        }

        // Persist AFTER AkwaPay succeeds — intent_id is never null this way,
        // which is required by the current DB schema (see NOTE above).
        var intentId       = String.valueOf(response.get("id"));
        var nextActionType = nextActionType(response);

        recordPending(reference, intentId, user.getId(), amount, false);

        log.info("initDeposit: intent='{}' status='{}' next_action='{}' ussdFallback='{}' checkoutUrl='{}' for userId='{}'",
                intentId,
                response.get("status"),
                nextActionType,
                nextActionUssd(response),
                response.get("checkout_url"),
                user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }


    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    /**
     * Same fresh-reference-on-fallback fix as {@link #initDeposit}: the
     * hosted-checkout retry after a failed MoMo push must use a brand new
     * reference, never the one that just failed against NaloPay.
     */
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

        log.info("initAdminUpgrade: userId='{}' email='{}' ref='{}' method='{}' network='{}'",
                user.getId(), user.getEmail(), reference, method, network);

        var upgradeAmountGhs = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_PESEWAS)
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

        // Validate MSISDN for admin upgrade too
        if (useMomoPush) {
            var detected = detectNetworkFromPhone(phone);
            if (detected.isEmpty()) {
                log.warn("initAdminUpgrade: phone not a Ghana number — switching to hosted checkout");
                useMomoPush = false;
                network     = null;
                method      = "card";
            }
        }

        // NOTE (2026-09-09): persist-before-call reverted here too — see the
        // matching NOTE in initDeposit. The current DB schema has intent_id
        // NOT NULL, so this must stay persist-after-call until that column
        // is migrated.
        Map<String, Object> response;
        try {
            response = akwapayCreateIntent(
                    ADMIN_UPGRADE_FEE_PESEWAS,
                    reference,
                    user.getEmail(),
                    useMomoPush ? phone : null,
                    useMomoPush ? network : null,
                    method,
                    frontendUrl + "/app/upgrade?payment=success",
                    Map.of(
                            "userId",        user.getId().toString(),
                            "upgradeIntent", UPGRADE_INTENT_ADMIN
                    )
            );
        } catch (Exception ex) {
            if (useMomoPush) {
                log.warn("initAdminUpgrade: MoMo push FAILED for ref='{}' userId='{}' — auto-falling back to hosted checkout. cause: {}",
                        reference, user.getId(), ex.getMessage());

                // Fresh reference for the fallback — see fix note above initDeposit.
                var fallbackReference = buildReference(REF_PREFIX_ADMIN);
                log.info("initAdminUpgrade: retrying with fresh ref='{}' (was '{}') for userId='{}'",
                        fallbackReference, reference, user.getId());

                try {
                    response = akwapayCreateIntent(
                            ADMIN_UPGRADE_FEE_PESEWAS,
                            fallbackReference,
                            user.getEmail(),
                            null,
                            null,
                            "card",
                            frontendUrl + "/app/upgrade?payment=success",
                            Map.of(
                                    "userId",        user.getId().toString(),
                                    "upgradeIntent", UPGRADE_INTENT_ADMIN,
                                    "momo_fallback", "true"
                            )
                    );
                    reference = fallbackReference;
                    log.info("initAdminUpgrade: fallback checkout OK for ref='{}' userId='{}'", reference, user.getId());
                } catch (Exception fallbackEx) {
                    log.error("initAdminUpgrade: both MoMo and checkout failed for userId='{}' (attempted refs '{}' then '{}')",
                            user.getId(), reference, fallbackReference, fallbackEx);
                    throw fallbackEx;
                }
            } else {
                throw ex;
            }
        }

        // Persist AFTER AkwaPay succeeds — required by current schema.
        var intentId = String.valueOf(response.get("id"));
        recordPending(reference, intentId, user.getId(), upgradeAmountGhs, true);

        log.info("initAdminUpgrade: intent='{}' status='{}' next_action='{}' checkoutUrl='{}' for userId='{}'",
                intentId, response.get("status"), nextActionType(response),
                response.get("checkout_url"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Persists a pending-intent row AFTER AkwaPay has confirmed an intent id.
     *
     * NOTE (2026-09-09): this is temporarily back to persist-AFTER-call only.
     * The live "akwapay_pending_intents" table has intent_id as NOT NULL, so
     * persist-before-call (recordPending with intentId=null) throws a
     * DataIntegrityViolationException before AkwaPay is ever called — see
     * prod log, SQLState 23502. The entity javadoc and its @Column
     * annotation (no nullable=false) both describe intent_id as meant to be
     * nullable; the DB schema just hasn't been migrated to match yet.
     *
     * Once `ALTER TABLE akwapay_pending_intents ALTER COLUMN intent_id DROP
     * NOT NULL;` has been run: switch callers back to persist-before-call
     * (recordPending(reference, null, ...) before the AkwaPay call,
     * attachIntentId(reference, intentId) after success — see that method
     * below, currently unused but left in place for this) to restore the
     * crash-safety the entity javadoc describes.
     *
     * A failure to persist here is logged and swallowed rather than thrown:
     * the AkwaPay call has already succeeded by the time this runs, so the
     * charge exists on AkwaPay's side either way — the only thing lost on a
     * failed save is our local backstop for reconciling it, which is why the
     * error message says to investigate/credit manually.
     *
     * NOTE (2026-09-10): a failed save here is no longer an unrecoverable
     * loss on its own — see {@link #settle}, which now recovers the owning
     * user from AkwaPay's own intent metadata whenever this row is missing.
     * The error log below stays, because metadata recovery is a fallback,
     * not a substitute for having the row.
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
                            "this payment can only be credited by webhook, sweep, status-probe settle-on-read, " +
                            "or by hand. Investigate.",
                    reference, intentId, userId, amountGhs, e);
        }
    }

    /**
     * Fills in the intentId on a row previously persisted with intentId=null.
     *
     * Currently UNUSED — no caller reaches this while recordPending() is
     * persist-after-call only (see the NOTE on recordPending above). Left in
     * place, fully working, for when the intent_id column is migrated to
     * nullable and callers switch back to persist-before-call.
     */
    private void attachIntentId(String reference, String intentId) {
        try {
            var existing = pendingIntents.findById(reference);
            if (existing.isEmpty()) {
                log.error("attachIntentId: no pending row for ref='{}' intent='{}' — AkwaPay confirmed an intent " +
                                "but we have nowhere to record it. The sweep cannot pick this up; investigate and " +
                                "credit manually if the payment succeeds.",
                        reference, intentId);
                return;
            }
            var row = existing.get();
            row.setIntentId(intentId);
            pendingIntents.save(row);
            log.info("attachIntentId: ref='{}' intent='{}' attached — sweep can now poll", reference, intentId);
        } catch (Exception e) {
            log.error("attachIntentId: FAILED to attach intent='{}' to ref='{}' — sweep cannot poll until this " +
                            "is fixed manually.",
                    intentId, reference, e);
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

        log.info("initCheckout: userId='{}' amount={} ref='{}' — hosted checkout fallback",
                user.getId(), amount, reference);

        // Persist AFTER AkwaPay succeeds — required by current schema (see
        // NOTE in initDeposit).
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
        recordPending(reference, intentId, user.getId(), amount, false);

        log.info("initCheckout: intent='{}' checkoutUrl='{}' for userId='{}'",
                intentId, response.get("checkout_url"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Status probe (SETTLE-ON-READ) ─────────────────────────────────────────

    /**
     * Reads the intent from AkwaPay and returns it unchanged to the caller —
     * same response shape as before this fix, so the frontend needs no
     * changes to consume this. The one behavioural addition (2026-09-10):
     * if AkwaPay reports "succeeded" for a reference we recognise
     * (sbdep-/sbadm- prefix), credit it right here via {@link #settle}
     * before responding.
     *
     * WHY HERE: this is the one endpoint the customer's browser is actively
     * polling every few seconds while a payment is in flight (see
     * useBackgroundPoll in DepositPage.tsx, which now also polls during the
     * await_prompt step, not just the pending step). If AkwaPay's own async
     * webhook/intent-status pipeline is lagging — or has desynced entirely
     * from the underlying NaloPay channel, as confirmed in production on
     * 2026-09-10 (AkwaPay dashboard showed "awaiting customer" for several
     * intents whose exact same reference showed "Successful" on NaloPay's
     * own collection report) — this is the fastest and cheapest place to
     * catch up: no new infrastructure, no second polling loop, just
     * "credit before you answer, if the answer is good news".
     *
     * SAFETY: settle() → WalletService.credit() dedupes on reference via a
     * 409 on repeat, so this races safely against the webhook and the
     * scheduled sweep — whichever of the three notices "succeeded" first
     * wins, the other two are harmless no-ops.
     */
    @GetMapping("/api/wallet/deposit/akwapay/status/{intentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user,
            @PathVariable String intentId) {

        var result    = probeIntent(intentId);
        var statusStr = String.valueOf(result.get("status")).toLowerCase(Locale.ROOT);
        var reference = result.get("reference") == null ? null : result.get("reference").toString();

        log.info("status: userId='{}' intent='{}' status='{}' next_action='{}'",
                user.getId(), intentId, statusStr, nextActionType(result));

        // SETTLE-ON-READ — see the class-level "FIX (2026-09-10)" javadoc
        // above for the full story. credit() dedupes on reference, so this
        // is safe to call on every single poll tick that reports success,
        // not just the first one.
        if ("succeeded".equals(statusStr) && reference != null
                && (reference.startsWith(REF_PREFIX_DEPOSIT) || reference.startsWith(REF_PREFIX_ADMIN))) {
            settle(reference, intentId, result, "status-probe");
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Reads a payment intent straight from AkwaPay. Extracted so both
     * {@link #status} and {@link #reconcileOne} share one AkwaPay read
     * path instead of two near-identical inline WebClient calls.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> probeIntent(String intentId) {
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
        return result;
    }

    /**
     * Single shared "this reference is confirmed paid — make sure the
     * customer has been credited" path. Callable from the webhook, the
     * sweep, or (new, 2026-09-10) the status-probe endpoint — whichever
     * learns of a "succeeded" intent first.
     *
     * Resolves the owning user/amount from the pending row if it still
     * exists. If the row is missing — recordPending() has failed in
     * production before; see its own javadoc, SQLState 23502 — recovers
     * both from the metadata AkwaPay echoes back on every intent read,
     * which is stamped with userId/purpose at creation time in
     * {@link #akwapayCreateIntent}. Credit always goes to the intent's
     * OWNER, resolved from the pending row or from AkwaPay's own stored
     * metadata for that specific intent — never to whoever happened to
     * make the HTTP call that triggered settle(). A user polling a
     * different user's intentId can therefore only ever cause a credit to
     * land on the rightful owner of that intent, never on themselves.
     */
    private void settle(String reference, String intentId, Map<String, Object> akwapayIntent, String source) {
        UUID       userId;
        BigDecimal amount;
        boolean    adminUpgrade = reference.startsWith(REF_PREFIX_ADMIN);
        boolean    hadRow       = false;

        Optional<AkwaPayPendingIntent> pending;
        try {
            pending = pendingIntents.findById(reference);
        } catch (Exception e) {
            log.warn("settle({}): pending lookup failed for ref='{}': {}", source, reference, e.getMessage());
            pending = Optional.empty();
        }

        if (pending.isPresent()) {
            var row      = pending.get();
            userId       = row.getUserId();
            amount       = row.getAmountGhs();
            adminUpgrade = row.isAdminUpgrade();
            hadRow       = true;
        } else {
            userId = metadataUserId(akwapayIntent);
            amount = pesewasToGhs(akwapayIntent.get("amount"));
            log.warn("settle({}): pending row MISSING for ref='{}' intent='{}' — recovered owner from " +
                            "metadata userId='{}' amount={}",
                    source, reference, intentId, userId, amount);
        }

        if (userId == null || amount == null || amount.signum() <= 0) {
            log.error("settle({}): ref='{}' intent='{}' SUCCEEDED at AkwaPay but owner unresolvable " +
                            "(no pending row, no usable metadata). MANUAL CREDIT REQUIRED. intent={}",
                    source, reference, intentId, akwapayIntent);
            return;
        }

        try {
            if (adminUpgrade) handleAdminUpgrade(userId, reference, amount, intentId);
            else              handleDeposit(userId, reference, amount, intentId);
        } catch (Exception e) {
            log.error("settle({}): credit threw for ref='{}' userId='{}' amount={} — sweep/webhook/next " +
                            "status-probe will retry",
                    source, reference, userId, amount, e);
            return;
        }

        if (hadRow) deletePending(reference, "settled by " + source);
    }

    /** Recovers userId from AkwaPay's own echoed intent metadata (stamped at creation — see akwapayCreateIntent). */
    private UUID metadataUserId(Map<String, Object> akwapayIntent) {
        if (akwapayIntent.get("metadata") instanceof Map<?, ?> m && m.get("userId") != null) {
            try {
                return UUID.fromString(m.get("userId").toString());
            } catch (IllegalArgumentException ignored) {
                // fall through — malformed/foreign metadata, treat as unresolvable
            }
        }
        return null;
    }

    /** Converts AkwaPay's integer-pesewas amount field back to a GHS BigDecimal, or null if unusable. */
    private BigDecimal pesewasToGhs(Object amount) {
        if (amount == null) return null;
        try {
            var pesewas = new BigDecimal(amount.toString());
            return pesewas.signum() <= 0 ? null : pesewas.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        } catch (Exception e) {
            return null;
        }
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

        Map<String, Object> event;
        try {
            @SuppressWarnings("unchecked")
            var parsedEvent = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);
            event = parsedEvent;

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

            // FIX (2026-09-10): a missing pending row used to mean an
            // unrecoverable silent drop — "Ignored: unknown reference",
            // 200, done, money gone. Now: try to recover the owner from
            // the metadata AkwaPay echoes back on the webhook payload
            // itself (the same metadata stamped at intent creation), and
            // credit via the same settle()-adjacent handlers used
            // everywhere else. Only if BOTH the row AND the metadata are
            // unusable do we give up — and even then, we now say so loudly
            // (ERROR, not WARN) instead of quietly returning 200.
            var pending = resolvePending(reference);
            if (pending.isEmpty()) {
                var uid = metadataUserId(data);
                if (uid != null) {
                    log.warn("AkwaPay webhook: pending row missing for ref='{}' — crediting via metadata userId='{}'",
                            reference, uid);
                    if (reference.startsWith(REF_PREFIX_ADMIN)) handleAdminUpgrade(uid, reference, amount, intentId);
                    else                                        handleDeposit(uid, reference, amount, intentId);
                } else {
                    log.error("AkwaPay webhook: UNRECOVERABLE payment ref='{}' intent='{}' amount={} " +
                                    "— no pending row, no metadata. MANUAL CREDIT REQUIRED. delivery='{}' fullEvent={}",
                            reference, intentId, amount, deliveryId, event);
                }
                return ResponseEntity.ok("OK");
            }
            var parsed = pending.get();

            if (parsed.isAdminUpgrade()) {
                handleAdminUpgrade(parsed.getUserId(), reference, amount, intentId);
            } else {
                handleDeposit(parsed.getUserId(), reference, amount, intentId);
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
        // No intentId yet means AkwaPay's create-intent call hasn't (or
        // never will) complete for this row — there is nothing to poll AkwaPay
        // about. Per AkwaPayPendingIntent javadoc ("WHY intentId IS NULLABLE"),
        // the sweep skips these; reconcileOne still abandons a null-intentId
        // row that's stuck well past ABANDON_AFTER (the AkwaPay call itself
        // died and nothing ever attached an intentId).
        if (intent.getIntentId() == null) {
            return intent.getCreatedAt().isBefore(now.minus(ABANDON_AFTER));
        }
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

        if (intent.getIntentId() == null) {
            // isDue() only lets a null-intentId row reach here once it's past
            // ABANDON_AFTER, so in practice the branch above always catches
            // it first. This guard exists so we never build a
            // "/payment_intents/null" URL if that assumption ever changes.
            log.warn("reconcile: ref='{}' has no intentId yet — nothing to poll, skipping this tick", ref);
            return;
        }

        try {
            intent.markChecked(Instant.now());
            pendingIntents.save(intent);
        } catch (Exception e) {
            log.warn("reconcile: could not stamp lastCheckedAt for ref='{}': {}", ref, e.getMessage());
        }

        Map<String, Object> result;
        try {
            result = probeIntent(intent.getIntentId());
        } catch (Exception e) {
            log.warn("reconcile: status check failed for ref='{}' intent='{}' — retry next sweep: {}",
                    ref, intent.getIntentId(), e.getMessage());
            return;
        }

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase(Locale.ROOT);
        log.info("reconcile: ref='{}' intent='{}' akwapayStatus='{}' attempt={}",
                ref, intent.getIntentId(), akwapayStatus, intent.getAttempts());

        // DIAGNOSTIC (2026-09-09) — "push never arrives" investigation.
        // Dump the full raw status response on the first 3 attempts only
        // (avoids flooding logs for a payment that's legitimately just
        // sitting in await_prompt for a while). If NaloPay surfaces any
        // delivery/provider hint beyond `status`, it'll be visible here.
        if (intent.getAttempts() <= 3) {
            log.info("reconcile[momo-diag]: ref='{}' intent='{}' attempt={} FULL response body={}",
                    ref, intent.getIntentId(), intent.getAttempts(), result);
        }

        switch (akwapayStatus) {
            case "succeeded" -> {
                log.info("reconcile: ref='{}' succeeded on sweep — applying credit", ref);
                settle(ref, intent.getIntentId(), result, "sweep");
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
     *
     * Every caller MUST pass a reference that has never been sent to
     * NaloPay before — including on a same-request retry/fallback. NaloPay
     * can persist a payment_intent for a reference even when this call
     * ultimately throws, so retrying with the same reference is rejected
     * as a duplicate. Callers that fall back after a failure must generate
     * a new reference via {@link #buildReference} first.
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

        // ROOT CAUSE FIX (2026-09-09): customer.email BREAKS NaloPay's MoMo
        // collection path. Confirmed via a controlled live test — the exact
        // same request that succeeds with only {"phone": "..."} in customer
        // fails with 402 "NALOPAY collection rejected: Failed to create
        // collection" the moment an email field is added. This matches every
        // production mobile_money failure logged so far: the synthetic email
        // below was being attached unconditionally, on every call, including
        // mobile_money — so every real MoMo push attempt failed at NaloPay
        // and silently fell back to checkout.
        //
        // customer.email is still useful for the card/checkout path (it's
        // shown in AkwaPay's own docs example alongside phone), so only
        // suppress it for mobile_money specifically rather than dropping it
        // everywhere.
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
        if (!"mobile_money".equals(method)) {
            customer.put("email", syntheticEmail);
        }
        if (phone != null && !phone.isBlank()) customer.put("phone", phone);

        var body = new HashMap<String, Object>();
        body.put("amount",     amountPesewas);
        body.put("currency",   "GHS");
        body.put("reference",  reference);
        body.put("return_url", returnUrl);
        body.put("metadata",   metadata);
        body.put("customer",   customer);
        body.put("method",     method);
        // UNTESTED VARIABLE (2026-09-09): every manual curl/PowerShell test
        // that succeeded against NaloPay's mobile_money collection included
        // "description" in the body. The production backend never sent it.
        // return_url and metadata were each proven harmless in isolation
        // (Test A, Test C), but description was never tested on its own —
        // this is the one remaining untested difference between "known
        // good" and "known failing" requests. Sending a fixed, harmless
        // value costs nothing if it turns out not to matter.
        body.put("description", "Wallet deposit");

        // Only set network for mobile_money — card doesn't need it
        boolean networkAttached = false;
        if ("mobile_money".equals(method) && network != null) {
            body.put("network", network.toUpperCase());
            networkAttached = true;
        }

        var idempotencyKey = UUID.randomUUID().toString();

        // DIAGNOSTIC (2026-09-09) — "Failed to create collection" investigation.
        // Logs the exact shape of what we send NaloPay for a mobile_money
        // request, PLUS the literal JSON body on the wire (below) so there
        // is no more guessing from reading the code — this is what actually
        // gets sent, byte for byte, to compare directly against a manual
        // curl/PowerShell call that is known to succeed.
        if ("mobile_money".equals(method)) {
            var maskedPhone = phone == null ? "null"
                    : phone.length() > 4
                      ? phone.substring(0, 3) + "***" + phone.substring(phone.length() - 2)
                      : "<short>";
            log.info("akwapayCreateIntent[momo-diag]: ref='{}' phoneMasked='{}' phoneLength={} phoneStartsWithPlus={} " +
                            "network='{}' networkAttachedToBody={} emailOmittedFromBody={} idempotencyKey='{}'",
                    reference,
                    maskedPhone,
                    phone == null ? 0 : phone.length(),
                    phone != null && phone.startsWith("+"),
                    network,
                    networkAttached,
                    !customer.containsKey("email"),
                    idempotencyKey);

            try {
                // Mask the phone in the logged copy only — never log the raw
                // number, even in a diagnostic dump. The actual request sent
                // to AkwaPay still carries the real phone; only the log line
                // is redacted.
                var loggable = new HashMap<>(body);
                var loggableCustomer = new HashMap<>(customer);
                if (loggableCustomer.containsKey("phone")) {
                    loggableCustomer.put("phone", maskedPhone);
                }
                loggable.put("customer", loggableCustomer);
                log.info("akwapayCreateIntent[momo-diag]: ref='{}' LITERAL outbound JSON body={}",
                        reference, objectMapper.writeValueAsString(loggable));
            } catch (Exception serializeEx) {
                log.warn("akwapayCreateIntent[momo-diag]: could not serialize outbound body for logging, ref='{}': {}",
                        reference, serializeEx.getMessage());
            }
        }

        log.info("akwapayCreateIntent: ref='{}' method='{}' network='{}' amountPesewas={} idempotencyKey='{}'",
                reference, method, network, amountPesewas, idempotencyKey);

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
                                            userMessage = (msg != null && !msg.isBlank())
                                                    ? msg
                                                    : "Payment was rejected. Please check your details and try again.";
                                        } catch (Exception parseEx) {
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
                            log.error("AkwaPay API unreachable after {} retries", akwapayRetryAttempts, ex);
                            return new RuntimeException("AkwaPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");

        var status = String.valueOf(result.get("status"));
        log.info("akwapayCreateIntent: intent='{}' status='{}' next_action='{}' ussdFallback='{}' ref='{}'",
                result.get("id"), status, nextActionType(result), nextActionUssd(result), reference);

        // DIAGNOSTIC (2026-09-09) — dump the FULL raw response for mobile_money
        // calls only (card/checkout responses are noisier and less relevant to
        // "push never arrives"). AkwaPay may include provider-side fields we
        // don't currently parse (e.g. a warning, a delivery hint, a different
        // provider code) that explain why NaloPay accepted the request
        // (status=requires_action, next_action=await_prompt) but the handset
        // never got the prompt. If this ever shows something informative,
        // promote it into nextActionType()/nextActionUssd() as a real field.
        if ("mobile_money".equals(method)) {
            log.info("akwapayCreateIntent[momo-diag]: ref='{}' FULL response body={}", reference, result);
        }

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

    // ─── Reference generation / resolution ─────────────────────────────────────

    private static final String REF_TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int    REF_TOKEN_LENGTH    = 16;
    private static final java.security.SecureRandom REF_RANDOM = new java.security.SecureRandom();

    /**
     * Builds a short, opaque merchant reference: prefix + 16 random
     * lowercase-alphanumeric characters, exactly one hyphen total (the one
     * baked into the prefix constant). e.g. "sbdep-9k2m7qw1x0az4btc".
     *
     * Deliberately does NOT encode the userId or anything else structured —
     * NaloPay rejects longer, multi-hyphen references outright ("Invalid
     * value for reference"), and AkwaPay's own docs/tests only ever use
     * short single-segment references like "order-4471". The userId is
     * resolved later via {@link #resolvePending} (or, if that row is
     * missing, via {@link #metadataUserId}), not decoded from this string,
     * so there is nothing to gain by embedding it here.
     */
    private String buildReference(String prefix) {
        var sb = new StringBuilder(prefix.length() + REF_TOKEN_LENGTH);
        sb.append(prefix);
        for (int i = 0; i < REF_TOKEN_LENGTH; i++) {
            sb.append(REF_TOKEN_ALPHABET.charAt(REF_RANDOM.nextInt(REF_TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Resolves a reference back to the pending intent record we persisted
     * when the charge was created — this is how we recover userId and
     * whether the charge was an admin upgrade, now that the reference
     * itself carries no structured data.
     *
     * Returns empty for a reference we have no record of (already
     * reconciled and deleted, or genuinely foreign/unrecognised) — callers
     * treat that the same way the old "malformed reference" case was
     * treated: log it and skip, rather than throwing. As of 2026-09-10,
     * "empty" is no longer necessarily a dead end — see {@link #settle}
     * and the webhook's metadata-recovery branch, both of which fall back
     * to AkwaPay's own intent metadata when this returns empty.
     */
    private Optional<AkwaPayPendingIntent> resolvePending(String reference) {
        if (!reference.startsWith(REF_PREFIX_DEPOSIT) && !reference.startsWith(REF_PREFIX_ADMIN)) {
            return Optional.empty();
        }
        try {
            return pendingIntents.findById(reference);
        } catch (Exception e) {
            log.warn("resolvePending: lookup failed for ref='{}': {}", reference, e.getMessage());
            return Optional.empty();
        }
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
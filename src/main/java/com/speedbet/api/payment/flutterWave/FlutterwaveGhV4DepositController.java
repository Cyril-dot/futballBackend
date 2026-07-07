package com.speedbet.api.payment.flutterWave;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FlutterwaveGhV4DepositController — GHS MoMo deposits via Flutterwave's
 * v4 Orchestrator API (push-notification flow), built on the shared
 * plumbing in {@link AbstractFlutterwaveV4DepositController}.
 *
 * This is the v4 counterpart to {@link FlutterwaveGhDepositController}
 * (v3, redirect-based). See that class's javadoc and
 * {@link AbstractFlutterwaveDepositController}'s javadoc for why v3 exists
 * alongside this — short version: Flutterwave has no backend-relayed-OTP
 * option for Ghana MoMo in either API version. v4's push-notification flow
 * just removes the browser redirect; the customer still authorizes
 * directly on their own phone with their PIN, same as v3's redirect page
 * just without the redirect.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  IMPORTANT — read {@link AbstractFlutterwaveV4DepositController}'s class
 *  javadoc before deploying this. Short version: v4 is public beta, the
 *  production base URL needs confirming with Flutterwave, and the webhook
 *  payload shape here is inferred rather than confirmed against a real v4
 *  sample.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — Flutterwave's v4 orchestrator API rejects any
 *  `reference` outside 6–42 characters:
 *
 *    {"status":"failed","error":{"type":"REQUEST_NOT_VALID","code":"10400",
 *     "message":"Request is not valid","validation_errors":[{"field_name":
 *     "reference","message":"size must be between 6 and 42"}]}}
 *
 *  The previous reference format —
 *    "SPB-GH-V4-" + <36-char user UUID> + "-" + <36-char random UUID>
 *  — is ~85 characters, so EVERY v4 Ghana charge was failing at
 *  orchestratorCharge() with a 400 before this fix.
 *
 *  The user UUID was embedded in the reference so the webhook could
 *  recover the userId from the reference string alone (v4 doesn't
 *  reliably echo custom `meta` back the way v3 does). That's no longer
 *  necessary: {@link AbstractFlutterwaveV4DepositController} already
 *  caches userId against this exact reference in `pendingCharges` at
 *  charge-initiation time (see {@code cachePendingCharge}), and the
 *  webhook can just look it up from there instead of parsing it out of
 *  the string. So the reference is now a short, opaque, random token
 *  ("GHV4-" + 32 hex chars = 37 chars, safely inside the 6–42 window),
 *  and {@code webhook()} below resolves userId via
 *  {@code getPendingCharge(ref)} rather than a string-parsing helper.
 *
 *  NOTE: this means the reference -> userId mapping now lives ONLY in the
 *  in-memory `pendingCharges` cache. If the app restarts between charge
 *  initiation and the webhook arriving, that mapping is lost and the
 *  webhook will 400 (see the "Invalid reference format" log line) — this
 *  was already a stated limitation of the in-memory cache (see that
 *  class's javadoc: "In a multi-instance deployment replace this with
 *  Redis or a DB table"), just newly reachable now that init() actually
 *  succeeds. Worth moving `pendingCharges` to Redis/a DB table before
 *  relying on this in production.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ─── Flow ─────────────────────────────────────────────────────────────────
 *
 *  1. Initiate charge — Deposit
 *     POST /api/wallet/deposit/flutterwave/gh/v4/init
 *     • Accepts { amount, phoneNumber?, network }.
 *     • Calls orchestratorCharge() with payment_method.type = "mobile_money".
 *     • Caches reference -> Flutterwave charge id + userId via
 *       cachePendingCharge().
 *     • Returns { txRef, message }. No OTP screen — frontend goes straight
 *       to "waiting for approval on your phone".
 *
 *  2. Payment Verification (manual fallback / polling)
 *     POST /api/wallet/deposit/flutterwave/gh/v4/verify
 *     • Delegates to the shared verifyAndCredit() — safe to poll, credits
 *       once Flutterwave's own API confirms success.
 *
 *  3. Webhook (primary / automatic credit path)
 *     POST /api/webhooks/flutterwave/gh/v4
 *     • Delegates to the shared processV4Webhook(), which re-verifies via
 *       Flutterwave's API before crediting and never trusts the payload
 *       directly. Resolves userId via the pendingCharges cache (see FIX
 *       note above) rather than parsing the reference string.
 *     • Also reachable via {@link FlutterwaveWebhookRouterController}, which
 *       is the URL actually registered in the Flutterwave dashboard (only
 *       one webhook URL is allowed per account).
 *
 * ─── txRef / reference convention ────────────────────────────────────────
 *   "GHV4-<32 hex chars>" (37 chars total) — an opaque random token; the
 *   owning userId is resolved via the pendingCharges cache, not parsed
 *   out of this string (see FIX note above).
 *
 * ─── network values accepted by frontend ─────────────────────────────────
 *   "MTN", "VODAFONE", "TIGO"
 *
 * ─── application.properties keys needed ──────────────────────────────────
 *   See AbstractFlutterwaveV4DepositController for the shared v4 keys
 *   (client-id, client-secret, base-url, token-url, webhook-hash).
 *   This controller additionally needs:
 *     app.platform.min-deposit-amount-ghs (default: 1)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveGhV4DepositController extends AbstractFlutterwaveV4DepositController {

    private static final String EXPECTED_CURRENCY   = "GHS";
    private static final String GH_DIAL_CODE        = "233";
    private static final Set<String> VALID_NETWORKS = Set.of("MTN", "VODAFONE", "TIGO");
    private static final String TXREF_PREFIX        = "GHV4-";
    private static final String PROVIDER_TAG        = "flutterwave_gh_v4";

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.platform.min-deposit-amount-ghs:1}")
    private BigDecimal minDeposit;

    @Override protected WalletService     walletService()     { return walletService; }
    @Override protected ReferralService   referralService()   { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper      objectMapper()      { return objectMapper; }

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/gh/v4/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        }

        var phoneNumber = req.get("phoneNumber") != null
                ? req.get("phoneNumber").toString()
                : user.getPhone();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw ApiException.badRequest("phoneNumber is required");
        }

        var network = req.get("network");
        if (network == null || !VALID_NETWORKS.contains(network.toString().toUpperCase())) {
            throw ApiException.badRequest("network must be one of " + VALID_NETWORKS);
        }

        // FIX: Flutterwave v4 requires `reference` to be 6–42 characters.
        // The old format ("SPB-GH-V4-" + userId UUID + "-" + random UUID,
        // ~85 chars) always failed validation. userId no longer needs to be
        // embedded here — it's resolved from the pendingCharges cache in
        // the webhook instead (see class javadoc FIX note). This keeps the
        // reference short, unique, and opaque:
        //   "GHV4-" (5 chars) + 32 hex chars = 37 chars total.
        var txRef = TXREF_PREFIX + UUID.randomUUID().toString().replace("-", "");

        log.info("initDeposit(GH v4): userId='{}' amount={} network='{}' txRef='{}'",
                user.getId(), amount, network, txRef);

        var mobileMoney = new LinkedHashMap<String, Object>();
        mobileMoney.put("country_code", GH_DIAL_CODE);
        mobileMoney.put("network", network.toString().toUpperCase());
        mobileMoney.put("phone_number", normalizeLocalPhone(phoneNumber));

        var paymentMethod = new LinkedHashMap<String, Object>();
        paymentMethod.put("type", "mobile_money");
        paymentMethod.put("mobile_money", mobileMoney);

        var name = new LinkedHashMap<String, Object>();
        name.put("first", firstName(user));
        name.put("last", lastName(user));

        var phone = new LinkedHashMap<String, Object>();
        phone.put("country_code", GH_DIAL_CODE);
        phone.put("number", normalizeLocalPhone(phoneNumber));

        var customer = new LinkedHashMap<String, Object>();
        customer.put("email", user.getEmail());
        customer.put("name", name);
        customer.put("phone", phone);

        var body = new LinkedHashMap<String, Object>();
        body.put("amount", amount);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("reference", txRef);
        body.put("payment_method", paymentMethod);
        body.put("customer", customer);

        String message;
        try {
            var charge = orchestratorCharge(body);

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) charge.getOrDefault("data", Map.of());
            var chargeId = data.get("id") != null ? data.get("id").toString() : null;
            if (chargeId == null || chargeId.isBlank()) {
                throw new RuntimeException("Flutterwave did not return a charge id.");
            }

            // userId is cached against txRef here — this is now the ONLY
            // place the reference -> userId mapping lives, since the
            // reference itself no longer encodes it (see FIX note above).
            cachePendingCharge(txRef, chargeId, user.getId(), amount);

            @SuppressWarnings("unchecked")
            var nextAction = (Map<String, Object>) charge.getOrDefault("next_action", Map.of());
            var instructionMessage = nextAction.get("message");
            message = instructionMessage != null
                    ? instructionMessage.toString()
                    : "Please check your phone and approve the payment request.";

            log.info("initDeposit(GH v4): chargeId='{}' status='{}' txRef='{}'",
                    chargeId, data.get("status"), txRef);

        } catch (RuntimeException ex) {
            log.error("initDeposit(GH v4): Flutterwave charge failed for userId='{}' txRef='{}' — {}",
                    user.getId(), txRef, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Payment initiation failed. Please try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "txRef", txRef,
                "message", message
        )));
    }

    // ─── Payment Verification ──────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/gh/v4/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPayment(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var txRef = req.get("txRef");
        if (txRef == null || txRef.toString().isBlank())
            throw ApiException.badRequest("txRef is required.");

        var ref = txRef.toString().trim();
        log.info("verifyPayment(GH v4): userId='{}' txRef='{}'", user.getId(), ref);

        var result = verifyAndCredit(user.getId(), ref, EXPECTED_CURRENCY, PROVIDER_TAG);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ────────────────────────────────────────────────────────────────

    /**
     * Standalone GH v4 webhook endpoint. Kept for direct testing (e.g. curl),
     * but Flutterwave itself should be pointed at
     * FlutterwaveWebhookRouterController's /api/webhooks/flutterwave instead
     * — only one webhook URL can be registered per Flutterwave dashboard.
     *
     * userId is resolved via the pendingCharges cache (populated in
     * initDeposit() above) rather than parsed out of the reference string —
     * see the class javadoc FIX note for why.
     */
    @PostMapping("/api/webhooks/flutterwave/gh/v4")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {
        return processV4Webhook(verifHash, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG,
                this::resolveUserIdFromCache);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Resolves the owning userId for a reference via the in-memory
     * pendingCharges cache populated at charge-initiation time. Returns
     * null (causing the webhook to reject with 400) if the mapping isn't
     * found — e.g. a restart happened between init and webhook delivery.
     * See class javadoc FIX note: move pendingCharges to Redis/a DB table
     * to make this durable across restarts.
     */
    private UUID resolveUserIdFromCache(String txRef) {
        var pending = getPendingCharge(txRef);
        if (pending == null) {
            log.error("resolveUserIdFromCache(GH v4): no cached charge found for txRef='{}' " +
                    "(app restart between init and webhook? consider a durable cache)", txRef);
            return null;
        }
        return pending.userId();
    }

    /** Strips a leading "233"/"+233"/"0" prefix, since the country code is sent separately. */
    private static String normalizeLocalPhone(String phone) {
        var trimmed = phone.trim();
        if (trimmed.startsWith("+233")) return trimmed.substring(4);
        if (trimmed.startsWith("233"))  return trimmed.substring(3);
        if (trimmed.startsWith("0"))    return trimmed.substring(1);
        return trimmed;
    }

    private static String firstName(User user) {
        var first = user.getFirstName();
        if (first != null && !first.isBlank()) return first;
        var email = user.getEmail();
        return email != null ? email.split("@")[0] : "Customer";
    }

    private static String lastName(User user) {
        var last = user.getLastName();
        return last != null ? last : "";
    }
}
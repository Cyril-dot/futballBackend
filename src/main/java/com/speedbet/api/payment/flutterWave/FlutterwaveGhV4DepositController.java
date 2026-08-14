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
 *  FIX (this revision) — deposits no longer depend on the webhook arriving.
 *
 *  Symptom: a customer approves the MoMo prompt, is debited by their telco,
 *  and nothing lands in their wallet. Cause: the webhook was the only
 *  automatic credit path, and it can go missing (dropped retry, our endpoint
 *  down mid-deploy, router misrouting, Flutterwave retry exhaustion). Worse,
 *  the reference -> (chargeId, userId) mapping lived in an in-memory map, so
 *  a restart between init and callback destroyed the ONLY handle we had on
 *  the charge — after that neither the webhook nor /verify could recover it.
 *
 *  Fix, in the shared abstract class:
 *    - pending charges are persisted as {@link FlutterwaveV4PendingCharge}
 *      rows (durable across restarts, safe across instances);
 *    - {@link FlutterwaveV4DepositReconciler} polls every still-PENDING
 *      charge against Flutterwave's live GET /charges/{id} on a fixed delay
 *      (~8s initially, backing off to 2min, giving up after 45min) and
 *      credits on confirmed success.
 *
 *  The webhook and /verify flows below are UNCHANGED in behaviour — they're
 *  still the fast paths. All three routes funnel through the idempotent
 *  handleVerifiedDeposit(), so whichever gets there first wins and the
 *  others no-op. Worst case now is a deposit landing seconds-to-minutes
 *  late instead of never.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — Flutterwave's v4 orchestrator API rejects any
 *  `reference` outside 6–42 characters:
 *
 *    {"status":"failed","error":{"type":"REQUEST_NOT_VALID","code":"10400",
 *     "message":"Request is not valid","validation_errors":[{"field_name":
 *     "reference","message":"size must be between 6 and 42"}]}}
 *
 *  The previous reference format —
 *    "SPB-GH-V4-" + <36-char user UUID> + "-" + <36-char random UUID>
 *  — is ~85 characters, so EVERY v4 Ghana charge was failing at
 *  orchestratorCharge() with a 400 before that fix.
 *
 *  The user UUID had been embedded in the reference so the webhook could
 *  recover the userId from the reference string alone (v4 doesn't reliably
 *  echo custom `meta` back the way v3 does). It's no longer needed: the
 *  reference is now a short opaque token ("GHV4-" + 32 hex = 37 chars) and
 *  userId is looked up from the persisted pending-charge row instead. That
 *  lookup used to be the in-memory map (and so was restart-fragile); as of
 *  the fix above it's a DB row, so this is now durable.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — Ghana mobile money network values corrected
 *  against Flutterwave's live GET /mobile-networks?country=GH response:
 *
 *    {"status":"success","data":[
 *      {"id":"79","network":"AIRTELTIGO","name":"AIRTEL-TIGO"},
 *      {"id":"82","network":"MTN","name":"MTN Mobile"},
 *      {"id":"80","network":"VODAFONE","name":"Vodafone"}
 *    ]}
 *
 *  This SUPERSEDES an earlier assumption that Flutterwave had renamed
 *  Vodafone Ghana to "TELECEL" on the API side (following the real-world
 *  Telecel rebrand). The live endpoint confirms that guess was wrong:
 *  Flutterwave still expects the literal value "VODAFONE", and "TELECEL"
 *  is not a recognized network value at all. Sending "TELECEL" would have
 *  failed every Vodafone/Telecel deposit in production.
 *
 *  Backward compatibility: resolveNetwork() below still accepts "TIGO"
 *  and "AIRTEL" (pre-merger legacy values) and "TELECEL" (in case any
 *  client already picked up the previous, incorrect revision) and maps
 *  them onto the correct current network names rather than rejecting them.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ─── Flow ─────────────────────────────────────────────────────────────────
 *
 *  1. Initiate charge — Deposit
 *     POST /api/wallet/deposit/flutterwave/gh/v4/init
 *     • Accepts { amount, phoneNumber?, network }.
 *     • Calls orchestratorCharge() with payment_method.type = "mobile_money".
 *     • PERSISTS reference -> Flutterwave charge id + userId via
 *       cachePendingCharge(). This row is what the webhook, /verify AND the
 *       background reconciler all key off — without it the charge is
 *       unrecoverable, so it's written before the client gets a response.
 *     • Returns { txRef, message }. No OTP screen — frontend goes straight
 *       to "waiting for approval on your phone".
 *
 *  2. Payment Verification (frontend polling)
 *     POST /api/wallet/deposit/flutterwave/gh/v4/verify
 *     • Delegates to the shared verifyAndCredit() — safe to poll, credits
 *       once Flutterwave's own API confirms success.
 *
 *  3. Webhook (primary automatic credit path)
 *     POST /api/webhooks/flutterwave/gh/v4
 *     • Delegates to the shared processV4Webhook(), which re-verifies via
 *       Flutterwave's API before crediting and never trusts the payload
 *       directly. Resolves userId from the persisted pending-charge row.
 *     • Also reachable via {@link FlutterwaveWebhookRouterController}, which
 *       is the URL actually registered in the Flutterwave dashboard (only
 *       one webhook URL is allowed per account).
 *
 *  4. Background reconciler (safety net — no endpoint)
 *     • {@link FlutterwaveV4DepositReconciler} polls this controller's
 *       PENDING rows via reconcilePendingCharges() until every charge
 *       settles. This is what guarantees a customer who approved the prompt
 *       gets credited even if 2 and 3 both fail and they close the app.
 *
 * ─── txRef / reference convention ────────────────────────────────────────
 *   "GHV4-<32 hex chars>" (37 chars total) — an opaque random token; the
 *   owning userId is resolved from the persisted pending-charge row, not
 *   parsed out of this string (see FIX notes above).
 *
 * ─── network values accepted from the frontend ───────────────────────────
 *   "MTN", "AIRTELTIGO", "VODAFONE" — confirmed against Flutterwave's live
 *   /mobile-networks endpoint — plus the legacy "TIGO"/"AIRTEL" and the
 *   now-known-incorrect "TELECEL" value, accepted for backward
 *   compatibility and mapped onto "AIRTELTIGO" / "VODAFONE" respectively.
 *
 * ─── application.properties keys needed ──────────────────────────────────
 *   See AbstractFlutterwaveV4DepositController for the shared v4 keys
 *   (client-id, client-secret, base-url, token-url, webhook-hash) and the
 *   app.flutterwave.v4.reconcile.* tuning keys.
 *   This controller additionally needs:
 *     app.platform.min-deposit-amount-ghs (default: 1)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveGhV4DepositController extends AbstractFlutterwaveV4DepositController {

    private static final String EXPECTED_CURRENCY   = "GHS";
    private static final String GH_DIAL_CODE        = "233";

    /**
     * Networks accepted from the frontend, including legacy/superseded
     * values kept for backward compatibility (see resolveNetwork()).
     * Validation happens against this full set; the value actually sent
     * to Flutterwave is always the resolved, API-confirmed network name
     * (MTN / AIRTELTIGO / VODAFONE — see class javadoc FIX note).
     */
    private static final Set<String> VALID_NETWORKS =
            Set.of("MTN", "AIRTELTIGO", "VODAFONE", "TIGO", "AIRTEL", "TELECEL");

    private static final String TXREF_PREFIX        = "GHV4-";
    private static final String PROVIDER_TAG        = "flutterwave_gh_v4";

    private final WalletService                   walletService;
    private final ReferralService                 referralService;
    private final WebClient.Builder               webClientBuilder;
    private final ObjectMapper                    objectMapper;
    private final FlutterwaveV4PendingChargeStore pendingChargeStore;

    @Value("${app.platform.min-deposit-amount-ghs:1}")
    private BigDecimal minDeposit;

    @Override protected WalletService     walletService()     { return walletService; }
    @Override protected ReferralService   referralService()   { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper      objectMapper()      { return objectMapper; }

    @Override protected FlutterwaveV4PendingChargeStore pendingChargeStore() { return pendingChargeStore; }

    /** Used by the reconciler, which runs without a request context. */
    @Override public String expectedCurrency() { return EXPECTED_CURRENCY; }
    @Override public String providerTag()      { return PROVIDER_TAG; }

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

        var rawNetwork = req.get("network");
        if (rawNetwork == null || !VALID_NETWORKS.contains(rawNetwork.toString().toUpperCase())) {
            throw ApiException.badRequest("network must be one of MTN, AIRTELTIGO, VODAFONE");
        }
        // Resolves legacy "TIGO"/"AIRTEL" and the superseded "TELECEL"
        // value onto the network names Flutterwave's live API actually
        // recognizes — see class javadoc FIX note.
        var network = resolveNetwork(rawNetwork.toString());

        // Flutterwave v4 requires `reference` to be 6–42 characters, so this
        // stays short and opaque: "GHV4-" (5) + 32 hex = 37 chars. The userId
        // is NOT encoded here — it's persisted against this reference below.
        var txRef = TXREF_PREFIX + UUID.randomUUID().toString().replace("-", "");

        log.info("initDeposit(GH v4): userId='{}' amount={} network='{}' txRef='{}'",
                user.getId(), amount, network, txRef);

        var mobileMoney = new LinkedHashMap<String, Object>();
        mobileMoney.put("country_code", GH_DIAL_CODE);
        mobileMoney.put("network", network);
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

            // CRITICAL: this row is the only handle we'll ever have on this
            // charge — v4 has no lookup-by-our-reference. It carries the
            // userId for the webhook, the chargeId for /verify, and puts the
            // charge on the reconciler's queue so it gets credited even if
            // every other path fails. Written before the client responds.
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
     * userId is resolved from the persisted pending-charge row written in
     * initDeposit() — durable across restarts and instances, unlike the
     * in-memory map this replaced.
     */
    @PostMapping("/api/webhooks/flutterwave/gh/v4")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {
        return processV4Webhook(verifHash, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG,
                this::resolveUserIdFromStore);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Resolves the owning userId for a reference from the persisted
     * pending-charge row. Returns null (causing the webhook to reject with
     * 400) only if no such row exists — which now means the reference was
     * never ours or was pruned, not merely that we restarted.
     */
    private UUID resolveUserIdFromStore(String txRef) {
        var pending = getPendingCharge(txRef);
        if (pending == null) {
            log.error("resolveUserIdFromStore(GH v4): no pending charge row for txRef='{}'", txRef);
            return null;
        }
        return pending.userId();
    }

    /**
     * Maps an accepted frontend network value onto the network name
     * Flutterwave's live API actually recognizes (confirmed via
     * GET /mobile-networks?country=GH — see class javadoc FIX note):
     * MTN, AIRTELTIGO, VODAFONE.
     *
     * "TIGO" and "AIRTEL" (pre-merger legacy values) and "TELECEL" (sent
     * by a previous, incorrect revision of this controller) are accepted
     * here purely for backward compatibility with any client still
     * sending an old value, and are mapped onto their correct current
     * equivalents rather than rejected.
     */
    private static String resolveNetwork(String rawNetwork) {
        return switch (rawNetwork.toUpperCase()) {
            case "TIGO", "AIRTEL", "AIRTELTIGO" -> "AIRTELTIGO";
            case "TELECEL", "VODAFONE" -> "VODAFONE";
            default -> "MTN";
        };
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
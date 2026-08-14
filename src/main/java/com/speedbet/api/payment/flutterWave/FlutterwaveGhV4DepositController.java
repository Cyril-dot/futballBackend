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
 * (v3, redirect-based). Flutterwave has no backend-relayed-OTP option for
 * Ghana MoMo in either API version; v4's push-notification flow just
 * removes the browser redirect — the customer still authorizes on their own
 * phone with their PIN.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  IMPORTANT — read {@link AbstractFlutterwaveV4DepositController}'s class
 *  javadoc before deploying. v4 is public beta, the production base URL
 *  needs confirming, and the webhook signature header + payload shape are
 *  the two things that have actually bitten in production.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — webhook signature header, and a /verify 500 leak.
 *
 *  Production logs showed EVERY inbound webhook rejected with "missing
 *  verif-hash header" — v4 does not send v3's header. The webhook therefore
 *  credited nothing, ever, which is the root cause of "user deposits but
 *  it doesn't enter their account". The webhook endpoint below now forwards
 *  the whole header map to the shared handler, which matches
 *  case-insensitively across candidate names and can log unknown ones. See
 *  the abstract class's FIX note for the two temporary config flags used to
 *  identify the real header.
 *
 *  Separately, /verify used to propagate Flutterwave's 10500
 *  INTERNAL_SERVER_ERROR straight out to the client on every poll. That's
 *  now reported as "still confirming" — see verifyAndCredit().
 *
 *  Operational note from the same logs: the GHS 1 test deposits are the ones
 *  whose GET /charges/{id} 500s persistently, while GHS 200 charges resolve
 *  fine. That points at a Flutterwave-side minimum rather than a bug here —
 *  consider raising app.platform.min-deposit-amount-ghs above 1.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — durable pending charges + background reconciler.
 *
 *  Pending charges were an in-memory ConcurrentHashMap, so a restart between
 *  init and webhook destroyed the only handle we had on the charge. They're
 *  now {@link FlutterwaveV4PendingCharge} rows, and
 *  {@link FlutterwaveV4DepositReconciler} polls every still-PENDING charge
 *  against Flutterwave's live API until it settles. Given the webhook was
 *  100% broken, the reconciler is currently doing the real work.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — reference length. Flutterwave v4 rejects any
 *  `reference` outside 6–42 characters:
 *
 *    {"status":"failed","error":{"type":"REQUEST_NOT_VALID","code":"10400",
 *     "message":"Request is not valid","validation_errors":[{"field_name":
 *     "reference","message":"size must be between 6 and 42"}]}}
 *
 *  The old format ("SPB-GH-V4-" + user UUID + "-" + random UUID, ~85 chars)
 *  failed every charge. Now "GHV4-" + 32 hex = 37 chars, and the userId is
 *  resolved from the persisted pending-charge row rather than parsed out of
 *  the reference string.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — Ghana network values, confirmed against
 *  Flutterwave's live GET /mobile-networks?country=GH:
 *
 *    {"status":"success","data":[
 *      {"id":"79","network":"AIRTELTIGO","name":"AIRTEL-TIGO"},
 *      {"id":"82","network":"MTN","name":"MTN Mobile"},
 *      {"id":"80","network":"VODAFONE","name":"Vodafone"}
 *    ]}
 *
 *  This superseded an earlier guess that Flutterwave had renamed Vodafone
 *  Ghana to "TELECEL" following the real-world rebrand. It hasn't —
 *  "TELECEL" is not a recognized value and would have failed every
 *  Vodafone deposit. resolveNetwork() still accepts the legacy
 *  "TIGO"/"AIRTEL" and the incorrect "TELECEL" from un-updated clients and
 *  maps them onto the correct names.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ─── Flow ─────────────────────────────────────────────────────────────────
 *
 *  1. POST /api/wallet/deposit/flutterwave/gh/v4/init
 *     • Accepts { amount, phoneNumber?, network }.
 *     • Calls orchestratorCharge() with payment_method.type = "mobile_money".
 *     • PERSISTS reference -> charge id + userId via cachePendingCharge().
 *       Without this row the charge is unrecoverable — v4 has no
 *       lookup-by-our-reference.
 *     • Returns { txRef, message }. No OTP screen.
 *
 *  2. POST /api/wallet/deposit/flutterwave/gh/v4/verify
 *     • Shared verifyAndCredit() — safe to poll, credits once Flutterwave's
 *       own API confirms success.
 *
 *  3. POST /api/webhooks/flutterwave/gh/v4
 *     • Shared processV4Webhook(), which re-verifies via Flutterwave's API
 *       before crediting and never trusts the payload.
 *     • Also reachable via {@link FlutterwaveWebhookRouterController}, the
 *       URL actually registered in the dashboard (one per account).
 *
 *  4. Background reconciler (no endpoint) — the safety net.
 *
 *  All three credit paths funnel into the idempotent handleVerifiedDeposit();
 *  whichever lands first wins.
 *
 * ─── application.properties keys needed ──────────────────────────────────
 *   See AbstractFlutterwaveV4DepositController for the shared v4 keys and
 *   the reconcile/webhook-diagnostic keys. This controller additionally
 *   needs:
 *     app.platform.min-deposit-amount-ghs (default: 1 — see FIX note, likely
 *                                          too low for Flutterwave)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveGhV4DepositController extends AbstractFlutterwaveV4DepositController {

    private static final String EXPECTED_CURRENCY = "GHS";
    private static final String GH_DIAL_CODE      = "233";

    /**
     * Networks accepted from the frontend, including legacy/superseded
     * values kept for backward compatibility (see resolveNetwork()). The
     * value actually sent to Flutterwave is always the resolved,
     * API-confirmed name.
     */
    private static final Set<String> VALID_NETWORKS =
            Set.of("MTN", "AIRTELTIGO", "VODAFONE", "TIGO", "AIRTEL", "TELECEL");

    private static final String TXREF_PREFIX = "GHV4-";
    private static final String PROVIDER_TAG = "flutterwave_gh_v4";

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

    /** Durable pending-charge store — replaced the in-memory map. */
    @Override protected FlutterwaveV4PendingChargeStore pendingChargeStore() { return pendingChargeStore; }

    /**
     * Exposed to the base class because the reconciler runs without a request
     * context. providerTag() MUST match what's passed to verifyAndCredit()
     * and processV4Webhook() below, or the reconciler looks for rows under a
     * tag nothing writes and silently polls nothing.
     */
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
        var network = resolveNetwork(rawNetwork.toString());

        // Flutterwave v4 requires `reference` to be 6–42 characters. Short,
        // opaque, random — userId is NOT encoded here, it's persisted below.
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

            // CRITICAL: the only handle we'll ever have on this charge. Carries
            // the userId for the webhook, the chargeId for /verify, and puts the
            // charge on the reconciler's queue. Written before the client responds.
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
     * Standalone GH v4 webhook endpoint. Kept for direct testing (curl), but
     * Flutterwave should be pointed at FlutterwaveWebhookRouterController's
     * /api/webhooks/flutterwave instead — only one webhook URL per account.
     *
     * Takes the FULL header map rather than a named @RequestHeader: v4 does
     * not send v3's "verif-hash", and binding to that one name is what made
     * every delivery fail authentication. The shared handler decides which
     * header carries the signature — see the abstract class's FIX note.
     */
    @PostMapping("/api/webhooks/flutterwave/gh/v4")
    public ResponseEntity<String> webhook(
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] rawBody) {
        return processV4Webhook(headers, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG,
                this::resolveUserIdFromStore);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Resolves the owning userId for a reference from the persisted
     * pending-charge row. Returns null (webhook rejects with 400) only if no
     * such row exists — which now means the reference was never ours or was
     * pruned, not merely that we restarted.
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
     * Maps an accepted frontend network value onto the name Flutterwave's
     * live API actually recognizes (confirmed via GET /mobile-networks?country=GH):
     * MTN, AIRTELTIGO, VODAFONE.
     *
     * "TIGO"/"AIRTEL" (pre-merger legacy) and "TELECEL" (sent by a previous,
     * incorrect revision) are accepted purely for backward compatibility with
     * un-updated clients and mapped onto their correct equivalents.
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
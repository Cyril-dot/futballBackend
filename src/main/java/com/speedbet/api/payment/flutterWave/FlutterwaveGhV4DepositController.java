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
 * ─── Flow ─────────────────────────────────────────────────────────────────
 *
 *  1. Initiate charge — Deposit
 *     POST /api/wallet/deposit/flutterwave/gh/v4/init
 *     • Accepts { amount, phoneNumber?, network }.
 *     • Calls orchestratorCharge() with payment_method.type = "mobile_money".
 *     • Caches reference -> Flutterwave charge id via cachePendingCharge().
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
 *       directly.
 *     • Also reachable via {@link FlutterwaveWebhookRouterController}, which
 *       is the URL actually registered in the Flutterwave dashboard (only
 *       one webhook URL is allowed per account).
 *
 * ─── txRef / reference convention ────────────────────────────────────────
 *   "SPB-GH-V4-<userId>-<uuid>" — userId is parsed back out of this string
 *   in the webhook handler, since v4's meta round-trip isn't relied upon.
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
    private static final String TXREF_PREFIX        = "SPB-GH-V4-";
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

        var txRef = TXREF_PREFIX + user.getId() + "-" + UUID.randomUUID();

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
     */
    @PostMapping("/api/webhooks/flutterwave/gh/v4")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {
        return processV4Webhook(verifHash, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG,
                FlutterwaveGhV4DepositController::extractUserIdFromTxRef);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static UUID extractUserIdFromTxRef(String txRef) {
        // Format: SPB-GH-V4-<uuid>-<uuid>
        if (!txRef.startsWith(TXREF_PREFIX)) return null;
        var remainder = txRef.substring(TXREF_PREFIX.length());
        if (remainder.length() < 36) return null;
        try {
            return UUID.fromString(remainder.substring(0, 36));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
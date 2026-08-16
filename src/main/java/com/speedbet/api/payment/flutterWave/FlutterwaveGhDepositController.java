package com.speedbet.api.payment.flutterWave;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ghanaian Cedi (GHS) deposits via Flutterwave Mobile Money Ghana charge.
 *
 * Flow:
 *   1. POST /api/wallet/deposit/flutterwave/gh/init
 *        -> calls POST /v3/charges?type=mobile_money_ghana
 *        -> response contains meta.authorization.redirect — the frontend
 *           must send the customer there to complete OTP/captcha verification
 *   2. Customer completes verification at the redirect URL
 *   3. Flutterwave sends a "charge.completed" webhook
 *        -> we re-verify the transaction server-side, then credit the wallet
 *   4. GET /api/wallet/deposit/flutterwave/gh/status?ref={tx_ref}
 *        -> read-only poll the frontend can call while waiting on step 3,
 *           so the UI has something to show between "customer completed the
 *           redirect" and "webhook actually landed and credited." See the
 *           FIX note below — this endpoint did not exist before and is why
 *           this controller couldn't be used as a drop-in replacement for
 *           the v4 push-notification flow, which had its own /verify.
 *
 * NOTE: Flutterwave only allows one webhook URL per dashboard account.
 * Register FlutterwaveWebhookRouterController's URL
 * (/api/webhooks/flutterwave) in the Flutterwave dashboard — NOT this
 * controller's /api/webhooks/flutterwave/gh directly. The router inspects
 * data.currency and delegates here via webhook(...) below. This endpoint
 * still works standalone too (e.g. for manual curl testing).
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — added GET .../gh/status, a read-only poll endpoint.
 *
 *  This controller previously had init() and webhook() only. The v4 GH
 *  controller (FlutterwaveGhV4DepositController) additionally exposed
 *  POST .../gh/v4/verify for the frontend to poll while waiting on a push
 *  notification. v3 has no equivalent charge type — the customer instead
 *  completes an OTP/captcha redirect — but the frontend still needs
 *  something to poll in between "redirect completed" and "webhook landed
 *  and credited," the same way the existing NG bank v3 flow
 *  (FlutterwaveNgBankDepositController, referenced in the base class
 *  javadoc) already does via statusResponse()/verifyTransactionByReference().
 *
 *  This is being adopted now specifically because v4's GH flow (push
 *  notification, no redirect) has an unresolved, Flutterwave-side webhook
 *  signature mismatch (their dashboard "Secret hash" does not match what
 *  actually signs production deliveries) plus persistent 500s on
 *  GET /charges/{id}, both confirmed via direct testing against Flutterwave's
 *  own API — see AbstractFlutterwaveV4DepositController's FIX 6 note. v3
 *  does not share either failure mode: its webhook auth is a simple static
 *  secret comparison (no HMAC/Svix mismatch possible), and its
 *  verify_by_reference lookup has not exhibited the same persistent 500s in
 *  production logs. Until Flutterwave resolves the v4 issues, GH MoMo
 *  deposits should go through this v3 flow instead.
 *
 *  IMPORTANT: this new status() method is READ-ONLY, matching the base
 *  class's contract for statusResponse() — it never credits a wallet.
 *  Crediting only ever happens through webhook() -> processWebhook() ->
 *  verifyTransaction(), which re-verifies server-side before crediting. A
 *  client polling status() and seeing "successful" slightly before the
 *  webhook has actually landed and credited is expected and safe — the
 *  frontend should keep showing a confirming state until the webhook (or a
 *  follow-up status() poll after it lands) reflects the credit, and
 *  handleVerifiedDeposit() is idempotent on tx_ref regardless of ordering.
 * ══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveGhDepositController extends AbstractFlutterwaveDepositController {

    static final String CHARGE_TYPE       = "mobile_money_ghana";
    static final String EXPECTED_CURRENCY = "GHS";
    static final String PROVIDER_TAG      = "flutterwave_gh";
    private static final Set<String> VALID_NETWORKS = Set.of("MTN", "VODAFONE", "TIGO");

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.platform.min-deposit-amount-ghs:1}")
    private BigDecimal minDeposit;

    @Value("${app.platform.frontend-url}")
    private String frontendUrlOverride; // kept separate from base class field for clarity in logs

    @Override protected WalletService walletService()         { return walletService; }
    @Override protected ReferralService referralService()     { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper objectMapper()           { return objectMapper; }

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/gh/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req,
            HttpServletRequest servletRequest) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        }

        // Fall back to the phone number on file if the request doesn't override it.
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

        var txRef    = "SPB-GH-" + UUID.randomUUID();
        var clientIp = servletRequest.getRemoteAddr();
        var fullName = fullName(user);

        log.info("initDeposit(GH): userId='{}' amount={} network='{}' txRef='{}'",
                user.getId(), amount, network, txRef);

        var body = new HashMap<String, Object>();
        body.put("amount", amount);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("email", user.getEmail());
        body.put("tx_ref", txRef);
        body.put("phone_number", phoneNumber);
        body.put("network", network.toString().toUpperCase());
        body.put("fullname", fullName);
        body.put("client_ip", clientIp);
        // Points at the dedicated CheckoutCallbackPage rather than a generic
        // "/wallet?payment=success" path — Flutterwave appends its own
        // query params (status, tx_ref, transaction_id) to whatever URL is
        // given here, and the callback page is what reads those and hands
        // off back into the deposit flow. See CheckoutCallbackPage.tsx.
        body.put("redirect_url", frontendUrlOverride + "/deposit/callback");
        body.put("meta", Map.of("userId", user.getId().toString()));

        var response = charge(CHARGE_TYPE, body);

        // ══════════════════════════════════════════════════════════════════
        //  FIX v2 (this revision) — corrected tx_ref injection.
        //
        //  The previous revision assumed Flutterwave's response had a nested
        //  "data" object to merge tx_ref into (response.get("data")). A real
        //  captured response proved that's wrong for this charge type —
        //  Flutterwave's response is FLAT:
        //    {"status":"success","message":"Charge initiated",
        //     "meta":{"authorization":{"redirect":"...","mode":"redirect"}}}
        //  — status/message/meta sit at the TOP level, no "data" key at all.
        //  Because response.get("data") was null, the old code created a
        //  brand-new, otherwise-empty "data" object containing only tx_ref
        //  and added it as an extra sibling field — burying tx_ref one level
        //  deeper than the frontend was looking (flwData.data.tx_ref instead
        //  of flwData.tx_ref), so every deposit still failed at init with
        //  "missing reference" despite this "fix."
        //
        //  Corrected: add tx_ref directly as a top-level sibling of
        //  status/message/meta, matching Flutterwave's actual flat shape.
        // ══════════════════════════════════════════════════════════════════
        var augmentedResponse = new HashMap<>(response);
        augmentedResponse.putIfAbsent("tx_ref", txRef);

        log.info("initDeposit(GH): Flutterwave responded status='{}' for userId='{}' txRef='{}'",
                response.get("status"), user.getId(), txRef);

        return ResponseEntity.ok(ApiResponse.ok(augmentedResponse));
    }

    // ─── Status (read-only poll) ────────────────────────────────────────────────

    /**
     * Read-only poll the frontend calls while waiting on the webhook to land
     * after the customer completes the OTP/captcha redirect. See the FIX
     * note in the class javadoc for why this was added and why it's safe:
     * it delegates straight to the base class's statusResponse(), which
     * never credits anything — only webhook()/processWebhook() does that.
     *
     * @param ref the tx_ref returned from initDeposit()'s response body
     *            (nested under data.tx_ref in Flutterwave's raw charge
     *            response — confirm the exact path against a real response
     *            before wiring the frontend, since this controller currently
     *            passes Flutterwave's response straight through unmodified)
     */
    @GetMapping("/api/wallet/deposit/flutterwave/gh/status")
    public ResponseEntity<Map<String, Object>> status(
            @AuthenticationPrincipal User user,
            @RequestParam String ref) {
        log.info("status(GH): userId='{}' ref='{}'", user.getId(), ref);
        return statusResponse(ref);
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Standalone GH webhook endpoint. Kept for direct testing (e.g. curl),
     * but Flutterwave itself should be pointed at
     * FlutterwaveWebhookRouterController's /api/webhooks/flutterwave instead,
     * since only one webhook URL can be registered per Flutterwave account.
     */
    @PostMapping("/api/webhooks/flutterwave/gh")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {
        return processWebhook(verifHash, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * User has no getFullName() — build one from firstName/lastName, falling
     * back to the email local-part if both are blank (Flutterwave requires a
     * non-empty fullname on some charge types).
     */
    private static String fullName(User user) {
        var first = user.getFirstName();
        var last  = user.getLastName();
        var name  = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
        if (!name.isBlank()) {
            return name;
        }
        var email = user.getEmail();
        return email != null ? email.split("@")[0] : "Customer";
    }
}
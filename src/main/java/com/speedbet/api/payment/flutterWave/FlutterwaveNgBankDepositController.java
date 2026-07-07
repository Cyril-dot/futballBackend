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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Nigerian Naira (NGN) deposits via Flutterwave "Pay with Bank (NG)" charge
 * (Flutterwave calls this the "mono" charge type).
 *
 * Flow:
 *   1. POST /api/wallet/deposit/flutterwave/ng-bank/init
 *        -> calls POST /v3/charges?type=mono, passing redirect_url pointing
 *           back at THIS controller's /redirect endpoint (not the frontend
 *           directly — see note below)
 *        -> Flutterwave returns data.auth_url — redirect the customer there
 *           to log in to / link their bank account and authorize the debit
 *           (data.status == "pending" at this point — nothing has been
 *           charged until the customer completes the auth_url flow)
 *   2. Customer completes authorization at auth_url. Flutterwave then
 *      redirects the customer's browser (GET) to our redirect_url, appending
 *      its own query params: status, tx_ref, transaction_id.
 *   3. GET /api/wallet/deposit/flutterwave/ng-bank/redirect (this controller)
 *        -> normalizes Flutterwave's "status" into our frontend's expected
 *           "payment=success|failed" shape and 302s the browser to
 *           {frontendUrl}/deposit?payment=...&tx_ref=...&method=ngbank
 *        -> This is just a UX hop for showing a waiting/status screen — it
 *           does NOT credit the wallet. Crediting only ever happens via the
 *           webhook + server-side verifyTransaction(), since a redirect can
 *           be spoofed/skipped by the client.
 *   4. Flutterwave (separately) sends a "charge.completed" webhook
 *        -> we re-verify the transaction server-side, then credit the wallet
 *
 * NOTE: Flutterwave only allows one webhook URL per dashboard account.
 * Register FlutterwaveWebhookRouterController's URL
 * (/api/webhooks/flutterwave) in the Flutterwave dashboard — NOT this
 * controller's /api/webhooks/flutterwave/ng-bank directly. The router
 * inspects data.currency and delegates to FlutterwaveNgDepositController's
 * webhook() for any NGN event (it doesn't distinguish USSD vs. bank charges,
 * since the shared processWebhook() logic only needs currency + tx id).
 * This endpoint still works standalone (e.g. for manual curl testing).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveNgBankDepositController extends AbstractFlutterwaveDepositController {

    static final String CHARGE_TYPE       = "mono";
    static final String EXPECTED_CURRENCY = "NGN";
    static final String PROVIDER_TAG      = "flutterwave_ng_bank";

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.platform.min-deposit-amount-ngn:100}")
    private BigDecimal minDeposit;

    /**
     * Publicly reachable base URL of THIS backend (e.g.
     * "https://futballbackend-production-67b0.up.railway.app"), used to build
     * the redirect_url Flutterwave calls back after bank authorization.
     * Distinct from frontendUrl (inherited from the abstract base), which is
     * where we forward the browser to *after* normalizing Flutterwave's params.
     */
    @Value("${app.platform.backend-public-url}")
    private String backendPublicUrl;

    @Override protected WalletService walletService()         { return walletService; }
    @Override protected ReferralService referralService()     { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper objectMapper()           { return objectMapper; }

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/ng-bank/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req,
            HttpServletRequest servletRequest) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest("Minimum deposit is NGN " + minDeposit);
        }

        // Fall back to the phone number on file if the request doesn't override it.
        var phoneNumber = req.get("phoneNumber") != null
                ? req.get("phoneNumber").toString()
                : user.getPhone();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw ApiException.badRequest("phoneNumber is required");
        }

        var txRef    = "SPB-NGB-" + UUID.randomUUID();
        var clientIp = servletRequest.getRemoteAddr();
        var fullName = fullName(user);

        log.info("initDeposit(NG-Bank): userId='{}' amount={} txRef='{}'",
                user.getId(), amount, txRef);

        // Points back at OUR /redirect endpoint below (not the frontend
        // directly) so we can normalize Flutterwave's status/tx_ref params
        // into the shape the frontend expects before forwarding the browser.
        var redirectUrl = backendPublicUrl + "/api/wallet/deposit/flutterwave/ng-bank/redirect";

        var body = new HashMap<String, Object>();
        body.put("amount", amount);
        body.put("email", user.getEmail());
        body.put("tx_ref", txRef);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("fullname", fullName);
        body.put("phone_number", phoneNumber);
        body.put("client_ip", clientIp);
        body.put("redirect_url", redirectUrl);
        body.put("meta", Map.of("userId", user.getId().toString()));

        var response = charge(CHARGE_TYPE, body);

        log.info("initDeposit(NG-Bank): Flutterwave responded status='{}' for userId='{}' txRef='{}'",
                response.get("status"), user.getId(), txRef);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Standalone NG-Bank webhook endpoint. Kept for direct testing (e.g.
     * curl), but Flutterwave itself should be pointed at
     * FlutterwaveWebhookRouterController's /api/webhooks/flutterwave instead
     * — the router already forwards every NGN charge.completed event
     * (regardless of charge type) to FlutterwaveNgDepositController's
     * webhook(), and processWebhook() only cares about currency + tx id, so
     * bank-initiated deposits are already covered without any router change.
     */
    @PostMapping("/api/webhooks/flutterwave/ng-bank")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {
        return processWebhook(verifHash, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG);
    }

    // ─── Redirect callback (browser hop, NOT a trust boundary) ────────────────

    /**
     * Flutterwave sends the customer's browser here (GET) after they finish
     * authorizing the bank charge at auth_url. Flutterwave appends its own
     * query params — typically:
     *   status         "successful" | "failed" | "cancelled"
     *   tx_ref         our original tx_ref
     *   transaction_id Flutterwave's numeric transaction id
     *
     * We do NOT trust "status" here for crediting anything — it's purely
     * cosmetic, to decide which waiting/result screen to show the customer
     * while they wait for the webhook (processWebhook() -> verifyTransaction())
     * to actually credit the wallet. We just normalize it into the
     * "payment=success|failed" shape the frontend's DepositPage already
     * handles for the GH Mobile Money redirect, tagging it with
     * "method=ngbank" so the frontend routes it to the right state instead of
     * defaulting to the GH flow.
     *
     * A missing/blank tx_ref, or any status other than "successful", is
     * treated as failed — better to show "couldn't confirm, contact support"
     * than to imply success on a bad or incomplete callback.
     */
    @GetMapping("/api/wallet/deposit/flutterwave/ng-bank/redirect")
    public ResponseEntity<Void> redirect(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "tx_ref", required = false) String txRef,
            @RequestParam(value = "transaction_id", required = false) String transactionId) {

        var normalizedStatus = "successful".equalsIgnoreCase(status) && txRef != null && !txRef.isBlank()
                ? "success"
                : "failed";

        log.info("redirect(NG-Bank): flutterwave status='{}' txRef='{}' transactionId='{}' -> forwarding as '{}'",
                status, txRef, transactionId, normalizedStatus);

        var target = UriComponentsBuilder.fromUriString(frontendUrl + "/deposit")
                .queryParam("payment", normalizedStatus)
                .queryParamIfPresent("tx_ref", java.util.Optional.ofNullable(txRef))
                .queryParam("method", "ngbank")
                .build(true)
                .toUri();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .build();
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
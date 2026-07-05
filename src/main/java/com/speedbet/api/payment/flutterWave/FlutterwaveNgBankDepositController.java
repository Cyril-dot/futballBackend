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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

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
 *        -> calls POST /v3/charges?type=mono
 *        -> Flutterwave returns data.auth_url — redirect the customer there
 *           to log in to / link their bank account and authorize the debit
 *           (data.status == "pending" at this point — nothing has been
 *           charged until the customer completes the auth_url flow)
 *   2. Customer completes authorization at auth_url
 *   3. Flutterwave sends a "charge.completed" webhook
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

    @Value("${app.platform.min-deposit-amount-ngn:200}")
    private BigDecimal minDeposit;

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

        var body = new HashMap<String, Object>();
        body.put("amount", amount);
        body.put("email", user.getEmail());
        body.put("tx_ref", txRef);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("fullname", fullName);
        body.put("phone_number", phoneNumber);
        body.put("client_ip", clientIp);
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
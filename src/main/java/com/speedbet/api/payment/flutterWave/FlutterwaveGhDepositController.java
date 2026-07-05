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
 *
 * NOTE: Flutterwave only allows one webhook URL per dashboard account.
 * Register FlutterwaveWebhookRouterController's URL
 * (/api/webhooks/flutterwave) in the Flutterwave dashboard — NOT this
 * controller's /api/webhooks/flutterwave/gh directly. The router inspects
 * data.currency and delegates here via webhook(...) below. This endpoint
 * still works standalone too (e.g. for manual curl testing).
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
        body.put("redirect_url", frontendUrlOverride + "/wallet?payment=success");
        body.put("meta", Map.of("userId", user.getId().toString()));

        var response = charge(CHARGE_TYPE, body);

        log.info("initDeposit(GH): Flutterwave responded status='{}' for userId='{}' txRef='{}'",
                response.get("status"), user.getId(), txRef);

        return ResponseEntity.ok(ApiResponse.ok(response));
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
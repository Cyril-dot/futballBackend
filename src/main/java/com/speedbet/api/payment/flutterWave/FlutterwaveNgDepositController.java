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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Nigerian Naira (NGN) deposits via Flutterwave USSD charge.
 *
 * Flow:
 *   1. POST /api/wallet/deposit/flutterwave/ng/init
 *        -> calls POST /v3/charges?type=ussd
 *        -> returns the USSD dial code / instructions to the frontend
 *           (data.status == "pending" at this point — the customer still
 *           has to dial the code on their phone to actually pay)
 *   2. Customer dials the USSD code and completes payment on their bank's menu
 *   3. Flutterwave sends a "charge.completed" webhook to
 *        POST /api/webhooks/flutterwave/ng
 *        -> we re-verify the transaction server-side, then credit the wallet
 *
 * NOTE: Flutterwave only allows one webhook URL per dashboard account. If
 * this and the Ghana controller are both deployed, only one of these
 * /webhooks endpoints can be registered directly — otherwise add a small
 * router in front of both that inspects data.currency and delegates.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveNgDepositController extends AbstractFlutterwaveDepositController {

    private static final String CHARGE_TYPE       = "ussd";
    private static final String EXPECTED_CURRENCY = "NGN";
    private static final String PROVIDER_TAG      = "flutterwave_ng";

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.platform.min-deposit-amount-ngn:100}")
    private BigDecimal minDeposit;

    @Override protected WalletService walletService()         { return walletService; }
    @Override protected ReferralService referralService()     { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper objectMapper()           { return objectMapper; }

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/ng/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req,
            HttpServletRequest servletRequest) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest("Minimum deposit is NGN " + minDeposit);
        }

        var accountBank = req.get("accountBank");
        if (accountBank == null || accountBank.toString().isBlank()) {
            throw ApiException.badRequest("accountBank is required (e.g. '058' for GTBank)");
        }

        // Fall back to the phone number on file if the request doesn't override it.
        var phoneNumber = req.get("phoneNumber") != null
                ? req.get("phoneNumber").toString()
                : user.getPhone();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw ApiException.badRequest("phoneNumber is required");
        }

        var txRef    = "SPB-NG-" + UUID.randomUUID();
        var clientIp = servletRequest.getRemoteAddr();
        var fullName = fullName(user);

        log.info("initDeposit(NG): userId='{}' amount={} accountBank='{}' txRef='{}'",
                user.getId(), amount, accountBank, txRef);

        var body = new java.util.HashMap<String, Object>();
        body.put("account_bank", accountBank.toString());
        body.put("amount", amount);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("email", user.getEmail());
        body.put("tx_ref", txRef);
        body.put("phone_number", phoneNumber);
        body.put("fullname", fullName);
        body.put("client_ip", clientIp);
        body.put("meta", Map.of("userId", user.getId().toString()));

        var response = charge(CHARGE_TYPE, body);

        log.info("initDeposit(NG): Flutterwave responded status='{}' for userId='{}' txRef='{}'",
                response.get("status"), user.getId(), txRef);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/flutterwave/ng")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Flutterwave NG webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (!verifyWebhookHash(verifHash)) {
            log.warn("Flutterwave NG webhook: invalid or missing verif-hash");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper()
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = String.valueOf(event.get("event"));
            log.info("Flutterwave NG webhook: received event='{}'", eventType);

            if (!"charge.completed".equals(eventType)) {
                log.info("Flutterwave NG webhook: ignoring event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var webhookData = (Map<String, Object>) event.get("data");
            if (webhookData == null || webhookData.get("id") == null) {
                log.error("Flutterwave NG webhook: missing data.id in payload");
                return ResponseEntity.status(400).body("Missing transaction id");
            }

            var flwTransactionId = Long.parseLong(webhookData.get("id").toString());

            // Do NOT trust webhookData directly — re-verify against Flutterwave's API.
            var verified = verifyTransaction(flwTransactionId);

            var status   = String.valueOf(verified.get("status"));
            var currency = String.valueOf(verified.get("currency"));

            if (!"successful".equals(status)) {
                log.info("Flutterwave NG webhook: flwTransactionId={} verified status='{}' — not crediting",
                        flwTransactionId, status);
                return ResponseEntity.ok("Not successful, ignored");
            }

            if (!EXPECTED_CURRENCY.equals(currency)) {
                log.error("Flutterwave NG webhook: flwTransactionId={} unexpected currency='{}' (expected {})",
                        flwTransactionId, currency, EXPECTED_CURRENCY);
                return ResponseEntity.status(400).body("Unexpected currency");
            }

            @SuppressWarnings("unchecked")
            var meta = (Map<String, Object>) verified.get("meta");
            if (meta == null || meta.get("userId") == null) {
                log.error("Flutterwave NG webhook: verified data missing meta.userId, flwTransactionId={}",
                        flwTransactionId);
                return ResponseEntity.status(400).body("Missing userId in meta");
            }

            var userId = UUID.fromString(meta.get("userId").toString());
            var ref    = String.valueOf(verified.get("tx_ref"));
            var amount = new BigDecimal(verified.get("amount").toString());

            handleVerifiedDeposit(userId, ref, amount, EXPECTED_CURRENCY, PROVIDER_TAG);

        } catch (ApiException e) {
            log.error("Flutterwave NG webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Flutterwave NG webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
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
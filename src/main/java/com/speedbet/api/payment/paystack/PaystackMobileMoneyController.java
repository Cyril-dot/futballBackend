package com.speedbet.api.payment.paystack;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles Ghanaian Mobile Money payments via Paystack's Charge API.
 *
 * Notes (per Paystack docs):
 *   - Hits POST /charge directly (not /transaction/initialize) — there's no
 *     redirect/authorization_url. The customer authorizes on their phone.
 *   - Currency is GHS, amount in pesewas — same convention as the card flow
 *     in PaystackController.
 *   - Supported Ghana providers: MTN ("mtn"), AirtelTigo ("atl"), Telecel ("vod").
 *   - The initial response has data.status == "pay_offline" with a
 *     data.display_text to show the customer. They must approve within
 *     180 seconds (a network-provider limitation, not configurable).
 *   - Final confirmation arrives via the charge.success webhook on the
 *     "mobile_money" channel. If it doesn't land within ~180s, fall back to
 *     the Verify Transaction endpoint exposed here.
 *   - No direct recurring/returning-customer charges — every attempt starts
 *     a brand new transaction.
 *
 * This controller only handles wallet deposits. Mirror handleAdminUpgrade()
 * from PaystackController if Mobile Money ever needs to support admin upgrades.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaystackMobileMoneyController {

    /** Paystack's character codes for Ghana mobile money providers. */
    private static final Set<String> VALID_GH_PROVIDERS = Set.of("mtn", "atl", "vod");

    /** How long to wait for Paystack to respond before timing out. */
    private final Duration paystackTimeout = Duration.ofSeconds(10);

    /**
     * How many times to retry on transient network failures (e.g. "Connection reset
     * by peer"). Does NOT retry on Paystack 4xx/5xx — those are mapped to a
     * RuntimeException by the onStatus handler and are therefore excluded from retry.
     */
    private final long paystackRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.paystack.secret-key}")             private String     secretKey;
    @Value("${app.paystack.base-url}")               private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    /**
     * Starts a Mobile Money charge for a wallet deposit. Returns the raw
     * Paystack response, including data.reference (needed for manual
     * verification) and data.display_text (show this to the customer).
     *
     * Expects req to contain: amount, phone, provider (one of "mtn", "atl", "vod").
     */
    @PostMapping("/api/wallet/deposit/paystack-momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initMomoDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var phone = String.valueOf(req.get("phone"));
        if (phone == null || phone.isBlank())
            throw ApiException.badRequest("Phone number is required.");

        var provider = String.valueOf(req.get("provider"));
        if (!VALID_GH_PROVIDERS.contains(provider))
            throw ApiException.badRequest(
                    "Unsupported provider '" + provider + "'. Use one of: mtn, atl, vod.");

        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        log.info("initMomoDeposit: userId='{}' amount={} pesewas={} provider='{}'",
                user.getId(), amount, amountPesewas, provider);

        var response = paystackMomoCharge(
                user.getEmail(),
                amountPesewas,
                phone,
                provider,
                Map.of("userId", user.getId().toString())
        );

        log.info("initMomoDeposit: Paystack responded status='{}' for userId='{}'",
                response.get("status"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Manual verification (fallback if webhook hasn't landed) ──────────────

    /**
     * Polls Paystack's Verify Transaction endpoint (GET /transaction/verify/:reference)
     * for a Mobile Money transaction's current status. Useful as a fallback when
     * the charge.success webhook is delayed past the customer's 180-second
     * authorization window.
     *
     * Deliberately does NOT credit the wallet itself — crediting only ever
     * happens inside the webhook handler (handleDeposit), so there's exactly
     * one code path that can move money. This is purely a status lookup for
     * the frontend to poll against.
     */
    @GetMapping("/api/wallet/deposit/paystack-momo/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyMomoCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        log.info("verifyMomoCharge: userId='{}' ref='{}'", user.getId(), reference);

        var response = verifyTransaction(reference);

        log.info("verifyMomoCharge: ref='{}' status='{}'", reference,
                ((Map<?, ?>) response.get("data")).get("status"));

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Same signature-verification + parsing approach as PaystackController's
     * webhook. Kept as a separate endpoint for readability/isolation, but
     * Paystack only allows ONE webhook URL per account — in production this
     * mobile_money handling needs to live inside (or be called from) your
     * single shared webhook endpoint. Route on data.channel there instead of
     * registering this URL separately on the dashboard.
     */
    @PostMapping("/api/webhooks/paystack-momo")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Paystack MoMo webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Paystack MoMo webhook: missing x-paystack-signature header");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("Paystack MoMo webhook: invalid signature received");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event").toString();
            log.info("Paystack MoMo webhook: received event='{}'", eventType);

            if (!"charge.success".equals(eventType)) {
                log.info("Paystack MoMo webhook: ignoring event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            var channel = String.valueOf(data.get("channel"));
            if (!"mobile_money".equals(channel)) {
                log.info("Paystack MoMo webhook: ignoring non-mobile_money channel='{}'", channel);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) data.get("metadata");

            if (metadata == null || metadata.get("userId") == null) {
                log.error("Paystack MoMo webhook: missing userId in metadata, ref='{}'",
                        data.get("reference"));
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            var userId        = UUID.fromString(metadata.get("userId").toString());
            var ref           = data.get("reference").toString();
            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            handleDeposit(userId, ref, amount);

        } catch (ApiException e) {
            log.error("Paystack MoMo webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Paystack MoMo webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission to
     * their referrer (if they were referred). Mirrors handleDeposit in
     * PaystackController — duplicated rather than shared so this controller
     * stays self-contained. Consider extracting a shared DepositHandler if
     * the copies start to drift across controllers.
     */
    private void handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit (momo): userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "paystack", "channel", "mobile_money", "reference", ref));
            log.info("handleDeposit (momo): GHS {} credited to userId='{}' ref='{}'",
                    amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit (momo): duplicate ref='{}' already processed — skipping", ref);
                return;
            }
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit (momo): commission attributed for userId='{}' deposit='{}'",
                    userId, amount);
        } catch (Exception ex) {
            // Never block a deposit because of a commission failure
            log.error("handleDeposit (momo): commission attribution failed for userId='{}' — investigate",
                    userId, ex);
        }
    }

    // ─── Paystack API helpers ──────────────────────────────────────────────────

    /**
     * Calls Paystack POST /charge with a mobile_money payload and returns the
     * FULL response map:
     *   {
     *     "status": true, "message": "...",
     *     "data": { "reference": "...", "status": "pay_offline", "display_text": "..." }
     *   }
     *
     * Same resilience pattern as PaystackController.paystackInit: 10s timeout,
     * 2 retries on transient network errors only (Paystack 4xx/5xx are not retried).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackMomoCharge(String email, int amountPesewas,
                                                    String phone, String provider,
                                                    Map<String, Object> metadata) {

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/charge")
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "email",        email,
                        "amount",       amountPesewas,
                        "currency",     "GHS",
                        "mobile_money", Map.of(
                                "phone",    phone,
                                "provider", provider
                        ),
                        "metadata",     metadata
                ))
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("Paystack MoMo API error: status={} body={}",
                                            clientResponse.statusCode(), body);
                                    return new RuntimeException(
                                            "Paystack returned " + clientResponse.statusCode() + ": " + body);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("Paystack MoMo API unreachable after {} retries", paystackRetryAttempts, ex);
                            return new RuntimeException("Paystack is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("Paystack returned an empty response.");
        }

        log.info("paystackMomoCharge: Paystack status='{}' message='{}'",
                result.get("status"), result.get("message"));

        if (Boolean.FALSE.equals(result.get("status"))) {
            var message = result.getOrDefault("message", "Paystack declined the request").toString();
            log.error("paystackMomoCharge: Paystack status=false — {}", message);
            throw new RuntimeException("Paystack error: " + message);
        }

        return result;
    }

    /**
     * Calls Paystack GET /transaction/verify/:reference to manually check a
     * Mobile Money transaction's status. Useful when the charge.success
     * webhook hasn't arrived after the customer's 180-second authorization
     * window has passed.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> verifyTransaction(String reference) {

        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/transaction/verify/" + reference)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("Paystack verify-transaction error: status={} body={}",
                                            clientResponse.statusCode(), body);
                                    return new RuntimeException(
                                            "Paystack returned " + clientResponse.statusCode() + ": " + body);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("Paystack verify-transaction unreachable after {} retries",
                                    paystackRetryAttempts, ex);
                            return new RuntimeException("Paystack is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("Paystack returned an empty response.");
        }

        return result;
    }

    // ─── Signature verification ───────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var hash = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return hash.equals(signature);
        } catch (Exception e) {
            log.error("Paystack MoMo webhook: signature verification error", e);
            return false;
        }
    }
}
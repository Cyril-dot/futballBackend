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
 *   - Currency is GHS, amount in pesewas (1 GHS = 100 pesewas).
 *   - Supported Ghana providers: MTN ("mtn"), AirtelTigo ("atl"), Telecel ("vod").
 *   - Phone numbers are normalized to local Ghana format (0XXXXXXXXX, 10 digits)
 *     before being sent to Paystack. Strip +233 or 233 prefix if present.
 *   - Possible data.status values after POST /charge:
 *       "pay_offline"  — customer must approve push prompt on their phone (most common)
 *       "pending"      — Paystack is processing; poll or wait for webhook
 *       "send_otp"     — provider requires OTP; call POST /charge/submit_otp
 *       "success"      — charged immediately (rare for MoMo)
 *       "failed"       — charge was declined
 *   - Final confirmation arrives via charge.success webhook on the "mobile_money"
 *     channel. If it doesn't land within ~180s, fall back to verifyMomoCharge.
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

    /**
     * Ghana network prefixes (local format, after stripping country code).
     * Used to warn early if the wrong number is passed for the chosen provider.
     */
    private static final Set<String> MTN_GH_PREFIXES = Set.of("024", "054", "055", "059");
    private static final Set<String> ATL_GH_PREFIXES = Set.of("026", "027", "056", "057");
    private static final Set<String> VOD_GH_PREFIXES = Set.of("020", "050");

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

    @Value("${app.paystack.secret-key}")            private String     secretKey;
    @Value("${app.paystack.base-url}")              private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:1}")  private BigDecimal minDeposit;

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    /**
     * Starts a Mobile Money charge for a wallet deposit.
     *
     * Returns the full Paystack response. Key fields the frontend needs:
     *   data.reference    — store this; needed for manual verification and OTP submission
     *   data.status       — one of: pay_offline | pending | send_otp | success | failed
     *   data.display_text — show this text to the customer (e.g. "Please approve on your phone")
     *
     * Expects body: { amount, phone, provider }
     *   amount   — GHS amount (e.g. 10 or 10.50)
     *   phone    — Ghana number in any format: 0XXXXXXXXX / +233XXXXXXXXX / 233XXXXXXXXX
     *   provider — one of: "mtn", "atl", "vod"
     */
    @PostMapping("/api/wallet/deposit/paystack-momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initMomoDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[MoMo][initMomoDeposit] START — userId='{}' email='{}'",
                user.getId(), user.getEmail());

        // ── Amount ────────────────────────────────────────────────────────────
        var rawAmount = req.get("amount");
        if (rawAmount == null)
            throw ApiException.badRequest("amount is required.");

        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.toString());
        } catch (NumberFormatException e) {
            log.warn("[MoMo][initMomoDeposit] Invalid amount='{}' for userId='{}'",
                    rawAmount, user.getId());
            throw ApiException.badRequest("amount must be a valid number.");
        }

        if (amount.compareTo(minDeposit) < 0) {
            log.warn("[MoMo][initMomoDeposit] Amount GHS {} below minimum GHS {} for userId='{}'",
                    amount, minDeposit, user.getId());
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        }

        log.info("[MoMo][initMomoDeposit] Amount validated: GHS {} for userId='{}'",
                amount, user.getId());

        // ── Phone ─────────────────────────────────────────────────────────────
        var rawPhone = req.get("phone") == null ? "" : String.valueOf(req.get("phone")).trim();
        if (rawPhone.isBlank() || rawPhone.equals("null"))
            throw ApiException.badRequest("Phone number is required.");

        log.info("[MoMo][initMomoDeposit] Normalizing phone='{}' for userId='{}'",
                maskPhone(rawPhone), user.getId());

        var phone = normalizeGhanaPhone(rawPhone);
        log.info("[MoMo][initMomoDeposit] Phone normalized to '{}' for userId='{}'",
                maskPhone(phone), user.getId());

        // ── Provider ──────────────────────────────────────────────────────────
        var rawProvider = req.get("provider");
        if (rawProvider == null)
            throw ApiException.badRequest("provider is required. Use one of: mtn, atl, vod.");

        var provider = rawProvider.toString().trim().toLowerCase();
        if (!VALID_GH_PROVIDERS.contains(provider)) {
            log.warn("[MoMo][initMomoDeposit] Invalid provider='{}' for userId='{}'",
                    provider, user.getId());
            throw ApiException.badRequest(
                    "Unsupported provider '" + provider + "'. Use one of: mtn, atl, vod.");
        }

        log.info("[MoMo][initMomoDeposit] Provider='{}' for userId='{}'",
                provider, user.getId());

        // Warn (don't block) if the number prefix doesn't match the chosen provider
        validateProviderPrefix(phone, provider);

        // ── Charge ────────────────────────────────────────────────────────────
        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        log.info("[MoMo][initMomoDeposit] Calling Paystack /charge — userId='{}' amount=GHS{} " +
                        "pesewas={} phone='{}' provider='{}'",
                user.getId(), amount, amountPesewas, maskPhone(phone), provider);

        Map<String, Object> response;
        try {
            response = paystackMomoCharge(
                    user.getEmail(),
                    amountPesewas,
                    phone,
                    provider,
                    Map.of("userId", user.getId().toString())
            );
        } catch (Exception e) {
            log.error("[MoMo][initMomoDeposit] Paystack /charge call FAILED for userId='{}' — {}",
                    user.getId(), e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data        = (Map<String, Object>) response.get("data");
        var dataStatus  = data != null ? data.get("status")       : "unknown";
        var ref         = data != null ? data.get("reference")     : "unknown";
        var displayText = data != null ? data.get("display_text")  : "";

        log.info("[MoMo][initMomoDeposit] Paystack responded — userId='{}' ref='{}' " +
                        "data.status='{}' display_text='{}'",
                user.getId(), ref, dataStatus, displayText);

        // Log the full Paystack data block at DEBUG so it's available when diagnosing issues
        // without cluttering INFO logs in normal operation
        log.debug("[MoMo][initMomoDeposit] Full Paystack data block — userId='{}' data='{}'",
                user.getId(), data);

        // Warn on unexpected statuses so we catch new Paystack behavior early
        if (!Set.of("pay_offline", "pending", "send_otp", "success", "failed")
                .contains(String.valueOf(dataStatus))) {
            log.warn("[MoMo][initMomoDeposit] UNEXPECTED data.status='{}' from Paystack for " +
                            "userId='{}' ref='{}' — may require frontend handling update",
                    dataStatus, user.getId(), ref);
        }

        log.info("[MoMo][initMomoDeposit] COMPLETE — userId='{}' ref='{}' status='{}'",
                user.getId(), ref, dataStatus);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── OTP Submission ───────────────────────────────────────────────────────

    /**
     * Submits an OTP for a Mobile Money charge where Paystack returned
     * data.status == "send_otp" from the initial /charge call.
     *
     * Calls Paystack POST /charge/submit_otp.
     *
     * Expected body: { otp, reference }
     *   otp       — the code the customer received on their phone
     *   reference — the reference returned by initMomoDeposit
     *
     * The response follows the same shape as /charge:
     *   data.status may be "pay_offline", "pending", "success", or "failed".
     *
     * NOTE: Wallet crediting NEVER happens here. Only the charge.success webhook
     * (or a re-check via verifyMomoCharge) triggers handleDeposit. This endpoint
     * just advances the Paystack charge state machine.
     */
    @PostMapping("/api/wallet/deposit/paystack-momo/submit-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[MoMo][submitOtp] START — userId='{}'", user.getId());

        var rawOtp = req.get("otp");
        var rawRef = req.get("reference");

        if (rawOtp == null || rawOtp.toString().isBlank())
            throw ApiException.badRequest("otp is required.");
        if (rawRef == null || rawRef.toString().isBlank())
            throw ApiException.badRequest("reference is required.");

        var otp       = rawOtp.toString().trim();
        var reference = rawRef.toString().trim();

        // Sanitize OTP length just in case (Paystack OTPs are typically 6 digits)
        if (otp.length() > 10) {
            log.warn("[MoMo][submitOtp] Suspiciously long OTP length={} for userId='{}' ref='{}'",
                    otp.length(), user.getId(), reference);
        }

        log.info("[MoMo][submitOtp] Calling Paystack /charge/submit_otp — userId='{}' ref='{}'",
                user.getId(), reference);

        Map<String, Object> result;
        try {
            result = paystackSubmitOtp(otp, reference);
        } catch (Exception e) {
            log.error("[MoMo][submitOtp] Paystack /charge/submit_otp FAILED — userId='{}' ref='{}' — {}",
                    user.getId(), reference, e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data       = (Map<String, Object>) result.get("data");
        var dataStatus = data != null ? data.get("status") : "unknown";

        log.info("[MoMo][submitOtp] COMPLETE — userId='{}' ref='{}' data.status='{}'",
                user.getId(), reference, dataStatus);

        log.debug("[MoMo][submitOtp] Full Paystack response — userId='{}' result='{}'",
                user.getId(), result);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Manual Verification (fallback if webhook hasn't landed) ──────────────

    /**
     * Polls Paystack GET /transaction/verify/:reference for the current status of
     * a Mobile Money transaction.
     *
     * Use this as a frontend fallback when the charge.success webhook is delayed
     * past the customer's ~180-second authorization window. Poll every 5-10s and
     * show the customer the result once data.status is "success" or "failed".
     *
     * Wallet crediting NEVER happens here — only the webhook handler does that.
     * This is purely a read-only status check.
     */
    @GetMapping("/api/wallet/deposit/paystack-momo/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyMomoCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        log.info("[MoMo][verifyMomoCharge] START — userId='{}' ref='{}'",
                user.getId(), reference);

        Map<String, Object> response;
        try {
            response = verifyTransaction(reference);
        } catch (Exception e) {
            log.error("[MoMo][verifyMomoCharge] Paystack verify FAILED — userId='{}' ref='{}' — {}",
                    user.getId(), reference, e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data       = (Map<String, Object>) response.get("data");
        var txStatus   = data != null ? data.get("status")  : "unknown";
        var txAmount   = data != null ? data.get("amount")  : "unknown";
        var txChannel  = data != null ? data.get("channel") : "unknown";

        log.info("[MoMo][verifyMomoCharge] COMPLETE — userId='{}' ref='{}' status='{}' " +
                        "amount='{}' channel='{}'",
                user.getId(), reference, txStatus, txAmount, txChannel);

        log.debug("[MoMo][verifyMomoCharge] Full Paystack verify response — userId='{}' data='{}'",
                user.getId(), data);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Receives Paystack charge.success events for the mobile_money channel.
     *
     * NOTE: Paystack only allows ONE webhook URL per account. In production
     * this handler must live inside (or be called from) your single shared
     * webhook endpoint — route on data.channel == "mobile_money" there.
     *
     * Returns HTTP 200 for all handled events (including ignored ones) so
     * Paystack doesn't keep retrying. Returns 4xx/5xx only on genuine errors.
     */
    @PostMapping("/api/webhooks/paystack-momo")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[MoMo][webhook] Received request — remote='{}'",
                request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[MoMo][webhook] Failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        log.debug("[MoMo][webhook] Raw body length={} bytes", rawBody.length);

        if (signature == null || signature.isBlank()) {
            log.warn("[MoMo][webhook] REJECTED — missing x-paystack-signature header");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("[MoMo][webhook] REJECTED — invalid HMAC signature. " +
                    "Check that app.paystack.secret-key matches the Paystack dashboard key.");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("[MoMo][webhook] Signature verified OK");

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event") != null ? event.get("event").toString() : "unknown";
            log.info("[MoMo][webhook] Event type='{}'", eventType);

            if (!"charge.success".equals(eventType)) {
                log.info("[MoMo][webhook] Ignoring event='{}' (not charge.success)", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            if (data == null) {
                log.error("[MoMo][webhook] charge.success event has no 'data' field — body='{}'",
                        new String(rawBody, StandardCharsets.UTF_8));
                return ResponseEntity.status(400).body("Missing data field");
            }

            var channel = String.valueOf(data.get("channel"));
            log.info("[MoMo][webhook] charge.success channel='{}' ref='{}'",
                    channel, data.get("reference"));

            if (!"mobile_money".equals(channel)) {
                log.info("[MoMo][webhook] Ignoring channel='{}' (not mobile_money)", channel);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) data.get("metadata");

            if (metadata == null || metadata.get("userId") == null) {
                log.error("[MoMo][webhook] Missing userId in metadata — ref='{}' full_data='{}'",
                        data.get("reference"), data);
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            var rawUserId     = metadata.get("userId").toString();
            var rawRef        = data.get("reference").toString();
            var rawAmount     = data.get("amount").toString();
            var amountPesewas = Long.parseLong(rawAmount);
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            log.info("[MoMo][webhook] Parsed event — userId='{}' ref='{}' " +
                            "amountPesewas={} amountGHS={} channel='{}'",
                    rawUserId, rawRef, amountPesewas, amount, channel);

            UUID userId;
            try {
                userId = UUID.fromString(rawUserId);
            } catch (IllegalArgumentException e) {
                log.error("[MoMo][webhook] Invalid userId format='{}' in metadata — ref='{}'",
                        rawUserId, rawRef);
                return ResponseEntity.status(400).body("Invalid userId format in metadata");
            }

            handleDeposit(userId, rawRef, amount);

        } catch (ApiException e) {
            log.error("[MoMo][webhook] ApiException during processing — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[MoMo][webhook] Unexpected error — Paystack will retry. Error: {}",
                    e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[MoMo][webhook] Processing COMPLETE — returning 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission to
     * their referrer (if they were referred).
     *
     * Duplicate reference (409) is silently skipped — idempotent by design so
     * Paystack webhook retries are safe.
     *
     * Commission failures are logged but never block the deposit.
     */
    private void handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("[MoMo][handleDeposit] START — userId='{}' amount=GHS{} ref='{}'",
                userId, amount, ref);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "paystack", "channel", "mobile_money", "reference", ref));
            log.info("[MoMo][handleDeposit] Wallet credited GHS {} to userId='{}' ref='{}'",
                    amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[MoMo][handleDeposit] Duplicate ref='{}' for userId='{}' — already " +
                        "processed, skipping (idempotent)", ref, userId);
                return;
            }
            log.error("[MoMo][handleDeposit] walletService.credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        log.info("[MoMo][handleDeposit] Attributing referral commission — userId='{}' depositGHS={}",
                userId, amount);
        try {
            referralService.attributeCommission(userId, amount);
            log.info("[MoMo][handleDeposit] Commission attributed for userId='{}' depositGHS={}",
                    userId, amount);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back or block the deposit
            log.error("[MoMo][handleDeposit] Commission attribution FAILED for userId='{}' " +
                            "depositGHS={} — INVESTIGATE: {}",
                    userId, amount, ex.getMessage(), ex);
        }

        log.info("[MoMo][handleDeposit] COMPLETE — userId='{}' ref='{}'", userId, ref);
    }

    // ─── Paystack API helpers ──────────────────────────────────────────────────

    /**
     * Calls Paystack POST /charge with a mobile_money payload.
     *
     * Returns the full response:
     *   { "status": true, "message": "Charge attempted",
     *     "data": { "reference": "...", "status": "pay_offline", "display_text": "..." } }
     *
     * Resilience: 10s timeout, 2 retries on transient network errors only.
     * Paystack 4xx/5xx throw RuntimeException and are NOT retried.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackMomoCharge(String email, int amountPesewas,
                                                   String phone, String provider,
                                                   Map<String, Object> metadata) {

        log.debug("[MoMo][paystackMomoCharge] Sending to Paystack — email='{}' pesewas={} " +
                        "phone='{}' provider='{}' metadata='{}'",
                email, amountPesewas, maskPhone(phone), provider, metadata);

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
                                    log.error("[MoMo][paystackMomoCharge] HTTP error from Paystack — " +
                                                    "status={} body='{}'",
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
                            log.error("[MoMo][paystackMomoCharge] Paystack unreachable after {} retries",
                                    paystackRetryAttempts, ex);
                            return new RuntimeException(
                                    "Paystack is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            log.error("[MoMo][paystackMomoCharge] Paystack returned null/empty response");
            throw new RuntimeException("Paystack returned an empty response.");
        }

        log.debug("[MoMo][paystackMomoCharge] Raw Paystack response — status='{}' message='{}' data='{}'",
                result.get("status"), result.get("message"), result.get("data"));

        if (Boolean.FALSE.equals(result.get("status"))) {
            var message = result.getOrDefault("message", "Paystack declined the request").toString();
            log.error("[MoMo][paystackMomoCharge] Paystack top-level status=false — message='{}'",
                    message);
            throw new RuntimeException("Paystack error: " + message);
        }

        return result;
    }

    /**
     * Calls Paystack POST /charge/submit_otp.
     *
     * Used when the initial /charge response has data.status == "send_otp".
     * After the customer enters the OTP on the frontend, call this to advance
     * the charge. The response has the same shape as the /charge response.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackSubmitOtp(String otp, String reference) {

        log.debug("[MoMo][paystackSubmitOtp] Sending OTP to Paystack — ref='{}'", reference);

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/charge/submit_otp")
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("otp", otp, "reference", reference))
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("[MoMo][paystackSubmitOtp] HTTP error from Paystack — " +
                                                    "status={} body='{}' ref='{}'",
                                            clientResponse.statusCode(), body, reference);
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
                            log.error("[MoMo][paystackSubmitOtp] Paystack unreachable after {} retries — ref='{}'",
                                    paystackRetryAttempts, reference, ex);
                            return new RuntimeException(
                                    "Paystack is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            log.error("[MoMo][paystackSubmitOtp] Paystack returned null/empty response for ref='{}'",
                    reference);
            throw new RuntimeException("Paystack returned an empty response.");
        }

        log.debug("[MoMo][paystackSubmitOtp] Raw Paystack response — ref='{}' status='{}' message='{}' data='{}'",
                reference, result.get("status"), result.get("message"), result.get("data"));

        if (Boolean.FALSE.equals(result.get("status"))) {
            var message = result.getOrDefault("message", "Paystack declined the OTP").toString();
            log.error("[MoMo][paystackSubmitOtp] Paystack top-level status=false for ref='{}' — '{}'",
                    reference, message);
            throw new RuntimeException("Paystack error: " + message);
        }

        return result;
    }

    /**
     * Calls Paystack GET /transaction/verify/:reference.
     * Used as a fallback when the charge.success webhook hasn't arrived
     * after the 180-second authorization window.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> verifyTransaction(String reference) {

        log.debug("[MoMo][verifyTransaction] Calling Paystack /transaction/verify/'{}' ", reference);

        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/transaction/verify/" + reference)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("[MoMo][verifyTransaction] HTTP error from Paystack — " +
                                                    "status={} body='{}' ref='{}'",
                                            clientResponse.statusCode(), body, reference);
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
                            log.error("[MoMo][verifyTransaction] Paystack unreachable after {} retries — ref='{}'",
                                    paystackRetryAttempts, reference, ex);
                            return new RuntimeException(
                                    "Paystack is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            log.error("[MoMo][verifyTransaction] Paystack returned null/empty response for ref='{}'",
                    reference);
            throw new RuntimeException("Paystack returned an empty response.");
        }

        return result;
    }

    // ─── Phone normalization ──────────────────────────────────────────────────

    /**
     * Normalizes a Ghana phone number to the local 10-digit format Paystack
     * expects for Ghana MoMo (0XXXXXXXXX).
     *
     * Handles:
     *   "+233XXXXXXXXX" (12 chars after stripping +) → strip "+233", prepend "0"
     *   "233XXXXXXXXX"  (11 chars)                   → strip "233",  prepend "0"
     *   "0XXXXXXXXX"    (10 chars)                   → unchanged
     *
     * Throws ApiException.badRequest if the result isn't exactly 10 digits.
     */
    private String normalizeGhanaPhone(String raw) {
        // Strip all whitespace and dashes
        var digits = raw.replaceAll("[\\s\\-]", "");

        if (digits.startsWith("+233")) {
            var before = digits;
            digits = "0" + digits.substring(4);
            log.debug("[MoMo][normalizePhone] Stripped +233 prefix: '{}' → '{}'",
                    maskPhone(before), maskPhone(digits));
        } else if (digits.startsWith("233") && digits.length() == 12) {
            var before = digits;
            digits = "0" + digits.substring(3);
            log.debug("[MoMo][normalizePhone] Stripped 233 prefix: '{}' → '{}'",
                    maskPhone(before), maskPhone(digits));
        }

        if (!digits.matches("^0\\d{9}$")) {
            log.warn("[MoMo][normalizePhone] Normalization failed for raw='{}' result='{}'",
                    maskPhone(raw), maskPhone(digits));
            throw ApiException.badRequest(
                    "Invalid Ghana phone number '" + raw + "'. " +
                            "Expected format: 0XXXXXXXXX (10 digits) or +233XXXXXXXXX.");
        }

        return digits;
    }

    /**
     * Logs a warning if the phone prefix doesn't match the declared provider.
     * Does NOT throw — Paystack is the final authority; this is just an early
     * signal to help debug mismatches in the logs.
     */
    private void validateProviderPrefix(String phone, String provider) {
        var prefix = phone.substring(0, 3);
        var mismatch = switch (provider) {
            case "mtn" -> !MTN_GH_PREFIXES.contains(prefix);
            case "atl" -> !ATL_GH_PREFIXES.contains(prefix);
            case "vod" -> !VOD_GH_PREFIXES.contains(prefix);
            default    -> false;
        };
        if (mismatch) {
            log.warn("[MoMo][validateProviderPrefix] Phone prefix '{}' may NOT belong to " +
                            "provider='{}' — Paystack will reject if mismatched. " +
                            "MTN prefixes={} ATL prefixes={} VOD prefixes={}",
                    prefix, provider, MTN_GH_PREFIXES, ATL_GH_PREFIXES, VOD_GH_PREFIXES);
        } else {
            log.debug("[MoMo][validateProviderPrefix] Prefix '{}' matches provider='{}'",
                    prefix, provider);
        }
    }

    // ─── Signature verification ───────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            var matches  = computed.equals(signature);
            if (!matches) {
                log.warn("[MoMo][verifySignature] HMAC mismatch — computed='{}...' received='{}...'",
                        computed.substring(0, 8), signature.substring(0, Math.min(8, signature.length())));
            }
            return matches;
        } catch (Exception e) {
            log.error("[MoMo][verifySignature] HMAC computation error", e);
            return false;
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /** Returns a masked phone number safe to log: e.g. "0551234987" → "055****987" */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}
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
 * Handles Ghanaian Mobile Money payments via Paystack's Direct Charge API.
 *
 * Flow (per Paystack docs):
 *
 *   Step 1 — POST /charge
 *     Send email, amount (pesewas), currency=GHS, mobile_money { phone, provider }.
 *     Inspect data.status in response:
 *       "pay_offline" — MTN dispatches a USSD flash / push prompt to the customer's phone.
 *                       No further action needed from backend; wait for webhook.
 *       "send_otp"    — Paystack sent an OTP to the phone. Collect it from the user
 *                       and call POST /charge/submit_otp.
 *       "success"     — Charged immediately (rare).
 *       "failed"      — Charge was declined by the network.
 *
 *   Step 2 — POST /charge/submit_otp  (only when data.status == "send_otp")
 *     Send { otp, reference }. Response has same data.status shape as Step 1.
 *
 *   Step 3 — Webhook (charge.success on channel == "mobile_money")
 *     Primary mechanism for crediting the wallet. Validate x-paystack-signature
 *     with HMAC-SHA512, then credit the user and return HTTP 200.
 *
 *   Step 4 — GET /transaction/verify/:reference  (fallback polling only)
 *     Used when the webhook hasn't landed. Returns data.status == "success"
 *     when cleared. Per Paystack docs this is the correct fallback verification
 *     endpoint. Only credit wallet via webhook — never via polling.
 *
 * Ghana MoMo providers: mtn | atl | vod
 * Amount unit: pesewas (GHS 1.00 = 100 pesewas)
 * Phone format sent to Paystack: local 0XXXXXXXXX (10 digits)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaystackMobileMoneyController {

    private static final Set<String> VALID_GH_PROVIDERS = Set.of("mtn", "atl", "vod");

    // Ghana network prefixes — used for early mismatch warnings only (not a hard block)
    private static final Set<String> MTN_GH_PREFIXES = Set.of("024", "025", "053", "054", "055", "059");
    private static final Set<String> ATL_GH_PREFIXES = Set.of("026", "027", "056", "057");
    private static final Set<String> VOD_GH_PREFIXES = Set.of("020", "050");

    private final Duration paystackTimeout      = Duration.ofSeconds(10);
    private final long     paystackRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.paystack.secret-key}")            private String     secretKey;
    @Value("${app.paystack.base-url}")              private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:1}")  private BigDecimal minDeposit;

    // ─── Step 1: Initiate Charge ──────────────────────────────────────────────

    /**
     * POST /api/wallet/deposit/paystack-momo/init
     *
     * Calls Paystack POST /charge with mobile_money payload.
     * Returns the raw Paystack response — frontend reads data.status to decide next step:
     *   "pay_offline" → tell user to check their phone for a push prompt / USSD menu
     *   "send_otp"    → show OTP input, then call /submit-otp
     *   "success"     → immediate success (rare)
     *   "failed"      → show failure message
     *
     * Body: { amount (GHS), phone (any Ghana format), provider ("mtn"|"atl"|"vod") }
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

        // ── Phone ─────────────────────────────────────────────────────────────
        var rawPhone = req.get("phone") == null ? "" : String.valueOf(req.get("phone")).trim();
        if (rawPhone.isBlank() || rawPhone.equals("null"))
            throw ApiException.badRequest("Phone number is required.");

        var phone = normalizeGhanaPhone(rawPhone);
        log.info("[MoMo][initMomoDeposit] Phone normalized to '{}' for userId='{}'",
                maskPhone(phone), user.getId());

        // ── Provider ──────────────────────────────────────────────────────────
        var rawProvider = req.get("provider");
        if (rawProvider == null)
            throw ApiException.badRequest("provider is required. Use one of: mtn, atl, vod.");

        var provider = rawProvider.toString().trim().toLowerCase();
        if (!VALID_GH_PROVIDERS.contains(provider))
            throw ApiException.badRequest(
                    "Unsupported provider '" + provider + "'. Use one of: mtn, atl, vod.");

        validateProviderPrefix(phone, provider);

        // ── Build pesewa amount and call Paystack POST /charge ─────────────────
        // Per docs: amount must be in pesewas (GHS 1.00 = 100 pesewas)
        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        log.info("[MoMo][initMomoDeposit] Calling Paystack POST /charge — userId='{}' " +
                        "amountGHS={} pesewas={} phone='{}' provider='{}'",
                user.getId(), amount, amountPesewas, maskPhone(phone), provider);

        Map<String, Object> response;
        try {
            response = paystackCharge(user.getEmail(), amountPesewas, phone, provider,
                    Map.of("userId", user.getId().toString()));
        } catch (Exception e) {
            log.error("[MoMo][initMomoDeposit] Paystack /charge FAILED — userId='{}' — {}",
                    user.getId(), e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data        = (Map<String, Object>) response.get("data");
        var dataStatus  = data != null ? data.get("status")      : "unknown";
        var ref         = data != null ? data.get("reference")   : "unknown";
        var displayText = data != null ? data.get("display_text"): "";

        log.info("[MoMo][initMomoDeposit] COMPLETE — userId='{}' ref='{}' data.status='{}' display_text='{}'",
                user.getId(), ref, dataStatus, displayText);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Step 2: Submit OTP ───────────────────────────────────────────────────

    /**
     * POST /api/wallet/deposit/paystack-momo/submit-otp
     *
     * Called only when Step 1 returned data.status == "send_otp".
     * Calls Paystack POST /charge/submit_otp.
     * Response has same data.status shape as Step 1.
     *
     * NOTE: Wallet is NEVER credited here. Only the webhook does that.
     *
     * Body: { otp, reference }
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

        log.info("[MoMo][submitOtp] Calling Paystack POST /charge/submit_otp — userId='{}' ref='{}'",
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

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Step 4: Verification Fallback ────────────────────────────────────────

    /**
     * GET /api/wallet/deposit/paystack-momo/verify/{reference}
     *
     * Fallback polling endpoint — used when the webhook hasn't landed yet.
     * Per Paystack docs (Step 4): calls GET /transaction/verify/:reference.
     * Returns data.status == "success" when the charge is confirmed.
     *
     * IMPORTANT: This is READ-ONLY. Wallet crediting only ever happens in the
     * webhook handler below. Never credit here to avoid double-crediting.
     */
    @GetMapping("/api/wallet/deposit/paystack-momo/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyMomoCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        log.info("[MoMo][verifyMomoCharge] START — userId='{}' ref='{}'",
                user.getId(), reference);

        Map<String, Object> response;
        try {
            // Per Paystack docs Step 4: GET /transaction/verify/:reference is the
            // correct fallback verification endpoint for completed transactions.
            response = paystackVerifyTransaction(reference);
        } catch (Exception e) {
            log.error("[MoMo][verifyMomoCharge] Paystack /transaction/verify FAILED — " +
                    "userId='{}' ref='{}' — {}", user.getId(), reference, e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data      = (Map<String, Object>) response.get("data");
        var txStatus  = data != null ? data.get("status")  : "unknown";

        log.info("[MoMo][verifyMomoCharge] COMPLETE — userId='{}' ref='{}' data.status='{}'",
                user.getId(), reference, txStatus);
        log.debug("[MoMo][verifyMomoCharge] Raw response — ref='{}' result='{}'", reference, response);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Step 3: Webhook ──────────────────────────────────────────────────────

    /**
     * POST /api/webhooks/paystack-momo
     *
     * Primary payment confirmation mechanism per Paystack docs Step 3.
     * Validates x-paystack-signature with HMAC-SHA512, then credits the wallet
     * on charge.success where channel == "mobile_money".
     *
     * Always returns HTTP 200 for handled events so Paystack stops retrying.
     */
    @PostMapping("/api/webhooks/paystack-momo")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[MoMo][webhook] Received — remote='{}'", request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[MoMo][webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("[MoMo][webhook] REJECTED — missing x-paystack-signature");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("[MoMo][webhook] REJECTED — invalid HMAC signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("[MoMo][webhook] Signature verified OK");

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event") != null ? event.get("event").toString() : "unknown";
            log.info("[MoMo][webhook] event='{}'", eventType);

            if (!"charge.success".equals(eventType)) {
                log.info("[MoMo][webhook] Ignoring event='{}' (not charge.success)", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            if (data == null) {
                log.error("[MoMo][webhook] charge.success has no data field");
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
                log.error("[MoMo][webhook] Missing userId in metadata — ref='{}'", data.get("reference"));
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            var rawUserId     = metadata.get("userId").toString();
            var rawRef        = data.get("reference").toString();
            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            log.info("[MoMo][webhook] Processing — userId='{}' ref='{}' amountGHS={}",
                    rawUserId, rawRef, amount);

            UUID userId;
            try {
                userId = UUID.fromString(rawUserId);
            } catch (IllegalArgumentException e) {
                log.error("[MoMo][webhook] Invalid userId='{}' in metadata — ref='{}'", rawUserId, rawRef);
                return ResponseEntity.status(400).body("Invalid userId in metadata");
            }

            handleDeposit(userId, rawRef, amount);

        } catch (ApiException e) {
            log.error("[MoMo][webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[MoMo][webhook] Unexpected error — Paystack will retry: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[MoMo][webhook] COMPLETE — returning 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    /**
     * Credits the user's wallet. Idempotent — duplicate references (409) are
     * silently skipped so webhook retries are safe.
     */
    private void handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("[MoMo][handleDeposit] START — userId='{}' amountGHS={} ref='{}'",
                userId, amount, ref);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "paystack", "channel", "mobile_money", "reference", ref));
            log.info("[MoMo][handleDeposit] Wallet credited GHS {} — userId='{}' ref='{}'",
                    amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[MoMo][handleDeposit] Duplicate ref='{}' — already processed, skipping", ref);
                return;
            }
            log.error("[MoMo][handleDeposit] walletService.credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("[MoMo][handleDeposit] Commission attributed — userId='{}' amountGHS={}", userId, amount);
        } catch (Exception ex) {
            // Commission failure must NEVER block or roll back the deposit
            log.error("[MoMo][handleDeposit] Commission FAILED — userId='{}' INVESTIGATE: {}",
                    userId, ex.getMessage(), ex);
        }

        log.info("[MoMo][handleDeposit] COMPLETE — userId='{}' ref='{}'", userId, ref);
    }

    // ─── Paystack API calls ────────────────────────────────────────────────────

    /**
     * POST /charge — Step 1 per Paystack docs.
     * Sends email, amount (pesewas), currency=GHS, mobile_money { phone, provider }.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackCharge(String email, int amountPesewas,
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
                        "mobile_money", Map.of("phone", phone, "provider", provider),
                        "metadata",     metadata
                ))
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(body -> {
                    log.error("[MoMo][paystackCharge] HTTP error — status={} body='{}'",
                            r.statusCode(), body);
                    return new RuntimeException("Paystack returned " + r.statusCode() + ": " + body);
                }))
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("Paystack is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("Paystack returned an empty response.");

        if (Boolean.FALSE.equals(result.get("status"))) {
            var msg = result.getOrDefault("message", "Paystack declined the request").toString();
            log.error("[MoMo][paystackCharge] top-level status=false — '{}'", msg);
            throw new RuntimeException("Paystack error: " + msg);
        }

        log.debug("[MoMo][paystackCharge] response — status='{}' data='{}'",
                result.get("status"), result.get("data"));
        return result;
    }

    /**
     * POST /charge/submit_otp — Step 2 per Paystack docs.
     * Only called when Step 1 returned data.status == "send_otp".
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackSubmitOtp(String otp, String reference) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/charge/submit_otp")
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("otp", otp, "reference", reference))
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(body -> {
                    log.error("[MoMo][paystackSubmitOtp] HTTP error — status={} body='{}' ref='{}'",
                            r.statusCode(), body, reference);
                    return new RuntimeException("Paystack returned " + r.statusCode() + ": " + body);
                }))
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("Paystack is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("Paystack returned an empty response.");

        if (Boolean.FALSE.equals(result.get("status"))) {
            var msg = result.getOrDefault("message", "Paystack declined the OTP").toString();
            log.error("[MoMo][paystackSubmitOtp] top-level status=false ref='{}' — '{}'", reference, msg);
            throw new RuntimeException("Paystack error: " + msg);
        }

        log.debug("[MoMo][paystackSubmitOtp] response — ref='{}' data='{}'", reference, result.get("data"));
        return result;
    }

    /**
     * GET /transaction/verify/:reference — Step 4 (fallback) per Paystack docs.
     * Used when the webhook hasn't landed. Returns data.status == "success" when cleared.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackVerifyTransaction(String reference) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/transaction/verify/" + reference)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(body -> {
                    log.error("[MoMo][paystackVerifyTransaction] HTTP error — status={} body='{}' ref='{}'",
                            r.statusCode(), body, reference);
                    return new RuntimeException("Paystack returned " + r.statusCode() + ": " + body);
                }))
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("Paystack is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("Paystack returned an empty response.");

        log.debug("[MoMo][paystackVerifyTransaction] response — ref='{}' result='{}'", reference, result);
        return result;
    }

    // ─── Phone normalization ──────────────────────────────────────────────────

    /**
     * Normalizes any Ghana phone format to local 0XXXXXXXXX (10 digits).
     * Handles: +233XXXXXXXXX, 233XXXXXXXXX, 0XXXXXXXXX
     */
    private String normalizeGhanaPhone(String raw) {
        var digits = raw.replaceAll("[\\s\\-]", "");

        if (digits.startsWith("+233")) {
            digits = "0" + digits.substring(4);
        } else if (digits.startsWith("233") && digits.length() == 12) {
            digits = "0" + digits.substring(3);
        }

        if (!digits.matches("^0\\d{9}$")) {
            log.warn("[MoMo][normalizePhone] Failed for raw='{}'", maskPhone(raw));
            throw ApiException.badRequest(
                    "Invalid Ghana phone number. Expected format: 0XXXXXXXXX or +233XXXXXXXXX.");
        }

        return digits;
    }

    /**
     * Logs a warning if the prefix doesn't match the provider. Does NOT throw —
     * Paystack is the final authority on whether a number/provider pairing is valid.
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
            log.warn("[MoMo][validateProviderPrefix] Prefix '{}' may not match provider='{}' — " +
                    "MTN={} ATL={} VOD={}", prefix, provider, MTN_GH_PREFIXES, ATL_GH_PREFIXES, VOD_GH_PREFIXES);
        }
    }

    // ─── Webhook signature verification ───────────────────────────────────────

    /**
     * Validates x-paystack-signature using HMAC-SHA512 as described in Paystack docs Step 3.
     */
    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            var matches  = computed.equals(signature);
            if (!matches) {
                log.warn("[MoMo][verifySignature] HMAC mismatch — computed='{}...' received='{}...'",
                        computed.substring(0, 8), signature.substring(0, Math.min(8, signature.length())));
            }
            return matches;
        } catch (Exception e) {
            log.error("[MoMo][verifySignature] HMAC error", e);
            return false;
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /** Masks phone for safe logging: "0551234987" → "055****987" */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}
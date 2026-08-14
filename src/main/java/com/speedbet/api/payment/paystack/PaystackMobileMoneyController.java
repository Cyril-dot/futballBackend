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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaystackMobileMoneyController {

    private static final Set<String> VALID_GH_PROVIDERS = Set.of("mtn", "atl", "vod");

    private static final Set<String> MTN_GH_PREFIXES = Set.of("024", "025", "053", "054", "055", "059");
    private static final Set<String> ATL_GH_PREFIXES = Set.of("026", "027", "056", "057");
    private static final Set<String> VOD_GH_PREFIXES = Set.of("020", "050");

    private static final Set<String> CREDITABLE_CHANNELS = Set.of("mobile_money", "bank", "card");

    private static final DateTimeFormatter BIRTHDAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Duration paystackTimeout       = Duration.ofSeconds(10);
    private final long     paystackRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.paystack.secret-key}")           private String     secretKey;
    @Value("${app.paystack.base-url}")             private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;
    @Value("${app.paystack.card-callback-url}")    private String     cardCallbackUrl;

    // ─── MoMo: Step 1 ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/paystack-momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initMomoDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[MoMo][init] START — userId='{}'", user.getId());

        var amount   = extractValidAmount(req, user.getId());
        var rawPhone = req.get("phone") == null ? "" : String.valueOf(req.get("phone")).trim();
        if (rawPhone.isBlank() || rawPhone.equals("null"))
            throw ApiException.badRequest("Phone number is required.");

        var phone = normalizeGhanaPhone(rawPhone);

        var rawProvider = req.get("provider");
        if (rawProvider == null)
            throw ApiException.badRequest("provider is required. Use one of: mtn, atl, vod.");

        var provider = rawProvider.toString().trim().toLowerCase();
        if (!VALID_GH_PROVIDERS.contains(provider))
            throw ApiException.badRequest("Unsupported provider '" + provider + "'.");

        validateProviderPrefix(phone, provider);

        var amountPesewas = toPesewas(amount);
        log.info("[MoMo][init] Calling Paystack POST /charge — userId='{}' pesewas={} phone='{}' provider='{}'",
                user.getId(), amountPesewas, maskPhone(phone), provider);

        var response = paystackChargeMomo(user.getEmail(), amountPesewas, phone, provider,
                Map.of("userId", user.getId().toString()));

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) response.get("data");
        log.info("[MoMo][init] DONE — userId='{}' ref='{}' data.status='{}'",
                user.getId(), data != null ? data.get("reference") : "?", data != null ? data.get("status") : "?");

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── MoMo: Step 2 — Submit OTP ────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/paystack-momo/submit-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var otp       = requireNonBlank(req, "otp");
        var reference = requireNonBlank(req, "reference");
        log.info("[MoMo][submitOtp] userId='{}' ref='{}'", user.getId(), reference);
        return ResponseEntity.ok(ApiResponse.ok(paystackSubmitOtp(otp, reference)));
    }

    // ─── MoMo: verify fallback ─────────────────────────────────────────────────

    @GetMapping("/api/wallet/deposit/paystack-momo/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyMomoCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {
        return verifyGeneric("MoMo", user, reference);
    }

    // ─── Bank: Step 1 ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/paystack-bank/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initBankDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Bank][init] START — userId='{}'", user.getId());

        var amount        = extractValidAmount(req, user.getId());
        var bankCode      = requireNonBlank(req, "bankCode");
        var accountNumber = requireNonBlank(req, "accountNumber").replaceAll("\\s", "");

        if (!accountNumber.matches("^\\d{6,20}$"))
            throw ApiException.badRequest("accountNumber must be numeric (6-20 digits).");

        String birthday = null;
        var rawBirthday = req.get("birthday");
        if (rawBirthday != null && !rawBirthday.toString().isBlank())
            birthday = validateBirthday(rawBirthday.toString().trim());

        var response = paystackChargeBank(user.getEmail(), toPesewas(amount), bankCode,
                accountNumber, birthday, Map.of("userId", user.getId().toString()));

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) response.get("data");
        log.info("[Bank][init] DONE — userId='{}' ref='{}' data.status='{}'",
                user.getId(), data != null ? data.get("reference") : "?", data != null ? data.get("status") : "?");

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Bank: Step 2a — Submit OTP ───────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/paystack-bank/submit-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitBankOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var otp       = requireNonBlank(req, "otp");
        var reference = requireNonBlank(req, "reference");
        log.info("[Bank][submitOtp] userId='{}' ref='{}'", user.getId(), reference);
        return ResponseEntity.ok(ApiResponse.ok(paystackSubmitOtp(otp, reference)));
    }

    // ─── Bank: Step 2b — Submit Birthday ──────────────────────────────────────

    @PostMapping("/api/wallet/deposit/paystack-bank/submit-birthday")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitBankBirthday(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var birthday  = validateBirthday(requireNonBlank(req, "birthday"));
        var reference = requireNonBlank(req, "reference");
        log.info("[Bank][submitBirthday] userId='{}' ref='{}'", user.getId(), reference);
        return ResponseEntity.ok(ApiResponse.ok(paystackSubmitBirthday(birthday, reference)));
    }

    // ─── Bank: verify fallback ─────────────────────────────────────────────────

    @GetMapping("/api/wallet/deposit/paystack-bank/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyBankCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {
        return verifyGeneric("Bank", user, reference);
    }

    // ─── Card: Step 1 — Initialize hosted checkout ────────────────────────────

    @PostMapping("/api/wallet/deposit/paystack-card/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initCardDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Card][init] START — userId='{}'", user.getId());
        var amount        = extractValidAmount(req, user.getId());
        var amountPesewas = toPesewas(amount);

        var response = paystackInitializeTransaction(user.getEmail(), amountPesewas,
                Map.of("userId", user.getId().toString()));

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) response.get("data");
        log.info("[Card][init] DONE — userId='{}' ref='{}' hasAuthUrl={}",
                user.getId(), data != null ? data.get("reference") : "?", data != null && data.get("authorization_url") != null);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Card: verify fallback ─────────────────────────────────────────────────

    @GetMapping("/api/wallet/deposit/paystack-card/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCardCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {
        return verifyGeneric("Card", user, reference);
    }

    // ─── Shared verify implementation ─────────────────────────────────────────

    private ResponseEntity<ApiResponse<Map<String, Object>>> verifyGeneric(
            String tag, User user, String reference) {

        if (reference == null || reference.isBlank())
            throw ApiException.badRequest("reference is required.");

        log.info("[{}][verify] userId='{}' ref='{}'", tag, user.getId(), reference);
        var response = paystackVerifyTransaction(reference);

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) response.get("data");
        log.info("[{}][verify] DONE — data.status='{}'", tag, data != null ? data.get("status") : "?");

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK — POST /api/webhooks/paystack-momo
    //
    // Already covered by  .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
    // in SecurityConfig, so Paystack's server-to-server POST reaches this method
    // without a JWT. Identity is proven by HMAC-SHA512 over the raw body.
    //
    // ROOT CAUSE OF "payment confirmed but user not credited":
    //
    //   Paystack's webhook metadata shape depends on HOW you sent the metadata:
    //
    //   A) Flat map  → { "metadata": { "userId": "abc-123" } }
    //      Paystack echoes this back exactly → metadata.get("userId") works fine.
    //
    //   B) custom_fields array → { "metadata": { "custom_fields": [ { "variable_name": "userId", "value": "abc-123" } ] } }
    //      metadata.get("userId") returns NULL → userId is never found → webhook returns 400
    //      → Paystack retries until timeout → wallet is never credited.
    //
    //   We send flat map (Map.of("userId", ...)), so shape A is expected.
    //   BUT Paystack sometimes normalises metadata into shape B on their side
    //   for certain transaction types. extractUserIdFromMetadata() handles both.
    //
    // ═══════════════════════════════════════════════════════════════════════════
    @PostMapping("/api/webhooks/paystack-momo")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            HttpServletRequest request) {

        // ── 1. Read raw body (MUST be before any parsing) ─────────────────────
        log.info("[Webhook] HIT — remote='{}'", request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        log.info("[Webhook] Body length={} bytes", rawBody.length);

        // ── 2. Signature check ─────────────────────────────────────────────────
        if (signature == null || signature.isBlank()) {
            log.warn("[Webhook] REJECTED — missing x-paystack-signature");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("[Webhook] REJECTED — HMAC mismatch (wrong secret key?)");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("[Webhook] Signature OK");

        // ── 3. Parse + dispatch ────────────────────────────────────────────────
        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event") != null ? event.get("event").toString() : "unknown";
            log.info("[Webhook] event='{}'", eventType);

            if (!"charge.success".equals(eventType)) {
                log.info("[Webhook] Ignored event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            if (data == null) {
                log.error("[Webhook] charge.success has null data");
                return ResponseEntity.status(400).body("Missing data");
            }

            var channel = String.valueOf(data.get("channel"));
            var ref     = data.get("reference") != null ? data.get("reference").toString() : "";

            log.info("[Webhook] channel='{}' ref='{}'", channel, ref);

            if (!CREDITABLE_CHANNELS.contains(channel)) {
                log.info("[Webhook] Ignored channel='{}'", channel);
                return ResponseEntity.ok("Ignored");
            }

            if (ref.isBlank()) {
                log.error("[Webhook] Missing reference in data");
                return ResponseEntity.status(400).body("Missing reference");
            }

            // ── Extract userId — handles BOTH flat and custom_fields shapes ────
            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) data.get("metadata");

            var rawUserId = extractUserIdFromMetadata(metadata, ref);
            if (rawUserId == null) {
                // Return 200 so Paystack stops retrying a malformed event we can't process.
                // Log at ERROR so it surfaces in monitoring.
                log.error("[Webhook] UNRESOLVABLE userId — channel='{}' ref='{}' metadata='{}'",
                        channel, ref, metadata);
                return ResponseEntity.ok("OK-NO-USER");
            }

            // ── Parse amount ───────────────────────────────────────────────────
            var rawAmount = data.get("amount");
            if (rawAmount == null) {
                log.error("[Webhook] Missing amount — ref='{}'", ref);
                return ResponseEntity.status(400).body("Missing amount");
            }

            long amountPesewas;
            try {
                amountPesewas = Long.parseLong(rawAmount.toString());
            } catch (NumberFormatException e) {
                log.error("[Webhook] Unparseable amount='{}' — ref='{}'", rawAmount, ref);
                return ResponseEntity.status(400).body("Invalid amount");
            }

            var amount = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            // ── Parse userId ───────────────────────────────────────────────────
            UUID userId;
            try {
                userId = UUID.fromString(rawUserId);
            } catch (IllegalArgumentException e) {
                log.error("[Webhook] Invalid userId='{}' — ref='{}'", rawUserId, ref);
                return ResponseEntity.ok("OK-BAD-UUID");
            }

            log.info("[Webhook] Crediting — channel='{}' userId='{}' ref='{}' amountGHS={}",
                    channel, userId, ref, amount);

            handleDeposit(userId, ref, amount, channel);

        } catch (ApiException e) {
            log.error("[Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Webhook] Unexpected error — Paystack will retry: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[Webhook] COMPLETE — 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String channel) {
        log.info("[handleDeposit] START — userId='{}' amountGHS={} ref='{}' channel='{}'",
                userId, amount, ref, channel);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "paystack", "channel", channel, "reference", ref));
            log.info("[handleDeposit] CREDITED GHS {} — userId='{}' ref='{}'", amount, userId, ref);

        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[handleDeposit] Duplicate ref='{}' — already credited, skipping", ref);
                return;
            }
            log.error("[handleDeposit] credit FAILED — userId='{}' ref='{}' — {}", userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("[handleDeposit] Commission attributed — userId='{}'", userId);
        } catch (Exception ex) {
            // Commission failure must NEVER roll back the deposit
            log.error("[handleDeposit] Commission FAILED (non-blocking) — userId='{}' — {}", userId, ex.getMessage(), ex);
        }

        log.info("[handleDeposit] COMPLETE — userId='{}' ref='{}'", userId, ref);
    }

    // ─── Metadata extraction — handles both shapes Paystack can send ───────────

    /**
     * Paystack can echo metadata back in two different shapes depending on
     * the transaction type and dashboard settings:
     *
     * Shape A (flat — what we send):
     *   { "userId": "uuid-here" }
     *
     * Shape B (custom_fields array — Paystack sometimes normalises to this):
     *   { "custom_fields": [ { "variable_name": "userId", "value": "uuid-here", "display_name": "User Id" } ] }
     *
     * This method tries shape A first, then falls back to shape B.
     * Returns null only if the userId genuinely cannot be found in either shape.
     */
    @SuppressWarnings("unchecked")
    private String extractUserIdFromMetadata(Map<String, Object> metadata, String ref) {
        if (metadata == null) {
            log.error("[extractUserId] metadata is null — ref='{}'", ref);
            return null;
        }

        // Shape A — flat map
        var flat = metadata.get("userId");
        if (flat != null && !flat.toString().isBlank()) {
            log.debug("[extractUserId] Found userId via flat key — ref='{}'", ref);
            return flat.toString().trim();
        }

        // Shape B — custom_fields array
        var customFields = metadata.get("custom_fields");
        if (customFields instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> field) {
                    var varName = field.get("variable_name");
                    var value   = field.get("value");
                    if ("userId".equals(varName) && value != null && !value.toString().isBlank()) {
                        log.debug("[extractUserId] Found userId via custom_fields — ref='{}'", ref);
                        return value.toString().trim();
                    }
                }
            }
        }

        log.error("[extractUserId] userId not found in metadata='{}' — ref='{}'", metadata, ref);
        return null;
    }

    // ─── Paystack API calls ────────────────────────────────────────────────────

    private Map<String, Object> paystackChargeMomo(String email, int amountPesewas,
                                                   String phone, String provider,
                                                   Map<String, Object> metadata) {
        return postToPaystack("/charge", Map.of(
                "email",        email,
                "amount",       amountPesewas,
                "currency",     "GHS",
                "mobile_money", Map.of("phone", phone, "provider", provider),
                "metadata",     metadata
        ), "paystackChargeMomo");
    }

    private Map<String, Object> paystackChargeBank(String email, int amountPesewas,
                                                   String bankCode, String accountNumber,
                                                   String birthdayOrNull,
                                                   Map<String, Object> metadata) {
        var body = new java.util.HashMap<String, Object>();
        body.put("email",    email);
        body.put("amount",   amountPesewas);
        body.put("currency", "GHS");
        body.put("bank",     Map.of("code", bankCode, "account_number", accountNumber));
        body.put("metadata", metadata);
        if (birthdayOrNull != null) body.put("birthday", birthdayOrNull);
        return postToPaystack("/charge", body, "paystackChargeBank");
    }

    private Map<String, Object> paystackInitializeTransaction(String email, int amountPesewas,
                                                              Map<String, Object> metadata) {
        return postToPaystack("/transaction/initialize", Map.of(
                "email",        email,
                "amount",       amountPesewas,
                "currency",     "GHS",
                "callback_url", cardCallbackUrl,
                "metadata",     metadata
        ), "paystackInitializeTransaction");
    }

    private Map<String, Object> paystackSubmitOtp(String otp, String reference) {
        return postToPaystack("/charge/submit_otp",
                Map.of("otp", otp, "reference", reference), "paystackSubmitOtp");
    }

    private Map<String, Object> paystackSubmitBirthday(String birthday, String reference) {
        return postToPaystack("/charge/submit_birthday",
                Map.of("birthday", birthday, "reference", reference), "paystackSubmitBirthday");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackVerifyTransaction(String reference) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/transaction/verify/" + reference)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(body -> {
                    log.error("[paystackVerify] HTTP error — status={} body='{}' ref='{}'",
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

        if (result == null) throw new RuntimeException("Paystack returned empty response.");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postToPaystack(String path, Map<String, Object> body, String tag) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + path)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] HTTP error — path='{}' status={} body='{}'",
                            tag, path, r.statusCode(), respBody);
                    return new RuntimeException("Paystack returned " + r.statusCode() + ": " + respBody);
                }))
                .bodyToMono(Map.class)
                .timeout(paystackTimeout)
                .retryWhen(Retry.max(paystackRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> new RuntimeException("Paystack is currently unavailable. Please try again."))
                .block();

        if (result == null) throw new RuntimeException("Paystack returned empty response.");

        if (Boolean.FALSE.equals(result.get("status"))) {
            var msg = result.getOrDefault("message", "Paystack declined the request").toString();
            log.error("[{}] status=false — path='{}' msg='{}'", tag, path, msg);
            throw new RuntimeException("Paystack error: " + msg);
        }

        return result;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private BigDecimal extractValidAmount(Map<String, Object> req, UUID userId) {
        var rawAmount = req.get("amount");
        if (rawAmount == null) throw ApiException.badRequest("amount is required.");
        BigDecimal amount;
        try { amount = new BigDecimal(rawAmount.toString()); }
        catch (NumberFormatException e) { throw ApiException.badRequest("amount must be a valid number."); }
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        return amount;
    }

    private int toPesewas(BigDecimal amountGhs) {
        return amountGhs.multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64).intValue();
    }

    private String requireNonBlank(Map<String, Object> req, String field) {
        var raw = req.get(field);
        if (raw == null || raw.toString().isBlank())
            throw ApiException.badRequest(field + " is required.");
        return raw.toString().trim();
    }

    private String validateBirthday(String raw) {
        try { LocalDate.parse(raw, BIRTHDAY_FORMAT); }
        catch (Exception e) { throw ApiException.badRequest("birthday must be YYYY-MM-DD."); }
        return raw;
    }

    private String normalizeGhanaPhone(String raw) {
        var digits = raw.replaceAll("[\\s\\-]", "");
        if (digits.startsWith("+233"))                          digits = "0" + digits.substring(4);
        else if (digits.startsWith("233") && digits.length() == 12) digits = "0" + digits.substring(3);
        if (!digits.matches("^0\\d{9}$"))
            throw ApiException.badRequest("Invalid Ghana phone. Use 0XXXXXXXXX or +233XXXXXXXXX.");
        return digits;
    }

    private void validateProviderPrefix(String phone, String provider) {
        var prefix    = phone.substring(0, 3);
        var mismatch  = switch (provider) {
            case "mtn" -> !MTN_GH_PREFIXES.contains(prefix);
            case "atl" -> !ATL_GH_PREFIXES.contains(prefix);
            case "vod" -> !VOD_GH_PREFIXES.contains(prefix);
            default    -> false;
        };
        if (mismatch)
            log.warn("[validateProviderPrefix] prefix='{}' may not match provider='{}'", prefix, provider);
    }

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var computed = HexFormat.of().formatHex(mac.doFinal(rawBody));
            var matches  = computed.equals(signature);
            if (!matches)
                log.warn("[verifySignature] HMAC mismatch — computed='{}...' received='{}...'",
                        computed.substring(0, 8), signature.substring(0, Math.min(8, signature.length())));
            return matches;
        } catch (Exception e) {
            log.error("[verifySignature] HMAC error", e);
            return false;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}
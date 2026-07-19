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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles Ghanaian deposit payments via Paystack: Mobile Money, direct Bank
 * charge, and Card (hosted checkout).
 *
 * ── Mobile Money flow (per Paystack docs) ──────────────────────────────────
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
 * ── Bank flow (direct charge, per Paystack "Pay with Bank" docs) ───────────
 *
 *   Step 1 — POST /charge
 *     Send email, amount (pesewas), currency=GHS, bank { code, account_number }
 *     (+ optional birthday, since some banks require it up front).
 *     Inspect data.status:
 *       "send_otp"      — collect OTP, call POST /charge/submit_otp.
 *       "send_birthday" — collect birthday (YYYY-MM-DD), call POST /charge/submit_birthday.
 *       "pending"       — wait 10s+ and re-check via the verify endpoint, then webhook.
 *       "success"       — charged immediately (rare).
 *       "failed"        — charge was declined.
 *
 *   Step 2a — POST /charge/submit_otp       (data.status == "send_otp")
 *   Step 2b — POST /charge/submit_birthday  (data.status == "send_birthday")
 *     Both return the same data.status shape as Step 1.
 *
 * ── Card flow (hosted checkout — deliberately NOT raw card charging) ───────
 *
 *   We never accept raw card number/CVV/expiry on our backend. Handling PANs
 *   directly pulls this service into PCI-DSS SAQ D / Level 1 scope. Instead we
 *   use Paystack's hosted Standard Checkout:
 *
 *   Step 1 — POST /transaction/initialize
 *     Send email, amount (pesewas), currency=GHS, callback_url, metadata { userId }.
 *     Returns data.authorization_url — redirect the customer's browser there.
 *     Paystack hosts card entry (and any PIN/OTP/3DS/AVS challenge) on their
 *     own PCI-compliant page.
 *
 *   Step 2 — Customer completes payment on Paystack's page and is redirected
 *     back to callback_url. Use the fallback verify endpoint below if needed,
 *     but the webhook remains the source of truth for crediting.
 *
 * ── Shared across all three methods ─────────────────────────────────────────
 *
 *   Step 3 — Webhook (charge.success on channel in {mobile_money, bank, card})
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
 *
 * NOTE: direct bank-debit charging ("Pay with Bank") availability can be
 * country/account-dependent on Paystack's side — confirm it's enabled for
 * your GHS integration (test-mode call or a chat with Paystack support)
 * before relying on it in production.
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

    // Channels the webhook will credit a wallet for. Any other channel is ignored.
    private static final Set<String> CREDITABLE_CHANNELS = Set.of("mobile_money", "bank", "card");

    private static final DateTimeFormatter BIRTHDAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE; // YYYY-MM-DD

    private final Duration paystackTimeout      = Duration.ofSeconds(10);
    private final long     paystackRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.paystack.secret-key}")             private String     secretKey;
    @Value("${app.paystack.base-url}")               private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:1}")   private BigDecimal minDeposit;
    @Value("${app.paystack.card-callback-url}")      private String     cardCallbackUrl;

    // ─── Step 1: Initiate MoMo Charge ─────────────────────────────────────────

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

        var amount = extractValidAmount(req, user.getId());

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

        var amountPesewas = toPesewas(amount);

        log.info("[MoMo][initMomoDeposit] Calling Paystack POST /charge — userId='{}' " +
                        "amountGHS={} pesewas={} phone='{}' provider='{}'",
                user.getId(), amount, amountPesewas, maskPhone(phone), provider);

        Map<String, Object> response;
        try {
            response = paystackChargeMomo(user.getEmail(), amountPesewas, phone, provider,
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

    // ─── Step 2: Submit MoMo OTP ───────────────────────────────────────────────

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

        var otp       = requireNonBlank(req, "otp");
        var reference = requireNonBlank(req, "reference");

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

    // ─── Bank: Step 1 — Initiate direct bank charge ────────────────────────────

    /**
     * POST /api/wallet/deposit/paystack-bank/init
     *
     * Calls Paystack POST /charge with a bank { code, account_number } payload
     * (Paystack's "Pay with Bank" direct-debit flow — distinct from card and
     * from the hosted bank-transfer/virtual-account flow).
     *
     * Returns the raw Paystack response — frontend reads data.status:
     *   "send_otp"      → show OTP input, then call /submit-otp
     *   "send_birthday" → show a birthday input (YYYY-MM-DD), then call /submit-birthday
     *   "pending"       → tell the user to wait; poll /verify or wait for webhook
     *   "success"       → immediate success (rare)
     *   "failed"        → show failure message
     *
     * Body: { amount (GHS), bankCode, accountNumber, birthday? (YYYY-MM-DD, optional
     *         up front — some banks only ask for it later via send_birthday) }
     */
    @PostMapping("/api/wallet/deposit/paystack-bank/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initBankDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Bank][initBankDeposit] START — userId='{}' email='{}'",
                user.getId(), user.getEmail());

        var amount = extractValidAmount(req, user.getId());

        var bankCode      = requireNonBlank(req, "bankCode");
        var accountNumber = requireNonBlank(req, "accountNumber").replaceAll("\\s", "");

        if (!accountNumber.matches("^\\d{6,20}$"))
            throw ApiException.badRequest("accountNumber must be numeric (6-20 digits).");

        String birthday = null;
        var rawBirthday = req.get("birthday");
        if (rawBirthday != null && !rawBirthday.toString().isBlank()) {
            birthday = validateBirthday(rawBirthday.toString().trim());
        }

        var amountPesewas = toPesewas(amount);

        log.info("[Bank][initBankDeposit] Calling Paystack POST /charge — userId='{}' " +
                        "amountGHS={} pesewas={} bankCode='{}' account='{}' hasBirthday={}",
                user.getId(), amount, amountPesewas, bankCode, maskAccount(accountNumber), birthday != null);

        Map<String, Object> response;
        try {
            response = paystackChargeBank(user.getEmail(), amountPesewas, bankCode, accountNumber, birthday,
                    Map.of("userId", user.getId().toString()));
        } catch (Exception e) {
            log.error("[Bank][initBankDeposit] Paystack /charge FAILED — userId='{}' — {}",
                    user.getId(), e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data       = (Map<String, Object>) response.get("data");
        var dataStatus = data != null ? data.get("status")    : "unknown";
        var ref        = data != null ? data.get("reference") : "unknown";

        log.info("[Bank][initBankDeposit] COMPLETE — userId='{}' ref='{}' data.status='{}'",
                user.getId(), ref, dataStatus);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Bank: Step 2a — Submit OTP ─────────────────────────────────────────────

    /**
     * POST /api/wallet/deposit/paystack-bank/submit-otp
     *
     * Called only when Step 1 returned data.status == "send_otp".
     * Shares the same Paystack /charge/submit_otp call as the MoMo flow —
     * Paystack's OTP submission endpoint isn't payment-method specific.
     *
     * Body: { otp, reference }
     */
    @PostMapping("/api/wallet/deposit/paystack-bank/submit-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitBankOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Bank][submitBankOtp] START — userId='{}'", user.getId());

        var otp       = requireNonBlank(req, "otp");
        var reference = requireNonBlank(req, "reference");

        Map<String, Object> result;
        try {
            result = paystackSubmitOtp(otp, reference);
        } catch (Exception e) {
            log.error("[Bank][submitBankOtp] Paystack /charge/submit_otp FAILED — userId='{}' ref='{}' — {}",
                    user.getId(), reference, e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data       = (Map<String, Object>) result.get("data");
        var dataStatus = data != null ? data.get("status") : "unknown";

        log.info("[Bank][submitBankOtp] COMPLETE — userId='{}' ref='{}' data.status='{}'",
                user.getId(), reference, dataStatus);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Bank: Step 2b — Submit Birthday ────────────────────────────────────────

    /**
     * POST /api/wallet/deposit/paystack-bank/submit-birthday
     *
     * Called only when Step 1 (or a prior submit call) returned
     * data.status == "send_birthday". Calls Paystack POST /charge/submit_birthday.
     *
     * Body: { birthday (YYYY-MM-DD), reference }
     */
    @PostMapping("/api/wallet/deposit/paystack-bank/submit-birthday")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitBankBirthday(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Bank][submitBankBirthday] START — userId='{}'", user.getId());

        var rawBirthday = requireNonBlank(req, "birthday");
        var reference    = requireNonBlank(req, "reference");
        var birthday     = validateBirthday(rawBirthday);

        log.info("[Bank][submitBankBirthday] Calling Paystack POST /charge/submit_birthday — userId='{}' ref='{}'",
                user.getId(), reference);

        Map<String, Object> result;
        try {
            result = paystackSubmitBirthday(birthday, reference);
        } catch (Exception e) {
            log.error("[Bank][submitBankBirthday] Paystack /charge/submit_birthday FAILED — userId='{}' ref='{}' — {}",
                    user.getId(), reference, e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data       = (Map<String, Object>) result.get("data");
        var dataStatus = data != null ? data.get("status") : "unknown";

        log.info("[Bank][submitBankBirthday] COMPLETE — userId='{}' ref='{}' data.status='{}'",
                user.getId(), reference, dataStatus);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Card: Step 1 — Initiate hosted checkout ────────────────────────────────

    /**
     * POST /api/wallet/deposit/paystack-card/init
     *
     * Calls Paystack POST /transaction/initialize (hosted Standard Checkout).
     * We deliberately do NOT accept card number/CVV/expiry here — see the
     * class-level doc comment for why. The frontend should redirect the
     * customer's browser to data.authorization_url from the response.
     *
     * Body: { amount (GHS) }
     */
    @PostMapping("/api/wallet/deposit/paystack-card/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initCardDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Card][initCardDeposit] START — userId='{}' email='{}'",
                user.getId(), user.getEmail());

        var amount        = extractValidAmount(req, user.getId());
        var amountPesewas = toPesewas(amount);

        log.info("[Card][initCardDeposit] Calling Paystack POST /transaction/initialize — " +
                        "userId='{}' amountGHS={} pesewas={}",
                user.getId(), amount, amountPesewas);

        Map<String, Object> response;
        try {
            response = paystackInitializeTransaction(user.getEmail(), amountPesewas,
                    Map.of("userId", user.getId().toString()));
        } catch (Exception e) {
            log.error("[Card][initCardDeposit] Paystack /transaction/initialize FAILED — userId='{}' — {}",
                    user.getId(), e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data              = (Map<String, Object>) response.get("data");
        var authorizationUrl  = data != null ? data.get("authorization_url") : null;
        var ref               = data != null ? data.get("reference")        : "unknown";

        log.info("[Card][initCardDeposit] COMPLETE — userId='{}' ref='{}' hasAuthUrl={}",
                user.getId(), ref, authorizationUrl != null);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Card: fallback verification ────────────────────────────────────────────

    /**
     * GET /api/wallet/deposit/paystack-card/verify/{reference}
     *
     * Fallback polling endpoint for after the customer returns from the hosted
     * checkout page, in case the webhook hasn't landed yet. Read-only — see
     * the shared verifyMomoCharge/verifyBankCharge endpoints for the same
     * caution: never credit the wallet here.
     */
    @GetMapping("/api/wallet/deposit/paystack-card/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCardCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        return verifyGeneric("Card", user, reference);
    }

    // ─── Step 4: MoMo verification fallback ─────────────────────────────────────

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

        return verifyGeneric("MoMo", user, reference);
    }

    // ─── Bank: verification fallback ────────────────────────────────────────────

    /**
     * GET /api/wallet/deposit/paystack-bank/verify/{reference}
     *
     * Same fallback semantics as the MoMo/Card verify endpoints — read-only,
     * webhook remains the source of truth for crediting.
     */
    @GetMapping("/api/wallet/deposit/paystack-bank/verify/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyBankCharge(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        return verifyGeneric("Bank", user, reference);
    }

    /**
     * Shared implementation for all three read-only verify endpoints above.
     * {@code /transaction/verify/:reference} is channel-agnostic on Paystack's
     * side, so one call works for MoMo, bank, and card references alike.
     */
    private ResponseEntity<ApiResponse<Map<String, Object>>> verifyGeneric(
            String tag, User user, String reference) {

        log.info("[{}][verify] START — userId='{}' ref='{}'", tag, user.getId(), reference);

        Map<String, Object> response;
        try {
            response = paystackVerifyTransaction(reference);
        } catch (Exception e) {
            log.error("[{}][verify] Paystack /transaction/verify FAILED — userId='{}' ref='{}' — {}",
                    tag, user.getId(), reference, e.getMessage(), e);
            throw e;
        }

        @SuppressWarnings("unchecked")
        var data     = (Map<String, Object>) response.get("data");
        var txStatus = data != null ? data.get("status") : "unknown";

        log.info("[{}][verify] COMPLETE — userId='{}' ref='{}' data.status='{}'",
                tag, user.getId(), reference, txStatus);
        log.debug("[{}][verify] Raw response — ref='{}' result='{}'", tag, reference, response);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Step 3: Webhook (shared by MoMo, Bank, and Card) ───────────────────────

    /**
     * POST /api/webhooks/paystack-momo
     *
     * Primary payment confirmation mechanism per Paystack docs Step 3.
     * Validates x-paystack-signature with HMAC-SHA512, then credits the wallet
     * on charge.success where channel is one of {@link #CREDITABLE_CHANNELS}
     * (mobile_money, bank, card).
     *
     * Always returns HTTP 200 for handled events so Paystack stops retrying.
     */
    @PostMapping("/api/webhooks/paystack-momo")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("[Webhook] Received — remote='{}'", request.getRemoteAddr());

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[Webhook] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("[Webhook] REJECTED — missing x-paystack-signature");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("[Webhook] REJECTED — invalid HMAC signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("[Webhook] Signature verified OK");

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event") != null ? event.get("event").toString() : "unknown";
            log.info("[Webhook] event='{}'", eventType);

            if (!"charge.success".equals(eventType)) {
                log.info("[Webhook] Ignoring event='{}' (not charge.success)", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            if (data == null) {
                log.error("[Webhook] charge.success has no data field");
                return ResponseEntity.status(400).body("Missing data field");
            }

            var channel = String.valueOf(data.get("channel"));
            log.info("[Webhook] charge.success channel='{}' ref='{}'",
                    channel, data.get("reference"));

            if (!CREDITABLE_CHANNELS.contains(channel)) {
                log.info("[Webhook] Ignoring channel='{}' (not in {})", channel, CREDITABLE_CHANNELS);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) data.get("metadata");

            if (metadata == null || metadata.get("userId") == null) {
                log.error("[Webhook] Missing userId in metadata — channel='{}' ref='{}'",
                        channel, data.get("reference"));
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            var rawUserId     = metadata.get("userId").toString();
            var rawRef        = data.get("reference").toString();
            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            log.info("[Webhook] Processing — channel='{}' userId='{}' ref='{}' amountGHS={}",
                    channel, rawUserId, rawRef, amount);

            UUID userId;
            try {
                userId = UUID.fromString(rawUserId);
            } catch (IllegalArgumentException e) {
                log.error("[Webhook] Invalid userId='{}' in metadata — ref='{}'", rawUserId, rawRef);
                return ResponseEntity.status(400).body("Invalid userId in metadata");
            }

            handleDeposit(userId, rawRef, amount, channel);

        } catch (ApiException e) {
            log.error("[Webhook] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Webhook] Unexpected error — Paystack will retry: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[Webhook] COMPLETE — returning 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ─── Wallet crediting ──────────────────────────────────────────────────────

    /**
     * Credits the user's wallet. Idempotent — duplicate references (409) are
     * silently skipped so webhook retries are safe. Shared by every payment
     * channel; {@code channel} is only carried through for logging/metadata.
     */
    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String channel) {
        log.info("[handleDeposit] START — userId='{}' amountGHS={} ref='{}' channel='{}'",
                userId, amount, ref, channel);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "paystack", "channel", channel, "reference", ref));
            log.info("[handleDeposit] Wallet credited GHS {} — userId='{}' ref='{}' channel='{}'",
                    amount, userId, ref, channel);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[handleDeposit] Duplicate ref='{}' — already processed, skipping", ref);
                return;
            }
            log.error("[handleDeposit] walletService.credit FAILED — userId='{}' ref='{}' — {}",
                    userId, ref, ex.getMessage(), ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("[handleDeposit] Commission attributed — userId='{}' amountGHS={}", userId, amount);
        } catch (Exception ex) {
            // Commission failure must NEVER block or roll back the deposit
            log.error("[handleDeposit] Commission FAILED — userId='{}' INVESTIGATE: {}",
                    userId, ex.getMessage(), ex);
        }

        log.info("[handleDeposit] COMPLETE — userId='{}' ref='{}'", userId, ref);
    }

    // ─── Paystack API calls ────────────────────────────────────────────────────

    /**
     * POST /charge — MoMo Step 1 per Paystack docs.
     * Sends email, amount (pesewas), currency=GHS, mobile_money { phone, provider }.
     */
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

    /**
     * POST /charge — Bank Step 1 ("Pay with Bank") per Paystack docs.
     * Sends email, amount (pesewas), currency=GHS, bank { code, account_number },
     * and an optional birthday if the bank requires it up front.
     */
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
        if (birthdayOrNull != null) {
            body.put("birthday", birthdayOrNull);
        }
        return postToPaystack("/charge", body, "paystackChargeBank");
    }

    /**
     * POST /transaction/initialize — Card Step 1 (hosted Standard Checkout).
     * Sends email, amount (pesewas), currency=GHS, callback_url, metadata.
     * Returns data.authorization_url for the frontend to redirect to.
     */
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

    /**
     * POST /charge/submit_otp — shared Step 2 for MoMo and Bank per Paystack docs.
     * Only called when a prior /charge call returned data.status == "send_otp".
     */
    private Map<String, Object> paystackSubmitOtp(String otp, String reference) {
        return postToPaystack("/charge/submit_otp",
                Map.of("otp", otp, "reference", reference), "paystackSubmitOtp");
    }

    /**
     * POST /charge/submit_birthday — Bank Step 2b per Paystack docs.
     * Only called when a prior /charge call returned data.status == "send_birthday".
     */
    private Map<String, Object> paystackSubmitBirthday(String birthday, String reference) {
        return postToPaystack("/charge/submit_birthday",
                Map.of("birthday", birthday, "reference", reference), "paystackSubmitBirthday");
    }

    /**
     * GET /transaction/verify/:reference — Step 4 (fallback) per Paystack docs.
     * Channel-agnostic: works for MoMo, bank, and card references alike.
     * Used when the webhook hasn't landed. Returns data.status == "success" when cleared.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackVerifyTransaction(String reference) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/transaction/verify/" + reference)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(body -> {
                    log.error("[paystackVerifyTransaction] HTTP error — status={} body='{}' ref='{}'",
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

        log.debug("[paystackVerifyTransaction] response — ref='{}' result='{}'", reference, result);
        return result;
    }

    /**
     * Shared POST helper for every Paystack call above — same timeout, retry,
     * error-mapping, and top-level status=false handling in one place.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postToPaystack(String path, Map<String, Object> body, String callerTag) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + path)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] HTTP error — path='{}' status={} body='{}'",
                            callerTag, path, r.statusCode(), respBody);
                    return new RuntimeException("Paystack returned " + r.statusCode() + ": " + respBody);
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
            log.error("[{}] top-level status=false — path='{}' — '{}'", callerTag, path, msg);
            throw new RuntimeException("Paystack error: " + msg);
        }

        log.debug("[{}] response — path='{}' status='{}' data='{}'",
                callerTag, path, result.get("status"), result.get("data"));
        return result;
    }

    // ─── Request validation helpers ─────────────────────────────────────────────

    /** Extracts and validates the "amount" field (GHS) shared by all three deposit-init endpoints. */
    private BigDecimal extractValidAmount(Map<String, Object> req, UUID userId) {
        var rawAmount = req.get("amount");
        if (rawAmount == null)
            throw ApiException.badRequest("amount is required.");

        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.toString());
        } catch (NumberFormatException e) {
            log.warn("[extractValidAmount] Invalid amount='{}' for userId='{}'", rawAmount, userId);
            throw ApiException.badRequest("amount must be a valid number.");
        }

        if (amount.compareTo(minDeposit) < 0) {
            log.warn("[extractValidAmount] Amount GHS {} below minimum GHS {} for userId='{}'",
                    amount, minDeposit, userId);
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        }
        return amount;
    }

    /** Converts a GHS amount to integer pesewas (GHS 1.00 = 100 pesewas), as Paystack expects. */
    private int toPesewas(BigDecimal amountGhs) {
        return amountGhs.multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64).intValue();
    }

    /** Reads a required, non-blank string field from the request body or throws a 400. */
    private String requireNonBlank(Map<String, Object> req, String field) {
        var raw = req.get(field);
        if (raw == null || raw.toString().isBlank())
            throw ApiException.badRequest(field + " is required.");
        return raw.toString().trim();
    }

    /** Validates a birthday string is a real calendar date in YYYY-MM-DD format. */
    private String validateBirthday(String raw) {
        try {
            LocalDate.parse(raw, BIRTHDAY_FORMAT);
        } catch (Exception e) {
            throw ApiException.badRequest("birthday must be in YYYY-MM-DD format.");
        }
        return raw;
    }

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
            log.warn("[normalizePhone] Failed for raw='{}'", maskPhone(raw));
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
            log.warn("[validateProviderPrefix] Prefix '{}' may not match provider='{}' — " +
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
                log.warn("[verifySignature] HMAC mismatch — computed='{}...' received='{}...'",
                        computed.substring(0, 8), signature.substring(0, Math.min(8, signature.length())));
            }
            return matches;
        } catch (Exception e) {
            log.error("[verifySignature] HMAC error", e);
            return false;
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /** Masks phone for safe logging: "0551234987" → "055****987" */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }

    /** Masks an account number for safe logging: "1234567890" → "12****90" */
    private String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 6) return "***";
        return accountNumber.substring(0, 2) + "****" + accountNumber.substring(accountNumber.length() - 2);
    }
}
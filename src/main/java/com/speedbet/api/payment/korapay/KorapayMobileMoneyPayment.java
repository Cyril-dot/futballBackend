package com.speedbet.api.payment.korapay;

import com.speedbet.api.chat.AdminUpgradeChatService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserService;
import com.speedbet.api.wallet.TxKind;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KorapayMobileMoneyPayment — GHS MoMo payments via Korapay Mobile Money Charge API.
 *
 * NOTE: All routes in this controller are prefixed with "/momo" to avoid
 * ambiguous-mapping conflicts if another Korapay controller (e.g. Checkout /
 * Payment Link flow) is added later on non-prefixed paths.
 *
 * ─── Payment flows ───────────────────────────────────────────────────────────
 *
 *  1. Initiate Mobile Money Charge — Deposit
 *     POST /api/wallet/deposit/korapay/momo/init
 *     • Accepts { amount, phone, email } from the frontend (email is required
 *       by Korapay's customer object — fall back to the user's account email
 *       if the frontend does not collect one separately).
 *     • Calls Korapay POST /merchant/api/v1/charges/mobile-money.
 *     • Returns { reference, authModel, message, redirectUrl } to the frontend.
 *       authModel is one of "OTP", "STK_PROMPT", "REDIRECT":
 *         - OTP         → user must submit the SMS code via /momo/otp.
 *         - STK_PROMPT  → user approves a PIN prompt on their phone; no further
 *                          action needed from our backend — poll /momo/verify
 *                          or wait for the webhook.
 *         - REDIRECT    → send the user to redirectUrl to finish on the telco page.
 *     • Korapay fires our webhook → wallet is credited automatically.
 *
 *  2. OTP Submission (OTP auth model only)
 *     POST /api/wallet/deposit/korapay/momo/otp
 *     • Only needed when /momo/init returns authModel=OTP.
 *     • Accepts { reference, otp } from the frontend.
 *     • Calls Korapay POST /merchant/api/v1/charges/mobile-money/authorize
 *       with { reference, token }.
 *     • On success Korapay returns the NEXT authModel (STK_PROMPT or REDIRECT),
 *       which we pass back to the frontend to continue the flow.
 *
 *  3. Initiate Mobile Money Charge — Admin Upgrade
 *     POST /api/user/upgrade-to-admin/korapay/momo/init
 *     • Same charge flow but amount is fixed at GHS 200 and promotes
 *       the user to ADMIN on successful payment.
 *
 *  4. Payment Verification (manual fallback / polling)
 *     POST /api/wallet/deposit/korapay/momo/verify
 *     • Accepts the reference returned by /momo/init.
 *     • Calls Korapay GET /merchant/api/v1/charges/:reference.
 *     • Credits wallet immediately if status=success and not already credited.
 *     • Idempotent — safe to poll; duplicate refs are silently ignored.
 *
 *  5. Webhook (primary / automatic credit path)
 *     POST /api/webhooks/korapay/momo
 *     • Korapay POSTs here on charge.success / charge.failed.
 *     • Verified via the X-Korapay-Signature header — an HMAC-SHA256 of the
 *       raw JSON `data` object, signed with our secret key.
 *     • /momo/verify above is the fallback for missed or delayed webhooks.
 *     • IMPORTANT: point this flow's Korapay dashboard webhook URL at
 *       /api/webhooks/korapay/momo.
 *
 * ─── reference convention ─────────────────────────────────────────────────────
 *   "deposit_<userId>_<uuid>"       → credit wallet
 *   "adminupgrade_<userId>_<uuid>"  → promote user to ADMIN
 *   (Korapay requires references to be at least 8 characters — this format
 *    is always well above that.)
 *
 * ─── Korapay charge statuses (verify / webhook) ──────────────────────────────
 *   "processing" = pending (still awaiting OTP/PIN/redirect completion)
 *   "success"     = success
 *   "failed"      = failed / cancelled
 *
 * ─── Korapay API base URL (hardcoded) ────────────────────────────────────────
 *   https://api.korapay.com
 *
 * ─── Phone number format ─────────────────────────────────────────────────────
 *   Korapay expects the MoMo number as digits only, no leading 0, with the
 *   233 country code prefix (e.g. "233244123456"). The frontend should collect
 *   the number in local 0XXXXXXXXX format; this controller normalizes it.
 *
 * ─── application.properties keys needed ──────────────────────────────────────
 *   app.korapay.secret-key           → env: KORAPAY_SECRET_KEY
 *                                       (used as "Bearer {secret}" for all API calls
 *                                        AND as the HMAC key for webhook verification)
 *   app.platform.min-deposit-amount (default: 1)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class KorapayMobileMoneyPayment {

    // ─── Hardcoded Korapay base URL ───────────────────────────────────────────
    private static final String KORAPAY_BASE_URL = "https://api.korapay.com";

    private static final BigDecimal ADMIN_UPGRADE_FEE    = BigDecimal.valueOf(200);
    private static final String     UPGRADE_INTENT_ADMIN = "adminupgrade";
    private static final String     DEPOSIT_INTENT       = "deposit";

    // Korapay charge statuses
    private static final String STATUS_SUCCESS    = "success";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_FAILED     = "failed";

    // Korapay mobile money auth models
    private static final String AUTH_MODEL_OTP        = "OTP";
    private static final String AUTH_MODEL_STK_PROMPT = "STK_PROMPT";
    private static final String AUTH_MODEL_REDIRECT    = "REDIRECT";

    /**
     * Pending charge cache — keyed by reference.
     * Stores { amount, phone, userId } purely for bookkeeping/log correlation
     * across the OTP step; Korapay's authorize endpoint only needs
     * { reference, token }, so nothing here is strictly required to replay
     * the charge — unlike Moolre, Korapay tracks the whole charge server-side
     * once it's created.
     * In a multi-instance deployment replace this with Redis or a DB table.
     */
    private final ConcurrentHashMap<String, PendingCharge> pendingCharges = new ConcurrentHashMap<>();

    /** Lightweight struct for cached charge params. */
    record PendingCharge(BigDecimal amount, String phone, UUID userId) {}

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    @Value("${app.korapay.secret-key}")            private String     secretKey;
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ─── 1. Initiate Mobile Money Charge — Deposit ───────────────────────────

    /**
     * Initiates a Korapay mobile money charge for a wallet deposit.
     *
     * Required body fields:
     *   amount – GHS amount to deposit (e.g. "300")
     *   phone  – customer's MoMo number in 0XXXXXXXXX format (e.g. "0244123456")
     *
     * Optional body fields:
     *   email  – customer email for Korapay's required customer.email field.
     *            Falls back to user.getEmail() if omitted.
     *
     * Response (always HTTP 200 on valid request):
     *   {
     *     "reference":    "deposit_<userId>_<uuid>",
     *     "authModel":    "OTP" | "STK_PROMPT" | "REDIRECT",
     *     "redirectUrl":  "..." (only present when authModel=REDIRECT),
     *     "message":      "..."
     *   }
     */
    @PostMapping("/api/wallet/deposit/korapay/momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        var phone  = req.get("phone");
        var email  = req.get("email");

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        if (phone == null || phone.toString().isBlank())
            throw ApiException.badRequest("phone is required.");

        var customerEmail = (email != null && !email.toString().isBlank())
                ? email.toString()
                : user.getEmail();
        if (customerEmail == null || customerEmail.isBlank())
            throw ApiException.badRequest("email is required.");

        var reference = DEPOSIT_INTENT + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initDeposit (Korapay MoMo): userId='{}' amount={} phone='{}' reference='{}'",
                user.getId(), amount, phone, reference);

        pendingCharges.put(reference, new PendingCharge(amount, phone.toString(), user.getId()));

        Map<String, Object> chargeResult;
        try {
            chargeResult = korapayInitCharge(amount, phone.toString(), customerEmail, reference);
        } catch (RuntimeException ex) {
            pendingCharges.remove(reference);
            log.error("initDeposit: Korapay charge failed for userId='{}' reference='{}' — {}",
                    user.getId(), reference, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Payment initiation failed. Please try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(buildInitResponse(reference, chargeResult)));
    }

    // ─── 2. OTP Submission ────────────────────────────────────────────────────

    /**
     * Submits the SMS verification code sent by Korapay/the telco.
     *
     * Only relevant when /momo/init (or a prior /momo/otp call) returned
     * authModel="OTP". Calls Korapay's authorize endpoint, which returns the
     * next authModel (typically STK_PROMPT) for the frontend to act on.
     *
     * Required body fields:
     *   reference – the reference returned by /momo/init
     *   otp       – the code the user received via SMS
     *
     * Response (HTTP 200 on success):
     *   {
     *     "authModel": "STK_PROMPT" | "REDIRECT",
     *     "redirectUrl": "..." (only present when authModel=REDIRECT),
     *     "message": "..."
     *   }
     */
    @PostMapping("/api/wallet/deposit/korapay/momo/otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var reference = req.get("reference");
        var otp       = req.get("otp");

        if (reference == null || reference.toString().isBlank())
            throw ApiException.badRequest("reference is required.");
        if (otp == null || otp.toString().isBlank())
            throw ApiException.badRequest("otp is required.");

        var ref = reference.toString().trim();
        var refUserId = extractUserId(ref);

        if (!refUserId.equals(user.getId()))
            throw ApiException.forbidden("This payment reference does not belong to your account.");

        log.info("submitOtp: userId='{}' reference='{}'", user.getId(), ref);

        Map<String, Object> authResult;
        try {
            authResult = korapayAuthorizeOtp(ref, otp.toString().trim());
        } catch (RuntimeException ex) {
            log.error("submitOtp: OTP submission failed for userId='{}' reference='{}' — {}",
                    user.getId(), ref, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "OTP verification failed. Please check the code and try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(buildInitResponse(ref, authResult)));
    }

    // ─── 3. Initiate Mobile Money Charge — Admin Upgrade ─────────────────────

    /**
     * Initiates a Korapay mobile money charge for the GHS 200 admin upgrade fee.
     *
     * Required body fields:
     *   phone – customer's MoMo number in 0XXXXXXXXX format
     *
     * Optional body fields:
     *   email – customer email; falls back to user.getEmail() if omitted.
     *
     * Response (always HTTP 200 on valid request):
     *   {
     *     "reference":   "adminupgrade_<userId>_<uuid>",
     *     "authModel":   "OTP" | "STK_PROMPT" | "REDIRECT",
     *     "redirectUrl": "...",
     *     "message":     "..."
     *   }
     */
    @PostMapping("/api/user/upgrade-to-admin/korapay/momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var phone = req.get("phone");
        var email = req.get("email");

        if (phone == null || phone.toString().isBlank())
            throw ApiException.badRequest("phone is required.");

        var customerEmail = (email != null && !email.toString().isBlank())
                ? email.toString()
                : user.getEmail();
        if (customerEmail == null || customerEmail.isBlank())
            throw ApiException.badRequest("email is required.");

        var reference = UPGRADE_INTENT_ADMIN + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initAdminUpgrade (Korapay MoMo): userId='{}' phone='{}' reference='{}'",
                user.getId(), phone, reference);

        pendingCharges.put(reference, new PendingCharge(ADMIN_UPGRADE_FEE, phone.toString(), user.getId()));

        Map<String, Object> chargeResult;
        try {
            chargeResult = korapayInitCharge(ADMIN_UPGRADE_FEE, phone.toString(), customerEmail, reference);
        } catch (RuntimeException ex) {
            pendingCharges.remove(reference);
            log.error("initAdminUpgrade: Korapay charge failed for userId='{}' reference='{}' — {}",
                    user.getId(), reference, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Upgrade payment initiation failed. Please try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(buildInitResponse(reference, chargeResult)));
    }

    // ─── 4. Payment Verification ──────────────────────────────────────────────

    /**
     * Manually verifies a Korapay payment by its reference and credits the
     * wallet if successful. Idempotent — safe to poll.
     *
     * Required body fields:
     *   reference – the reference returned by /momo/init
     */
    @PostMapping("/api/wallet/deposit/korapay/momo/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPayment(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var reference = req.get("reference");
        if (reference == null || reference.toString().isBlank())
            throw ApiException.badRequest("reference is required.");

        var ref = reference.toString().trim();
        var refUserId = extractUserId(ref);

        if (!refUserId.equals(user.getId()))
            throw ApiException.forbidden("This payment reference does not belong to your account.");

        log.info("verifyPayment: userId='{}' reference='{}'", user.getId(), ref);

        var chargeResponse = korapayCheckStatus(ref);

        @SuppressWarnings("unchecked")
        var data   = (Map<String, Object>) chargeResponse.get("data");
        var status = data != null ? String.valueOf(data.getOrDefault("status", "")) : "";

        if (STATUS_PROCESSING.equals(status)) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "status",   STATUS_PROCESSING,
                    "message",  "Payment is still processing. Please complete the authorization on your phone."
            )));
        }

        if (STATUS_FAILED.equals(status)) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "status",   STATUS_FAILED,
                    "message",  "Payment failed or was cancelled."
            )));
        }

        if (!STATUS_SUCCESS.equals(status)) {
            log.warn("verifyPayment: unknown status='{}' reference='{}'", status, ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "status",   status,
                    "message",  "Unexpected payment status. Please contact support."
            )));
        }

        // status = success — credit wallet
        var amount = resolveAmount(data, ref);
        var parts  = ref.split("_", 3);
        var intent = parts[0];

        // Clean up cache on confirmed success
        pendingCharges.remove(ref);

        boolean credited = UPGRADE_INTENT_ADMIN.equals(intent)
                ? verifyAndHandleAdminUpgrade(user.getId(), ref, amount)
                : verifyAndHandleDeposit(user.getId(), ref, amount);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "credited", credited,
                "status",   STATUS_SUCCESS,
                "message",  credited
                        ? "Payment verified. GHS " + amount + " has been added to your wallet."
                        : "Payment was already processed."
        )));
    }

    // ─── 5. Webhook ───────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/korapay/momo")
    public ResponseEntity<String> webhook(HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Korapay webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        try {
            var rawJson = new String(rawBody, StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper.readValue(rawJson, Map.class);

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            if (data == null) {
                log.warn("Korapay webhook: missing data field");
                return ResponseEntity.status(400).body("Missing data");
            }

            // Signature is computed over ONLY the `data` object, re-serialized —
            // NOT over the raw request body (which also contains the `event` field).
            String dataJson;
            try {
                dataJson = objectMapper.writeValueAsString(data);
            } catch (Exception e) {
                log.error("Korapay webhook: failed to re-serialize data for signature check", e);
                return ResponseEntity.status(400).body("Bad payload");
            }

            var signature = request.getHeader("x-korapay-signature");
            if (!verifyWebhookSignature(dataJson, signature)) {
                log.warn("Korapay webhook: invalid or missing x-korapay-signature");
                return ResponseEntity.status(400).body("Invalid signature");
            }

            var eventName = String.valueOf(event.getOrDefault("event", ""));
            var status    = String.valueOf(data.getOrDefault("status", ""));

            if (!"charge.success".equals(eventName) || !STATUS_SUCCESS.equals(status)) {
                log.info("Korapay webhook: ignoring event='{}' status='{}' reference='{}'",
                        eventName, status, data.get("reference"));
                return ResponseEntity.ok("Ignored");
            }

            var refObj = data.get("reference");
            if (refObj == null || refObj.toString().isBlank()) {
                log.error("Korapay webhook: missing reference in data");
                return ResponseEntity.status(400).body("Missing reference");
            }

            var ref    = refObj.toString();
            var amount = resolveAmount(data, ref);

            var parts = ref.split("_", 3);
            if (parts.length < 3) {
                log.error("Korapay webhook: unexpected reference format ref='{}'", ref);
                return ResponseEntity.status(400).body("Unexpected reference format");
            }

            var intent = parts[0];
            UUID userId;
            try {
                userId = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                log.error("Korapay webhook: cannot parse userId from ref='{}'", ref);
                return ResponseEntity.status(400).body("Invalid userId in reference");
            }

            // Clean up pending cache on webhook success
            pendingCharges.remove(ref);

            if (UPGRADE_INTENT_ADMIN.equals(intent)) {
                handleAdminUpgrade(userId, ref, amount);
            } else {
                handleDeposit(userId, ref, amount);
            }

        } catch (ApiException e) {
            log.error("Korapay webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Korapay webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Private — wallet handlers ────────────────────────────────────────────

    private boolean handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "korapay", "reference", ref));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'", amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return false;
            }
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit='{}'", userId, amount);
        } catch (Exception ex) {
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate", userId, ex);
        }

        return true;
    }

    private boolean verifyAndHandleDeposit(UUID userId, String ref, BigDecimal amount) {
        return handleDeposit(userId, ref, amount);
    }

    private boolean handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(ADMIN_UPGRADE_FEE) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'", amount, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            userService.upgradeToAdmin(userId, ref);
            log.info("handleAdminUpgrade: userId='{}' promoted to ADMIN ref='{}'", userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleAdminUpgrade: duplicate ref='{}' — skipping", ref);
                return false;
            }
            throw ex;
        }

        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "korapay", "reference", ref));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);

        return true;
    }

    private boolean verifyAndHandleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        return handleAdminUpgrade(userId, ref, amount);
    }

    // ─── Korapay API helpers ───────────────────────────────────────────────────

    /**
     * Calls Korapay POST /merchant/api/v1/charges/mobile-money to initiate a
     * mobile money charge.
     *
     * Phone is normalized from local 0XXXXXXXXX format to Korapay's expected
     * 233XXXXXXXXX format (digits only, country code, no leading 0, no '+').
     *
     * Returns the response's `data` object (amount, auth_model, message,
     * transaction_reference, authorization.redirect_url when present, etc.)
     * on success; throws RuntimeException on transport or hard API errors.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> korapayInitCharge(
            BigDecimal amount, String phone, String email, String reference) {

        var normalizedPhone = normalizeGhanaPhone(phone);

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("amount",       amount.toPlainString());
        body.put("currency",     "GHS");
        body.put("reference",    reference);
        body.put("description",  "Wallet transaction");
        body.put("customer", Map.of("email", email));
        body.put("mobile_money", Map.of("number", normalizedPhone));

        log.info("korapayInitCharge: calling /merchant/api/v1/charges/mobile-money — phone='{}' amount='{}' reference='{}'",
                normalizedPhone, amount, reference);

        var result = callKorapay("/merchant/api/v1/charges/mobile-money", body);

        var status  = Boolean.TRUE.equals(result.get("status"));
        var message = String.valueOf(result.getOrDefault("message", ""));

        if (!status) {
            log.error("korapayInitCharge: Korapay error message='{}'", message);
            throw new RuntimeException("Korapay error: " + message);
        }

        var data = (Map<String, Object>) result.getOrDefault("data", Map.of());
        log.info("korapayInitCharge: authModel='{}' message='{}' reference='{}'",
                data.get("auth_model"), message, reference);

        return data;
    }

    /**
     * Calls Korapay POST /merchant/api/v1/charges/mobile-money/authorize to
     * submit an OTP for a pending charge.
     *
     * Returns the response's `data` object, which contains the NEXT auth_model
     * (typically STK_PROMPT) to continue the flow on success.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> korapayAuthorizeOtp(String reference, String otp) {

        var body = Map.of(
                "reference", reference,
                "token",     otp
        );

        log.info("korapayAuthorizeOtp: calling /merchant/api/v1/charges/mobile-money/authorize — reference='{}'",
                reference);

        var result = callKorapay("/merchant/api/v1/charges/mobile-money/authorize", body);

        var status  = Boolean.TRUE.equals(result.get("status"));
        var message = String.valueOf(result.getOrDefault("message", ""));

        if (!status) {
            log.error("korapayAuthorizeOtp: Korapay error message='{}' reference='{}'", message, reference);
            throw new RuntimeException("Korapay error: " + message);
        }

        var data = (Map<String, Object>) result.getOrDefault("data", Map.of());
        log.info("korapayAuthorizeOtp: nextAuthModel='{}' message='{}' reference='{}'",
                data.get("auth_model"), message, reference);

        return data;
    }

    /**
     * Calls Korapay GET /merchant/api/v1/charges/:reference to check payment status.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> korapayCheckStatus(String reference) {

        String rawBody = webClientBuilder.build()
                .get().uri(KORAPAY_BASE_URL + "/merchant/api/v1/charges/" + reference)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .retrieve()
                .onStatus(
                        httpStatus -> httpStatus.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("Korapay checkStatus HTTP error: status={} body={}",
                                            clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "Korapay returned HTTP " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(String.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException),
                        ex -> {
                            log.error("Korapay API unreachable during status check", ex);
                            return new RuntimeException("Korapay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (rawBody == null || rawBody.isBlank())
            throw new RuntimeException("Korapay returned an empty status response.");

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.error("Korapay checkStatus: non-JSON response body='{}'", rawBody);
            throw new RuntimeException("Korapay returned an unexpected status response. Please try again.");
        }

        log.info("korapayCheckStatus: status='{}' message='{}' for reference='{}'",
                result.get("status"), result.get("message"), reference);

        return result;
    }

    /**
     * Shared POST helper for the charge + authorize endpoints, which share
     * identical headers/error handling.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callKorapay(String path, Object body) {

        String rawBody = webClientBuilder.build()
                .post().uri(KORAPAY_BASE_URL + path)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        httpStatus -> httpStatus.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("Korapay HTTP error: path={} status={} body={}",
                                            path, clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "Korapay returned HTTP " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(String.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException),
                        ex -> {
                            log.error("Korapay API unreachable — path={}", path, ex);
                            return new RuntimeException("Korapay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (rawBody == null || rawBody.isBlank())
            throw new RuntimeException("Korapay returned an empty response.");

        try {
            return (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.error("Korapay: non-JSON response from path={} body='{}'", path, rawBody);
            throw new RuntimeException("Korapay returned an unexpected response. Please try again.");
        }
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────

    /**
     * Builds the common { reference, authModel, redirectUrl?, message } shape
     * returned by /momo/init and /momo/otp.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInitResponse(String reference, Map<String, Object> data) {
        var authModel = String.valueOf(data.getOrDefault("auth_model", ""));
        var message   = String.valueOf(data.getOrDefault("message", "Please complete the payment on your phone."));

        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("reference", reference);
        response.put("authModel", authModel);
        response.put("message",   message);

        if (AUTH_MODEL_REDIRECT.equals(authModel)) {
            var authorization = (Map<String, Object>) data.get("authorization");
            if (authorization != null) {
                response.put("redirectUrl", authorization.get("redirect_url"));
            }
        }

        return response;
    }

    /**
     * Normalizes a local Ghanaian number ("0244123456") into the digits-only,
     * country-code-prefixed format Korapay expects ("233244123456").
     * Passes through numbers that already look like they have the 233 prefix.
     */
    private static String normalizeGhanaPhone(String phone) {
        var digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("233")) return digits;
        if (digits.startsWith("0"))    return "233" + digits.substring(1);
        return "233" + digits;
    }

    private static UUID extractUserId(String ref) {
        var parts = ref.split("_", 3);
        if (parts.length < 3)
            throw ApiException.badRequest("Invalid reference format.");
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid reference format.");
        }
    }

    private static BigDecimal resolveAmount(Map<String, Object> data, String ref) {
        var amount = data.get("amount");
        if (amount != null && !amount.toString().isBlank()) return new BigDecimal(amount.toString());

        throw ApiException.badRequest(
                "Korapay response is missing the 'amount' field for ref='" + ref + "'");
    }

    /**
     * Verifies the X-Korapay-Signature header: an HMAC-SHA256 of the JSON-
     * serialized `data` object from the webhook payload, signed with our
     * secret key, hex-encoded.
     */
    private boolean verifyWebhookSignature(String dataJson, String incomingSignature) {
        if (incomingSignature == null || incomingSignature.isBlank()) {
            log.warn("Korapay webhook: x-korapay-signature header is missing or blank");
            return false;
        }

        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var digest   = mac.doFinal(dataJson.getBytes(StandardCharsets.UTF_8));
            var computed = bytesToHex(digest);

            return java.security.MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    incomingSignature.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Korapay webhook: failed to compute HMAC signature", e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var hexChars = new char[bytes.length * 2];
        var hexDigits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2]     = hexDigits[v >> 4];
            hexChars[i * 2 + 1] = hexDigits[v & 0x0F];
        }
        return new String(hexChars);
    }
}
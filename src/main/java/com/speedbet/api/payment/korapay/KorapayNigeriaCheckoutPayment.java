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
 * KorapayNigeriaCheckoutPayment — NGN payments via Korapay's Standard Checkout
 * (hosted redirect page) and direct USSD Charge APIs.
 *
 * NOTE: All routes in this controller are prefixed with "/checkout" to avoid
 * ambiguous-mapping conflicts with {@link com.speedbet.api.payment.korapay.KorapayMobileMoneyPayment},
 * which owns the "/momo" prefix on the same base paths.
 *
 * ─── Payment flows ───────────────────────────────────────────────────────────
 *
 *  1. Initiate Charge — Deposit
 *     POST /api/wallet/deposit/korapay/checkout/init
 *     • Accepts { amount, email, method, bankCode } from the frontend.
 *       method is "checkout" (default) or "ussd":
 *         - "checkout" → calls Korapay POST /merchant/api/v1/charges/initialize.
 *                        Response includes checkoutUrl; send the user there to
 *                        pick card / bank transfer / USSD / etc. on Korapay's
 *                        hosted page.
 *         - "ussd"     → calls Korapay POST /merchant/api/v1/charges/ussd with
 *                        the customer's bankCode. Response includes ussdCode
 *                        for the user to dial directly on their phone — no
 *                        redirect needed.
 *     • Korapay fires our webhook → wallet is credited automatically.
 *     • Either path is a fallback/poll-able via /checkout/verify.
 *
 *  2. Initiate Charge — Admin Upgrade
 *     POST /api/user/upgrade-to-admin/korapay/checkout/init
 *     • Same { method, bankCode } shape but amount is fixed at the configured
 *       NGN admin-upgrade fee and promotes the user to ADMIN on success.
 *
 *  3. Payment Verification (manual fallback / polling)
 *     POST /api/wallet/deposit/korapay/checkout/verify
 *     • Accepts the reference returned by /checkout/init.
 *     • Calls Korapay GET /merchant/api/v1/charges/:reference (shared status
 *       endpoint across all Korapay charge types).
 *     • Credits wallet immediately if status=success and not already credited.
 *     • Idempotent — safe to poll; duplicate refs are silently ignored.
 *
 *  4. Webhook (primary / automatic credit path)
 *     POST /api/webhooks/korapay/checkout
 *     • Korapay POSTs here on charge.success / charge.failed.
 *     • Verified via the X-Korapay-Signature header — an HMAC-SHA256 of the
 *       raw JSON `data` object, signed with our secret key (identical scheme
 *       to the MoMo webhook).
 *     • /checkout/verify above is the fallback for missed or delayed webhooks.
 *     • IMPORTANT: point this flow's Korapay dashboard webhook URL at
 *       /api/webhooks/korapay/checkout. Korapay's dashboard supports a single
 *       webhook URL per merchant, so if both this and the MoMo controller are
 *       deployed, one shared webhook receiver that fans out by reference
 *       prefix may be required instead of two separate endpoints — flagging
 *       this for infra/product to confirm before go-live.
 *
 * ─── reference convention ─────────────────────────────────────────────────────
 *   "deposit_<userId>_<uuid>"       → credit wallet
 *   "adminupgrade_<userId>_<uuid>"  → promote user to ADMIN
 *   (Korapay requires references to be at least 8 characters — this format
 *    is always well above that.)
 *
 * ─── Korapay charge statuses (verify / webhook) ──────────────────────────────
 *   "processing" = pending (still awaiting payment / USSD dial completion)
 *   "success"     = success
 *   "failed"      = failed / cancelled
 *
 * ─── Korapay API base URL (hardcoded) ────────────────────────────────────────
 *   https://api.korapay.com
 *
 * ─── Field-name disclaimer ────────────────────────────────────────────────────
 *   The /charges/initialize and /charges/ussd request/response shapes below
 *   are modeled on Korapay's published API conventions (mirroring the same
 *   `data.authorization.*` envelope used for redirect/STK auth models in the
 *   MoMo controller). Confirm exact field names (checkout_url vs redirect_url,
 *   ussd_code location, bank_code list, expiry field) against the current
 *   Korapay API reference before deploying — Korapay has been known to version
 *   these payloads.
 *
 * ─── application.properties keys needed ──────────────────────────────────────
 *   app.korapay.secret-key                    → env: KORAPAY_SECRET_KEY
 *                                                (shared with the MoMo controller —
 *                                                 same merchant secret, used as
 *                                                 "Bearer {secret}" for all API
 *                                                 calls AND as the HMAC key for
 *                                                 webhook verification)
 *   app.platform.min-deposit-amount           (default: 1) — shared across currencies
 *   app.platform.admin-upgrade-fee-ngn        (default: 20000) — CONFIRM with
 *                                                product before go-live; this is
 *                                                a placeholder, not a sourced figure.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class KorapayNigeriaCheckoutPayment {

    // ─── Hardcoded Korapay base URL ───────────────────────────────────────────
    private static final String KORAPAY_BASE_URL = "https://api.korapay.com";

    private static final String UPGRADE_INTENT_ADMIN = "adminupgrade";
    private static final String DEPOSIT_INTENT       = "deposit";
    private static final String CURRENCY_NGN         = "NGN";

    // Korapay charge statuses
    private static final String STATUS_SUCCESS    = "success";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_FAILED     = "failed";

    // Supported init methods for this controller
    private static final String METHOD_CHECKOUT = "checkout";
    private static final String METHOD_USSD      = "ussd";

    /**
     * Pending charge cache — keyed by reference.
     * Stores { amount, method, userId } purely for bookkeeping/log correlation;
     * Korapay tracks the whole charge server-side once created, so nothing
     * here is strictly required to replay the charge.
     * In a multi-instance deployment replace this with Redis or a DB table.
     */
    private final ConcurrentHashMap<String, PendingCharge> pendingCharges = new ConcurrentHashMap<>();

    /** Lightweight struct for cached charge params. */
    record PendingCharge(BigDecimal amount, String method, UUID userId) {}

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    @Value("${app.korapay.secret-key}")                     private String     secretKey;
    @Value("${app.platform.min-deposit-amount:1}")          private BigDecimal minDeposit;
    @Value("${app.platform.admin-upgrade-fee-ngn:20000}")   private BigDecimal adminUpgradeFee;

    // ─── 1. Initiate Charge — Deposit ─────────────────────────────────────────

    /**
     * Initiates a Korapay NGN charge for a wallet deposit, via either the
     * hosted Checkout page or a direct USSD charge.
     *
     * Required body fields:
     *   amount – NGN amount to deposit (e.g. "5000")
     *   email  – customer email for Korapay's required customer.email field
     *
     * Optional body fields:
     *   method   – "checkout" (default) or "ussd"
     *   bankCode – required when method="ussd"; the customer's bank USSD code
     *              as recognized by Korapay (e.g. "058" for GTBank). Not used
     *              for method="checkout" — the hosted page lets the customer
     *              choose their own channel, USSD included.
     *
     * Response (always HTTP 200 on valid request):
     *   {
     *     "reference":   "deposit_<userId>_<uuid>",
     *     "method":      "checkout" | "ussd",
     *     "checkoutUrl": "..." (method=checkout only),
     *     "ussdCode":    "..." (method=ussd only),
     *     "bankName":    "..." (method=ussd only),
     *     "message":     "..."
     *   }
     */
    @PostMapping("/api/wallet/deposit/korapay/checkout/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        var email  = req.get("email");

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is NGN " + minDeposit);

        var customerEmail = (email != null && !email.toString().isBlank())
                ? email.toString()
                : user.getEmail();
        if (customerEmail == null || customerEmail.isBlank())
            throw ApiException.badRequest("email is required.");

        var method = resolveMethod(req.get("method"));
        var bankCode = requireBankCodeIfUssd(method, req.get("bankCode"));

        var reference = DEPOSIT_INTENT + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initDeposit (Korapay Checkout): userId='{}' amount={} method='{}' reference='{}'",
                user.getId(), amount, method, reference);

        pendingCharges.put(reference, new PendingCharge(amount, method, user.getId()));

        Map<String, Object> chargeResult;
        try {
            chargeResult = METHOD_USSD.equals(method)
                    ? korapayInitUssdCharge(amount, customerEmail, bankCode, reference)
                    : korapayInitCheckout(amount, customerEmail, reference);
        } catch (RuntimeException ex) {
            pendingCharges.remove(reference);
            log.error("initDeposit: Korapay charge failed for userId='{}' reference='{}' — {}",
                    user.getId(), reference, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Payment initiation failed. Please try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(buildInitResponse(reference, method, chargeResult)));
    }

    // ─── 2. Initiate Charge — Admin Upgrade ──────────────────────────────────

    /**
     * Initiates a Korapay NGN charge for the admin upgrade fee.
     *
     * Required body fields:
     *   email – customer email; falls back to user.getEmail() if omitted.
     *
     * Optional body fields:
     *   method   – "checkout" (default) or "ussd"
     *   bankCode – required when method="ussd"
     *
     * Response (always HTTP 200 on valid request):
     *   {
     *     "reference":   "adminupgrade_<userId>_<uuid>",
     *     "method":      "checkout" | "ussd",
     *     "checkoutUrl": "..." (method=checkout only),
     *     "ussdCode":    "..." (method=ussd only),
     *     "bankName":    "..." (method=ussd only),
     *     "message":     "..."
     *   }
     */
    @PostMapping("/api/user/upgrade-to-admin/korapay/checkout/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var email = req.get("email");
        var customerEmail = (email != null && !email.toString().isBlank())
                ? email.toString()
                : user.getEmail();
        if (customerEmail == null || customerEmail.isBlank())
            throw ApiException.badRequest("email is required.");

        var method = resolveMethod(req.get("method"));
        var bankCode = requireBankCodeIfUssd(method, req.get("bankCode"));

        var reference = UPGRADE_INTENT_ADMIN + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initAdminUpgrade (Korapay Checkout): userId='{}' method='{}' reference='{}'",
                user.getId(), method, reference);

        pendingCharges.put(reference, new PendingCharge(adminUpgradeFee, method, user.getId()));

        Map<String, Object> chargeResult;
        try {
            chargeResult = METHOD_USSD.equals(method)
                    ? korapayInitUssdCharge(adminUpgradeFee, customerEmail, bankCode, reference)
                    : korapayInitCheckout(adminUpgradeFee, customerEmail, reference);
        } catch (RuntimeException ex) {
            pendingCharges.remove(reference);
            log.error("initAdminUpgrade: Korapay charge failed for userId='{}' reference='{}' — {}",
                    user.getId(), reference, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Upgrade payment initiation failed. Please try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(buildInitResponse(reference, method, chargeResult)));
    }

    // ─── 3. Payment Verification ──────────────────────────────────────────────

    /**
     * Manually verifies a Korapay payment by its reference and credits the
     * wallet if successful. Idempotent — safe to poll.
     *
     * Required body fields:
     *   reference – the reference returned by /checkout/init
     */
    @PostMapping("/api/wallet/deposit/korapay/checkout/verify")
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
                    "message",  "Payment is still processing. Please complete it on your bank app or the checkout page."
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
                        ? "Payment verified. NGN " + amount + " has been added to your wallet."
                        : "Payment was already processed."
        )));
    }

    // ─── 4. Webhook ───────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/korapay/checkout")
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
            log.info("handleDeposit: NGN {} credited to userId='{}' ref='{}'", amount, userId, ref);
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

        if (amount.compareTo(adminUpgradeFee) < 0) {
            log.error("handleAdminUpgrade: amount {} < NGN {} for userId='{}' ref='{}'",
                    amount, adminUpgradeFee, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment NGN " + amount + " is less than required NGN " + adminUpgradeFee + ".");
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
     * Calls Korapay POST /merchant/api/v1/charges/initialize to create a
     * hosted Checkout session.
     *
     * Returns the response's `data` object (reference, checkout_url, etc.)
     * on success; throws RuntimeException on transport or hard API errors.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> korapayInitCheckout(BigDecimal amount, String email, String reference) {

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("amount",      amount.toPlainString());
        body.put("currency",    CURRENCY_NGN);
        body.put("reference",   reference);
        body.put("description", "Wallet transaction");
        body.put("customer",    Map.of("email", email));

        log.info("korapayInitCheckout: calling /merchant/api/v1/charges/initialize — amount='{}' reference='{}'",
                amount, reference);

        var result = callKorapay("/merchant/api/v1/charges/initialize", body);

        var status  = Boolean.TRUE.equals(result.get("status"));
        var message = String.valueOf(result.getOrDefault("message", ""));

        if (!status) {
            log.error("korapayInitCheckout: Korapay error message='{}'", message);
            throw new RuntimeException("Korapay error: " + message);
        }

        var data = (Map<String, Object>) result.getOrDefault("data", Map.of());
        log.info("korapayInitCheckout: message='{}' reference='{}'", message, reference);

        return data;
    }

    /**
     * Calls Korapay POST /merchant/api/v1/charges/ussd to initiate a direct
     * USSD charge for the given bank.
     *
     * Returns the response's `data` object (reference, ussd_code, bank_name,
     * etc.) on success; throws RuntimeException on transport or hard API errors.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> korapayInitUssdCharge(
            BigDecimal amount, String email, String bankCode, String reference) {

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("amount",      amount.toPlainString());
        body.put("currency",    CURRENCY_NGN);
        body.put("reference",   reference);
        body.put("description", "Wallet transaction");
        body.put("customer",    Map.of("email", email));
        body.put("bank_code",   bankCode);

        log.info("korapayInitUssdCharge: calling /merchant/api/v1/charges/ussd — bankCode='{}' amount='{}' reference='{}'",
                bankCode, amount, reference);

        var result = callKorapay("/merchant/api/v1/charges/ussd", body);

        var status  = Boolean.TRUE.equals(result.get("status"));
        var message = String.valueOf(result.getOrDefault("message", ""));

        if (!status) {
            log.error("korapayInitUssdCharge: Korapay error message='{}'", message);
            throw new RuntimeException("Korapay error: " + message);
        }

        var data = (Map<String, Object>) result.getOrDefault("data", Map.of());
        log.info("korapayInitUssdCharge: message='{}' reference='{}'", message, reference);

        return data;
    }

    /**
     * Calls Korapay GET /merchant/api/v1/charges/:reference to check payment
     * status. Shared across checkout- and USSD-initiated charges.
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
     * Shared POST helper for the initialize + ussd endpoints, which share
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

    private static String resolveMethod(Object raw) {
        if (raw == null || raw.toString().isBlank()) return METHOD_CHECKOUT;
        var method = raw.toString().trim().toLowerCase();
        if (!METHOD_CHECKOUT.equals(method) && !METHOD_USSD.equals(method))
            throw ApiException.badRequest("method must be 'checkout' or 'ussd'.");
        return method;
    }

    private static String requireBankCodeIfUssd(String method, Object bankCode) {
        if (!METHOD_USSD.equals(method)) return null;
        if (bankCode == null || bankCode.toString().isBlank())
            throw ApiException.badRequest("bankCode is required when method='ussd'.");
        return bankCode.toString().trim();
    }

    /**
     * Builds the common { reference, method, checkoutUrl?, ussdCode?,
     * bankName?, message } shape returned by /checkout/init.
     */
    private Map<String, Object> buildInitResponse(String reference, String method, Map<String, Object> data) {
        var message = String.valueOf(data.getOrDefault("message",
                METHOD_USSD.equals(method)
                        ? "Please dial the USSD code on your phone to complete payment."
                        : "Please complete the payment on the checkout page."));

        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("reference", reference);
        response.put("method",    method);
        response.put("message",   message);

        if (METHOD_USSD.equals(method)) {
            response.put("ussdCode", data.get("ussd_code"));
            response.put("bankName", data.get("bank_name"));
        } else {
            response.put("checkoutUrl", data.get("checkout_url"));
        }

        return response;
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
     * secret key, hex-encoded. Identical scheme to the MoMo webhook.
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
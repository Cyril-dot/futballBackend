package com.speedbet.api.payment.moolre;

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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * MoolreController — LOCAL MoMo payments only (MTN, Telecel, AT — Ghana GHS).
 *
 * ─── Three flows ────────────────────────────────────────────────────────────
 *
 *  1. USSD Push (Initiate Payment)
 *     POST /api/wallet/deposit/moolre/init
 *     • Requires the user's MoMo phone number + channel + amount.
 *     • Moolre sends a USSD prompt to the customer's phone.
 *     • Two-step: first call returns OTP_REQ → frontend collects OTP from user
 *       and calls the same endpoint again with otpcode in the body.
 *     • On OTP approval Moolre fires our webhook → wallet is credited.
 *
 *  2. Payment Verification (manual status check)
 *     POST /api/wallet/deposit/moolre/verify
 *     • Accepts the externalref the frontend stored during init.
 *     • Calls Moolre /open/transact/status.
 *     • If txstatus=1 (success) AND the ref hasn't been credited yet,
 *       credits the wallet immediately.
 *     • Idempotent — safe to call multiple times; duplicate refs are ignored.
 *
 *  3. Admin Upgrade
 *     POST /api/user/upgrade-to-admin/moolre/init
 *     • Same USSD push flow but promotes user to ADMIN on success.
 *     • Same verify endpoint works — externalref prefix drives intent.
 *
 *  4. Webhook  (primary / automatic path)
 *     POST /api/webhooks/moolre
 *     • Moolre calls this automatically after a successful payment.
 *     • Verified via the `secret` field in the payload body.
 *     • Verification endpoint above is the fallback for missed webhooks.
 *
 * ─── externalref convention ──────────────────────────────────────────────────
 *   "deposit_<userId>_<uuid>"       → credit wallet
 *   "adminupgrade_<userId>_<uuid>"  → promote user to ADMIN
 *
 * ─── Moolre channels ─────────────────────────────────────────────────────────
 *   13 = MTN MoMo
 *    6 = Telecel Cash
 *    7 = AT Money
 *
 * ─── application.yml keys needed ─────────────────────────────────────────────
 *   app.moolre.api-user
 *   app.moolre.public-key
 *   app.moolre.account-number
 *   app.moolre.webhook-secret
 *   app.moolre.base-url          (default: https://api.moolre.com)
 *   app.platform.min-deposit-amount  (default: 1)
 *   app.platform.frontend-url
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MoolreController {

    private static final BigDecimal ADMIN_UPGRADE_FEE    = BigDecimal.valueOf(200);
    private static final String     UPGRADE_INTENT_ADMIN = "adminupgrade";
    private static final String     DEPOSIT_INTENT       = "deposit";

    // Moolre txstatus codes
    private static final int TX_SUCCESS = 1;
    private static final int TX_PENDING = 0;
    private static final int TX_FAILED  = 2;

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    @Value("${app.moolre.api-user}")                         private String     apiUser;
    @Value("${app.moolre.public-key}")                       private String     publicKey;
    @Value("${app.moolre.account-number}")                   private String     accountNumber;
    @Value("${app.moolre.webhook-secret}")                   private String     webhookSecret;
    @Value("${app.moolre.base-url:https://api.moolre.com}")  private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:1}")           private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")                   private String     frontendUrl;

    // ─── 1. USSD Push — Deposit Init ─────────────────────────────────────────

    /**
     * Sends a USSD payment prompt to the customer's MoMo phone.
     *
     * Required body fields:
     *   amount   – GHS amount (e.g. "50")
     *   phone    – customer's MoMo number (e.g. "0244123456" or "233244123456")
     *   channel  – "13" (MTN), "6" (Telecel), "7" (AT)
     *
     * Optional body fields:
     *   otpcode  – OTP entered by the user (only on the second call after
     *              Moolre returns code "OTP_REQ")
     *
     * Response shapes from Moolre:
     *
     *   Step 1 — OTP sent to customer phone:
     *     { "status": "1", "code": "OTP_REQ", "message": "OTP sent..." }
     *     → Frontend should prompt user to enter OTP, then call this endpoint
     *       again with the same body + otpcode field.
     *
     *   Step 2 — OTP verified, USSD prompt sent:
     *     { "status": "1", "code": "PAYMENT_REQ", "message": "Payment requested" }
     *     → Customer approves on their phone. Moolre fires webhook on completion.
     *       Frontend should poll /verify endpoint or wait for a push notification.
     *
     * The externalref is returned in the response data so the frontend can
     * store it and use it to call the /verify endpoint.
     */
    @PostMapping("/api/wallet/deposit/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount  = new BigDecimal(req.get("amount").toString());
        var phone   = req.get("phone").toString().trim();
        var channel = req.get("channel").toString().trim();
        var otpCode = req.containsKey("otpcode") ? req.get("otpcode").toString().trim() : null;

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        if (phone.isBlank())
            throw ApiException.badRequest("Phone number is required.");

        if (!channel.equals("13") && !channel.equals("6") && !channel.equals("7"))
            throw ApiException.badRequest("Invalid channel. Use 13 (MTN), 6 (Telecel), or 7 (AT).");

        // Build a stable externalref tied to the user so we can route the webhook
        // and verify calls back to the right user without any session state.
        // Format: deposit_<userId>_<uuid>
        var externalRef = DEPOSIT_INTENT + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initDeposit: userId='{}' amount={} channel={} externalRef='{}'",
                user.getId(), amount, channel, externalRef);

        var response = moolreInitiatePayment(
                phone,
                channel,
                amount,
                externalRef,
                otpCode,
                "Deposit to SpeedBet wallet"
        );

        // Attach the externalRef to the response so the frontend can store it
        // and use it when calling /verify.
        @SuppressWarnings("unchecked")
        var mutableResponse = new java.util.LinkedHashMap<>(response);
        mutableResponse.put("externalref", externalRef);

        log.info("initDeposit: Moolre code='{}' message='{}' for userId='{}'",
                response.get("code"), response.get("message"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(mutableResponse));
    }

    // ─── 2. Admin Upgrade Init ────────────────────────────────────────────────

    /**
     * Sends a USSD GHS 200 upgrade-fee prompt to the user's MoMo phone.
     *
     * Required body fields: phone, channel  (same as deposit init)
     * Optional body fields: otpcode
     */
    @PostMapping("/api/user/upgrade-to-admin/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var phone   = req.get("phone").toString().trim();
        var channel = req.get("channel").toString().trim();
        var otpCode = req.containsKey("otpcode") ? req.get("otpcode").toString().trim() : null;

        if (phone.isBlank())
            throw ApiException.badRequest("Phone number is required.");

        // Format: adminupgrade_<userId>_<uuid>
        var externalRef = UPGRADE_INTENT_ADMIN + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initAdminUpgrade: userId='{}' phone='{}' externalRef='{}'",
                user.getId(), phone, externalRef);

        var response = moolreInitiatePayment(
                phone,
                channel,
                ADMIN_UPGRADE_FEE,
                externalRef,
                otpCode,
                "SpeedBet Admin Upgrade — GHS 200"
        );

        @SuppressWarnings("unchecked")
        var mutableResponse = new java.util.LinkedHashMap<>(response);
        mutableResponse.put("externalref", externalRef);

        return ResponseEntity.ok(ApiResponse.ok(mutableResponse));
    }

    // ─── 3. Payment Verification ──────────────────────────────────────────────

    /**
     * Manually verifies a Moolre payment by its externalref and, if successful,
     * credits the wallet immediately (idempotent).
     *
     * Use this as a fallback when:
     *   • The webhook was delayed or missed.
     *   • The user returns to the app after completing MoMo approval and wants
     *     their balance updated right away.
     *
     * Required body fields:
     *   externalref – the reference returned by /init (stored by the frontend)
     *
     * Response:
     *   { credited: true/false, txstatus: 1|0|2, message: "..." }
     */
    @PostMapping("/api/wallet/deposit/moolre/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPayment(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var externalRef = req.get("externalref");
        if (externalRef == null || externalRef.toString().isBlank())
            throw ApiException.badRequest("externalref is required.");

        var ref = externalRef.toString().trim();

        // Security: ensure the externalref belongs to this authenticated user
        // Format is always "<intent>_<userId>_<uuid>"
        var parts = ref.split("_", 3);
        if (parts.length < 3)
            throw ApiException.badRequest("Invalid externalref format.");

        UUID refUserId;
        try {
            refUserId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid externalref format.");
        }

        if (!refUserId.equals(user.getId()))
            throw ApiException.forbidden("This payment reference does not belong to your account.");

        log.info("verifyPayment: userId='{}' externalRef='{}'", user.getId(), ref);

        // Call Moolre status API
        var statusResponse = moolreCheckStatus(ref);

        @SuppressWarnings("unchecked")
        var data      = (Map<String, Object>) statusResponse.get("data");
        var txStatus  = data != null
                ? Integer.parseInt(data.getOrDefault("txstatus", "-1").toString())
                : -1;

        if (txStatus == TX_PENDING) {
            log.info("verifyPayment: still pending externalRef='{}'", ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited",  false,
                    "txstatus",  TX_PENDING,
                    "message",   "Payment is still pending. Please approve the USSD prompt on your phone."
            )));
        }

        if (txStatus == TX_FAILED) {
            log.warn("verifyPayment: payment failed externalRef='{}'", ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited",  false,
                    "txstatus",  TX_FAILED,
                    "message",   "Payment failed or was cancelled."
            )));
        }

        if (txStatus != TX_SUCCESS) {
            log.warn("verifyPayment: unknown txstatus={} externalRef='{}'", txStatus, ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited",  false,
                    "txstatus",  txStatus,
                    "message",   "Unexpected payment status. Please contact support."
            )));
        }

        // txstatus = 1 (success) — credit the wallet
        var valueStr = data.getOrDefault("value", data.get("amount")).toString();
        var amount   = new BigDecimal(valueStr);
        var intent   = parts[0]; // "deposit" or "adminupgrade"

        boolean credited = false;

        if (UPGRADE_INTENT_ADMIN.equals(intent)) {
            credited = verifyAndHandleAdminUpgrade(user.getId(), ref, amount);
        } else {
            credited = verifyAndHandleDeposit(user.getId(), ref, amount);
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "credited",  credited,
                "txstatus",  TX_SUCCESS,
                "message",   credited
                        ? "Payment verified. GHS " + amount + " has been added to your wallet."
                        : "Payment was already processed."
        )));
    }

    // ─── 4. Webhook ───────────────────────────────────────────────────────────

    /**
     * Receives Moolre payment callbacks automatically after each successful payment.
     *
     * Payload shape:
     * {
     *   "status": 1,
     *   "code":   "P01",
     *   "data": {
     *     "txstatus":      1,
     *     "payer":         "233244123456",
     *     "accountnumber": "...",
     *     "amount":        "50.00",
     *     "value":         "50.00",
     *     "transactionid": "32712684",
     *     "externalref":   "deposit_uuid_uuid",
     *     "secret":        "<webhook-secret-from-dashboard>",
     *     "ts":            "2024-11-27 21:11:29"
     *   }
     * }
     *
     * Verification: compare data.secret with app.moolre.webhook-secret using
     * constant-time comparison (MessageDigest.isEqual) to prevent timing attacks.
     */
    @PostMapping("/api/webhooks/moolre")
    public ResponseEntity<String> webhook(HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Moolre webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            if (data == null) {
                log.warn("Moolre webhook: missing data field");
                return ResponseEntity.status(400).body("Missing data");
            }

            // ── Verify webhook secret ────────────────────────────────────────
            var secret = data.getOrDefault("secret", "").toString();
            if (!verifyWebhookSecret(secret)) {
                log.warn("Moolre webhook: invalid secret received");
                return ResponseEntity.status(400).body("Invalid secret");
            }

            // ── Only process successful transactions ─────────────────────────
            var txStatus = Integer.parseInt(data.getOrDefault("txstatus", "-1").toString());
            if (txStatus != TX_SUCCESS) {
                log.info("Moolre webhook: ignoring txstatus={} externalref='{}'",
                        txStatus, data.get("externalref"));
                return ResponseEntity.ok("Ignored");
            }

            // ── Extract required fields ──────────────────────────────────────
            var externalRef = data.get("externalref");
            if (externalRef == null || externalRef.toString().isBlank()) {
                log.error("Moolre webhook: missing externalref in data");
                return ResponseEntity.status(400).body("Missing externalref");
            }

            var ref      = externalRef.toString();
            var valueStr = data.getOrDefault("value", data.get("amount")).toString();
            var amount   = new BigDecimal(valueStr);

            // ── Route by intent encoded in externalref prefix ────────────────
            var parts = ref.split("_", 3);
            if (parts.length < 3) {
                log.error("Moolre webhook: unexpected externalref format ref='{}'", ref);
                return ResponseEntity.status(400).body("Unexpected externalref format");
            }

            var intent = parts[0];
            UUID userId;
            try {
                userId = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                log.error("Moolre webhook: cannot parse userId from ref='{}'", ref);
                return ResponseEntity.status(400).body("Invalid userId in externalref");
            }

            if (UPGRADE_INTENT_ADMIN.equals(intent)) {
                handleAdminUpgrade(userId, ref, amount);
            } else {
                handleDeposit(userId, ref, amount);
            }

        } catch (ApiException e) {
            log.error("Moolre webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Moolre webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Private — wallet handlers ────────────────────────────────────────────

    /**
     * Credits the user's wallet on a successful deposit.
     * Returns true if credited now, false if already credited (duplicate ref).
     */
    private boolean handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "moolre", "reference", ref));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'",
                    amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return false;
            }
            throw ex;
        }

        // ── Attribute referral commission ──────────────────────────────────
        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit='{}'",
                    userId, amount);
        } catch (Exception ex) {
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate",
                    userId, ex);
        }

        return true;
    }

    /**
     * Same as handleDeposit but called from the /verify endpoint.
     * Separated for clarity — behaviour is identical.
     */
    private boolean verifyAndHandleDeposit(UUID userId, String ref, BigDecimal amount) {
        return handleDeposit(userId, ref, amount);
    }

    /**
     * Promotes the user to ADMIN after a successful GHS 200 upgrade payment.
     * Returns true if promoted now, false if already processed (duplicate ref).
     *
     * Steps:
     *   1. Validate amount >= GHS 200
     *   2. userService.upgradeToAdmin  (409 = already done → idempotent skip)
     *   3. walletService.recordExternalDebit — audit record only (Moolre collected funds)
     *   4. adminUpgradeChatService.createUpgradeChat — onboarding with Super Admin
     */
    private boolean handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(ADMIN_UPGRADE_FEE) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'",
                    amount, userId, ref);
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
                Map.of("provider", "moolre", "reference", ref));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);

        return true;
    }

    /**
     * Same as handleAdminUpgrade but called from the /verify endpoint.
     */
    private boolean verifyAndHandleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        return handleAdminUpgrade(userId, ref, amount);
    }

    // ─── Moolre API helpers ───────────────────────────────────────────────────

    /**
     * Calls Moolre POST /open/transact/payment (Initiate Payment — USSD push).
     *
     * This is a two-step flow:
     *   Call 1 (no otpcode): Moolre sends OTP to customer's phone.
     *                         Returns code="OTP_REQ".
     *   Call 2 (with otpcode): Moolre verifies OTP and sends USSD approval
     *                          prompt to customer's phone.
     *                          Returns code="PAYMENT_REQ".
     *
     * The sessionid field (optional) can skip the OTP step if you have a
     * USSD session ID — not used here.
     *
     * Moolre channel codes:
     *   13 = MTN MoMo
     *    6 = Telecel Cash
     *    7 = AT Money
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreInitiatePayment(String phone,
                                                      String channel,
                                                      BigDecimal amount,
                                                      String externalRef,
                                                      String otpCode,
                                                      String reference) {

        var bodyBuilder = new java.util.LinkedHashMap<String, Object>();
        bodyBuilder.put("type",          1);
        bodyBuilder.put("channel",       channel);
        bodyBuilder.put("currency",      "GHS");
        bodyBuilder.put("payer",         phone);
        bodyBuilder.put("amount",        amount.toPlainString());
        bodyBuilder.put("externalref",   externalRef);
        bodyBuilder.put("reference",     reference);
        bodyBuilder.put("accountnumber", accountNumber);
        // Only include otpcode when the frontend is submitting the OTP (Step 2)
        if (otpCode != null && !otpCode.isBlank()) {
            bodyBuilder.put("otpcode", otpCode);
        }

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/open/transact/payment")
                .header("X-API-USER",    apiUser)
                .header("X-API-PUBKEY",  publicKey)
                .header("Content-Type",  "application/json")
                .bodyValue(bodyBuilder)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("Moolre initiatePayment error: status={} body={}",
                                            clientResponse.statusCode(), body);
                                    return new RuntimeException(
                                            "Moolre returned " + clientResponse.statusCode()
                                                    + ": " + body);
                                })
                )
                .bodyToMono(java.util.Map.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("Moolre API unreachable", ex);
                            return new RuntimeException(
                                    "Moolre is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null)
            throw new RuntimeException("Moolre returned an empty response.");

        log.info("moolreInitiatePayment: status='{}' code='{}' message='{}'",
                result.get("status"), result.get("code"), result.get("message"));

        // Moolre uses status "0" (string) for errors in this endpoint
        var status = String.valueOf(result.get("status"));
        if ("0".equals(status)) {
            var message = result.getOrDefault("message", "Moolre declined the request").toString();
            log.error("moolreInitiatePayment: Moolre status=0 — {}", message);
            throw new RuntimeException("Moolre error: " + message);
        }

        return result;
    }

    /**
     * Calls Moolre POST /open/transact/status to check payment status by externalref.
     *
     * Returns the full Moolre response map including the nested `data` object:
     * {
     *   "status": 1,
     *   "data": {
     *     "txstatus": 1,     ← 1=success, 0=pending, 2=failed
     *     "amount":   "50",
     *     "value":    "50",  ← post-fee actual credited amount
     *     "externalref": "deposit_uuid_uuid",
     *     ...
     *   }
     * }
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreCheckStatus(String externalRef) {

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/open/transact/status")
                .header("X-API-USER",   apiUser)
                .header("X-API-PUBKEY", publicKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "type",          1,
                        "idtype",        "1",      // 1 = lookup by externalref
                        "id",            externalRef,
                        "accountnumber", accountNumber
                ))
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("Moolre checkStatus error: status={} body={}",
                                            clientResponse.statusCode(), body);
                                    return new RuntimeException(
                                            "Moolre returned " + clientResponse.statusCode()
                                                    + ": " + body);
                                })
                )
                .bodyToMono(java.util.Map.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("Moolre API unreachable during status check", ex);
                            return new RuntimeException(
                                    "Moolre is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null)
            throw new RuntimeException("Moolre returned an empty status response.");

        log.info("moolreCheckStatus: status='{}' message='{}' for externalRef='{}'",
                result.get("status"), result.get("message"), externalRef);

        return result;
    }

    // ─── Webhook secret verification ──────────────────────────────────────────

    /**
     * Moolre does not use HMAC — it sends a plain secret string inside the
     * payload body that matches the one configured on your Moolre dashboard.
     * We use MessageDigest.isEqual for constant-time comparison.
     */
    private boolean verifyWebhookSecret(String incomingSecret) {
        if (incomingSecret == null || incomingSecret.isBlank()) {
            log.warn("Moolre webhook: secret field is missing or blank");
            return false;
        }
        return java.security.MessageDigest.isEqual(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                incomingSecret.getBytes(StandardCharsets.UTF_8)
        );
    }
}
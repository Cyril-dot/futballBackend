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
 * MoolreController — GHS MoMo payments via Moolre USSD Direct Charge.
 *
 * ─── Payment flows ───────────────────────────────────────────────────────────
 *
 *  1. Initiate USSD Charge — Deposit
 *     POST /api/wallet/deposit/moolre/init
 *     • Accepts { amount, phone, network } from the frontend.
 *     • Calls Moolre POST /open/transact/payment to push a USSD prompt directly
 *       to the customer's MoMo number — no hosted page, no redirect.
 *     • Returns { externalref, message } to the frontend.
 *     • Customer approves the USSD prompt on their phone.
 *     • Moolre fires our webhook → wallet is credited automatically.
 *
 *  2. Initiate USSD Charge — Admin Upgrade
 *     POST /api/user/upgrade-to-admin/moolre/init
 *     • Same USSD direct flow but amount is fixed at GHS 200 and promotes
 *       the user to ADMIN on successful payment.
 *
 *  3. Payment Verification (manual fallback / polling)
 *     POST /api/wallet/deposit/moolre/verify
 *     • Accepts the externalref stored by the frontend after /init.
 *     • Calls Moolre /open/transact/status.
 *     • Credits wallet immediately if txstatus=1 and not already credited.
 *     • Idempotent — safe to poll; duplicate refs are silently ignored.
 *
 *  4. Webhook (primary / automatic credit path)
 *     POST /api/webhooks/moolre
 *     • Moolre POSTs here after every successful payment.
 *     • Verified by matching the `secret` field in the payload.
 *     • /verify above is the fallback for missed or delayed webhooks.
 *
 * ─── network values accepted by frontend → Moolre channel codes ──────────────
 *   "MTN"        → channel "13"  (MTN MoMo)
 *   "VODAFONE"   → channel "6"   (Telecel, formerly Vodafone Cash)
 *   "AIRTELTIGO" → channel "7"   (AirtelTigo Money)
 *
 * ─── externalref convention ──────────────────────────────────────────────────
 *   "deposit_<userId>_<uuid>"       → credit wallet
 *   "adminupgrade_<userId>_<uuid>"  → promote user to ADMIN
 *
 * ─── Moolre txstatus codes ───────────────────────────────────────────────────
 *   0 = pending
 *   1 = success
 *   2 = failed / cancelled
 *
 * ─── Moolre API base URL (hardcoded) ─────────────────────────────────────────
 *   https://api.moolre.com
 *
 * ─── application.properties keys needed ──────────────────────────────────────
 *   app.moolre.api-user          → env: MOOLRE_API_USER
 *   app.moolre.public-key        → env: MOOLRE_PUBLIC_KEY
 *   app.moolre.account-number    → env: MOOLRE_ACCOUNT_NUMBER
 *   app.moolre.webhook-secret    → env: MOOLRE_WEBHOOK_SECRET
 *   app.platform.min-deposit-amount (default: 1)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MoolreController {

    // ─── Hardcoded Moolre base URL ────────────────────────────────────────────
    private static final String MOOLRE_BASE_URL = "https://api.moolre.com";

    private static final BigDecimal ADMIN_UPGRADE_FEE    = BigDecimal.valueOf(200);
    private static final String     UPGRADE_INTENT_ADMIN = "adminupgrade";
    private static final String     DEPOSIT_INTENT       = "deposit";

    // Moolre txstatus codes
    private static final int TX_SUCCESS = 1;
    private static final int TX_PENDING = 0;
    private static final int TX_FAILED  = 2;

    // Moolre channel codes (from official docs)
    private static final String CHANNEL_MTN        = "13";
    private static final String CHANNEL_VODAFONE   = "6";
    private static final String CHANNEL_AIRTELTIGO = "7";

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    @Value("${app.moolre.api-user}")               private String     apiUser;
    @Value("${app.moolre.public-key}")             private String     publicKey;
    @Value("${app.moolre.account-number}")         private String     accountNumber;
    @Value("${app.moolre.webhook-secret}")         private String     webhookSecret;
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ─── 1. Initiate USSD Charge — Deposit ───────────────────────────────────

    /**
     * Initiates a Moolre USSD direct charge for a wallet deposit.
     *
     * Required body fields:
     *   amount  – GHS amount to deposit (e.g. "300")
     *   phone   – customer's MoMo number (e.g. "0244123456" or "233244123456")
     *   network – "MTN", "VODAFONE", or "AIRTELTIGO"
     *
     * Response:
     *   { "externalref": "deposit_<userId>_<uuid>", "message": "..." }
     */
    @PostMapping("/api/wallet/deposit/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount  = new BigDecimal(req.get("amount").toString());
        var phone   = req.get("phone");
        var network = req.get("network");

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        if (phone == null || phone.toString().isBlank())
            throw ApiException.badRequest("phone is required.");
        if (network == null || network.toString().isBlank())
            throw ApiException.badRequest("network is required (MTN, VODAFONE, AIRTELTIGO).");

        var externalRef = DEPOSIT_INTENT + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initDeposit (USSD): userId='{}' amount={} phone='{}' network='{}' externalRef='{}'",
                user.getId(), amount, phone, network, externalRef);

        Map<String, Object> chargeResult;
        try {
            chargeResult = moolreDirectCharge(amount, phone.toString(), network.toString(), externalRef);
        } catch (RuntimeException ex) {
            log.error("initDeposit: Moolre charge failed for userId='{}' externalRef='{}' — {}",
                    user.getId(), externalRef, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Payment initiation failed. Please try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref", externalRef,
                "message", chargeResult.getOrDefault("message",
                        "Please approve the USSD prompt on your phone.").toString()
        )));
    }

    // ─── 2. Initiate USSD Charge — Admin Upgrade ──────────────────────────────

    /**
     * Initiates a Moolre USSD direct charge for the GHS 200 admin upgrade fee.
     *
     * Required body fields:
     *   phone   – customer's MoMo number
     *   network – "MTN", "VODAFONE", or "AIRTELTIGO"
     *
     * Response:
     *   { "externalref": "adminupgrade_<userId>_<uuid>", "message": "..." }
     */
    @PostMapping("/api/user/upgrade-to-admin/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var phone   = req.get("phone");
        var network = req.get("network");

        if (phone == null || phone.toString().isBlank())
            throw ApiException.badRequest("phone is required.");
        if (network == null || network.toString().isBlank())
            throw ApiException.badRequest("network is required (MTN, VODAFONE, AIRTELTIGO).");

        var externalRef = UPGRADE_INTENT_ADMIN + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initAdminUpgrade (USSD): userId='{}' phone='{}' network='{}' externalRef='{}'",
                user.getId(), phone, network, externalRef);

        Map<String, Object> chargeResult;
        try {
            chargeResult = moolreDirectCharge(ADMIN_UPGRADE_FEE, phone.toString(), network.toString(), externalRef);
        } catch (RuntimeException ex) {
            log.error("initAdminUpgrade: Moolre charge failed for userId='{}' externalRef='{}' — {}",
                    user.getId(), externalRef, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Upgrade payment initiation failed. Please try again.");
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref", externalRef,
                "message", chargeResult.getOrDefault("message",
                        "Please approve the USSD prompt on your phone.").toString()
        )));
    }

    // ─── 3. Payment Verification ──────────────────────────────────────────────

    /**
     * Manually verifies a Moolre payment by its externalref and credits the
     * wallet if successful. Idempotent — safe to poll.
     *
     * Required body fields:
     *   externalref – the reference returned by /init
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

        var ref   = externalRef.toString().trim();
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

        var statusResponse = moolreCheckStatus(ref);

        @SuppressWarnings("unchecked")
        var data     = (Map<String, Object>) statusResponse.get("data");
        var txStatus = data != null
                ? Integer.parseInt(data.getOrDefault("txstatus", "-1").toString())
                : -1;

        if (txStatus == TX_PENDING) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_PENDING,
                    "message",  "Payment is still pending. Please approve the USSD prompt on your phone."
            )));
        }

        if (txStatus == TX_FAILED) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_FAILED,
                    "message",  "Payment failed or was cancelled."
            )));
        }

        if (txStatus != TX_SUCCESS) {
            log.warn("verifyPayment: unknown txstatus={} externalRef='{}'", txStatus, ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", txStatus,
                    "message",  "Unexpected payment status. Please contact support."
            )));
        }

        // txstatus = 1 (success) — credit the wallet
        var valueStr = resolveAmount(data, ref);
        var amount   = new BigDecimal(valueStr);
        var intent   = parts[0];

        boolean credited = UPGRADE_INTENT_ADMIN.equals(intent)
                ? verifyAndHandleAdminUpgrade(user.getId(), ref, amount)
                : verifyAndHandleDeposit(user.getId(), ref, amount);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "credited", credited,
                "txstatus", TX_SUCCESS,
                "message",  credited
                        ? "Payment verified. GHS " + amount + " has been added to your wallet."
                        : "Payment was already processed."
        )));
    }

    // ─── 4. Webhook ───────────────────────────────────────────────────────────

    /**
     * Receives Moolre payment callbacks automatically after each successful payment.
     *
     * Payload shape (from Moolre docs):
     * {
     *   "status": 1,
     *   "code":   "P01",
     *   "message": "Transaction Successful",
     *   "data": {
     *     "txstatus":      1,
     *     "payer":         "233244123456",
     *     "accountnumber": "...",
     *     "amount":        "50.00",
     *     "value":         "50.00",
     *     "transactionid": "32712684",
     *     "externalref":   "deposit_<userId>_<uuid>",
     *     "secret":        "<webhook-secret>",
     *     "ts":            "2024-11-27 21:11:29"
     *   }
     * }
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

            // ── Belt-and-suspenders: verify accountnumber matches ours ───────
            var incomingAccount = data.getOrDefault("accountnumber", "").toString();
            if (!accountNumber.equals(incomingAccount)) {
                log.warn("Moolre webhook: accountnumber mismatch — incoming='{}' expected='{}'",
                        incomingAccount, accountNumber);
                return ResponseEntity.status(400).body("Account mismatch");
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
            var valueStr = resolveAmount(data, ref);
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

    private boolean handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "moolre", "reference", ref));
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
                Map.of("provider", "moolre", "reference", ref));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);

        return true;
    }

    private boolean verifyAndHandleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        return handleAdminUpgrade(userId, ref, amount);
    }

    // ─── Moolre API helpers ───────────────────────────────────────────────────

    /**
     * Calls Moolre POST /open/transact/payment to initiate a USSD direct charge.
     *
     * Moolre docs: https://docs.moolre.com/#/initiate-payment
     *
     * Key request fields (per official docs):
     *   type          = 1            (required by Moolre)
     *   channel       = "13" | "6" | "7"  (MTN | Telecel | AirtelTigo)
     *   currency      = "GHS"
     *   payer         = MoMo number of the customer
     *   amount        = amount to charge
     *   externalref   = unique ID per transaction
     *   accountnumber = your Moolre account number
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreDirectCharge(
            BigDecimal amount, String phone, String network, String externalRef) {

        // Map frontend network name → Moolre channel code (per official docs)
        String channel = switch (network.toUpperCase()) {
            case "MTN"        -> CHANNEL_MTN;
            case "VODAFONE"   -> CHANNEL_VODAFONE;
            case "AIRTELTIGO" -> CHANNEL_AIRTELTIGO;
            default -> throw new RuntimeException("Unsupported network: " + network
                    + ". Must be MTN, VODAFONE, or AIRTELTIGO.");
        };

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type",          1);
        body.put("channel",       channel);
        body.put("currency",      "GHS");
        body.put("payer",         phone);
        body.put("amount",        amount.toPlainString());
        body.put("externalref",   externalRef);
        body.put("accountnumber", accountNumber);

        log.info("moolreDirectCharge: calling /open/transact/payment — channel='{}' phone='{}' amount='{}' externalRef='{}'",
                channel, phone, amount, externalRef);

        String rawBody = webClientBuilder.build()
                .post().uri(MOOLRE_BASE_URL + "/open/transact/payment")
                .header("X-API-USER",   apiUser)
                .header("X-API-PUBKEY", publicKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("Moolre directCharge HTTP error: status={} body={}",
                                            clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "Moolre returned HTTP " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(String.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException),
                        ex -> {
                            log.error("Moolre API unreachable during directCharge", ex);
                            return new RuntimeException("Moolre is currently unavailable. Please try again.");
                        }
                )
                .onErrorMap(
                        ex -> ex instanceof RuntimeException && ex.getMessage() == null,
                        ex -> {
                            log.error("Moolre directCharge: RuntimeException with null message", ex);
                            return new RuntimeException("Moolre is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (rawBody == null || rawBody.isBlank())
            throw new RuntimeException("Moolre returned an empty response.");

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.error("Moolre directCharge: non-JSON response body='{}'", rawBody);
            throw new RuntimeException("Moolre returned an unexpected response. Please try again.");
        }

        var status  = String.valueOf(result.get("status"));
        var message = String.valueOf(result.getOrDefault("message", ""));

        log.info("moolreDirectCharge: status='{}' message='{}' externalRef='{}'",
                status, message, externalRef);

        if (!"1".equals(status)) {
            log.error("moolreDirectCharge: Moolre error status='{}' message='{}'", status, message);
            throw new RuntimeException("Moolre error: " + message);
        }

        var data = (Map<String, Object>) result.getOrDefault("data", Map.of());

        // Inject top-level message into data map for easy retrieval by caller
        if (!message.isBlank()) {
            var mutable = new java.util.LinkedHashMap<>(data);
            mutable.put("message", message);
            return mutable;
        }

        return data;
    }

    /**
     * Calls Moolre POST /open/transact/status to check payment status by externalref.
     *
     * Moolre docs: https://docs.moolre.com/#/payment-status
     *
     * Returns the full Moolre response map including the nested `data` object:
     * {
     *   "status": 1,
     *   "data": {
     *     "txstatus": 1,     ← 1=success, 0=pending, 2=failed
     *     "amount":   "50",
     *     "value":    "50",
     *     "externalref": "deposit_<userId>_<uuid>",
     *     ...
     *   }
     * }
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreCheckStatus(String externalRef) {

        String rawBody = webClientBuilder.build()
                .post().uri(MOOLRE_BASE_URL + "/open/transact/status")
                .header("X-API-USER",   apiUser)
                .header("X-API-PUBKEY", publicKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "type",          1,
                        "idtype",        "1",
                        "id",            externalRef,
                        "accountnumber", accountNumber
                ))
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("Moolre checkStatus HTTP error: status={} body={}",
                                            clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "Moolre returned HTTP " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(String.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException),
                        ex -> {
                            log.error("Moolre API unreachable during status check", ex);
                            return new RuntimeException("Moolre is currently unavailable. Please try again.");
                        }
                )
                .onErrorMap(
                        ex -> ex instanceof RuntimeException && ex.getMessage() == null,
                        ex -> {
                            log.error("Moolre checkStatus: RuntimeException with null message", ex);
                            return new RuntimeException("Moolre is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (rawBody == null || rawBody.isBlank())
            throw new RuntimeException("Moolre returned an empty status response.");

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.error("Moolre checkStatus: non-JSON response body='{}'", rawBody);
            throw new RuntimeException("Moolre returned an unexpected status response. Please try again.");
        }

        log.info("moolreCheckStatus: status='{}' message='{}' for externalRef='{}'",
                result.get("status"), result.get("message"), externalRef);

        return result;
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────

    /**
     * Safely resolves the transaction amount from a Moolre data map.
     * Priority: "value" → "amount" → throws ApiException if neither present.
     */
    private static String resolveAmount(Map<String, Object> data, String ref) {
        var value = data.get("value");
        if (value != null && !value.toString().isBlank()) return value.toString();

        var amount = data.get("amount");
        if (amount != null && !amount.toString().isBlank()) return amount.toString();

        throw ApiException.badRequest(
                "Moolre response is missing both 'value' and 'amount' fields for ref='" + ref + "'");
    }

    // ─── Webhook secret verification ──────────────────────────────────────────

    /**
     * Moolre sends a plain secret string inside the payload body that matches
     * the one configured on your Moolre dashboard.
     * Uses MessageDigest.isEqual for constant-time comparison.
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
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MoolreController — GHS MoMo payments via Moolre Payment Link (Web POS).
 *
 * ─── Payment flows ───────────────────────────────────────────────────────────
 *
 *  1. Generate Payment Link — Deposit
 *     POST /api/wallet/deposit/moolre/init
 *     • Accepts { amount, email } from the frontend.
 *     • Calls Moolre POST /embed/link (type=1, reusable=0) to get a hosted
 *       Web POS checkout URL (authorization_url).
 *     • Returns { externalref, moolreRef, checkoutUrl } to the frontend.
 *     • Frontend redirects the user to checkoutUrl — the customer selects
 *       their MoMo network and enters their number on Moolre's hosted page.
 *     • Moolre fires our webhook on completion → wallet is credited automatically.
 *     • Frontend may also poll /verify for status while waiting.
 *
 *  2. Generate Payment Link — Admin Upgrade
 *     POST /api/user/upgrade-to-admin/moolre/init
 *     • Same flow, amount fixed at GHS 200, promotes user to ADMIN on success.
 *     • New admin commission rate initialised at 70% inside UserService.upgradeToAdmin().
 *
 *  3. Payment Verification (manual / polling fallback)
 *     POST /api/wallet/deposit/moolre/verify
 *     • Accepts { externalref } (and optionally moolreRef) from frontend.
 *     • Queries Moolre POST /open/transact/status:
 *         idtype=1 → query by externalref
 *         idtype=2 → query by Moolre's generated reference UUID
 *     • "Transaction not found" is mapped to PENDING so the frontend keeps polling.
 *     • Credits wallet if txstatus=1 and not already credited. Idempotent.
 *
 *  4. Webhook (primary automatic credit path)
 *     POST /api/webhooks/moolre
 *     • Moolre POSTs here after every successful payment.
 *     • Verified by matching the `secret` field in the payload.
 *
 *  5. Automatic Verification Scheduler
 *     • Runs every 30 seconds.
 *     • Polls Moolre status for every pending session older than 30 seconds.
 *     • Removes sessions older than 30 minutes (TTL expired).
 *     • Ensures wallet credit even when webhook is missed/delayed.
 *
 * ─── Moolre Payment Link API ─────────────────────────────────────────────────
 *   Generate Link:  POST /embed/link
 *     Required: type=1, amount, currency, accountnumber, email,
 *               externalref, reusable=0
 *     Optional: callback (webhook URL), redirect (post-payment redirect)
 *     Returns:  data.authorization_url  — redirect the user here
 *               data.reference          — Moolre's internal reference UUID
 *
 *   Status Check:   POST /open/transact/status
 *     idtype=1 → query by our externalref
 *     idtype=2 → query by Moolre's generated reference UUID
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
 * ─── Moolre API base URLs ────────────────────────────────────────────────────
 *   Payment Link:  https://api.moolre.com/embed/link
 *   Status:        https://api.moolre.com/open/transact/status
 *
 * ─── application.properties keys needed ──────────────────────────────────────
 *   app.moolre.api-user              → env: MOOLRE_API_USER
 *   app.moolre.public-key            → env: MOOLRE_PUBLIC_KEY
 *   app.moolre.account-number        → env: MOOLRE_ACCOUNT_NUMBER
 *   app.moolre.webhook-secret        → env: MOOLRE_WEBHOOK_SECRET
 *   app.moolre.callback-url          → env: MOOLRE_CALLBACK_URL  (webhook URL)
 *   app.moolre.redirect-url          → env: MOOLRE_REDIRECT_URL  (post-payment frontend page)
 *   app.moolre.business-email        → env: MOOLRE_BUSINESS_EMAIL
 *   app.platform.min-deposit-amount  (default: 1)
 *
 * NOTE: Enable scheduling with @EnableScheduling on a @Configuration class.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MoolreController {

    // ─── Moolre API endpoints ─────────────────────────────────────────────────
    private static final String MOOLRE_BASE_URL       = "https://api.moolre.com";
    private static final String ENDPOINT_PAYMENT_LINK = "/embed/link";
    private static final String ENDPOINT_STATUS       = "/open/transact/status";

    private static final BigDecimal ADMIN_UPGRADE_FEE    = BigDecimal.valueOf(200);
    private static final String     UPGRADE_INTENT_ADMIN = "adminupgrade";
    private static final String     DEPOSIT_INTENT       = "deposit";

    /**
     * Default admin commission rate (70%). Stored on the Referral entity;
     * this constant is for logging/documentation only.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // Moolre txstatus codes
    private static final int TX_SUCCESS = 1;
    private static final int TX_PENDING = 0;
    private static final int TX_FAILED  = 2;

    /** 30 minutes TTL for pending checkout sessions. */
    private static final long PENDING_SESSION_TTL_MS = 30 * 60 * 1000L;

    /**
     * Pending checkout session cache — keyed by externalref.
     * Replace with Redis/DB in a multi-instance deployment.
     */
    private final ConcurrentHashMap<String, PendingCheckout> pendingCheckouts = new ConcurrentHashMap<>();

    /**
     * @param externalRef  "deposit_<userId>_<uuid>" or "adminupgrade_<userId>_<uuid>"
     * @param amount       GHS amount for the session.
     * @param userId       Initiating user's ID.
     * @param moolreRef    Moolre's internal reference UUID (data.reference from /embed/link).
     * @param checkoutUrl  Moolre Web POS URL (data.authorization_url from /embed/link).
     * @param createdAt    Epoch millis when this session was created (for TTL).
     */
    record PendingCheckout(
            String     externalRef,
            BigDecimal amount,
            UUID       userId,
            String     moolreRef,
            String     checkoutUrl,
            long       createdAt
    ) {}

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
    @Value("${app.moolre.callback-url}")           private String     callbackUrl;
    @Value("${app.moolre.redirect-url:}")          private String     redirectUrl;
    @Value("${app.moolre.business-email}")         private String     businessEmail;
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ─── 1. Generate Payment Link — Deposit ──────────────────────────────────

    /**
     * Generates a Moolre Web POS payment link for a wallet deposit.
     *
     * The user is redirected to Moolre's hosted checkout page where they
     * select their MoMo network and enter their number.
     *
     * Required body: { "amount": "300" }
     * Optional body: { "email": "user@example.com" }
     *
     * Response (HTTP 200):
     *   {
     *     "externalref": "deposit_<userId>_<uuid>",
     *     "moolreRef":   "<moolre-reference-uuid>",
     *     "checkoutUrl": "https://pos.moolre.com/...",
     *     "message":     "Redirect the user to checkoutUrl to complete payment."
     *   }
     */
    @PostMapping("/api/wallet/deposit/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        var email  = req.containsKey("email") && req.get("email") != null
                ? req.get("email").toString()
                : businessEmail;

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var externalRef = DEPOSIT_INTENT + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initDeposit: userId='{}' amount={} externalRef='{}'",
                user.getId(), amount, externalRef);

        Map<String, Object> linkResult = moolreGeneratePaymentLink(amount, externalRef, email);

        String checkoutUrl = linkResult.getOrDefault("checkoutUrl", "").toString();
        String moolreRef   = linkResult.getOrDefault("moolreRef",   "").toString();

        pendingCheckouts.put(externalRef, new PendingCheckout(
                externalRef, amount, user.getId(), moolreRef, checkoutUrl, System.currentTimeMillis()
        ));

        log.info("initDeposit: session cached — externalRef='{}' moolreRef='{}' url='{}'",
                externalRef, moolreRef, checkoutUrl);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref", externalRef,
                "moolreRef",   moolreRef,
                "checkoutUrl", checkoutUrl,
                "message",     "Redirect the user to the checkoutUrl to complete payment."
        )));
    }

    // ─── 2. Generate Payment Link — Admin Upgrade ─────────────────────────────

    /**
     * Generates a Moolre Web POS payment link for the GHS 200 admin upgrade fee.
     *
     * On successful payment:
     *   • User is promoted to ADMIN
     *   • Referral link created with 70% commission rate
     *   • Onboarding chat opened with Super Admin
     *
     * Optional body: { "email": "user@example.com" }
     *
     * Response (HTTP 200):
     *   {
     *     "externalref": "adminupgrade_<userId>_<uuid>",
     *     "moolreRef":   "<moolre-reference-uuid>",
     *     "checkoutUrl": "https://pos.moolre.com/...",
     *     "message":     "Redirect the user to checkoutUrl to complete the GHS 200 upgrade payment."
     *   }
     */
    @PostMapping("/api/user/upgrade-to-admin/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var email = req.containsKey("email") && req.get("email") != null
                ? req.get("email").toString()
                : businessEmail;

        var externalRef = UPGRADE_INTENT_ADMIN + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initAdminUpgrade: userId='{}' externalRef='{}'", user.getId(), externalRef);

        Map<String, Object> linkResult = moolreGeneratePaymentLink(ADMIN_UPGRADE_FEE, externalRef, email);

        String checkoutUrl = linkResult.getOrDefault("checkoutUrl", "").toString();
        String moolreRef   = linkResult.getOrDefault("moolreRef",   "").toString();

        pendingCheckouts.put(externalRef, new PendingCheckout(
                externalRef, ADMIN_UPGRADE_FEE, user.getId(), moolreRef, checkoutUrl, System.currentTimeMillis()
        ));

        log.info("initAdminUpgrade: session cached — externalRef='{}' moolreRef='{}'",
                externalRef, moolreRef);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref", externalRef,
                "moolreRef",   moolreRef,
                "checkoutUrl", checkoutUrl,
                "message",     "Redirect the user to the checkoutUrl to complete the GHS 200 upgrade payment."
        )));
    }

    // ─── 3. Payment Verification (manual / polling fallback) ──────────────────

    /**
     * Manually verifies a Moolre payment and credits the wallet if successful.
     * Idempotent — safe to poll.
     *
     * Status ID resolution (Moolre idtype):
     *   • If moolreRef supplied by client → idtype=2 (Moolre's generated reference)
     *   • Else if moolreRef found in pending cache → idtype=2
     *   • Else → idtype=1 (our externalref)
     *
     * "Transaction not found" is mapped to PENDING so the frontend keeps polling.
     *
     * Required body: { "externalref": "deposit_<userId>_<uuid>" }
     * Optional body: { "moolreRef": "<moolre-reference-uuid>" }
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

        // ── Resolve query ID and idtype ────────────────────────────────────────
        String queryId = ref;
        int    idType  = 1; // default: query by externalref

        String clientMoolreRef = req.containsKey("moolreRef") && req.get("moolreRef") != null
                ? req.get("moolreRef").toString().trim()
                : "";

        if (!clientMoolreRef.isBlank()) {
            queryId = clientMoolreRef;
            idType  = 2;
            log.info("verifyPayment: using client-supplied moolreRef='{}' (idtype=2) for externalRef='{}'",
                    queryId, ref);
        } else {
            var pending = pendingCheckouts.get(ref);
            if (pending != null && pending.moolreRef() != null && !pending.moolreRef().isBlank()) {
                queryId = pending.moolreRef();
                idType  = 2;
                log.info("verifyPayment: using cached moolreRef='{}' (idtype=2) for externalRef='{}'",
                        queryId, ref);
            } else {
                log.info("verifyPayment: no moolreRef available — querying by externalRef='{}' (idtype=1)", ref);
            }
        }

        var statusResponse = moolreCheckStatus(queryId, idType);

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) statusResponse.get("data");

        // ── "Transaction not found" detection ─────────────────────────────────
        var topLevelMessage = String.valueOf(statusResponse.getOrDefault("message", "")).toLowerCase();
        if (topLevelMessage.contains("not found")) {
            log.info("verifyPayment: 'not found' — treating as PENDING for externalRef='{}'", ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_PENDING,
                    "message",  "Payment is still being processed. Please wait and try again."
            )));
        }

        if (data != null) {
            var dataMessage = String.valueOf(data.getOrDefault("message", "")).toLowerCase();
            if (dataMessage.contains("not found")) {
                log.info("verifyPayment: data.message 'not found' — treating as PENDING for externalRef='{}'", ref);
                return ResponseEntity.ok(ApiResponse.ok(Map.of(
                        "credited", false,
                        "txstatus", TX_PENDING,
                        "message",  "Payment is still being processed. Please wait and try again."
                )));
            }
        }

        var txStatus = data != null
                ? Integer.parseInt(data.getOrDefault("txstatus", "-1").toString())
                : -1;

        if (txStatus == TX_PENDING) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_PENDING,
                    "message",  "Payment is still pending. Please complete payment on the Moolre checkout page."
            )));
        }

        if (txStatus == TX_FAILED) {
            pendingCheckouts.remove(ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_FAILED,
                    "message",  "Payment failed or was cancelled."
            )));
        }

        if (txStatus != TX_SUCCESS) {
            log.warn("verifyPayment: unknown txstatus={} externalRef='{}' — treating as PENDING", txStatus, ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_PENDING,
                    "message",  "Payment is still being processed. Please try again shortly."
            )));
        }

        // txstatus=1 — credit wallet
        var valueStr = resolveAmount(data, ref);
        var amount   = new BigDecimal(valueStr);
        var intent   = parts[0];

        pendingCheckouts.remove(ref);

        boolean credited = UPGRADE_INTENT_ADMIN.equals(intent)
                ? handleAdminUpgrade(user.getId(), ref, amount)
                : handleDeposit(user.getId(), ref, amount);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "credited", credited,
                "txstatus", TX_SUCCESS,
                "message",  credited
                        ? "Payment verified. GHS " + amount + " has been added to your wallet."
                        : "Payment was already processed."
        )));
    }

    // ─── 4. Webhook (primary automatic credit path) ───────────────────────────

    /**
     * Moolre fires this endpoint after every successful payment.
     * Verified by matching the `secret` field in the payload against
     * app.moolre.webhook-secret.
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

            // ── Secret verification ────────────────────────────────────────────
            var secret = data.getOrDefault("secret", "").toString();
            if (!verifyWebhookSecret(secret)) {
                log.warn("Moolre webhook: invalid secret received");
                return ResponseEntity.status(400).body("Invalid secret");
            }

            // ── Account number guard ───────────────────────────────────────────
            var incomingAccount = data.getOrDefault("accountnumber", "").toString();
            if (!accountNumber.equals(incomingAccount)) {
                log.warn("Moolre webhook: accountnumber mismatch — incoming='{}' expected='{}'",
                        incomingAccount, accountNumber);
                return ResponseEntity.status(400).body("Account mismatch");
            }

            // ── Status guard ───────────────────────────────────────────────────
            var txStatus = Integer.parseInt(data.getOrDefault("txstatus", "-1").toString());
            if (txStatus != TX_SUCCESS) {
                log.info("Moolre webhook: ignoring txstatus={} externalref='{}'",
                        txStatus, data.get("externalref"));
                return ResponseEntity.ok("Ignored");
            }

            // ── Process payment ────────────────────────────────────────────────
            var externalRef = data.get("externalref");
            if (externalRef == null || externalRef.toString().isBlank()) {
                log.error("Moolre webhook: missing externalref in data");
                return ResponseEntity.status(400).body("Missing externalref");
            }

            var ref      = externalRef.toString();
            var valueStr = resolveAmount(data, ref);
            var amount   = new BigDecimal(valueStr);

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

            pendingCheckouts.remove(ref);

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

    // ─── 5. Automatic Verification Scheduler ──────────────────────────────────

    /**
     * Polls Moolre status for every pending session every 30 seconds.
     *
     * Skips sessions < 30 seconds old (give webhook a chance first).
     * Removes sessions > 30 minutes old (TTL expired).
     * Uses idtype=2 (moolreRef) when available, idtype=1 (externalref) otherwise.
     *
     * Requires @EnableScheduling on a @Configuration class.
     */
    @Scheduled(fixedDelay = 30_000)
    public void autoVerifyPendingCheckouts() {

        if (pendingCheckouts.isEmpty()) return;

        log.debug("autoVerify: scanning {} pending session(s)", pendingCheckouts.size());

        long         now      = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, PendingCheckout> entry : pendingCheckouts.entrySet()) {
            var ref     = entry.getKey();
            var session = entry.getValue();
            long ageMs  = now - session.createdAt();

            if (ageMs < 30_000) continue; // too recent

            if (ageMs > PENDING_SESSION_TTL_MS) {
                log.warn("autoVerify: session expired ({}ms old) — removing externalRef='{}'", ageMs, ref);
                toRemove.add(ref);
                continue;
            }

            try {
                String queryId;
                int    idType;
                if (session.moolreRef() != null && !session.moolreRef().isBlank()) {
                    queryId = session.moolreRef();
                    idType  = 2;
                } else {
                    queryId = ref;
                    idType  = 1;
                }

                log.info("autoVerify: checking externalRef='{}' queryId='{}' idtype={}", ref, queryId, idType);

                var statusResponse = moolreCheckStatus(queryId, idType);

                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) statusResponse.get("data");

                var topMsg = String.valueOf(statusResponse.getOrDefault("message", "")).toLowerCase();
                if (topMsg.contains("not found")) {
                    log.debug("autoVerify: 'not found' for externalRef='{}' — will retry", ref);
                    continue;
                }

                if (data == null) {
                    log.debug("autoVerify: no data for externalRef='{}' — will retry", ref);
                    continue;
                }

                var dataMsg = String.valueOf(data.getOrDefault("message", "")).toLowerCase();
                if (dataMsg.contains("not found")) {
                    log.debug("autoVerify: data.message 'not found' for externalRef='{}' — will retry", ref);
                    continue;
                }

                int txStatus = Integer.parseInt(data.getOrDefault("txstatus", "-1").toString());

                if (txStatus == TX_SUCCESS) {
                    var valueStr = resolveAmount(data, ref);
                    var amount   = new BigDecimal(valueStr);
                    var parts    = ref.split("_", 3);

                    if (parts.length < 3) {
                        log.error("autoVerify: bad externalref format '{}' — removing", ref);
                        toRemove.add(ref);
                        continue;
                    }

                    var intent = parts[0];
                    UUID userId;
                    try {
                        userId = UUID.fromString(parts[1]);
                    } catch (IllegalArgumentException ex) {
                        log.error("autoVerify: cannot parse userId from ref='{}' — removing", ref);
                        toRemove.add(ref);
                        continue;
                    }

                    log.info("autoVerify: success for externalRef='{}' — crediting userId='{}'", ref, userId);
                    toRemove.add(ref);

                    if (UPGRADE_INTENT_ADMIN.equals(intent)) {
                        handleAdminUpgrade(userId, ref, amount);
                    } else {
                        handleDeposit(userId, ref, amount);
                    }

                } else if (txStatus == TX_FAILED) {
                    log.info("autoVerify: failed/cancelled for externalRef='{}' — removing", ref);
                    toRemove.add(ref);
                } else {
                    log.debug("autoVerify: still pending (txstatus={}) for externalRef='{}'", txStatus, ref);
                }

            } catch (Exception ex) {
                log.error("autoVerify: error checking externalRef='{}' — will retry: {}", ref, ex.getMessage());
            }
        }

        toRemove.forEach(pendingCheckouts::remove);
        if (!toRemove.isEmpty()) {
            log.info("autoVerify: removed {} resolved/expired session(s)", toRemove.size());
        }
    }

    // ─── Private — wallet handlers ────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission to
     * their referring admin (rate stored on Referral entity, default 70%).
     */
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
            log.info("handleDeposit: commission attributed for userId='{}' deposit='{}' adminRate={}",
                    userId, amount, ADMIN_COMMISSION_RATE);
        } catch (Exception ex) {
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate", userId, ex);
        }

        return true;
    }

    /**
     * Promotes user to ADMIN, records audit transaction, opens onboarding chat.
     *
     * Steps:
     *   1. Validates amount >= GHS 200
     *   2. Promotes user to ADMIN + initialises referral link at 70% commission
     *   3. Records audit transaction (Moolre collected the funds externally)
     *   4. Creates onboarding chat with Super Admin
     */
    private boolean handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(ADMIN_UPGRADE_FEE) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'", amount, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            userService.upgradeToAdmin(userId, ref);
            log.info("handleAdminUpgrade: userId='{}' promoted to ADMIN with {}% commission ref='{}'",
                    userId, ADMIN_COMMISSION_RATE.multiply(BigDecimal.valueOf(100)).toPlainString(), ref);
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

    // ─── Moolre API helpers ───────────────────────────────────────────────────

    /**
     * Calls Moolre POST /embed/link to generate a hosted Web POS payment link.
     *
     * Moolre request body:
     *   type=1, amount, currency=GHS, accountnumber, email,
     *   externalref, reusable=0, callback (webhook), redirect (optional)
     *
     * Moolre response:
     *   {
     *     "status": 1,
     *     "data": {
     *       "authorization_url": "https://pos.moolre.com/...",
     *       "reference": "<moolre-internal-uuid>"
     *     }
     *   }
     *
     * @param amount      GHS amount.
     * @param externalRef Our unique reference.
     * @param email       Business/customer email for Moolre.
     * @return Map containing "checkoutUrl" and "moolreRef".
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreGeneratePaymentLink(
            BigDecimal amount, String externalRef, String email) {

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type",          1);
        body.put("amount",        amount.toPlainString());
        body.put("currency",      "GHS");
        body.put("accountnumber", accountNumber);
        body.put("email",         email);
        body.put("externalref",   externalRef);
        body.put("reusable",      "0");
        body.put("callback",      callbackUrl);
        if (redirectUrl != null && !redirectUrl.isBlank()) {
            body.put("redirect", redirectUrl);
        }

        log.info("moolreGeneratePaymentLink: calling /embed/link — amount='{}' externalRef='{}'",
                amount, externalRef);

        String rawResponse = webClientBuilder.build()
                .post().uri(MOOLRE_BASE_URL + ENDPOINT_PAYMENT_LINK)
                .header("X-API-USER",   apiUser)
                .header("X-API-PUBKEY", publicKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("Moolre /embed/link HTTP error: status={} body={}",
                                            clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "Moolre returned HTTP " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(String.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException),
                        ex -> {
                            log.error("Moolre API unreachable during payment link generation", ex);
                            return new RuntimeException("Moolre is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (rawResponse == null || rawResponse.isBlank())
            throw new RuntimeException("Moolre returned an empty response from /embed/link.");

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) objectMapper.readValue(rawResponse, Map.class);
        } catch (Exception e) {
            log.error("Moolre /embed/link: non-JSON response body='{}'", rawResponse);
            throw new RuntimeException("Moolre returned an unexpected response. Please try again.");
        }

        var status  = String.valueOf(result.getOrDefault("status", "0"));
        var message = String.valueOf(result.getOrDefault("message", ""));

        log.info("moolreGeneratePaymentLink: status='{}' message='{}' externalRef='{}'",
                status, message, externalRef);

        if (!"1".equals(status)) {
            log.error("moolreGeneratePaymentLink: Moolre error status='{}' message='{}' body='{}'",
                    status, message, rawResponse);
            throw new RuntimeException("Moolre payment link error: " + message);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = result.get("data") instanceof Map
                ? (Map<String, Object>) result.get("data")
                : new java.util.LinkedHashMap<>();

        // authorization_url is the hosted Web POS page URL
        var checkoutUrl = String.valueOf(data.getOrDefault("authorization_url", ""));
        if (checkoutUrl.isBlank()) {
            log.error("moolreGeneratePaymentLink: no authorization_url in response data='{}' externalRef='{}'",
                    data, externalRef);
            throw new RuntimeException("Moolre did not return a payment URL. Please try again.");
        }

        // Moolre's internal reference UUID — used for idtype=2 status checks
        var moolreRef = String.valueOf(data.getOrDefault("reference", ""));
        if (moolreRef.isBlank()) {
            log.warn("moolreGeneratePaymentLink: no reference UUID in response for externalRef='{}' — status polling will use externalref (idtype=1)",
                    externalRef);
        }

        return Map.of(
                "checkoutUrl", checkoutUrl,
                "moolreRef",   moolreRef,
                "message",     message
        );
    }

    /**
     * Calls Moolre POST /open/transact/status to check payment status.
     *
     * @param queryId The ID to query — either our externalref or Moolre's reference UUID.
     * @param idType  1 = query by externalref (idtype=1), 2 = query by Moolre reference (idtype=2).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreCheckStatus(String queryId, int idType) {

        String rawResponse = webClientBuilder.build()
                .post().uri(MOOLRE_BASE_URL + ENDPOINT_STATUS)
                .header("X-API-USER",   apiUser)
                .header("X-API-PUBKEY", publicKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "type",          1,
                        "idtype",        String.valueOf(idType),
                        "id",            queryId,
                        "accountnumber", accountNumber
                ))
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("Moolre status HTTP error: status={} body={}",
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
                .block();

        if (rawResponse == null || rawResponse.isBlank())
            throw new RuntimeException("Moolre returned an empty status response.");

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) objectMapper.readValue(rawResponse, Map.class);
        } catch (Exception e) {
            log.error("Moolre status: non-JSON response body='{}'", rawResponse);
            throw new RuntimeException("Moolre returned an unexpected status response. Please try again.");
        }

        log.info("moolreCheckStatus: status='{}' message='{}' queryId='{}' idtype={}",
                result.get("status"), result.get("message"), queryId, idType);

        return result;
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────

    /** Resolves the credited amount from Moolre's response — prefers "value", falls back to "amount". */
    private static String resolveAmount(Map<String, Object> data, String ref) {
        var value = data.get("value");
        if (value != null && !value.toString().isBlank()) return value.toString();

        var amount = data.get("amount");
        if (amount != null && !amount.toString().isBlank()) return amount.toString();

        throw ApiException.badRequest(
                "Moolre response is missing both 'value' and 'amount' fields for ref='" + ref + "'");
    }

    /** Constant-time comparison to prevent timing attacks on webhook secret verification. */
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
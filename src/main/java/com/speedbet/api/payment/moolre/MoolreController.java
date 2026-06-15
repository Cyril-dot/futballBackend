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
import java.util.Arrays;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MoolreController — GHS MoMo payments via Moolre Checkout Page.
 *
 * ─── Payment flows ───────────────────────────────────────────────────────────
 *
 *  1. Initiate Checkout — Deposit
 *     POST /api/wallet/deposit/moolre/init
 *     • Accepts { amount, email } from the frontend (email optional).
 *     • Calls Moolre POST /open/checkout/initiate to create a hosted checkout session.
 *     • Returns { externalref, checkoutUrl, moolreTxId } to the frontend.
 *     • Frontend redirects the user to checkoutUrl — the customer selects their
 *       MoMo network and enters their number on Moolre's hosted page.
 *     • Moolre approves the payment and fires our webhook → wallet is credited automatically.
 *     • Frontend may also poll /verify for status while the user is on the checkout page.
 *
 *  2. Initiate Checkout — Admin Upgrade
 *     POST /api/user/upgrade-to-admin/moolre/init
 *     • Same checkout flow but amount is fixed at GHS 200 and promotes
 *       the user to ADMIN on successful payment.
 *     • New admin's commission rate is initialised at 70% (set inside
 *       UserService.upgradeToAdmin). Super Admin can adjust via onboarding chat.
 *
 *  3. Payment Verification (manual fallback / polling)
 *     POST /api/wallet/deposit/moolre/verify
 *     • Accepts the externalref stored by the frontend after /init.
 *     • Queries Moolre /open/transact/status by the Moolre TX ID (cached after init)
 *       or falls back to querying by externalref.
 *     • Returns txstatus=0 (PENDING) when Moolre returns "Transaction not found"
 *       so the frontend knows to keep polling rather than giving up.
 *     • Credits wallet immediately if txstatus=1 and not already credited.
 *     • Idempotent — safe to poll; duplicate refs are silently ignored.
 *
 *  4. Webhook (primary / automatic credit path)
 *     POST /api/webhooks/moolre
 *     • Moolre POSTs here after every successful payment.
 *     • Verified by matching the `secret` field in the payload.
 *     • /verify above and the auto-polling scheduler are fallbacks for missed webhooks.
 *
 *  5. Automatic Verification Scheduler
 *     • Runs every 30 seconds.
 *     • Scans all pending checkout sessions older than 30 seconds.
 *     • For each, calls Moolre /open/transact/status and credits the wallet
 *       automatically on success — no user action needed.
 *     • Removes sessions that have been pending more than 30 minutes (expired).
 *     • This ensures wallet credit even when the webhook is missed/delayed.
 *
 * ─── Commission structure ─────────────────────────────────────────────────────
 *   Every deposit triggers ReferralService.attributeCommission(), which credits
 *   the referring admin's affiliate wallet based on their stored commission rate.
 *   Default admin commission rate: 70% (ADMIN_COMMISSION_RATE constant below).
 *   The rate is stored on the Referral entity and set during upgradeToAdmin().
 *   Super Admin can negotiate a different rate via the onboarding chat created
 *   after a successful admin upgrade payment.
 *
 * ─── Moolre Checkout API ─────────────────────────────────────────────────────
 *   Initiate:  POST /open/checkout/initiate
 *   Status:    POST /open/transact/status
 *   Moolre returns a hosted checkoutUrl the user is redirected to.
 *   No phone/network selection needed from the frontend — the user picks on
 *   Moolre's page.
 *
 * ─── network values (for status response mapping) ────────────────────────────
 *   Moolre handles network selection internally on the checkout page.
 *   No channel codes needed from our side on initiation.
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
 *   app.moolre.api-user              → env: MOOLRE_API_USER
 *   app.moolre.public-key            → env: MOOLRE_PUBLIC_KEY
 *   app.moolre.account-number        → env: MOOLRE_ACCOUNT_NUMBER
 *   app.moolre.webhook-secret        → env: MOOLRE_WEBHOOK_SECRET
 *   app.moolre.callback-url          → env: MOOLRE_CALLBACK_URL  (your frontend return URL)
 *   app.platform.min-deposit-amount  (default: 1)
 *
 * NOTE: Enable scheduling in your Spring Boot app with @EnableScheduling on a
 *       @Configuration class for the automatic verification scheduler to run.
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

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * Admins earn 70% of the platform commission on each referred deposit.
     * The actual per-admin rate is stored on the Referral entity and resolved
     * inside ReferralService.attributeCommission(). This constant is for
     * logging/documentation purposes only.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // Moolre txstatus codes
    private static final int TX_SUCCESS = 1;
    private static final int TX_PENDING = 0;
    private static final int TX_FAILED  = 2;

    /**
     * How long (in milliseconds) a pending checkout session is kept in the
     * auto-polling cache before it is considered expired (30 minutes).
     */
    private static final long PENDING_SESSION_TTL_MS = 30 * 60 * 1000L;

    /**
     * Pending checkout session cache — keyed by externalref.
     *
     * Populated by /init, consumed by /verify, the webhook, and the scheduler.
     * Stores the Moolre TX ID so status can be queried by UUID rather than
     * externalref (Moolre indexes by UUID internally).
     *
     * In a multi-instance deployment replace this with Redis or a DB table.
     */
    private final ConcurrentHashMap<String, PendingCheckout> pendingCheckouts = new ConcurrentHashMap<>();

    /**
     * Lightweight struct for a pending checkout session.
     *
     * @param externalRef  Our reference: "deposit_<userId>_<uuid>" etc.
     * @param amount       GHS amount for the checkout session.
     * @param userId       The user who initiated the checkout.
     * @param moolreTxId   Moolre's internal transaction UUID (from initiate response).
     * @param checkoutUrl  Hosted checkout page URL to redirect the user to.
     * @param createdAt    Epoch millis when this session was created (for TTL).
     */
    record PendingCheckout(
            String     externalRef,
            BigDecimal amount,
            UUID       userId,
            String     moolreTxId,
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
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;

    // ─── 1. Initiate Checkout — Deposit ──────────────────────────────────────

    /**
     * Initiates a Moolre Checkout page session for a wallet deposit.
     *
     * The user is redirected to the Moolre-hosted checkout page where they
     * select their MoMo network and enter their number. No phone/network
     * input is required from our frontend.
     *
     * Required body fields:
     *   amount  – GHS amount to deposit (e.g. "300")
     *
     * Optional body fields:
     *   email   – customer email for Moolre receipt
     *
     * Response (HTTP 200):
     *   {
     *     "externalref":  "deposit_<userId>_<uuid>",
     *     "moolreTxId":   "<moolre-internal-uuid>",
     *     "checkoutUrl":  "https://checkout.moolre.com/pay/<session>",
     *     "message":      "Redirect the user to checkoutUrl to complete payment."
     *   }
     *
     * Frontend should redirect the user to checkoutUrl and poll /verify
     * (passing both externalref and moolreTxId) until credited=true.
     * The wallet is also credited automatically via webhook + scheduler.
     */
    @PostMapping("/api/wallet/deposit/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        var email  = req.containsKey("email") ? req.get("email").toString() : "";

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var externalRef = DEPOSIT_INTENT + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initDeposit (checkout): userId='{}' amount={} externalRef='{}'",
                user.getId(), amount, externalRef);

        Map<String, Object> checkoutResult = moolreInitiateCheckout(amount, externalRef, email);

        String checkoutUrl = checkoutResult.getOrDefault("checkoutUrl", "").toString();
        String moolreTxId  = checkoutResult.getOrDefault("moolreTxId", "").toString();

        // Cache the session for automatic verification polling
        pendingCheckouts.put(externalRef, new PendingCheckout(
                externalRef, amount, user.getId(), moolreTxId, checkoutUrl, System.currentTimeMillis()
        ));

        log.info("initDeposit: checkout session cached — externalRef='{}' moolreTxId='{}' url='{}'",
                externalRef, moolreTxId, checkoutUrl);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref", externalRef,
                "moolreTxId",  moolreTxId,
                "checkoutUrl", checkoutUrl,
                "message",     "Redirect the user to the checkoutUrl to complete payment."
        )));
    }

    // ─── 2. Initiate Checkout — Admin Upgrade ────────────────────────────────

    /**
     * Initiates a Moolre Checkout page session for the GHS 200 admin upgrade fee.
     *
     * On successful payment:
     *   • User is promoted to ADMIN
     *   • Their referral link is created with a 70% commission rate
     *   • An onboarding chat is opened with Super Admin to confirm/adjust the rate
     *
     * Required body fields:
     *   (none beyond the authenticated user)
     *
     * Optional body fields:
     *   email – customer email for Moolre receipt
     *
     * Response (HTTP 200):
     *   {
     *     "externalref":  "adminupgrade_<userId>_<uuid>",
     *     "moolreTxId":   "<moolre-internal-uuid>",
     *     "checkoutUrl":  "https://checkout.moolre.com/pay/<session>",
     *     "message":      "Redirect the user to checkoutUrl to complete payment."
     *   }
     */
    @PostMapping("/api/user/upgrade-to-admin/moolre/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var email = req.containsKey("email") ? req.get("email").toString() : "";
        var externalRef = UPGRADE_INTENT_ADMIN + "_" + user.getId() + "_" + UUID.randomUUID();

        log.info("initAdminUpgrade (checkout): userId='{}' externalRef='{}'",
                user.getId(), externalRef);

        Map<String, Object> checkoutResult = moolreInitiateCheckout(ADMIN_UPGRADE_FEE, externalRef, email);

        String checkoutUrl = checkoutResult.getOrDefault("checkoutUrl", "").toString();
        String moolreTxId  = checkoutResult.getOrDefault("moolreTxId", "").toString();

        pendingCheckouts.put(externalRef, new PendingCheckout(
                externalRef, ADMIN_UPGRADE_FEE, user.getId(), moolreTxId, checkoutUrl, System.currentTimeMillis()
        ));

        log.info("initAdminUpgrade: checkout session cached — externalRef='{}' moolreTxId='{}'",
                externalRef, moolreTxId);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref", externalRef,
                "moolreTxId",  moolreTxId,
                "checkoutUrl", checkoutUrl,
                "message",     "Redirect the user to the checkoutUrl to complete the GHS 200 upgrade payment."
        )));
    }

    // ─── 3. Payment Verification (manual / polling fallback) ─────────────────

    /**
     * Manually verifies a Moolre checkout payment and credits the wallet if successful.
     * Idempotent — safe to poll.
     *
     * Status check ID resolution:
     *   Moolre's /open/transact/status indexes by their internal TX UUID, not our
     *   externalref. This endpoint resolves the UUID from:
     *     (1) client-supplied moolreTxId in the request body
     *     (2) the pendingCheckouts cache (set during /init)
     *     (3) externalref as a last fallback
     *
     *   "Transaction not found" is mapped to PENDING so the frontend keeps polling.
     *
     * Required body fields:
     *   externalref – the reference returned by /init
     *
     * Optional body fields:
     *   moolreTxId  – Moolre's internal UUID (returned by /init). When supplied,
     *                 status is queried by this ID directly. Use this when the server
     *                 has restarted and the in-memory cache was cleared.
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

        // ── Resolve the query ID ────────────────────────────────────────────────
        String queryId = ref; // default fallback

        String clientMoolreTxId = req.containsKey("moolreTxId")
                ? req.get("moolreTxId").toString().trim()
                : "";

        if (!clientMoolreTxId.isBlank()) {
            queryId = clientMoolreTxId;
            log.info("verifyPayment: using client-supplied moolreTxId='{}' for externalRef='{}'",
                    queryId, ref);
        } else {
            var pending = pendingCheckouts.get(ref);
            if (pending != null && pending.moolreTxId() != null && !pending.moolreTxId().isBlank()) {
                queryId = pending.moolreTxId();
                log.info("verifyPayment: using cached moolreTxId='{}' for externalRef='{}'",
                        queryId, ref);
            } else {
                log.info("verifyPayment: no moolreTxId available — falling back to externalRef='{}'", ref);
            }
        }

        var statusResponse = moolreCheckStatus(queryId);

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) statusResponse.get("data");

        // ── "Transaction not found" detection ──────────────────────────────────
        var topLevelMessage = String.valueOf(statusResponse.getOrDefault("message", "")).toLowerCase();
        if (topLevelMessage.contains("transaction not found") || topLevelMessage.contains("not found")) {
            log.info("verifyPayment: Moolre 'Transaction not found' — treating as PENDING for externalRef='{}'", ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_PENDING,
                    "message",  "Payment is still being processed. Please wait a moment and try again."
            )));
        }

        var txStatus = data != null
                ? Integer.parseInt(data.getOrDefault("txstatus", "-1").toString())
                : -1;

        // Also check for "not found" inside data.message
        if (data != null) {
            var dataMessage = String.valueOf(data.getOrDefault("message", "")).toLowerCase();
            if (dataMessage.contains("transaction not found") || dataMessage.contains("not found")) {
                log.info("verifyPayment: data.message 'not found' — treating as PENDING for externalRef='{}'", ref);
                return ResponseEntity.ok(ApiResponse.ok(Map.of(
                        "credited", false,
                        "txstatus", TX_PENDING,
                        "message",  "Payment is still being processed. Please wait a moment and try again."
                )));
            }
        }

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

        // txstatus=1 — credit wallet and attribute commission
        var valueStr = resolveAmount(data, ref);
        var amount   = new BigDecimal(valueStr);
        var intent   = parts[0];

        pendingCheckouts.remove(ref);

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

    // ─── 4. Webhook (primary automatic credit path) ───────────────────────────

    /**
     * Moolre fires this endpoint after every successful payment.
     * Verified by matching the `secret` field in the payload.
     * This is the primary path for automatic wallet crediting.
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

            var secret = data.getOrDefault("secret", "").toString();
            if (!verifyWebhookSecret(secret)) {
                log.warn("Moolre webhook: invalid secret received");
                return ResponseEntity.status(400).body("Invalid secret");
            }

            var incomingAccount = data.getOrDefault("accountnumber", "").toString();
            if (!accountNumber.equals(incomingAccount)) {
                log.warn("Moolre webhook: accountnumber mismatch — incoming='{}' expected='{}'",
                        incomingAccount, accountNumber);
                return ResponseEntity.status(400).body("Account mismatch");
            }

            var txStatus = Integer.parseInt(data.getOrDefault("txstatus", "-1").toString());
            if (txStatus != TX_SUCCESS) {
                log.info("Moolre webhook: ignoring txstatus={} externalref='{}'",
                        txStatus, data.get("externalref"));
                return ResponseEntity.ok("Ignored");
            }

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

            // Remove from auto-polling cache on webhook success
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

    // ─── 5. Automatic Verification Scheduler ─────────────────────────────────

    /**
     * Polls Moolre for every pending checkout session every 30 seconds.
     *
     * This is the fallback automatic credit path for cases where the webhook
     * was not received (network issues, Moolre delays, server restart after
     * the user completed payment on the checkout page).
     *
     * Flow:
     *   1. Iterate all entries in pendingCheckouts.
     *   2. Skip sessions created less than 30 seconds ago (give webhook a chance first).
     *   3. Remove sessions older than 30 minutes (TTL expired — too late to credit).
     *   4. Call Moolre /open/transact/status for each remaining session.
     *   5. On txstatus=1, credit wallet / handle admin upgrade immediately.
     *   6. On txstatus=2 (failed), remove from cache.
     *   7. On txstatus=0 or "not found", leave in cache for next cycle.
     *
     * Requires @EnableScheduling on a @Configuration class.
     */
    @Scheduled(fixedDelay = 30_000)
    public void autoVerifyPendingCheckouts() {

        if (pendingCheckouts.isEmpty()) return;

        log.debug("autoVerify: scanning {} pending checkout session(s)", pendingCheckouts.size());

        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, PendingCheckout> entry : pendingCheckouts.entrySet()) {
            var ref     = entry.getKey();
            var session = entry.getValue();

            long ageMs = now - session.createdAt();

            // Too recent — let webhook handle it first
            if (ageMs < 30_000) continue;

            // TTL expired — stop polling
            if (ageMs > PENDING_SESSION_TTL_MS) {
                log.warn("autoVerify: session expired ({}ms old) — removing externalRef='{}'", ageMs, ref);
                toRemove.add(ref);
                continue;
            }

            try {
                String queryId = (session.moolreTxId() != null && !session.moolreTxId().isBlank())
                        ? session.moolreTxId()
                        : ref;

                log.info("autoVerify: checking externalRef='{}' queryId='{}'", ref, queryId);

                var statusResponse = moolreCheckStatus(queryId);

                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) statusResponse.get("data");

                // "Transaction not found" → still pending, leave in cache
                var topMsg = String.valueOf(statusResponse.getOrDefault("message", "")).toLowerCase();
                if (topMsg.contains("not found")) {
                    log.debug("autoVerify: 'not found' response for externalRef='{}' — will retry", ref);
                    continue;
                }

                if (data == null) {
                    log.debug("autoVerify: no data in status response for externalRef='{}' — will retry", ref);
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

                    log.info("autoVerify: txstatus=1 detected for externalRef='{}' — crediting userId='{}'", ref, userId);
                    toRemove.add(ref);

                    if (UPGRADE_INTENT_ADMIN.equals(intent)) {
                        handleAdminUpgrade(userId, ref, amount);
                    } else {
                        handleDeposit(userId, ref, amount);
                    }

                } else if (txStatus == TX_FAILED) {
                    log.info("autoVerify: payment failed/cancelled for externalRef='{}' — removing", ref);
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
     * their referring admin.
     *
     * Commission structure:
     *   The referring admin earns a percentage of every deposit made by users
     *   they referred. The rate is stored on the Referral entity and defaults
     *   to 70% (ADMIN_COMMISSION_RATE). Resolution is handled entirely inside
     *   ReferralService.attributeCommission().
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
            // Never block a deposit because of a commission failure
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate", userId, ex);
        }

        return true;
    }

    private boolean verifyAndHandleDeposit(UUID userId, String ref, BigDecimal amount) {
        return handleDeposit(userId, ref, amount);
    }

    /**
     * Handles an admin upgrade payment.
     *
     * Steps:
     *   1. Validates amount >= GHS 200
     *   2. Promotes user to ADMIN + initialises their referral link at 70% commission
     *   3. Records an audit transaction (Moolre collected the funds externally)
     *   4. Creates onboarding chat with Super Admin to confirm/adjust the 70% rate
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

    private boolean verifyAndHandleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        return handleAdminUpgrade(userId, ref, amount);
    }

    // ─── Moolre API helpers ───────────────────────────────────────────────────

    /**
     * Calls Moolre POST /open/checkout/initiate to create a hosted checkout session.
     *
     * Moolre response (expected):
     *   {
     *     "status":  "1",
     *     "message": "...",
     *     "data": {
     *       "id":          "<moolre-tx-uuid>",
     *       "checkoutUrl": "https://checkout.moolre.com/pay/<session>",
     *       ...
     *     }
     *   }
     *
     * Returns a map containing "checkoutUrl" and "moolreTxId" for the caller.
     *
     * @param amount      GHS amount for the checkout.
     * @param externalRef Our reference string.
     * @param email       Optional customer email for Moolre receipt.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreInitiateCheckout(
            BigDecimal amount, String externalRef, String email) {

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("amount",        amount.toPlainString());
        body.put("currency",      "GHS");
        body.put("externalref",   externalRef);
        body.put("accountnumber", accountNumber);
        body.put("callbackurl",   callbackUrl);
        if (email != null && !email.isBlank()) {
            body.put("email", email);
        }

        log.info("moolreInitiateCheckout: calling /open/checkout/initiate — amount='{}' externalRef='{}'",
                amount, externalRef);

        String rawBody = webClientBuilder.build()
                .post().uri(MOOLRE_BASE_URL + "/open/checkout/initiate")
                .header("X-API-USER",   apiUser)
                .header("X-API-PUBKEY", publicKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("Moolre checkout HTTP error: status={} body={}",
                                            clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "Moolre returned HTTP " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(String.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException),
                        ex -> {
                            log.error("Moolre API unreachable during checkout initiate", ex);
                            return new RuntimeException("Moolre is currently unavailable. Please try again.");
                        }
                )
                .onErrorMap(
                        ex -> ex instanceof RuntimeException && ex.getMessage() == null,
                        ex -> {
                            log.error("Moolre checkout: RuntimeException with null message", ex);
                            return new RuntimeException("Moolre is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (rawBody == null || rawBody.isBlank())
            throw new RuntimeException("Moolre returned an empty checkout response.");

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.error("Moolre checkout: non-JSON response body='{}'", rawBody);
            throw new RuntimeException("Moolre returned an unexpected checkout response. Please try again.");
        }

        var status  = String.valueOf(result.get("status"));
        var message = String.valueOf(result.getOrDefault("message", ""));

        log.info("moolreInitiateCheckout: status='{}' message='{}' externalRef='{}'",
                status, message, externalRef);

        if (!"1".equals(status)) {
            log.error("moolreInitiateCheckout: Moolre error status='{}' message='{}'", status, message);
            throw new RuntimeException("Moolre checkout error: " + message);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = result.get("data") instanceof Map
                ? (Map<String, Object>) result.get("data")
                : new java.util.LinkedHashMap<>();

        // Extract checkout URL — Moolre may return it as "checkoutUrl", "checkout_url", or "url"
        String checkoutUrl = Stream.of("checkoutUrl", "checkout_url", "url")
                .map(data::get)
                .filter(v -> v != null && !v.toString().isBlank())
                .map(Object::toString)
                .findFirst()
                .orElseThrow(() -> {
                    log.error("moolreInitiateCheckout: no checkout URL in response data='{}' for externalRef='{}'",
                            data, externalRef);
                    return new RuntimeException(
                            "Moolre did not return a checkout URL. Please try again.");
                });

        // Extract Moolre's internal TX UUID
        String moolreTxId = Stream.of("id", "txid", "transactionId", "transaction_id")
                .map(data::get)
                .filter(v -> v != null && !v.toString().isBlank())
                .map(Object::toString)
                .findFirst()
                .orElse("");

        if (moolreTxId.isBlank()) {
            log.warn("moolreInitiateCheckout: no Moolre TX ID in response for externalRef='{}' — status polling will use externalref",
                    externalRef);
        }

        return Map.of(
                "checkoutUrl", checkoutUrl,
                "moolreTxId",  moolreTxId,
                "message",     message
        );
    }

    /**
     * Calls Moolre POST /open/transact/status to check payment status.
     *
     * @param queryId Moolre's internal TX UUID (preferred) or our externalref (fallback).
     *                Always pass the Moolre UUID when available — Moolre indexes by UUID,
     *                not externalref.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreCheckStatus(String queryId) {

        String rawBody = webClientBuilder.build()
                .post().uri(MOOLRE_BASE_URL + "/open/transact/status")
                .header("X-API-USER",   apiUser)
                .header("X-API-PUBKEY", publicKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "type",          1,
                        "idtype",        "1",
                        "id",            queryId,
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

        log.info("moolreCheckStatus: status='{}' message='{}' for queryId='{}'",
                result.get("status"), result.get("message"), queryId);

        return result;
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────

    private static String resolveAmount(Map<String, Object> data, String ref) {
        var value = data.get("value");
        if (value != null && !value.toString().isBlank()) return value.toString();

        var amount = data.get("amount");
        if (amount != null && !amount.toString().isBlank()) return amount.toString();

        throw ApiException.badRequest(
                "Moolre response is missing both 'value' and 'amount' fields for ref='" + ref + "'");
    }

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
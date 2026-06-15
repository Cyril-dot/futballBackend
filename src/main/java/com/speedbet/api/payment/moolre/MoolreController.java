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
import java.util.concurrent.ConcurrentHashMap;

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
 *     • Returns { externalref, moolreTxId, actionRequired, message } to the frontend.
 *       actionRequired=true means Moolre sent an SMS code instead of a USSD push;
 *       the user must enter that code via /otp before the USSD prompt is sent.
 *     • Customer approves the USSD prompt on their phone.
 *     • Moolre fires our webhook → wallet is credited automatically.
 *
 *  2. OTP / SMS Code Submission (actionRequired flow only)
 *     POST /api/wallet/deposit/moolre/otp
 *     • Only needed when /init returns actionRequired=true (MTN subscribers
 *       who require SMS verification before a USSD push can be sent).
 *     • Accepts { externalref, otp } from the frontend.
 *     • Re-calls Moolre POST /open/transact/payment with the same params + otpcode field.
 *       (Per Moolre docs, there is NO separate OTP endpoint — the OTP is submitted
 *        back to the same /open/transact/payment endpoint with otpcode in the body.)
 *     • On success Moolre immediately pushes the USSD prompt to the user's phone.
 *     • Frontend then moves user to the normal "approve USSD" waiting screen.
 *
 *  3. Initiate USSD Charge — Admin Upgrade
 *     POST /api/user/upgrade-to-admin/moolre/init
 *     • Same USSD direct flow but amount is fixed at GHS 200 and promotes
 *       the user to ADMIN on successful payment.
 *     • New admin's commission rate is initialised at 70% (set inside
 *       UserService.upgradeToAdmin). Super Admin can adjust via onboarding chat.
 *     • Also returns actionRequired flag when applicable.
 *
 *  4. Payment Verification (manual fallback / polling)
 *     POST /api/wallet/deposit/moolre/verify
 *     • Accepts the externalref stored by the frontend after /init.
 *     • Resolves Moolre's internal transaction ID from the pendingCharges cache
 *       (populated during step B of moolreDirectCharge) and queries by that ID.
 *     • Falls back to querying by externalref if no Moolre TX ID is cached yet
 *       (e.g. very early poll before step B completes).
 *     • Returns txstatus=0 (PENDING) when Moolre returns "Transaction not found"
 *       so the frontend knows to keep polling rather than giving up.
 *     • Credits wallet immediately if txstatus=1 and not already credited.
 *     • Idempotent — safe to poll; duplicate refs are silently ignored.
 *
 *  5. Webhook (primary / automatic credit path)
 *     POST /api/webhooks/moolre
 *     • Moolre POSTs here after every successful payment.
 *     • Verified by matching the `secret` field in the payload.
 *     • /verify above is the fallback for missed or delayed webhooks.
 *
 * ─── Commission structure ─────────────────────────────────────────────────────
 *   Every deposit triggers ReferralService.attributeCommission(), which credits
 *   the referring admin's affiliate wallet based on their stored commission rate.
 *   Default admin commission rate: 70% (ADMIN_COMMISSION_RATE constant below).
 *   The rate is stored on the Referral entity and set during upgradeToAdmin().
 *   Super Admin can negotiate a different rate via the onboarding chat created
 *   after a successful admin upgrade payment.
 *
 * ─── OTP flow (per official Moolre docs) ─────────────────────────────────────
 *   There is NO separate /authorize endpoint.  The OTP is submitted by re-calling
 *   POST /open/transact/payment with the SAME body as /init PLUS the `otpcode`
 *   field populated.  Pending charge params (amount, phone, channel, externalref)
 *   are cached in pendingCharges (ConcurrentHashMap) keyed by externalref so
 *   the /otp endpoint can reconstruct the full body without the frontend having
 *   to re-send those fields.
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
 * ─── Status check ID resolution ──────────────────────────────────────────────
 *   Moolre indexes completed transactions by their internal UUID (returned in the
 *   `data` field of the step B /open/transact/payment response), NOT by our
 *   externalref.  Querying /open/transact/status by externalref while the
 *   transaction is not yet fully recorded returns status=1 + message=
 *   "Transaction not found" — which looks like a success but contains no data.
 *
 *   Fix: after step B succeeds, the Moolre TX UUID is extracted from the response
 *   `data` field and stored in pendingCharges.moolreTxId.  moolreCheckStatus()
 *   always queries by this UUID when available, falling back to externalref only
 *   if the UUID hasn't been captured yet.
 *
 *   "Transaction not found" is treated as PENDING (txstatus=0) so the frontend
 *   keeps polling rather than surfacing an error to the user.
 *
 * ─── Moolre API base URL (hardcoded) ─────────────────────────────────────────
 *   https://api.moolre.com
 *
 * ─── Phone number format ─────────────────────────────────────────────────────
 *   Moolre expects the phone with a leading 0 (e.g. "0244123456").
 *   Do NOT send the 233 country-code prefix — Moolre rejects it.
 *   The frontend must collect the number in 0XXXXXXXXX format and send it as-is.
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

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * Admins earn 70% of the platform commission on each referred deposit.
     * The actual per-admin rate is stored on the Referral entity (set during
     * upgradeToAdmin) and resolved inside ReferralService.attributeCommission().
     * This constant is for logging/documentation purposes only.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // Moolre txstatus codes
    private static final int TX_SUCCESS = 1;
    private static final int TX_PENDING = 0;
    private static final int TX_FAILED  = 2;

    // Moolre channel codes (from official docs)
    private static final String CHANNEL_MTN        = "13";
    private static final String CHANNEL_VODAFONE   = "6";
    private static final String CHANNEL_AIRTELTIGO = "7";

    /**
     * Pending charge cache — keyed by externalref.
     *
     * Stores { amount, phone, network, externalRef, moolreTxId } so that:
     *   • /otp can re-call /open/transact/payment with the same params + otpcode.
     *   • /verify can query Moolre's status endpoint by their internal TX UUID
     *     rather than our externalref (Moolre indexes by UUID, not externalref).
     *
     * moolreTxId is null until step B of moolreDirectCharge completes and
     * Moolre returns their internal UUID in the `data` field of the response.
     *
     * Entries are removed after OTP submission or on successful payment.
     * In a multi-instance deployment replace this with Redis or a DB table.
     */
    private final ConcurrentHashMap<String, PendingCharge> pendingCharges = new ConcurrentHashMap<>();

    /**
     * Lightweight struct for cached charge params.
     *
     * @param amount      GHS amount being charged
     * @param phone       Customer MoMo number in 0XXXXXXXXX format
     * @param network     "MTN" / "VODAFONE" / "AIRTELTIGO"
     * @param externalRef Our reference: "deposit_<userId>_<uuid>" etc.
     * @param moolreTxId  Moolre's internal transaction UUID returned in step B data.
     *                    Null until step B completes. Used as the query key for
     *                    /open/transact/status (Moolre does NOT index by externalref).
     */
    record PendingCharge(
            BigDecimal amount,
            String     phone,
            String     network,
            String     externalRef,
            String     moolreTxId   // null until step B returns Moolre's UUID
    ) {
        /** Returns a copy with the Moolre transaction ID set after step B. */
        PendingCharge withMoolreTxId(String txId) {
            return new PendingCharge(amount, phone, network, externalRef, txId);
        }
    }

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
     *   phone   – customer's MoMo number in 0XXXXXXXXX format (e.g. "0244123456")
     *   network – "MTN", "VODAFONE", or "AIRTELTIGO"
     *
     * Response (always HTTP 200 on valid request):
     *   {
     *     "externalref":    "deposit_<userId>_<uuid>",
     *     "moolreTxId":     "<moolre-internal-uuid>",  // null if actionRequired
     *     "actionRequired": false,   // true → user must enter SMS code via /otp first
     *     "message":        "..."
     *   }
     *
     * On success, Moolre fires a webhook which triggers handleDeposit → wallet
     * credit + commission attribution at the admin's 70% rate.
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
        boolean actionRequired = false;
        String  actionMessage  = "";

        try {
            chargeResult = moolreDirectCharge(amount, phone.toString(), network.toString(), externalRef, null);
        } catch (ActionRequiredException ex) {
            log.warn("initDeposit: action required for userId='{}' externalRef='{}' — {}",
                    user.getId(), externalRef, ex.getMessage());
            actionRequired = true;
            actionMessage  = ex.getMessage();
            chargeResult   = Map.of();
        } catch (RuntimeException ex) {
            log.error("initDeposit: Moolre charge failed for userId='{}' externalRef='{}' — {}",
                    user.getId(), externalRef, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Payment initiation failed. Please try again.");
        }

        // Surface Moolre's internal TX ID so /verify can query by it directly.
        // This is null when actionRequired=true (step B hasn't run yet).
        String moolreTxId = resolveMoolreTxId(externalRef, chargeResult);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref",    externalRef,
                "moolreTxId",     moolreTxId != null ? moolreTxId : "",
                "actionRequired", actionRequired,
                "message", actionRequired
                        ? actionMessage
                        : chargeResult.getOrDefault("message",
                        "Please approve the USSD prompt on your phone.").toString()
        )));
    }

    // ─── 2. OTP / SMS Code Submission ────────────────────────────────────────

    /**
     * Submits the SMS verification code sent by Moolre/MTN.
     *
     * Per Moolre's official API documentation, there is NO separate OTP endpoint.
     * The OTP is submitted by re-calling POST /open/transact/payment with the
     * same body as the original /init request PLUS the `otpcode` field set.
     *
     * The original charge params (amount, phone, channel) are retrieved from
     * the pendingCharges cache that was populated during /init.
     *
     * Required body fields:
     *   externalref – the reference returned by /init
     *   otp         – the code the user received via SMS
     *
     * Response (HTTP 200 on success):
     *   {
     *     "moolreTxId": "<moolre-internal-uuid>",
     *     "message":    "USSD prompt sent. Please approve on your phone."
     *   }
     *
     * The frontend should persist moolreTxId (alongside externalref) and use it
     * for subsequent /verify calls so status checks query by Moolre's UUID.
     */
    @PostMapping("/api/wallet/deposit/moolre/otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var externalRef = req.get("externalref");
        var otp         = req.get("otp");

        if (externalRef == null || externalRef.toString().isBlank())
            throw ApiException.badRequest("externalref is required.");
        if (otp == null || otp.toString().isBlank())
            throw ApiException.badRequest("otp is required.");

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

        // Retrieve cached charge params
        var pending = pendingCharges.get(ref);
        if (pending == null) {
            log.error("submitOtp: no pending charge found for externalRef='{}' userId='{}'", ref, user.getId());
            throw ApiException.badRequest(
                    "Payment session not found. Please start a new deposit.");
        }

        log.info("submitOtp: userId='{}' externalRef='{}'", user.getId(), ref);

        Map<String, Object> chargeResult;
        try {
            chargeResult = moolreDirectCharge(
                    pending.amount(), pending.phone(), pending.network(),
                    ref, otp.toString().trim());
            // Remove from cache — USSD prompt has been triggered
            pendingCharges.remove(ref);
        } catch (ActionRequiredException ex) {
            log.warn("submitOtp: unexpected actionRequired after OTP for externalRef='{}' — {}", ref, ex.getMessage());
            throw ApiException.badRequest("OTP verification failed: " + ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("submitOtp: OTP submission failed for userId='{}' externalRef='{}' — {}",
                    user.getId(), ref, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "OTP verification failed. Please check the code and try again.");
        }

        // After OTP + step B, Moolre's TX ID is now available. Surface it so the
        // frontend can pass it to /verify rather than querying by externalref.
        String moolreTxId = resolveMoolreTxId(ref, chargeResult);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "moolreTxId", moolreTxId != null ? moolreTxId : "",
                "message", "Code verified. A USSD prompt has been sent to your phone — please approve it to complete your payment."
        )));
    }

    // ─── 3. Initiate USSD Charge — Admin Upgrade ─────────────────────────────

    /**
     * Initiates a Moolre USSD direct charge for the GHS 200 admin upgrade fee.
     *
     * On successful payment:
     *   • User is promoted to ADMIN
     *   • Their referral link is created with a 70% commission rate (set inside
     *     UserService.upgradeToAdmin)
     *   • An onboarding chat is opened with Super Admin to confirm/adjust the rate
     *
     * Required body fields:
     *   phone   – customer's MoMo number in 0XXXXXXXXX format
     *   network – "MTN", "VODAFONE", or "AIRTELTIGO"
     *
     * Response (always HTTP 200 on valid request):
     *   {
     *     "externalref":    "adminupgrade_<userId>_<uuid>",
     *     "moolreTxId":     "<moolre-internal-uuid>",  // null if actionRequired
     *     "actionRequired": false,
     *     "message":        "..."
     *   }
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
        boolean actionRequired = false;
        String  actionMessage  = "";

        try {
            chargeResult = moolreDirectCharge(ADMIN_UPGRADE_FEE, phone.toString(), network.toString(), externalRef, null);
        } catch (ActionRequiredException ex) {
            log.warn("initAdminUpgrade: action required for userId='{}' externalRef='{}' — {}",
                    user.getId(), externalRef, ex.getMessage());
            actionRequired = true;
            actionMessage  = ex.getMessage();
            chargeResult   = Map.of();
        } catch (RuntimeException ex) {
            log.error("initAdminUpgrade: Moolre charge failed for userId='{}' externalRef='{}' — {}",
                    user.getId(), externalRef, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Upgrade payment initiation failed. Please try again.");
        }

        String moolreTxId = resolveMoolreTxId(externalRef, chargeResult);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "externalref",    externalRef,
                "moolreTxId",     moolreTxId != null ? moolreTxId : "",
                "actionRequired", actionRequired,
                "message", actionRequired
                        ? actionMessage
                        : chargeResult.getOrDefault("message",
                        "Please approve the USSD prompt on your phone.").toString()
        )));
    }

    // ─── 4. Payment Verification ──────────────────────────────────────────────

    /**
     * Manually verifies a Moolre payment and credits the wallet if successful.
     * Idempotent — safe to poll.
     *
     * Status check ID resolution:
     *   Moolre's /open/transact/status indexes by their internal TX UUID, not our
     *   externalref. Querying by externalref before the transaction is fully
     *   recorded returns status=1 + message="Transaction not found" with no data.
     *
     *   This endpoint resolves the Moolre TX UUID from the pendingCharges cache
     *   (set during step B), and passes that as the query key. If the cache entry
     *   doesn't exist yet (very early poll), it falls back to externalref.
     *
     *   "Transaction not found" is mapped to PENDING so the frontend keeps polling.
     *
     * On deposit success: credits user wallet + attributes 70% commission to
     * referring admin via ReferralService.attributeCommission().
     *
     * Required body fields:
     *   externalref – the reference returned by /init
     *
     * Optional body fields:
     *   moolreTxId  – Moolre's internal UUID (returned by /init or /otp). When
     *                 supplied, status is queried by this ID directly, bypassing
     *                 the cache lookup. Use this when the cache has been cleared
     *                 (e.g. server restart) to still allow correct polling.
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

        // ── Resolve the query ID for /open/transact/status ─────────────────────
        // Moolre indexes by their internal TX UUID (returned in step B `data` field).
        // Querying by externalref returns "Transaction not found" even on success.
        // Priority: (1) caller-supplied moolreTxId, (2) cached UUID, (3) externalref fallback.
        String queryId = ref; // default fallback
        String clientMoolreTxId = req.containsKey("moolreTxId")
                ? req.get("moolreTxId").toString().trim()
                : "";

        if (!clientMoolreTxId.isBlank()) {
            queryId = clientMoolreTxId;
            log.info("verifyPayment: using client-supplied moolreTxId='{}' for externalRef='{}'",
                    queryId, ref);
        } else {
            var pending = pendingCharges.get(ref);
            if (pending != null && pending.moolreTxId() != null && !pending.moolreTxId().isBlank()) {
                queryId = pending.moolreTxId();
                log.info("verifyPayment: using cached moolreTxId='{}' for externalRef='{}'",
                        queryId, ref);
            } else {
                log.info("verifyPayment: no moolreTxId cached yet — falling back to externalRef='{}'", ref);
            }
        }

        var statusResponse = moolreCheckStatus(queryId);

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) statusResponse.get("data");

        // ── "Transaction not found" detection ──────────────────────────────────
        // Moolre returns status=1 + message="Transaction not found" when the USSD
        // charge hasn't been recorded yet (too early to poll). We treat this as
        // PENDING so the frontend keeps retrying rather than surfacing an error.
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

        // ── Also check for "not found" inside data.message ─────────────────────
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
            // Unknown txstatus — treat as pending so the frontend keeps polling.
            // Do not surface this as a terminal error; the transaction may still complete.
            log.warn("verifyPayment: unknown txstatus={} externalRef='{}' — treating as PENDING", txStatus, ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "txstatus", TX_PENDING,
                    "message",  "Payment is still being processed. Please try again shortly."
            )));
        }

        // txstatus = 1 — credit wallet and attribute commission
        var valueStr = resolveAmount(data, ref);
        var amount   = new BigDecimal(valueStr);
        var intent   = parts[0];

        // Clean up cache on confirmed success
        pendingCharges.remove(ref);

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

    // ─── 5. Webhook ───────────────────────────────────────────────────────────

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

            // Clean up pending cache on webhook success
            pendingCharges.remove(ref);

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
     * Credits the depositing user's wallet, then attributes commission to
     * their referring admin.
     *
     * Commission structure:
     *   The referring admin earns a percentage of every deposit made by users
     *   they referred. The rate is stored on the Referral entity and defaults
     *   to 70% of the platform commission (ADMIN_COMMISSION_RATE). Resolution
     *   is handled entirely inside ReferralService.attributeCommission() —
     *   this method just triggers it.
     *
     * Flow:
     *   deposit amount → walletService.credit (user wallet)
     *                  → referralService.attributeCommission (admin affiliate wallet)
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

        // ── Attribute commission to referring admin based on commission structure ──
        // The admin's rate (default 70%) is resolved from the Referral entity inside
        // ReferralService. No rate logic lives here — just trigger attribution.
        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit='{}' adminRate={}",
                    userId, amount, ADMIN_COMMISSION_RATE);
        } catch (Exception ex) {
            // Never block a deposit because of a commission failure
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate",
                    userId, ex);
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
     *      (rate is set inside UserService.upgradeToAdmin)
     *   3. Records an audit transaction (Moolre collected the funds externally)
     *   4. Creates onboarding chat with Super Admin to confirm/adjust the 70% rate
     *
     * Commission structure note:
     *   Super Admin may negotiate a custom rate during the onboarding chat. Any
     *   adjustment must be applied directly to the Referral entity — the rate
     *   stored there is what ReferralService.attributeCommission() uses.
     */
    private boolean handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(ADMIN_UPGRADE_FEE) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'", amount, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            // upgradeToAdmin sets the new admin's commission rate to 70% on the Referral entity
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

        // Audit record — Moolre collected GHS 200 externally, no wallet debit needed
        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "moolre", "reference", ref));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        // Create onboarding chat so Super Admin can confirm/adjust the 70% commission rate
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
     * Phone must be in 0XXXXXXXXX format (e.g. "0244123456"). Moolre rejects
     * the 233 country-code prefix — pass the number through as-is from the frontend.
     *
     * When otpCode is non-null, the `otpcode` field is included in the request body.
     * Per Moolre docs, OTP submission re-calls the same /open/transact/payment
     * endpoint with the original params + otpcode field.
     *
     * OTP two-step flow:
     *   Step A — call with otpcode → Moolre validates OTP, returns
     *             status=1 + message="Phone no. Verification Successful." + data="all"
     *   Step B — call WITHOUT otpcode (same externalref) → Moolre pushes the USSD
     *             prompt to the user's handset and returns their internal TX UUID
     *             in the `data` field.
     *
     * This method detects step-A success via isOtpVerifiedMessage() and
     * automatically performs step B before returning.
     *
     * Moolre TX ID caching (NEW):
     *   After step B, Moolre returns their internal transaction UUID in the `data`
     *   field (either as a plain String or inside a Map). This UUID is stored in
     *   the pendingCharges cache (pendingCharge.moolreTxId) and also returned in
     *   the method result under the "moolreTxId" key.
     *
     *   This ID is the correct key for /open/transact/status — Moolre does NOT
     *   index by externalref for status lookups. Using externalref returns
     *   "Transaction not found" even after approval.
     *
     * On the first call (no OTP), charge params are cached in pendingCharges
     * keyed by externalRef so /otp can reconstruct the full body later.
     *
     * Throws ActionRequiredException when Moolre returns status=1 with a message
     * indicating the user must complete an SMS verification step first.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> moolreDirectCharge(
            BigDecimal amount, String phone, String network, String externalRef, String otpCode) {

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
        if (otpCode != null && !otpCode.isBlank()) {
            body.put("otpcode", otpCode);
            log.info("moolreDirectCharge: including otpcode for externalRef='{}'", externalRef);
        }

        log.info("moolreDirectCharge: calling /open/transact/payment — channel='{}' phone='{}' amount='{}' externalRef='{}' hasOtp={}",
                channel, phone, amount, externalRef, otpCode != null && !otpCode.isBlank());

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

        // Safely coerce `data` — Moolre sometimes returns it as a plain String
        // (notably the Moolre TX UUID after step B).
        Map<String, Object> data;
        Object rawData = result.get("data");
        if (rawData instanceof Map) {
            data = (Map<String, Object>) rawData;
        } else {
            data = new java.util.LinkedHashMap<>();
            if (rawData != null && !rawData.toString().isBlank()) {
                data.put("dataMessage", rawData.toString());
                log.info("moolreDirectCharge: data field is a String (not Map): '{}'", rawData);
            }
        }

        // Hard failure
        if (!"1".equals(status)) {
            log.error("moolreDirectCharge: Moolre error status='{}' message='{}'", status, message);
            throw new RuntimeException("Moolre error: " + message);
        }

        // ── OTP verified (step A) — Moolre confirmed the SMS code.
        //    We must now make a second call WITHOUT otpcode (step B) to actually
        //    push the USSD prompt to the user's handset.
        if (!message.isBlank() && isOtpVerifiedMessage(message)) {
            log.info("moolreDirectCharge: OTP verified ('{}') — triggering USSD push (step B) for externalRef='{}'",
                    message, externalRef);
            return moolreDirectCharge(amount, phone, network, externalRef, null);
        }

        // ── Action required (MTN SMS verification step) — OTP not yet submitted.
        if (!message.isBlank() && isActionRequiredMessage(message)) {
            // Cache charge params so /otp can re-call with same body + otpcode.
            // moolreTxId is null at this stage — step B hasn't run yet.
            if (otpCode == null || otpCode.isBlank()) {
                pendingCharges.put(externalRef,
                        new PendingCharge(amount, phone, network, externalRef, null));
                log.info("moolreDirectCharge: cached pending charge for externalRef='{}'", externalRef);
            }
            log.warn("moolreDirectCharge: action-required message status='{}' message='{}'", status, message);
            throw new ActionRequiredException(message);
        }

        // ── Step B success — Moolre returned their internal TX UUID in `data`.
        //    Extract it and cache/update the pendingCharge entry so /verify can
        //    query status by this UUID instead of the externalref.
        //
        //    Moolre returns the TX UUID either as:
        //      • data = "<uuid-string>"   (plain String, stored in data.dataMessage)
        //      • data = { "id": "<uuid>", ... }  (Map with an "id" key)
        //    We capture whichever form is present.
        String moolreTxId = extractMoolreTxId(rawData, data);
        if (moolreTxId != null) {
            // Update the cache entry (upsert — may not exist if /init had no OTP step)
            pendingCharges.merge(externalRef,
                    new PendingCharge(amount, phone, network, externalRef, moolreTxId),
                    (existing, incoming) -> existing.withMoolreTxId(moolreTxId));
            log.info("moolreDirectCharge: cached moolreTxId='{}' for externalRef='{}'", moolreTxId, externalRef);
            data.put("moolreTxId", moolreTxId); // surface in return value
        } else {
            log.warn("moolreDirectCharge: step B completed but no Moolre TX UUID found in data for externalRef='{}'", externalRef);
        }

        if (!message.isBlank()) {
            var mutable = new java.util.LinkedHashMap<>(data);
            mutable.put("message", message);
            return mutable;
        }

        return data;
    }

    /**
     * Extracts Moolre's internal transaction UUID from the `data` field returned
     * by step B of /open/transact/payment.
     *
     * Moolre returns the UUID in one of two forms:
     *   • Plain String: data = "cb6fe586-cad5-4819-ba35-edce36b0abfe"
     *     → stored in the coerced Map as data.dataMessage
     *   • Map: data = { "id": "cb6fe586-...", ... }
     *     → accessible via data.get("id")
     *
     * Returns null if no UUID-shaped value is found.
     */
    private static String extractMoolreTxId(Object rawData, Map<String, Object> data) {
        // Case 1: plain String (most common for step B per observed responses)
        if (rawData instanceof String s && isUuidShaped(s.trim())) {
            return s.trim();
        }
        // Case 2: coerced dataMessage (set when rawData was a non-map String)
        var dataMsg = data.get("dataMessage");
        if (dataMsg instanceof String s && isUuidShaped(s.trim())) {
            return s.trim();
        }
        // Case 3: Map with "id" key
        var idField = data.get("id");
        if (idField instanceof String s && isUuidShaped(s.trim())) {
            return s.trim();
        }
        return null;
    }

    /** Returns true if the string looks like a UUID (8-4-4-4-12 hex). */
    private static boolean isUuidShaped(String s) {
        if (s == null || s.length() != 36) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Resolves Moolre's internal TX UUID to surface in API responses.
     * Checks the charge result map first, then falls back to the pendingCharges cache.
     * Returns null if not yet available (e.g. actionRequired=true, step B not run).
     */
    private String resolveMoolreTxId(String externalRef, Map<String, Object> chargeResult) {
        // Prefer value set directly in the charge result by moolreDirectCharge
        var fromResult = chargeResult.get("moolreTxId");
        if (fromResult instanceof String s && !s.isBlank()) return s;

        // Fall back to cache
        var pending = pendingCharges.get(externalRef);
        if (pending != null && pending.moolreTxId() != null && !pending.moolreTxId().isBlank()) {
            return pending.moolreTxId();
        }

        return null;
    }

    /**
     * Returns true if Moolre's response confirms the OTP was accepted.
     * After this, the USSD prompt hasn't been pushed yet — a second call
     * to /open/transact/payment WITHOUT otpcode is required (step B).
     * Known responses: "Phone no. Verification Successful." / data = "all"
     */
    private static boolean isOtpVerifiedMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("verification successful")
                || lower.contains("phone no. verification")
                || lower.contains("otp verified")
                || lower.contains("code verified");
    }

    /**
     * Returns true if the Moolre message indicates the subscriber must complete
     * an SMS-based verification step before the USSD prompt is sent.
     */
    private static boolean isActionRequiredMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("verification process")
                || lower.contains("complete the verification")
                || lower.contains("sim registration")
                || lower.contains("register your sim")
                || lower.contains("try again")
                || lower.contains("not eligible");
    }

    /**
     * Calls Moolre POST /open/transact/status to check payment status.
     *
     * @param queryId Moolre's internal TX UUID (preferred) or our externalref (fallback).
     *                Always pass the Moolre UUID when available — Moolre indexes by UUID,
     *                not externalref. Querying by externalref returns "Transaction not found"
     *                even after the USSD has been approved.
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
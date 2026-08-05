package com.speedbet.api.payment.rushPay;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RushPay Core v2 payment gateway controller.
 *
 * Flow (server-side portions only — the browser drives MoMo/card/gift-card funding
 * through a short-lived widget session token that this controller mints and hands out):
 *
 *   1. initDeposit / initAdminUpgrade
 *        POST /api/v1/merchant/payments/create          (X-API-Key)  -> payment_reference
 *        POST /api/v1/merchant/payments/widget-session  (X-API-Key)  -> widget_session_token
 *      We return { payment_reference, widget_session_token } to the frontend, which then
 *      embeds the hosted widget (core.rushpay.cash/widget/payment-widget-v2.js) or calls
 *      the funding endpoints directly with the widget session token.
 *
 *   2. RushPay calls back our webhook (charge/payment success) with a signed body.
 *      We verify the signature, then credit the wallet or promote to admin — keyed by
 *      the metadata.userId we planted at create time, and made idempotent by reference.
 *
 *   3. status (manual fallback / polling) — mirrors the Moolre USSD controller's
 *      verifyPayment() pattern. The frontend polls this after opening the widget.
 *      We call RushPay's own GET /api/v1/merchant/payments/status endpoint directly
 *      (confirmed from RushPay's public API docs) and credit the wallet immediately
 *      if status="completed" and it hasn't been credited yet. This is idempotent —
 *      walletService.credit() throws a 409 (via ApiException) on a duplicate
 *      providerRef, which we catch and treat as "already credited", exactly like the
 *      webhook handler does. This means a lost/delayed webhook no longer strands a
 *      deposit — the poll itself can complete the credit.
 *
 * IMPORTANT — webhook signature:
 *   RushPay's published OpenAPI spec issues a `webhook_secret` at application creation
 *   but does NOT document the signature header name or HMAC algorithm. This controller
 *   mirrors the Paystack convention (HMAC-SHA512 hex over the raw body) but keeps the
 *   header + secret configurable. CONFIRM WITH RUSHPAY SUPPORT BEFORE PRODUCTION.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RushPayController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    /** RushPay payments/status "status" field values we care about. Others (e.g. "pending", "failed") pass through as-is. */
    private static final String STATUS_COMPLETED = "completed";

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * Admins earn 70% of the configured platform commission on each referred deposit.
     * The actual per-admin rate is stored on the Referral entity (set during
     * upgradeToAdmin) and resolved inside ReferralService.attributeCommission().
     * This constant is for logging/documentation purposes only.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    /** RushPay Core amounts are GHS major units as strings, e.g. "149.99" (not pesewas). */
    private static final MathContext MC = MathContext.DECIMAL64;

    /** How long to wait for RushPay to respond before timing out. */
    private final Duration rushpayTimeout = Duration.ofSeconds(10);

    /**
     * How many times to retry on transient network failures (e.g. "Connection reset
     * by peer"). Does NOT retry on RushPay 4xx/5xx — those are mapped to a
     * RuntimeException by the onStatus handler and are therefore excluded from retry.
     */
    private final long rushpayRetryAttempts = 2;

    /**
     * Tracks which user created each payment_reference, purely so the status-poll
     * endpoint can enforce ownership (see depositStatus() javadoc above — RushPay's
     * ref format and status response give us no other way to recover this).
     *
     * Same caveat as Moolre's pendingCharges: in-memory only, keyed by reference,
     * lost on restart and not shared across instances in a multi-instance deployment.
     * A restart just means an in-flight poll gets "Unknown payment reference" and the
     * user has to re-initiate — it does NOT cause a missed credit, since the webhook
     * path is independent of this map. Replace with Redis or a DB column if that
     * restart-window gap needs closing.
     */
    private final ConcurrentHashMap<String, UUID> pendingRefs = new ConcurrentHashMap<>();

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    // Server-side merchant key — NEVER expose to the browser.
    @Value("${app.rushpay.api-key}")                 private String     apiKey;
    // Used only to verify inbound webhook signatures (returned once at app creation).
    @Value("${app.rushpay.webhook-secret}")          private String     webhookSecret;
    // e.g. https://core.rushpay.cash  (docs live on app.rushpay.cash, API on core.*)
    @Value("${app.rushpay.base-url}")                private String     baseUrl;
    // Header RushPay stamps the signature on. Configurable until confirmed with support.
    @Value("${app.rushpay.signature-header:x-rushpay-signature}") private String signatureHeader;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")           private String     frontendUrl;

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/rushpay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        log.info("initDeposit: userId='{}' amount={}", user.getId(), amount);

        var session = createCheckoutAndSession(
                amount,
                "Wallet deposit",
                frontendUrl + "/app/wallet?payment=success",
                Map.of("userId", user.getId().toString())
        );

        log.info("initDeposit: RushPay checkout created ref='{}' for userId='{}'",
                session.get("payment_reference"), user.getId());

        pendingRefs.put(session.get("payment_reference").toString(), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    @PostMapping("/api/user/upgrade-to-admin/rushpay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        log.info("initAdminUpgrade: userId='{}' email='{}'", user.getId(), user.getEmail());

        var amount = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_PESEWAS)
                .divide(BigDecimal.valueOf(100), MC); // -> GHS 200 major units

        var session = createCheckoutAndSession(
                amount,
                "Admin upgrade fee",
                frontendUrl + "/app/upgrade?payment=success",
                Map.of(
                        "userId",        user.getId().toString(),
                        "upgradeIntent", UPGRADE_INTENT_ADMIN
                )
        );

        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    // ─── Status (manual fallback / polling) ───────────────────────────────────

    /**
     * Polled by the frontend after the widget is opened (this is the endpoint that
     * was previously missing, causing every poll to 404 → surfaced to the frontend
     * as a generic 500 "An internal error occurred").
     *
     * Mirrors MoolreUSSDPayment#verifyPayment(): calls the provider's own status
     * endpoint directly and credits immediately if confirmed, rather than only
     * trusting our own transaction table (which would stay empty forever if the
     * webhook never arrived).
     *
     * Required query param:
     *   ref – the payment_reference returned by /init
     *
     * Response body (data):
     *   { "credited": bool, "status": "pending"|"completed"|"failed"|..., "message": "..." }
     *
     * credited=true on this call means THIS call performed the credit just now.
     * credited=false with status="completed" means it was already credited earlier
     * (by the webhook or a prior poll) — not an error, just already-done.
     *
     * IMPORTANT — ownership check:
     *   Unlike Moolre's externalref ("deposit_<userId>_<uuid>"), RushPay's
     *   payment_reference does not encode the userId, and RushPay's status
     *   response doesn't echo back our create-time metadata either — so we cannot
     *   recover which user a bare ref belongs to from RushPay's response alone.
     *   To avoid crediting the caller's own account for someone else's ref (or
     *   leaking another user's payment status), we require the deposit to have
     *   been initiated by THIS user in THIS server instance: initDeposit() below
     *   stashes payment_reference -> userId in pendingRefs at creation time, and
     *   this endpoint checks the ref belongs to the calling user before doing
     *   anything else. This mirrors the ownership check in Moolre's verifyPayment
     *   (which parses userId out of the ref) — same guarantee, different mechanism
     *   because RushPay's ref format gives us nothing to parse.
     */
    @GetMapping("/api/wallet/deposit/rushpay/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> depositStatus(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String ref) {

        if (ref == null || ref.isBlank())
            throw ApiException.badRequest("ref is required.");

        var owner = pendingRefs.get(ref);
        if (owner == null) {
            // Either this ref was never created by us, or this server instance
            // restarted and lost the in-memory map (see pendingRefs caveat below).
            log.error("depositStatus: unknown ref='{}' requested by userId='{}'", ref, user.getId());
            throw ApiException.badRequest("Unknown payment reference. Please start a new deposit.");
        }
        if (!owner.equals(user.getId())) {
            log.warn("depositStatus: userId='{}' attempted to poll ref='{}' owned by userId='{}'",
                    user.getId(), ref, owner);
            throw ApiException.forbidden("This payment reference does not belong to your account.");
        }

        log.info("depositStatus: userId='{}' ref='{}'", user.getId(), ref);

        var statusResponse = rushpayGetStatus(ref);

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) statusResponse.get("data");
        var status = data != null ? String.valueOf(data.get("status")) : "unknown";

        if (!STATUS_COMPLETED.equals(status)) {
            // "pending", "failed", or anything else RushPay might return — not an
            // error on our side, just not credited (yet, or ever).
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "status",   status,
                    "message",  "pending".equals(status)
                            ? "Payment is still pending."
                            : "Payment was not completed (status: " + status + ")."
            )));
        }

        // status = "completed" — credit if not already credited.
        var amountStr = data.get("amount");
        if (amountStr == null || amountStr.toString().isBlank())
            throw ApiException.badRequest("RushPay status response is missing 'amount' for ref='" + ref + "'");

        var amount = new BigDecimal(amountStr.toString());

        boolean credited = verifyAndHandleDeposit(owner, ref, amount);
        // Clean up — confirmed terminal state, no need to keep tracking this ref.
        pendingRefs.remove(ref);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "credited", credited,
                "status",   STATUS_COMPLETED,
                "message",  credited
                        ? "Payment verified. GHS " + amount + " has been added to your wallet."
                        : "Payment was already processed."
        )));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/rushpay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-rushpay-signature", required = false) String signature,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("RushPay webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("RushPay webhook: missing {} header", signatureHeader);
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("RushPay webhook: invalid signature received");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            // RushPay envelopes typically look like { "event": "...", "data": { ... } }.
            // Adjust the event name / nesting once confirmed against a real payload.
            var eventType = String.valueOf(event.get("event"));
            log.info("RushPay webhook: received event='{}'", eventType);

            // Accept the terminal success events; ignore intermediate/other events.
            if (!isSuccessEvent(eventType)) {
                log.info("RushPay webhook: ignoring event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("RushPay webhook: missing data block");
                return ResponseEntity.status(400).body("Missing data");
            }

            // We planted metadata.userId at create time; RushPay echoes merchant metadata back.
            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) data.get("metadata");

            if (metadata == null || metadata.get("userId") == null) {
                log.error("RushPay webhook: missing userId in metadata, ref='{}'",
                        data.get("payment_reference"));
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            var userId        = UUID.fromString(metadata.get("userId").toString());
            var ref           = resolveReference(data);
            var amount        = new BigDecimal(String.valueOf(data.get("amount"))); // GHS major units
            var upgradeIntent = String.valueOf(metadata.getOrDefault("upgradeIntent", ""));

            if (UPGRADE_INTENT_ADMIN.equals(upgradeIntent)) {
                handleAdminUpgrade(userId, ref, amount);
            } else {
                handleDeposit(userId, ref, amount);
            }

        } catch (ApiException e) {
            log.error("RushPay webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("RushPay webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    /**
     * RushPay's success event name isn't fixed in the published spec. We accept the common
     * shapes rather than hard-coding one string, so a rename doesn't silently drop credits.
     * Tighten this to the exact event once confirmed.
     */
    private boolean isSuccessEvent(String eventType) {
        if (eventType == null) return false;
        var e = eventType.toLowerCase();
        return e.contains("success") || e.contains("completed") || e.contains("paid");
    }

    /** RushPay's own reference for this charge — fall back across the likely field names. */
    private String resolveReference(Map<String, Object> data) {
        Object ref = data.get("payment_reference");
        if (ref == null) ref = data.get("reference");
        if (ref == null) ref = data.get("charge_reference");
        if (ref == null) throw ApiException.badRequest("Webhook data has no reference");
        return ref.toString();
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission
     * to their referrer (if they were referred).
     *
     * Commission structure:
     *   The referring admin earns a percentage of every deposit made by users
     *   they referred. The rate is stored on the Referral entity and defaults
     *   to 70% of the platform commission. Resolution is handled entirely inside
     *   ReferralService.attributeCommission() — this method just triggers it.
     *
     * Flow:
     *   deposit amount → walletService.credit (user wallet)
     *                  → referralService.attributeCommission (admin affiliate wallet)
     *
     * Returns true if this call performed the credit, false if it was already
     * processed previously (idempotent — safe for webhook retries and for the
     * status-poll endpoint to call redundantly with the webhook).
     */
    private boolean handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "rushpay", "reference", ref));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'",
                    amount, userId, ref);
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

    /** Named to mirror MoolreUSSDPayment's verifyAndHandleDeposit — same intent, called from the poll path. */
    private boolean verifyAndHandleDeposit(UUID userId, String ref, BigDecimal amount) {
        return handleDeposit(userId, ref, amount);
    }

    /**
     * Handles an admin upgrade payment.
     *
     * Steps:
     *   1. Validates amount >= GHS 200
     *   2. Promotes user to ADMIN + initialises their referral link at 70% commission
     *   3. Records an audit transaction (RushPay already collected the funds externally)
     *   4. Creates onboarding chat with Super Admin for commission confirmation
     *
     * Commission structure note:
     *   The new admin's default commission rate is set to 70% inside
     *   UserService.upgradeToAdmin(). Super Admin can adjust the rate via the
     *   onboarding chat created in step 4.
     */
    private void handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(BigDecimal.valueOf(200)) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'",
                    amount, userId, ref);
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
                return;
            }
            throw ex;
        }

        // Audit record — RushPay collected GHS 200 externally, no wallet debit needed
        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "rushpay", "reference", ref));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        // Create onboarding chat so Super Admin can confirm/adjust the 70% commission rate
        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── RushPay API helpers ──────────────────────────────────────────────────

    /**
     * Two server-side calls, chained:
     *   1. POST /api/v1/merchant/payments/create         -> payment_reference
     *   2. POST /api/v1/merchant/payments/widget-session -> widget_session_token
     *
     * Returns a compact map for the frontend:
     *   { "payment_reference": "...", "widget_session_token": "..." }
     *
     * The browser then either mounts the hosted widget or calls the funding endpoints
     * (initiate-mobile-money, submit-momo-otp, charge-status) with the widget token.
     */
    private Map<String, Object> createCheckoutAndSession(BigDecimal amount, String description,
                                                         String callbackUrl,
                                                         Map<String, Object> metadata) {

        var create = rushpayPost("/api/v1/merchant/payments/create", Map.of(
                "amount",       amount.toPlainString(),   // GHS major units, string
                "description",  description,
                "callback_url", callbackUrl,
                "metadata",     metadata
        ));

        var paymentReference = extractString(create, "payment_reference");
        if (paymentReference == null)
            throw new RuntimeException("RushPay create returned no payment_reference.");

        var sessionResp = rushpayPost("/api/v1/merchant/payments/widget-session", Map.of(
                "payment_reference", paymentReference
        ));

        var widgetSessionToken = extractString(sessionResp, "widget_session_token");
        if (widgetSessionToken == null)
            throw new RuntimeException("RushPay widget-session returned no token.");

        return Map.of(
                "payment_reference",    paymentReference,
                "widget_session_token", widgetSessionToken
        );
    }

    /**
     * Calls a RushPay merchant endpoint with X-API-Key auth and returns the FULL
     * response envelope: { "success": true, "data": { ... } }.
     *
     * Resilience (mirrors the Paystack helper):
     *   - Times out after 10 seconds so the caller thread is never held indefinitely.
     *   - Retries up to 2 times on transient network errors only. Retries do NOT fire
     *     on RushPay 4xx/5xx — those are mapped to a RuntimeException by the onStatus
     *     handler, which excludes them from the retry predicate.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rushpayPost(String path, Map<String, Object> body) {

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + path)
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("RushPay API error: path={} status={} body={}",
                                            path, clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "RushPay returned " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(Map.class)
                // Fail fast: don't hold a thread longer than rushpayTimeout.
                // TimeoutException is a network-level error and will be picked up by retry.
                .timeout(rushpayTimeout)
                // Retry on transient network failures only. RuntimeExceptions thrown directly
                // by onStatus (RushPay 4xx/5xx) have no wrapped cause, so they're excluded
                // by the filter and surface immediately without retrying.
                .retryWhen(Retry.max(rushpayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("RushPay API unreachable after {} retries", rushpayRetryAttempts, ex);
                            return new RuntimeException("RushPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("RushPay returned an empty response.");
        }

        log.info("rushpayPost: path={} success='{}' message='{}'",
                path, result.get("success"), result.get("message"));

        // RushPay uses { "success": false, "message": "..." } for logical failures.
        if (Boolean.FALSE.equals(result.get("success"))) {
            var message = String.valueOf(result.getOrDefault("message", "RushPay declined the request"));
            log.error("rushpayPost: success=false — {}", message);
            throw new RuntimeException("RushPay error: " + message);
        }

        return result;
    }

    /**
     * Calls RushPay GET /api/v1/merchant/payments/status?payment_reference=... to
     * confirm payment status directly with the provider.
     *
     * Confirmed from RushPay's public API docs (Payments — Status):
     *   GET https://core.rushpay.cash/api/v1/merchant/payments/status?payment_reference=...
     *   Auth: X-API-Key
     *   200 response: { "success": true, "data": { "payment_reference": "...",
     *                    "status": "completed", "amount": "149.99", "paid_at": "..." } }
     *
     * Same resilience shape as rushpayPost: 10s timeout, retry only on transient
     * network failures, RushPay 4xx/5xx surfaced immediately without retry.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rushpayGetStatus(String paymentReference) {

        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/api/v1/merchant/payments/status?payment_reference=" + paymentReference)
                .header("X-API-Key", apiKey)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("RushPay status API error: ref={} status={} body={}",
                                            paymentReference, clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "RushPay returned " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(rushpayTimeout)
                .retryWhen(Retry.max(rushpayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("RushPay status API unreachable after {} retries", rushpayRetryAttempts, ex);
                            return new RuntimeException("RushPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("RushPay status endpoint returned an empty response.");
        }

        log.info("rushpayGetStatus: ref='{}' success='{}'", paymentReference, result.get("success"));

        if (Boolean.FALSE.equals(result.get("success"))) {
            var message = String.valueOf(result.getOrDefault("message", "RushPay declined the status request"));
            log.error("rushpayGetStatus: success=false ref='{}' — {}", paymentReference, message);
            throw new RuntimeException("RushPay error: " + message);
        }

        return result;
    }

    /** Pull a field out of the { success, data:{...} } envelope, tolerating a flat shape. */
    @SuppressWarnings("unchecked")
    private String extractString(Map<String, Object> envelope, String key) {
        Object dataObj = envelope.get("data");
        if (dataObj instanceof Map<?, ?> data && data.get(key) != null) {
            return data.get(key).toString();
        }
        // Fall back to top level in case the envelope isn't nested.
        return envelope.get(key) != null ? envelope.get(key).toString() : null;
    }

    // ─── Signature verification ───────────────────────────────────────────────

    /**
     * Verifies the webhook signature as HMAC-SHA512 hex over the raw request body,
     * keyed by the webhook_secret — the same construction Paystack uses.
     *
     * NOTE: RushPay's published spec does not confirm the algorithm or header. If RushPay
     * signs differently (e.g. HMAC-SHA256, base64 encoding, or a signed timestamp+body),
     * update this method and `signatureHeader` accordingly. Constant-time comparison is
     * used to avoid leaking the expected digest via timing.
     */
    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return constantTimeEquals(expected, signature.trim());
        } catch (Exception e) {
            log.error("RushPay webhook: signature verification error", e);
            return false;
        }
    }

    /** Length-aware constant-time string comparison. */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        var ab = a.getBytes(StandardCharsets.UTF_8);
        var bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
        return diff == 0;
    }
}
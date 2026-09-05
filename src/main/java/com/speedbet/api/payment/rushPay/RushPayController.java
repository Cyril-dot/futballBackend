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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RushPay Core v2 payment gateway controller.
 *
 * ARCHITECTURE — full backend integration
 * ─────────────────────────────────────────────────────────────────────────────────────────
 * Per RushPay's published API docs (https://rushpay.cash/api-docs), the endpoints used to
 * fund a mobile money charge — initiate-mobile-money, submit-momo-otp, charge-status — are
 * documented as "Auth: Widget", i.e. they only require the X-RushPay-Widget-Session header.
 * Nothing in the docs restricts that header to browser-origin requests, so this controller
 * mints the widget session AND calls initiate-mobile-money itself, server-to-server. The
 * browser never talks to RushPay Core directly and never sees the widget session token.
 *
 * FLOW:
 *   1. Browser calls POST /api/wallet/deposit/rushpay/init-momo with { amount, phone, provider }.
 *   2. Backend (X-API-Key) creates a checkout, mints a widget session, then (X-RushPay-Widget-Session)
 *      calls initiate-mobile-money itself. Returns { payment_reference, status } to the browser.
 *   3. Browser polls GET /api/wallet/deposit/rushpay/status?ref=... (X-API-Key, server-to-server)
 *      to check payment status and credit the wallet.
 *   4. RushPay webhook POST /api/webhooks/rushpay → verify signature → credit wallet / upgrade admin.
 *
 * NOTE: an earlier version of this integration tried the same server-side proxy and got
 * "Invalid or expired access token" (HTTP 400) from RushPay on the widget-session-authenticated
 * calls. That failure was never conclusively diagnosed — it may have been a stale/reused
 * session, a header issue, or a real browser-binding restriction RushPay doesn't document.
 * If /init-momo starts failing again with an auth/session error specifically (as opposed to a
 * plain validation error like "phone and provider are required"), that's real evidence the
 * session is browser-bound, and the initiate-mobile-money call should move back to the browser
 * (X-RushPay-Widget-Session called directly from client JS) while this endpoint just returns
 * payment_reference + widget_session_token as it did before.
 *
 * The legacy deposit-init endpoint below (/init) is kept as-is for that fallback path and for
 * the admin-upgrade flow, which still hands the widget session to the browser.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RushPayController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    private static final String STATUS_COMPLETED    = "completed";
    private static final String STATUS_RESP_SUCCESS = "success";
    private static final String STATUS_RESP_FAILED  = "failed";

    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");
    private static final MathContext MC = MathContext.DECIMAL64;

    private final Duration rushpayTimeout      = Duration.ofSeconds(10);
    private final long     rushpayRetryAttempts = 2;

    /**
     * Tracks payment_reference → userId so depositStatus() can enforce ownership.
     * In-memory only; lost on restart (restart just means the user re-initiates).
     * The webhook path is independent and still guarantees eventual credit.
     */
    private final ConcurrentHashMap<String, UUID> pendingRefs = new ConcurrentHashMap<>();

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    @Value("${app.rushpay.api-key}")                 private String     apiKey;
    @Value("${app.rushpay.webhook-secret}")          private String     webhookSecret;
    @Value("${app.rushpay.base-url}")                private String     baseUrl;
    @Value("${app.rushpay.signature-header:x-rushpay-signature}") private String signatureHeader;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")           private String     frontendUrl;

    // ─── Deposit Init (legacy / fallback — browser calls RushPay Core directly) ─

    /**
     * Creates a RushPay checkout and a widget session, returns both to the browser.
     * Kept as a fallback: if /init-momo below turns out to be blocked by a real
     * browser-binding restriction on widget sessions, the frontend can go back to
     * calling RushPay Core directly with this payment_reference + widget_session_token.
     *
     * Response: { payment_reference: "API...", widget_session_token: "..." }
     */
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

        var ref = session.get("payment_reference").toString();
        log.info("initDeposit: checkout created ref='{}' userId='{}'", ref, user.getId());

        pendingRefs.put(ref, user.getId());

        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    // ─── Mobile Money Deposit — full backend integration ───────────────────────

    /**
     * Does create → widget-session → initiate-mobile-money, entirely server-side.
     * The browser only ever talks to our backend for this flow.
     *
     * Request body: { "amount": "300", "phone": "0244123456", "provider": "MTN" }
     * ("network" is also accepted as an alias for "provider" for frontend compat.)
     *
     * Response: { "payment_reference": "API...", "status": "requires_action" | "pending" | "completed" | ... }
     */
    @PostMapping("/api/wallet/deposit/rushpay/init-momo")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initMobileMoneyDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (req.get("amount") == null)
            throw ApiException.badRequest("amount is required.");

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var phoneRaw = String.valueOf(req.getOrDefault("phone", "")).trim();
        var provider = String.valueOf(req.getOrDefault("provider", req.getOrDefault("network", ""))).trim();

        if (phoneRaw.isBlank())
            throw ApiException.badRequest("phone is required.");
        if (provider.isBlank())
            throw ApiException.badRequest("provider (network) is required.");

        var phone = normalizeGhMsisdn(phoneRaw);

        log.info("initMobileMoneyDeposit: userId='{}' amount={} provider='{}'", user.getId(), amount, provider);

        // Step 1+2: create checkout, mint widget session (X-API-Key, server-to-server)
        var session     = createCheckoutAndSession(
                amount,
                "Wallet deposit",
                frontendUrl + "/app/wallet?payment=success",
                Map.of("userId", user.getId().toString())
        );
        var ref         = session.get("payment_reference").toString();
        var widgetToken = session.get("widget_session_token").toString();

        pendingRefs.put(ref, user.getId());
        log.info("initMobileMoneyDeposit: checkout+session ready ref='{}' userId='{}'", ref, user.getId());

        // Step 3: initiate-mobile-money, server-side, using the widget session.
        Map<String, Object> initiateResp;
        try {
            initiateResp = rushpayWidgetPost(
                    "/api/v1/merchant/payments/initiate-mobile-money",
                    widgetToken,
                    Map.of(
                            "payment_reference", ref,
                            "phone",             phone,
                            "provider",          provider
                    )
            );
        } catch (RuntimeException ex) {
            // Clean up the tracked ref if funding never actually started, so the
            // user isn't left with a dangling pending reference they can't reuse.
            pendingRefs.remove(ref);
            log.error("initMobileMoneyDeposit: initiate-mobile-money failed ref='{}' — {}", ref, ex.getMessage());
            throw ex;
        }

        @SuppressWarnings("unchecked")
        var data      = (Map<String, Object>) initiateResp.getOrDefault("data", initiateResp);
        var rawStatus = String.valueOf(data.getOrDefault("status", "requires_action")).toLowerCase();

        log.info("initMobileMoneyDeposit: ref='{}' rushpay status='{}'", ref, rawStatus);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "payment_reference", ref,
                "status",            rawStatus
        )));
    }

    // ─── Admin Upgrade Init (unchanged — browser still calls RushPay Core directly) ─

    @PostMapping("/api/user/upgrade-to-admin/rushpay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        log.info("initAdminUpgrade: userId='{}' email='{}'", user.getId(), user.getEmail());

        var amount = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_PESEWAS)
                .divide(BigDecimal.valueOf(100), MC);

        var session = createCheckoutAndSession(
                amount,
                "Admin upgrade fee",
                frontendUrl + "/app/upgrade?payment=success",
                Map.of(
                        "userId",        user.getId().toString(),
                        "upgradeIntent", UPGRADE_INTENT_ADMIN
                )
        );

        var ref = session.get("payment_reference").toString();
        pendingRefs.put(ref, user.getId());

        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    // ─── Status (polling endpoint — called by the browser after RushPay funding) ─

    /**
     * Polled by the frontend after RushPay confirms payment on the browser side.
     * Uses X-API-Key (server-to-server) — not a widget session — so RushPay
     * accepts it unconditionally.
     *
     * Returns:
     *   { credited: bool, status: "pending"|"success"|"failed", message: "..." }
     *
     * credited=true  → this call performed the credit
     * credited=false + status="success" → already credited by webhook or prior poll
     */
    @GetMapping("/api/wallet/deposit/rushpay/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> depositStatus(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String ref) {

        if (ref == null || ref.isBlank())
            throw ApiException.badRequest("ref is required.");

        var owner = pendingRefs.get(ref);
        if (owner == null) {
            log.error("depositStatus: unknown ref='{}' userId='{}'", ref, user.getId());
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
            boolean looksTerminal = status.contains("fail") || status.contains("cancel")
                    || status.contains("declin") || status.contains("error") || status.equals("unknown");
            String mappedStatus = looksTerminal ? STATUS_RESP_FAILED : "pending";
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "credited", false,
                    "status",   mappedStatus,
                    "message",  "pending".equals(mappedStatus)
                            ? "Payment is still pending."
                            : "Payment was not completed (status: " + status + ")."
            )));
        }

        var amountStr = data.get("amount");
        if (amountStr == null || amountStr.toString().isBlank())
            throw ApiException.badRequest("RushPay status response missing 'amount' for ref='" + ref + "'");

        var amount = new BigDecimal(amountStr.toString());
        boolean credited = verifyAndHandleDeposit(owner, ref, amount);

        pendingRefs.remove(ref);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "credited", credited,
                "status",   STATUS_RESP_SUCCESS,
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

            var eventType = String.valueOf(event.get("event"));
            log.info("RushPay webhook: received event='{}'", eventType);

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

            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) data.get("metadata");

            if (metadata == null || metadata.get("userId") == null) {
                log.error("RushPay webhook: missing userId in metadata, ref='{}'",
                        data.get("payment_reference"));
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            var userId        = UUID.fromString(metadata.get("userId").toString());
            var ref           = resolveReference(data);
            var amount        = new BigDecimal(String.valueOf(data.get("amount")));
            var upgradeIntent = String.valueOf(metadata.getOrDefault("upgradeIntent", ""));

            if (UPGRADE_INTENT_ADMIN.equals(upgradeIntent)) {
                handleAdminUpgrade(userId, ref, amount);
            } else {
                handleDeposit(userId, ref, amount);
            }

            pendingRefs.remove(ref);

        } catch (ApiException e) {
            log.error("RushPay webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("RushPay webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    private boolean isSuccessEvent(String eventType) {
        if (eventType == null) return false;
        var e = eventType.toLowerCase();
        return e.contains("success") || e.contains("completed") || e.contains("paid");
    }

    private String resolveReference(Map<String, Object> data) {
        Object ref = data.get("payment_reference");
        if (ref == null) ref = data.get("reference");
        if (ref == null) ref = data.get("charge_reference");
        if (ref == null) throw ApiException.badRequest("Webhook data has no reference");
        return ref.toString();
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    private boolean handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "rushpay", "reference", ref));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'", amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' — skipping", ref);
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

    private void handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(BigDecimal.valueOf(200)) < 0) {
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
                return;
            }
            throw ex;
        }

        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "rushpay", "reference", ref));

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── RushPay API helpers ──────────────────────────────────────────────────

    /**
     * Creates a RushPay checkout then a widget session.
     * Returns { payment_reference, widget_session_token } for the frontend.
     */
    private Map<String, Object> createCheckoutAndSession(BigDecimal amount, String description,
                                                         String callbackUrl,
                                                         Map<String, Object> metadata) {
        var create = rushpayPost("/api/v1/merchant/payments/create", Map.of(
                "amount",       amount.toPlainString(),
                "description",  description,
                "callback_url", callbackUrl,
                "metadata",     metadata
        ));

        var paymentReference = extractString(create, "payment_reference");
        if (paymentReference == null)
            throw new RuntimeException("RushPay create returned no payment_reference.");

        log.info("createCheckoutAndSession: ref='{}' calling widget-session", paymentReference);

        var sessionResp = rushpayPost("/api/v1/merchant/payments/widget-session", Map.of(
                "payment_reference", paymentReference
        ));

        var widgetSessionToken = extractString(sessionResp, "widget_session_token");
        if (widgetSessionToken == null)
            throw new RuntimeException("RushPay widget-session returned no token.");

        log.info("createCheckoutAndSession: widget session minted for ref='{}'", paymentReference);

        return Map.of(
                "payment_reference",    paymentReference,
                "widget_session_token", widgetSessionToken
        );
    }

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
                .timeout(rushpayTimeout)
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

        if (result == null)
            throw new RuntimeException("RushPay returned an empty response.");

        log.info("rushpayPost: path={} success='{}' message='{}'",
                path, result.get("success"), result.get("message"));

        if (Boolean.FALSE.equals(result.get("success"))) {
            var message = String.valueOf(result.getOrDefault("message", "RushPay declined the request"));
            log.error("rushpayPost: success=false — {}", message);
            throw new RuntimeException("RushPay error: " + message);
        }

        return result;
    }

    /**
     * Same request pattern as rushpayPost, but authenticates with the widget
     * session header instead of X-API-Key — for endpoints RushPay's docs mark
     * "Auth: Widget" (initiate-mobile-money, submit-momo-otp, charge-status, pay).
     * No retry-on-error here: retrying a mobile-money initiate on ambiguous
     * failure risks sending the customer two MoMo prompts for one deposit.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rushpayWidgetPost(String path, String widgetSessionToken, Map<String, Object> body) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + path)
                .header("X-RushPay-Widget-Session", widgetSessionToken)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(b -> {
                                    log.error("RushPay widget API error: path={} status={} body={}",
                                            path, clientResponse.statusCode(), b);
                                    return new RuntimeException(
                                            "RushPay returned " + clientResponse.statusCode() + ": " + b);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(rushpayTimeout)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("RushPay widget API unreachable: path={}", path, ex);
                            return new RuntimeException("RushPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null)
            throw new RuntimeException("RushPay returned an empty response.");

        log.info("rushpayWidgetPost: path={} success='{}' message='{}'",
                path, result.get("success"), result.get("message"));

        if (Boolean.FALSE.equals(result.get("success"))) {
            var message = String.valueOf(result.getOrDefault("message", "RushPay declined the request"));
            log.error("rushpayWidgetPost: success=false path={} — {}", path, message);
            throw new RuntimeException("RushPay error: " + message);
        }

        return result;
    }

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

        if (result == null)
            throw new RuntimeException("RushPay status endpoint returned an empty response.");

        log.info("rushpayGetStatus: ref='{}' success='{}'", paymentReference, result.get("success"));

        if (Boolean.FALSE.equals(result.get("success"))) {
            var message = String.valueOf(result.getOrDefault("message", "RushPay declined the status request"));
            log.error("rushpayGetStatus: success=false ref='{}' — {}", paymentReference, message);
            throw new RuntimeException("RushPay error: " + message);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private String extractString(Map<String, Object> envelope, String key) {
        Object dataObj = envelope.get("data");
        if (dataObj instanceof Map<?, ?> data && data.get(key) != null) {
            return data.get(key).toString();
        }
        return envelope.get(key) != null ? envelope.get(key).toString() : null;
    }

    /**
     * Normalizes a Ghanaian mobile number to the local MSISDN form RushPay's
     * examples use elsewhere in the docs (no leading '+', includes country
     * code): "0244123456" -> "233244123456", "+233244123456" -> "233244123456",
     * "233244123456" -> unchanged.
     */
    private String normalizeGhMsisdn(String raw) {
        var digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("233")) return digits;
        if (digits.startsWith("0"))   return "233" + digits.substring(1);
        return "233" + digits;
    }

    // ─── Signature verification ───────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var expected = java.util.HexFormat.of().formatHex(mac.doFinal(rawBody));
            return constantTimeEquals(expected, signature.trim());
        } catch (Exception e) {
            log.error("RushPay webhook: signature verification error", e);
            return false;
        }
    }

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
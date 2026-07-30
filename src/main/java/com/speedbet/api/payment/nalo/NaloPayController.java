package com.speedbet.api.payment.nalo;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles Ghanaian deposit payments via NALOPAY (NALO Solutions Limited).
 *
 * Base URL: https://api.nalopay.com
 *
 * Two NALOPAY products are used:
 *
 *   NALOPAY PAYMENT API V1   — direct Mobile Money collection (approval prompt
 *                              on the customer's handset).
 *   NALOPAY CHECKOUT API V1  — hosted checkout page for Card and Bank.
 *
 * Structured to mirror {@code PaystackController} and
 * {@code PaystackMobileMoneyController} — same init → callback → credit →
 * attribute-commission pipeline, same idempotency-by-reference, same
 * "commission failure never blocks a deposit" rule, same admin-upgrade handler.
 *
 * ══ How NALOPAY differs from Paystack ══════════════════════════════════════
 *
 *  1. TWO-STEP AUTH, SHORT-LIVED TOKEN.
 *     Paystack uses a static bearer secret key. NALOPAY requires a JWT minted
 *     from a Basic Auth credential, and that JWT lives ~15 minutes
 *     (exp - iat = 900s on every observed token). {@link #currentToken()}
 *     caches it and refreshes 60s before expiry.
 *
 *     Three distinct credentials, three distinct jobs — do not mix them up:
 *       basic-auth  → Authorization header, ONLY on /generate-payment-token/
 *       JWT         → token header, on /collection/ and /checkout/session/
 *       secret-key  → NEVER transmitted. HMAC key for trans_hash only.
 *
 *  2. REQUESTS ARE SIGNED, CALLBACKS ARE NOT.
 *     Every charge carries a {@code trans_hash} = HMAC-SHA256 over concatenated
 *     fields. But the inbound callback has no signature at all — the exact
 *     inverse of Paystack, which signs the webhook and not the request.
 *     Our replacement for {@code x-paystack-signature} is a shared secret in
 *     the callback URL path, since we control that URL (it ships on every
 *     charge as {@code callback} / {@code callback_url}).
 *
 *  3. METADATA ROUND-TRIPS, BUT VIA TWO DIFFERENT KEYS.
 *     Collection: {@code extra_data} on the request → {@code extra_data} on the
 *                 callback, verbatim.
 *     Checkout:   {@code extra_data} on the callback is occupied by the order
 *                 summary, so our metadata rides on
 *                 {@code summary.products[0].metadata} → comes back at
 *                 {@code extra_data.products[0].metadata}.
 *     {@link #extractMetadata} handles both shapes.
 *
 *  4. THE CALLBACK DOES NOT ECHO OUR REFERENCE.
 *     It carries NALOPAY's {@code order_id}, not the {@code reference} we sent.
 *     So {@code order_id} is the wallet idempotency key — it is stable across
 *     callback retries and unique per transaction. Our own reference is carried
 *     inside the metadata for cross-referencing in support tickets.
 *
 *  5. AMOUNTS ARE CEDIS, NOT PESEWAS.
 *     Paystack wants integer pesewas (GHS 1.00 → 100). NALOPAY wants a decimal
 *     cedi amount (GHS 1.00 → 1.00).
 *
 * ── Mobile Money flow ──────────────────────────────────────────────────────
 *
 *   Step 1 — POST /clientapi/generate-payment-token/   (Basic → JWT)
 *   Step 2 — POST /clientapi/collection/               (JWT + trans_hash)
 *            → 201, data.status = "PENDING", data.order_id, data.otp_code
 *            The customer receives an approval prompt. Nothing further from
 *            the backend; wait for the callback.
 *   Step 3 — Callback → status "COMPLETED" | "FAILED"
 *   Step 4 — POST /clientapi/collection-status/        (fallback polling)
 *
 * ── Card / Bank flow (hosted checkout) ─────────────────────────────────────
 *
 *   We never accept raw PAN/CVV/expiry, and never raw bank credentials. That
 *   would pull this service into PCI-DSS SAQ D scope. We create a checkout
 *   session, get back {@code checkout_url}, and redirect the browser there.
 *   NALOPAY hosts the card entry and any 3DS/OTP/PIN challenge.
 *
 *   Step 1 — POST /clientapi/generate-payment-token/
 *   Step 2 — POST /checkout/session/  → 201, data.checkout_url (1800s TTL)
 *   Step 3 — Callback, same handler as MoMo
 *
 * NALOPAY networks: MTN | AT | TELECEL   (note: NOT Paystack's mtn/atl/vod)
 * Amount unit: cedis, 2 dp.
 *
 * ⚠ ATTRIBUTION DEPENDS ENTIRELY ON METADATA ROUND-TRIPPING.
 *   The callback's only carriers are {@code order_id} (NALOPAY's, unknown at
 *   init) and the metadata block. The status endpoint returns status + amount
 *   only. With no local persistence table, a callback that arrives without
 *   metadata CANNOT be attributed to a user — it is logged loudly and skipped
 *   rather than credited to the wrong wallet. If that ever fires in production,
 *   a small nalopay_intent table is the correct fix.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NaloPayController {

    // ─── Constants ────────────────────────────────────────────────────────────

    private static final BigDecimal ADMIN_UPGRADE_FEE_GHS = new BigDecimal("200");

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * Admins earn 70% of the configured platform commission on each referred deposit.
     * The actual per-admin rate is stored on the Referral entity (set during
     * upgradeToAdmin) and resolved inside ReferralService.attributeCommission().
     * This constant is for logging/documentation purposes only.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    private static final String INTENT_DEPOSIT       = "DEPOSIT";
    private static final String INTENT_ADMIN_UPGRADE = "ADMIN_UPGRADE";

    private static final String SERVICE_MOMO = "MOMO_TRANSACTION";

    /** NALOPAY network codes. Distinct from Paystack's mtn/atl/vod. */
    private static final Set<String> VALID_NETWORKS = Set.of("MTN", "AT", "TELECEL");

    /** Ghana network prefixes — early mismatch warnings only, never a hard block. */
    private static final Set<String> MTN_PREFIXES     = Set.of("024", "025", "053", "054", "055", "059");
    private static final Set<String> AT_PREFIXES      = Set.of("026", "027", "056", "057");
    private static final Set<String> TELECEL_PREFIXES = Set.of("020", "050");

    /** Hosted checkout modes. */
    private static final Set<String> VALID_MODES = Set.of("MOMO", "CARD", "BANK", "ANY");

    /** Terminal callback statuses. */
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED    = "FAILED";

    /** NALOPAY response codes, used for assertion + log correlation. */
    private static final String CODE_TOKEN_CREATED    = "TOKEN-CRTD-0050";
    private static final String CODE_COLLECTION_CREATED = "PAY-CRTD-0055";
    private static final String CODE_CHECKOUT_CREATED = "CHECKOUT-CRTD-0071";

    /** Refresh the JWT this long before its stated expiry. */
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofSeconds(60);
    /** Used when a token's exp claim cannot be read. Real lifetime is ~900s. */
    private static final Duration TOKEN_FALLBACK_TTL = Duration.ofSeconds(600);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How long to wait for NALOPAY before timing out. */
    private final Duration nalopayTimeout = Duration.ofSeconds(20);

    /**
     * How many times to retry on transient network failures (e.g. "Connection reset
     * by peer"). Does NOT retry on NALOPAY 4xx/5xx — those are mapped to a
     * RuntimeException by the onStatus handler and are therefore excluded from retry.
     */
    private final long nalopayRetryAttempts = 2;

    // ─── Dependencies ─────────────────────────────────────────────────────────

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    // ─── Configuration ────────────────────────────────────────────────────────

    /** https://api.nalopay.com — no trailing slash. */
    @Value("${app.nalopay.base-url}")            private String baseUrl;
    @Value("${app.nalopay.merchant-id}")         private String merchantId;

    /** Basic Auth credential. Used ONLY to mint the JWT. */
    @Value("${app.nalopay.basic-auth}")          private String basicAuth;

    /**
     * HMAC key for trans_hash. NEVER transmitted.
     * Most sensitive of the three credentials: leak the Basic token and someone
     * can mint JWTs; leak this and they can forge valid charges for any amount.
     */
    @Value("${app.nalopay.secret-key}")          private String secretKey;

    /** Public base of our callback endpoint, WITHOUT the token. */
    @Value("${app.nalopay.callback-base-url}")   private String callbackBaseUrl;

    /** Shared secret in the callback path — our stand-in for Paystack's HMAC header. */
    @Value("${app.nalopay.callback-token}")      private String callbackToken;

    /** Optional comma-separated IP allowlist for the callback. Empty = allow all. */
    @Value("${app.nalopay.allowed-ips:}")        private String allowedIps;

    /**
     * Whether account_number is sent as LOCAL (0XXXXXXXXX) or INTERNATIONAL
     * (233XXXXXXXXX). NALOPAY's own samples use both. Whatever is sent must be
     * byte-identical to what goes into trans_hash, so this switch drives both.
     */
    @Value("${app.nalopay.msisdn-format:LOCAL}") private String msisdnFormat;

    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")         private String frontendUrl;

    // ─── Cached JWT ───────────────────────────────────────────────────────────

    private record CachedToken(String jwt, Instant expiresAt) {}
    private volatile CachedToken cachedToken;

    // ══════════════════════════════════════════════════════════════════════════
    //  MoMo — deposit init
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/wallet/deposit/nalopay-momo/init
     *
     * Creates a NALOPAY collection. The customer gets an approval prompt on
     * their handset; the wallet is credited only when the callback lands.
     *
     * Body: { amount (GHS), phone (any Ghana format), network ("MTN"|"AT"|"TELECEL") }
     */
    @PostMapping("/api/wallet/deposit/nalopay-momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initMomoDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[MoMo][initMomoDeposit] START — userId='{}' email='{}'",
                user.getId(), user.getEmail());

        var amount  = extractValidAmount(req, user.getId());
        var msisdn  = extractValidMsisdn(req, user.getId());
        var network = extractValidNetwork(req);

        validateNetworkPrefix(msisdn, network);

        return collect("MoMo", user, amount, msisdn, network,
                INTENT_DEPOSIT, "OmegaBet wallet topup");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Card / Bank — hosted checkout init
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/wallet/deposit/nalopay-card/init
     *
     * Hosted checkout restricted to CARD. We deliberately do NOT accept card
     * number/CVV/expiry here — see the class doc for why. The frontend redirects
     * the browser to {@code checkoutUrl} from the response.
     *
     * Body: { amount (GHS) }
     */
    @PostMapping("/api/wallet/deposit/nalopay-card/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initCardDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = extractValidAmount(req, user.getId());
        return checkout("Card", user, amount, "CARD", INTENT_DEPOSIT,
                "Wallet Topup", frontendUrl + "/app/wallet?payment=success");
    }

    /**
     * POST /api/wallet/deposit/nalopay-bank/init
     *
     * Hosted checkout restricted to BANK. No raw bank credentials touch this
     * backend.
     *
     * Body: { amount (GHS) }
     */
    @PostMapping("/api/wallet/deposit/nalopay-bank/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initBankDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = extractValidAmount(req, user.getId());
        return checkout("Bank", user, amount, "BANK", INTENT_DEPOSIT,
                "Wallet Topup", frontendUrl + "/app/wallet?payment=success");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Admin upgrade init — GHS 200
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/user/upgrade-to-admin/nalopay-momo/init
     *
     * GHS 200 upgrade fee over MoMo. The intent marker in the metadata is what
     * routes the callback to {@link #handleAdminUpgrade} instead of
     * {@link #handleDeposit} — the direct equivalent of Paystack's
     * {@code metadata.upgradeIntent}.
     *
     * Body: { phone, network }
     */
    @PostMapping("/api/user/upgrade-to-admin/nalopay-momo/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgradeMomo(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        log.info("[Upgrade][initAdminUpgradeMomo] START — userId='{}' email='{}'",
                user.getId(), user.getEmail());

        assertNotAlreadyAdmin(user, "initAdminUpgradeMomo");

        var msisdn  = extractValidMsisdn(req, user.getId());
        var network = extractValidNetwork(req);

        validateNetworkPrefix(msisdn, network);

        return collect("Upgrade", user, ADMIN_UPGRADE_FEE_GHS, msisdn, network,
                INTENT_ADMIN_UPGRADE, "OmegaBet admin upgrade");
    }

    /**
     * POST /api/user/upgrade-to-admin/nalopay-card/init
     *
     * Same GHS 200 upgrade, paid by card on NALOPAY's hosted page.
     */
    @PostMapping("/api/user/upgrade-to-admin/nalopay-card/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgradeCard(
            @AuthenticationPrincipal User user) {

        assertNotAlreadyAdmin(user, "initAdminUpgradeCard");

        return checkout("Upgrade", user, ADMIN_UPGRADE_FEE_GHS, "CARD",
                INTENT_ADMIN_UPGRADE, "Admin Upgrade",
                frontendUrl + "/app/upgrade?payment=success");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Callback — the ONLY place a wallet is credited
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/webhooks/nalopay/{token}
     *
     * NALOPAY sends, for a collection:
     *
     *   { "order_id": "LVFf4MHD2xJ7yJ7uW9ZyMi", "status": "COMPLETED",
     *     "amount": "50.00", "charges": "0.00", "transaction_fee": "0.00",
     *     "extra_data": { "userId": "...", "intent": "DEPOSIT", ... } }
     *
     * and for a hosted checkout the same envelope, with our metadata one level
     * deeper at {@code extra_data.products[0].metadata}.
     *
     * NALOPAY does not sign callbacks. Authentication is:
     *   1. {@code token} path segment, constant-time compared.
     *   2. Optional source-IP allowlist.
     *
     * Always returns HTTP 200 for handled events so NALOPAY stops retrying.
     */
    @PostMapping("/api/webhooks/nalopay/{token}")
    public ResponseEntity<String> callback(
            @PathVariable String token,
            HttpServletRequest request) {

        var remoteIp = resolveRemoteIp(request);
        log.info("[Callback] Received — remoteIp='{}'", remoteIp);

        if (!constantTimeEquals(token, callbackToken)) {
            log.warn("[Callback] REJECTED — bad callback token from ip='{}'", remoteIp);
            return ResponseEntity.status(401).body("Unauthorized");
        }

        if (!isIpAllowed(remoteIp)) {
            log.warn("[Callback] REJECTED — ip='{}' not in allowlist='{}'", remoteIp, allowedIps);
            return ResponseEntity.status(403).body("Forbidden");
        }

        log.info("[Callback] Token + IP verified OK — ip='{}'", remoteIp);

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("[Callback] Failed to read body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        try {
            var event = parseCallbackBody(rawBody, request);
            log.info("[Callback] Parsed payload — {}", redacted(event));

            var orderId   = str(event, "order_id");
            var status    = str(event, "status");
            var rawAmount = str(event, "amount");

            if (orderId == null || orderId.isBlank()) {
                log.error("[Callback] Missing order_id — status='{}'", status);
                return ResponseEntity.status(400).body("Missing order_id");
            }

            log.info("[Callback] order_id='{}' status='{}' amount='{}' charges='{}' fee='{}'",
                    orderId, status, rawAmount,
                    str(event, "charges"), str(event, "transaction_fee"));

            if (!STATUS_COMPLETED.equalsIgnoreCase(nullSafe(status))) {
                if (STATUS_FAILED.equalsIgnoreCase(nullSafe(status)))
                    log.warn("[Callback] Payment FAILED — order_id='{}' amount='{}'", orderId, rawAmount);
                else
                    log.info("[Callback] Ignoring non-terminal status='{}' order_id='{}'", status, orderId);
                return ResponseEntity.ok("Ignored");
            }

            // ── Attribution ───────────────────────────────────────────────────
            var metadata = extractMetadata(event);

            if (metadata == null || metadata.get("userId") == null) {
                log.error("[Callback] UNATTRIBUTABLE — no userId in metadata. order_id='{}' " +
                        "amount='{}' keys={}. Money was collected but cannot be credited — " +
                        "RECONCILE MANUALLY.", orderId, rawAmount, event.keySet());
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            UUID userId;
            try {
                userId = UUID.fromString(metadata.get("userId").toString());
            } catch (IllegalArgumentException e) {
                log.error("[Callback] Invalid userId='{}' in metadata — order_id='{}'",
                        metadata.get("userId"), orderId);
                return ResponseEntity.status(400).body("Invalid userId in metadata");
            }

            var intent   = nullSafe(strOf(metadata.get("intent"))).toUpperCase(Locale.ROOT);
            var ourRef   = strOf(metadata.get("ref"));
            var expected = strOf(metadata.get("amt"));

            log.info("[Callback] Attributed — userId='{}' intent='{}' ourRef='{}' order_id='{}'",
                    userId, intent, ourRef, orderId);

            // ── Amount ────────────────────────────────────────────────────────
            // Prefer the amount we minted at init over the callback body: the
            // callback is unsigned, so its amount field is not a trusted input.
            var amount = resolveAmount(expected, rawAmount, orderId);

            if (INTENT_ADMIN_UPGRADE.equals(intent)) {
                handleAdminUpgrade(userId, orderId, amount, ourRef);
            } else {
                handleDeposit(userId, orderId, amount, ourRef);
            }

        } catch (ApiException e) {
            log.error("[Callback] ApiException — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Callback] Unexpected error — NALOPAY will retry: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Processing error");
        }

        log.info("[Callback] COMPLETE — returning 200 OK");
        return ResponseEntity.ok("OK");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Verification fallback — READ ONLY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/wallet/deposit/nalopay/verify/{orderId}
     *
     * POST /clientapi/collection-status/ under the hood. Works for MoMo and
     * checkout order IDs alike.
     *
     * IMPORTANT: READ-ONLY. Wallet crediting only ever happens in the callback
     * handler. Never credit here — that is how you double-credit.
     *
     * Note the status endpoint returns only {status, amount} — no metadata, no
     * ownership information. We therefore cannot verify the caller owns this
     * order, so the response is deliberately limited to what NALOPAY returns
     * and carries nothing user-identifying.
     */
    @GetMapping("/api/wallet/deposit/nalopay/verify/{orderId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @PathVariable String orderId) {

        if (orderId == null || orderId.isBlank()) {
            log.warn("[Verify] REJECTED — blank orderId from userId='{}'", user.getId());
            throw ApiException.badRequest("orderId is required.");
        }

        log.info("[Verify] START — userId='{}' order_id='{}'", user.getId(), orderId);

        Map<String, Object> response;
        try {
            response = post("/clientapi/collection-status/",
                    Map.of("merchant_id", merchantId, "order_id", orderId),
                    false, "collectionStatus");
        } catch (Exception e) {
            log.error("[Verify] Status lookup FAILED — userId='{}' order_id='{}' — {}",
                    user.getId(), orderId, e.getMessage(), e);
            throw e;
        }

        var data = mapOf(response.get("data"));
        log.info("[Verify] COMPLETE — userId='{}' order_id='{}' status='{}' amount='{}'",
                user.getId(), orderId,
                data == null ? null : data.get("status"),
                data == null ? null : data.get("amount"));

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Private handlers — identical semantics to PaystackController
    // ══════════════════════════════════════════════════════════════════════════

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
     * Idempotent — duplicate order IDs (409) are silently skipped so NALOPAY
     * callback retries are safe.
     */
    private void handleDeposit(UUID userId, String orderId, BigDecimal amount, String ourRef) {
        log.info("[handleDeposit] START — userId='{}' amountGHS={} order_id='{}' ourRef='{}'",
                userId, amount, orderId, ourRef);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, orderId,
                    Map.of("provider", "nalopay", "orderId", orderId, "reference", nullSafe(ourRef)));
            log.info("[handleDeposit] Wallet credited GHS {} — userId='{}' order_id='{}'",
                    amount, userId, orderId);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[handleDeposit] Duplicate order_id='{}' — already processed, skipping", orderId);
                return;
            }
            log.error("[handleDeposit] walletService.credit FAILED — userId='{}' order_id='{}' — {}",
                    userId, orderId, ex.getMessage(), ex);
            throw ex;
        }

        // ── Attribute commission to referring admin based on commission structure ──
        // The admin's rate (default 70%) is resolved from the Referral entity inside
        // ReferralService. No rate logic lives here — just trigger attribution.
        try {
            referralService.attributeCommission(userId, amount);
            log.info("[handleDeposit] Commission attributed — userId='{}' depositGHS={} adminRate={}",
                    userId, amount, ADMIN_COMMISSION_RATE);
        } catch (Exception ex) {
            // Commission failure must NEVER block or roll back the deposit
            log.error("[handleDeposit] Commission FAILED — userId='{}' INVESTIGATE: {}",
                    userId, ex.getMessage(), ex);
        }

        log.info("[handleDeposit] COMPLETE — userId='{}' order_id='{}'", userId, orderId);
    }

    /**
     * Handles an admin upgrade payment.
     *
     * Steps:
     *   1. Validates amount >= GHS 200
     *   2. Promotes user to ADMIN + initialises their referral link at 70% commission
     *   3. Records an audit transaction (NALOPAY already collected the funds externally)
     *   4. Creates onboarding chat with Super Admin for commission confirmation
     *
     * Commission structure note:
     *   The new admin's default commission rate is set to 70% inside
     *   UserService.upgradeToAdmin(). Super Admin can adjust the rate via the
     *   onboarding chat created in step 4.
     */
    private void handleAdminUpgrade(UUID userId, String orderId, BigDecimal amount, String ourRef) {
        log.info("[handleAdminUpgrade] START — userId='{}' amountGHS={} order_id='{}' ourRef='{}'",
                userId, amount, orderId, ourRef);

        if (amount.compareTo(ADMIN_UPGRADE_FEE_GHS) < 0) {
            log.error("[handleAdminUpgrade] amount {} < GHS 200 for userId='{}' order_id='{}'",
                    amount, userId, orderId);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            // upgradeToAdmin sets the new admin's commission rate to 70% on the Referral entity
            userService.upgradeToAdmin(userId, orderId);
            log.info("[handleAdminUpgrade] userId='{}' promoted to ADMIN with {}% commission order_id='{}'",
                    userId,
                    ADMIN_COMMISSION_RATE.multiply(BigDecimal.valueOf(100)).toPlainString(),
                    orderId);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("[handleAdminUpgrade] Duplicate order_id='{}' — skipping", orderId);
                return;
            }
            log.error("[handleAdminUpgrade] upgradeToAdmin FAILED — userId='{}' order_id='{}' — {}",
                    userId, orderId, ex.getMessage(), ex);
            throw ex;
        }

        // Audit record — NALOPAY collected GHS 200 externally, no wallet debit needed
        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, orderId,
                Map.of("provider", "nalopay", "orderId", orderId, "reference", nullSafe(ourRef)));
        log.info("[handleAdminUpgrade] audit tx recorded for userId='{}' order_id='{}'", userId, orderId);

        // Create onboarding chat so Super Admin can confirm/adjust the 70% commission rate
        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("[handleAdminUpgrade] upgrade chat created for userId='{}'", userId);

        log.info("[handleAdminUpgrade] COMPLETE — userId='{}' order_id='{}'", userId, orderId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Shared init implementations
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /clientapi/collection/ — direct MoMo collection.
     *
     * trans_hash = HMAC-SHA256( merchant_id + account_number + amount + reference )
     *
     * The hashed amount string and the transmitted amount must be byte-identical,
     * so both come from {@link #fmt}.
     */
    private ResponseEntity<ApiResponse<Map<String, Object>>> collect(
            String tag, User user, BigDecimal amount, String msisdn,
            String network, String intent, String description) {

        var reference = mintReference();
        var amountStr = fmt(amount);
        var hash      = transHash(merchantId, msisdn, amountStr, reference);

        log.info("[{}][collect] Calling NALOPAY collection — userId='{}' amountGHS={} " +
                        "msisdn='{}' network='{}' reference='{}' intent='{}'",
                tag, user.getId(), amountStr, maskPhone(msisdn), network, reference, intent);

        var body = new LinkedHashMap<String, Object>();
        body.put("merchant_id",    merchantId);
        body.put("service_name",   SERVICE_MOMO);
        body.put("trans_hash",     hash);
        body.put("account_number", msisdn);
        body.put("account_name",   displayName(user));
        body.put("description",    description);
        body.put("reference",      reference);
        body.put("network",        network);
        body.put("amount",         new BigDecimal(amountStr));
        body.put("callback",       callbackUrl());
        body.put("extra_data",     metadataFor(user, intent, reference, amountStr));

        Map<String, Object> response;
        try {
            response = post("/clientapi/collection/", body, true, "collection:" + tag);
        } catch (Exception e) {
            log.error("[{}][collect] NALOPAY collection FAILED — userId='{}' reference='{}' — {}",
                    tag, user.getId(), reference, e.getMessage(), e);
            throw e;
        }

        assertCode(response, CODE_COLLECTION_CREATED, tag);

        var data    = mapOf(response.get("data"));
        var orderId = data == null ? null : strOf(data.get("order_id"));

        if (orderId == null) {
            log.error("[{}][collect] No order_id in NALOPAY response — userId='{}' reference='{}' keys={}",
                    tag, user.getId(), reference, response.keySet());
            throw new RuntimeException("NALOPAY did not return an order_id. Please try again.");
        }

        log.info("[{}][collect] COMPLETE — userId='{}' order_id='{}' status='{}' otp_code='{}' reference='{}'",
                tag, user.getId(), orderId,
                data.get("status"), data.get("otp_code"), reference);

        var payload = new LinkedHashMap<String, Object>(response);
        payload.put("orderId",   orderId);
        payload.put("reference", reference);

        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    /**
     * POST /checkout/session/ — hosted checkout for card and bank.
     *
     * trans_hash = HMAC-SHA256( merchant_id + order_id + total_price + reference )
     *
     * Note the hash inputs differ from the collection endpoint: {@code order_id}
     * here is OURS (we choose it), whereas on a collection NALOPAY assigns the
     * order_id and it is not part of the hash at all.
     *
     * Our metadata rides on the single product line, because the callback's
     * top-level {@code extra_data} is occupied by the order summary.
     */
    private ResponseEntity<ApiResponse<Map<String, Object>>> checkout(
            String tag, User user, BigDecimal amount, String mode,
            String intent, String productName, String referralUrl) {

        if (!VALID_MODES.contains(mode))
            throw ApiException.badRequest("Unsupported checkout mode '" + mode + "'.");

        var ourOrderId = mintReference();
        var reference  = mintReference();
        var total      = fmt(amount);
        var hash       = transHash(merchantId, ourOrderId, total, reference);

        log.info("[{}][checkout] Calling NALOPAY checkout — userId='{}' totalGHS={} mode='{}' " +
                        "order_id='{}' reference='{}' intent='{}'",
                tag, user.getId(), total, mode, ourOrderId, reference, intent);

        var merchant = new LinkedHashMap<String, Object>();
        merchant.put("merchant_id",   merchantId);
        merchant.put("order_id",      ourOrderId);
        merchant.put("customer_name", displayName(user));
        merchant.put("referral_url",  referralUrl);
        merchant.put("callback_url",  callbackUrl());
        merchant.put("trans_hash",    hash);
        merchant.put("reference",     reference);
        merchant.put("mode",          mode);

        var product = new LinkedHashMap<String, Object>();
        product.put("name",     productName);
        product.put("count",    1);
        product.put("price",    total);
        product.put("metadata", metadataFor(user, intent, reference, total));

        var summary = new LinkedHashMap<String, Object>();
        summary.put("products",    List.of(product));
        summary.put("item_count",  1);
        summary.put("total_price", total);

        var body = new LinkedHashMap<String, Object>();
        body.put("merchant", merchant);
        body.put("summary",  summary);

        Map<String, Object> response;
        try {
            response = post("/checkout/session/", body, true, "checkout:" + mode);
        } catch (Exception e) {
            log.error("[{}][checkout] NALOPAY checkout FAILED — userId='{}' order_id='{}' — {}",
                    tag, user.getId(), ourOrderId, e.getMessage(), e);
            throw e;
        }

        assertCode(response, CODE_CHECKOUT_CREATED, tag);

        var data        = mapOf(response.get("data"));
        var checkoutUrl = data == null ? null : strOf(data.get("checkout_url"));

        if (checkoutUrl == null || checkoutUrl.isBlank() || checkoutUrl.startsWith("None")) {
            log.error("[{}][checkout] No usable checkout_url — userId='{}' order_id='{}' data={}",
                    tag, user.getId(), ourOrderId, data);
            throw new RuntimeException("NALOPAY did not return a checkout URL. Please try again.");
        }

        log.info("[{}][checkout] COMPLETE — userId='{}' order_id='{}' timeout={}s hasUrl=true",
                tag, user.getId(), ourOrderId, data.get("checkout_timeout"));

        var payload = new LinkedHashMap<String, Object>(response);
        payload.put("checkoutUrl", checkoutUrl);
        payload.put("orderId",     ourOrderId);
        payload.put("reference",   reference);

        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    /**
     * The metadata block that must survive the round trip. This is NALOPAY's
     * equivalent of Paystack's {@code metadata} map, and attribution depends on
     * it entirely — see the class-level warning.
     *
     *   userId — who to credit
     *   intent — DEPOSIT or ADMIN_UPGRADE; routes the callback
     *   ref    — our reference, for support cross-referencing
     *   amt    — the amount we minted; trusted over the unsigned callback body
     */
    private Map<String, Object> metadataFor(User user, String intent, String reference, String amountStr) {
        return Map.of(
                "userId", user.getId().toString(),
                "intent", intent,
                "ref",    reference,
                "amt",    amountStr
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Authentication
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a valid JWT, minting a new one when the cached token is missing or
     * within {@link #TOKEN_SAFETY_MARGIN} of expiry.
     *
     * NALOPAY's tokens live ~15 minutes, so a long-lived singleton is not an
     * option — but neither is minting one per request, which would double every
     * charge's latency. Cache-with-refresh is the middle path.
     */
    private String currentToken() {
        var cached = cachedToken;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            log.debug("[auth] Reusing cached JWT — expires at {}", cached.expiresAt());
            return cached.jwt();
        }

        synchronized (this) {
            // Re-check: another thread may have refreshed while we waited.
            var recheck = cachedToken;
            if (recheck != null && Instant.now().isBefore(recheck.expiresAt()))
                return recheck.jwt();

            log.info("[auth] Minting new NALOPAY JWT — merchantId='{}'", merchantId);

            var response = post("/clientapi/generate-payment-token/",
                    Map.of("merchant_id", merchantId), false, "generateToken");

            assertCode(response, CODE_TOKEN_CREATED, "auth");

            var data = mapOf(response.get("data"));
            var jwt  = data == null ? null : strOf(data.get("token"));

            if (jwt == null || jwt.isBlank()) {
                log.error("[auth] No token in NALOPAY response — keys={}", response.keySet());
                throw new RuntimeException("NALOPAY authentication failed.");
            }

            var expiresAt = jwtExpiry(jwt)
                    .map(exp -> exp.minus(TOKEN_SAFETY_MARGIN))
                    .orElseGet(() -> Instant.now().plus(TOKEN_FALLBACK_TTL));

            cachedToken = new CachedToken(jwt, expiresAt);
            log.info("[auth] JWT minted — refresh due at {}", expiresAt);

            return jwt;
        }
    }

    /** Reads the {@code exp} claim without verifying the signature — we only need the clock. */
    private java.util.Optional<Instant> jwtExpiry(String jwt) {
        try {
            var parts = jwt.split("\\.");
            if (parts.length < 2) return java.util.Optional.empty();
            var json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            var claims = (Map<String, Object>) objectMapper.readValue(json, Map.class);
            var exp = claims.get("exp");
            if (exp == null) return java.util.Optional.empty();
            return java.util.Optional.of(Instant.ofEpochSecond(Long.parseLong(exp.toString())));
        } catch (Exception e) {
            log.warn("[auth] Could not read exp from JWT — falling back to {}s TTL",
                    TOKEN_FALLBACK_TTL.getSeconds());
            return java.util.Optional.empty();
        }
    }

    /**
     * trans_hash = hex( HMAC-SHA256( concat(parts), secret-key ) )
     *
     * Fields are concatenated in order with NO separators. The secret key never
     * leaves this process — only the resulting hex string is transmitted.
     */
    private String transHash(String... parts) {
        var message = String.join("", parts);
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var hex = HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            log.debug("[transHash] message='{}' → hash='{}...'", message, hex.substring(0, 8));
            return hex;
        } catch (Exception e) {
            log.error("[transHash] HMAC failure — cannot sign NALOPAY requests", e);
            throw new RuntimeException("NALOPAY request signing failed.");
        }
    }

    /** Full callback URL sent on every charge, secret token included. */
    private String callbackUrl() {
        var base = callbackBaseUrl.endsWith("/")
                ? callbackBaseUrl.substring(0, callbackBaseUrl.length() - 1)
                : callbackBaseUrl;
        return base + "/" + callbackToken;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HTTP
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Shared POST helper for every NALOPAY call.
     *
     * @param withJwt true → send the {@code token} header (collection, checkout);
     *                false → Basic auth or no auth (token mint, status check).
     *
     * Resilience:
     *   - Times out after 20 seconds so the caller thread is never held indefinitely.
     *     (Longer than the Paystack timeout: NALOPAY reaches the telco's mobile-money
     *     switch synchronously before it can answer.)
     *   - Retries up to 2 times on transient network errors. Retries do NOT fire on
     *     NALOPAY 4xx/5xx — those are mapped to RuntimeException by the onStatus
     *     handler, which excludes them from the retry predicate.
     *   - Retries are safe: reference and trans_hash are fixed for the attempt, and
     *     the wallet credit is idempotent on the resulting order_id.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body,
                                     boolean withJwt, String callerTag) {

        var url = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + path
                : baseUrl + path;

        log.debug("[{}] POST {} — payload={}", callerTag, url, redacted(body));

        var spec = webClientBuilder.build()
                .post().uri(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (withJwt) {
            spec = spec.header("token", currentToken());
        } else if (path.contains("generate-payment-token")) {
            spec = spec.header("Authorization", "Basic " + basicAuth);
        }

        var result = (Map<String, Object>) spec
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), r -> r.bodyToMono(String.class).map(respBody -> {
                    log.error("[{}] HTTP error — path='{}' status={} body='{}'",
                            callerTag, path, r.statusCode(), respBody);
                    return new RuntimeException("NALOPAY returned " + r.statusCode() + ": " + respBody);
                }))
                .bodyToMono(Map.class)
                .timeout(nalopayTimeout)
                .retryWhen(Retry.max(nalopayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("[{}] NALOPAY unreachable after {} retries — path='{}'",
                                    callerTag, nalopayRetryAttempts, path, ex);
                            return new RuntimeException("NALOPAY is currently unavailable. Please try again.");
                        })
                .block();

        if (result == null) {
            log.error("[{}] NALOPAY returned an empty response — path='{}'", callerTag, path);
            throw new RuntimeException("NALOPAY returned an empty response.");
        }

        if (Boolean.FALSE.equals(result.get("success"))) {
            var error = mapOf(result.get("error"));
            var cause = error == null ? null : strOf(error.get("cause"));
            var desc  = error == null ? null : strOf(error.get("description"));
            log.error("[{}] success=false — path='{}' code='{}' cause='{}' description='{}'",
                    callerTag, path, result.get("code"), cause, desc);
            throw new RuntimeException("NALOPAY error: " + firstNonBlank(desc, cause, "request declined"));
        }

        log.debug("[{}] response — path='{}' code='{}'", callerTag, path, result.get("code"));
        return result;
    }

    /** Logs a warning when NALOPAY's response code isn't the one we expect. Non-fatal. */
    private void assertCode(Map<String, Object> response, String expected, String tag) {
        var code = strOf(response.get("code"));
        if (!expected.equals(code))
            log.warn("[{}] Unexpected NALOPAY code — expected='{}' actual='{}'", tag, expected, code);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Callback parsing
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Locates our metadata in a callback payload. Two shapes:
     *
     *   Collection: extra_data = { userId, intent, ref, amt }
     *   Checkout:   extra_data = { products: [ { metadata: { userId, ... } } ], ... }
     */
    private Map<String, Object> extractMetadata(Map<String, Object> event) {
        var extra = mapOf(event.get("extra_data"));
        if (extra == null) {
            log.warn("[Callback] No extra_data on payload");
            return null;
        }

        if (extra.get("userId") != null) {
            log.debug("[Callback] Metadata found at extra_data (collection shape)");
            return extra;
        }

        if (extra.get("products") instanceof List<?> products && !products.isEmpty()) {
            var first = mapOf(products.get(0));
            var meta  = first == null ? null : mapOf(first.get("metadata"));
            if (meta != null && meta.get("userId") != null) {
                log.debug("[Callback] Metadata found at extra_data.products[0].metadata (checkout shape)");
                return meta;
            }
        }

        log.warn("[Callback] extra_data present but contains no userId — keys={}", extra.keySet());
        return null;
    }

    /**
     * Chooses the amount to credit. The metadata amount is the one we minted at
     * init; the callback body is unsigned and therefore untrusted. A mismatch is
     * logged loudly — it means either a partial payment or tampering.
     */
    private BigDecimal resolveAmount(String expected, String callbackAmount, String orderId) {
        BigDecimal minted = null;
        if (expected != null && !expected.isBlank()) {
            try { minted = new BigDecimal(expected.trim()); } catch (NumberFormatException ignored) { }
        }

        BigDecimal reported = null;
        if (callbackAmount != null && !callbackAmount.isBlank()) {
            try { reported = new BigDecimal(callbackAmount.trim()); } catch (NumberFormatException ignored) { }
        }

        if (minted == null) {
            if (reported == null)
                throw ApiException.badRequest("No usable amount on callback for order_id " + orderId);
            log.warn("[Callback] No minted amount in metadata — falling back to callback amount {} " +
                    "for order_id='{}'", reported, orderId);
            return reported;
        }

        if (reported != null && minted.compareTo(reported) != 0)
            log.error("[Callback] AMOUNT MISMATCH — minted={} callback={} order_id='{}'. " +
                    "Crediting the minted amount. INVESTIGATE.", minted, reported, orderId);

        return minted;
    }

    /** NALOPAY posts JSON; form-encoded is accepted as a fallback. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCallbackBody(byte[] rawBody, HttpServletRequest request) {
        var text = new String(rawBody, StandardCharsets.UTF_8).trim();

        if (text.startsWith("{")) {
            try {
                return (Map<String, Object>) objectMapper.readValue(text, Map.class);
            } catch (Exception e) {
                log.warn("[Callback] Body looked like JSON but failed to parse — trying form params");
            }
        }

        var form = new LinkedHashMap<String, Object>();
        request.getParameterMap().forEach((k, v) -> form.put(k, v.length > 0 ? v[0] : null));

        if (form.isEmpty())
            throw new IllegalArgumentException("callback body is neither JSON nor form-encoded");

        log.info("[Callback] Parsed as form-encoded — {} field(s)", form.size());
        return form;
    }

    private String resolveRemoteIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank())
            return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private boolean isIpAllowed(String ip) {
        if (allowedIps == null || allowedIps.isBlank()) return true;
        return List.of(allowedIps.split(",")).stream()
                .map(String::trim)
                .anyMatch(allowed -> allowed.equals(ip));
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Request validation
    // ══════════════════════════════════════════════════════════════════════════

    private void assertNotAlreadyAdmin(User user, String tag) {
        if (user.getRole().name().equals("ADMIN")) {
            log.warn("[Upgrade][{}] REJECTED — userId='{}' is already ADMIN", tag, user.getId());
            throw ApiException.badRequest("You are already an Admin.");
        }
    }

    /** Extracts and validates the "amount" field (GHS). */
    private BigDecimal extractValidAmount(Map<String, Object> req, UUID userId) {
        var rawAmount = req.get("amount");
        if (rawAmount == null)
            throw ApiException.badRequest("amount is required.");

        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("[extractValidAmount] Invalid amount='{}' for userId='{}'", rawAmount, userId);
            throw ApiException.badRequest("amount must be a valid number.");
        }

        if (amount.scale() > 2) {
            log.warn("[extractValidAmount] amount='{}' has more than 2 dp for userId='{}'", amount, userId);
            throw ApiException.badRequest("amount cannot have more than 2 decimal places.");
        }

        if (amount.compareTo(minDeposit) < 0) {
            log.warn("[extractValidAmount] Amount GHS {} below minimum GHS {} for userId='{}'",
                    amount, minDeposit, userId);
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);
        }
        return amount;
    }

    /** Extracts and normalizes the MSISDN into whichever format {@code msisdn-format} selects. */
    private String extractValidMsisdn(Map<String, Object> req, UUID userId) {
        var raw = req.get("phone") == null ? "" : String.valueOf(req.get("phone")).trim();
        if (raw.isBlank() || raw.equals("null"))
            throw ApiException.badRequest("Phone number is required.");

        var msisdn = normalizeGhanaMsisdn(raw);
        log.info("[extractValidMsisdn] Phone normalized to '{}' (format={}) for userId='{}'",
                maskPhone(msisdn), msisdnFormat, userId);
        return msisdn;
    }

    /** Extracts and validates the network, mapping common aliases to NALOPAY's codes. */
    private String extractValidNetwork(Map<String, Object> req) {
        var raw = req.get("network");
        if (raw == null)
            throw ApiException.badRequest("network is required. Use one of: MTN, AT, TELECEL.");

        var network = raw.toString().trim().toUpperCase(Locale.ROOT);

        // Paystack/legacy aliases → NALOPAY codes
        switch (network) {
            case "AIRTELTIGO", "AIRTEL", "TIGO", "ATL" -> {
                log.info("[extractValidNetwork] Mapping '{}' → AT", raw);
                network = "AT";
            }
            case "VODAFONE", "VOD" -> {
                log.info("[extractValidNetwork] Mapping '{}' → TELECEL", raw);
                network = "TELECEL";
            }
            default -> { }
        }

        if (!VALID_NETWORKS.contains(network))
            throw ApiException.badRequest(
                    "Unsupported network '" + raw + "'. Use one of: MTN, AT, TELECEL.");

        return network;
    }

    /**
     * Normalizes any Ghana phone format to the configured wire format.
     * Accepts: 0XXXXXXXXX, 233XXXXXXXXX, +233XXXXXXXXX.
     *
     * The chosen format feeds both the request body and trans_hash — they must
     * match byte for byte or NALOPAY rejects the signature.
     */
    private String normalizeGhanaMsisdn(String raw) {
        var digits = raw.replaceAll("[\\s\\-()]", "");
        if (digits.startsWith("+")) digits = digits.substring(1);

        String local;
        if (digits.matches("^233\\d{9}$"))      local = "0" + digits.substring(3);
        else if (digits.matches("^0\\d{9}$"))   local = digits;
        else {
            log.warn("[normalizeGhanaMsisdn] Failed for raw='{}'", maskPhone(raw));
            throw ApiException.badRequest(
                    "Invalid Ghana phone number. Expected format: 0XXXXXXXXX or 233XXXXXXXXX.");
        }

        return "INTERNATIONAL".equalsIgnoreCase(msisdnFormat) ? "233" + local.substring(1) : local;
    }

    /**
     * Logs a warning if the prefix doesn't match the network. Does NOT throw —
     * NALOPAY and the telco are the final authority.
     */
    private void validateNetworkPrefix(String msisdn, String network) {
        var local  = msisdn.startsWith("233") ? "0" + msisdn.substring(3) : msisdn;
        var prefix = local.substring(0, 3);
        var mismatch = switch (network) {
            case "MTN"     -> !MTN_PREFIXES.contains(prefix);
            case "AT"      -> !AT_PREFIXES.contains(prefix);
            case "TELECEL" -> !TELECEL_PREFIXES.contains(prefix);
            default        -> false;
        };
        if (mismatch)
            log.warn("[validateNetworkPrefix] Prefix '{}' may not match network='{}' — " +
                    "MTN={} AT={} TELECEL={}", prefix, network, MTN_PREFIXES, AT_PREFIXES, TELECEL_PREFIXES);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Unique reference / order_id. 22 chars, alphanumeric — matches NALOPAY's own shape. */
    private String mintReference() {
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        var sb = new StringBuilder(22);
        for (var i = 0; i < 22; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }

    /**
     * Canonical amount rendering. Used for BOTH the transmitted amount and the
     * hashed amount, so the two can never drift.
     *
     * NALOPAY's worked hash example uses 2 dp ("50.00"). If signature rejections
     * appear, this is the first thing to change — and changing it here fixes
     * both sides at once.
     */
    private String fmt(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** NALOPAY wants a human name on the charge; fall back to the email local part. */
    private String displayName(User user) {
        var email = user.getEmail() == null ? "" : user.getEmail();
        var local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        return local.isBlank() ? "OmegaBet Customer" : local;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String str(Map<String, Object> map, String key) {
        return strOf(map.get(key));
    }

    private static String strOf(Object v) {
        return v == null ? null : v.toString();
    }

    private static String firstNonBlank(String... values) {
        for (var v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    /** Masks phone for safe logging: "0536064739" → "053****739" */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }

    /**
     * Strips credentials before a payload reaches a log file. trans_hash is
     * included because it is a valid signature — replaying it authorises a charge.
     */
    private String redacted(Map<String, Object> body) {
        var copy = new LinkedHashMap<String, Object>(body);
        for (var k : List.of("trans_hash", "token", "merchant_id", "callback", "callback_url"))
            if (copy.containsKey(k)) copy.put(k, "***");
        if (copy.get("merchant") instanceof Map<?, ?> m) {
            var inner = new LinkedHashMap<String, Object>((Map<String, Object>) m);
            for (var k : List.of("trans_hash", "merchant_id", "callback_url"))
                if (inner.containsKey(k)) inner.put(k, "***");
            copy.put("merchant", inner);
        }
        return copy.toString();
    }
}
package com.speedbet.api.payment.gmp;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global Market Pay (GMP) deposits — Ghana (direct MoMo collection) + Nigeria
 * (redirect checkout via Flutterwave, unchanged).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT CHANGED (GHANA ONLY — the Nigerian flow below is untouched)
 * ─────────────────────────────────────────────────────────────────────────────
 *   OLD #1: MoMo USSD push via NaloPay/HSTpay — POST /api/merchant/api/collect/
 *           then GET /api/merchant/api/verify/{transaction_id}/.
 *           *** RETIRED. ***
 *
 *   OLD #2: ZaaPay hosted checkout (Flutterwave) — redirect + callback + verify
 *           by tx_ref. *** ALSO RETIRED. POST /api/merchant/gh/payments/initiate/
 *           now returns 410 Gone for GHS. ***
 *
 *   NEW: direct Mobile Money collection (MTN, Telecel, AirtelTigo). No checkout
 *        page, no redirect, no callback. Two calls:
 *          1. POST /api/merchant/api/collect/  { phone_number, amount, description }
 *             → 201 { success, transaction_id, reference, status:"pending",
 *                      amount, currency, type:"momo", message }
 *          2. GET  /api/merchant/api/verify/{transaction_id}/
 *             → { transaction_id, account, channel, gross_amount, net_amount,
 *                  status, currency }   (FLAT — only status:"success" is paid)
 *
 *   Consequences for callers:
 *     • /api/wallet/deposit/gmp/gh/init now takes phoneNumber (+ optional
 *       network, description) instead of customerName. It returns a
 *       transactionId, NOT a checkoutUrl — there is nothing to redirect to.
 *     • Verification is keyed by transaction_id (provider-issued), not tx_ref.
 *       Ownership is resolved purely from the in-memory pending map (keyed by
 *       transaction_id) since there is no merchant_reference/narration echoed
 *       back by this endpoint — after a restart, an in-flight GH deposit CANNOT
 *       be recovered from the verify payload alone. NG is unaffected; it still
 *       carries merchant_reference.
 *     • The wallet is credited net_amount automatically per the provider's
 *       docs, so credit-gross behaves differently for GH now: with
 *       app.gmp.credit-gross=true we still credit the bettor the GROSS figure
 *       (platform absorbs the fee, matching old behaviour); with it false we
 *       credit net_amount as returned — no derived-net fallback is needed
 *       since verify always returns both figures directly.
 *     • GH commission stays admin-configurable via app.gmp.gh-commission-rate
 *       (informational only now — verify returns the actual net_amount).
 *     • GH authenticates with a SECRET API KEY sent as
 *       "Authorization: Api-Key <key>", not the Bearer merchant-id used by NG.
 *     • The GH callback endpoint, ghCallbackUrl(), and callback_url body field
 *       are GONE — there is no browser round-trip for GH anymore. The
 *       "/app/wallet/gmp/gh/callback" permitAll() entry in SecurityConfig can
 *       be removed once no old client is relying on it (it's simply unmapped
 *       here now).
 *     • The GH poller now polls by transaction_id instead of tx_ref.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ENDPOINTS
 * ─────────────────────────────────────────────────────────────────────────────
 *   GH request:  POST {base}/api/merchant/api/collect/                 → 201
 *   GH verify:   GET  {base}/api/merchant/api/verify/{transaction_id}/
 *
 *   NG initiate: POST {base}/api/merchant/ng/payments/initiate/
 *                { success, tx_ref, transaction_id, checkout_url, amount, currency }
 *   NG verify:   GET  {base}/api/merchant/ng/payments/verify/?tx_ref=...
 *                { tx_ref, transaction_id, status, amount, currency,
 *                  merchant_reference, payment_type }      (FLAT — no "success")
 *
 *   GH verify vocabulary: pending | success | failed   (only "success" is paid)
 *   NG verify vocabulary: pending | success | failed
 *   NG callback redirect vocabulary: successful | failed | pending — NEVER
 *   trusted; always re-verified server-side.
 *   GH has no callback at all — the poller (every app.gmp.gh-poll-ms) and the
 *   user-triggered verify endpoint are the only ways a GH deposit settles.
 *   A customer who never approves the MoMo prompt sits at "pending" until
 *   either it flips to "failed" on the provider side or the TTL sweeper
 *   expires it.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CREDITING MODEL (mirrors PaystackController.handleDeposit)
 * ─────────────────────────────────────────────────────────────────────────────
 *   1. Resolve the owning userId — GH: pending map ONLY (no merchant_reference
 *      is echoed back by /api/merchant/api/verify/, so there is no fallback
 *      recovery path after a restart or on a second instance — this is a
 *      regression vs the old ZaaPay flow, flagged loudly in settleGhanaDeposit
 *      when it happens). NG: pending map first, then merchant_reference/
 *      narration as before.
 *   2. walletService.credit(...) with a non-null idempotency ref (transaction_id
 *      for GH, tx_ref for NG). HTTP 409 = already credited → swallowed.
 *   3. referralService.attributeCommission(...) — never allowed to fail a deposit.
 *   4. The pending record is removed ONLY after a confirmed credit.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * AMOUNT POLICY
 * ─────────────────────────────────────────────────────────────────────────────
 *   GH verify returns BOTH gross_amount (what the customer paid) and net_amount
 *   (what actually lands in the merchant wallet, fee already deducted) — no
 *   guessing required, unlike the old ZaaPay payload. With credit-gross=true
 *   (default) the bettor is credited gross_amount and the platform absorbs the
 *   fee, identical to the Paystack/NG flow. With credit-gross=false the bettor
 *   is credited net_amount exactly as returned.
 *   NG is unchanged: "amount" in the verify payload is gross; the ~15%
 *   commission comes out of the merchant payout, not the credited figure.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REQUIRED CONFIG (application.properties / Railway env)
 * ─────────────────────────────────────────────────────────────────────────────
 *   app.gmp.merchant-id:        <merchant id — NG auth, "Authorization: Bearer ...">
 *   app.gmp.gh-api-key:         <GH secret API key — "Authorization: Api-Key ...">
 *   app.gmp.base-url:           https://backendpay.tipsterhub.online
 *   app.gmp.backend-public-url: https://<this-spring-app>       (NG callback only now)
 *   app.platform.frontend-url:  https://<the-web-app>
 *
 * SecurityConfig MUST permitAll() the NG provider redirect — it arrives in the
 * customer's browser with no bearer token:
 *   "/app/wallet/gmp/ng/callback"
 * The GH callback route no longer exists in this controller; there is nothing
 * left to permitAll() for GH.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GlobalMarketPayController {

    // ════════════════════════════════════════════════════════════════════════
    // Configuration
    // ════════════════════════════════════════════════════════════════════════

    /** Nigeria auth. Sent as "Authorization: Bearer {merchantId}" (default header). */
    @Value("${app.gmp.merchant-id}")                    private String     merchantId;

    /** Optional legacy key, sent as X-API-Key if set. Not the GH secret key. */
    @Value("${app.gmp.api-key:}")                       private String     apiKey;

    /**
     * GHANA secret API key. Sent per-request as "Authorization: Api-Key {key}",
     * overriding the NG Bearer default header.
     * If blank we fall back to the merchant-id Bearer and log loudly — expect 401s.
     */
    @Value("${app.gmp.gh-api-key:}")                    private String     ghApiKey;

    /** GH accepts Api-Key (preferred), Bearer or Token with the same key. */
    @Value("${app.gmp.gh-auth-scheme:Api-Key}")         private String     ghAuthScheme;

    @Value("${app.gmp.base-url:https://backendpay.tipsterhub.online}")
    private String rawBaseUrl;

    /**
     * Deliberately separate from app.platform.min-deposit-amount, which is shared
     * with PaystackController (default 300 there) and would silently override the
     * intended GHS / NGN minimums.
     */
    @Value("${app.gmp.min-deposit-ghs:1}")              private BigDecimal minGhsDeposit;
    @Value("${app.gmp.min-deposit-ngn:1000}")           private BigDecimal minNgnDeposit;

    @Value("${app.platform.frontend-url}")              private String     frontendUrl;

    /**
     * MUST be the public URL of THIS Spring app (e.g. the Railway URL), NOT the
     * frontend. Only used for the NG callback now — GH has no callback.
     */
    @Value("${app.gmp.backend-public-url}")             private String     backendPublicUrl;

    /**
     * true  (default): credit the bettor the full gross amount they paid; the
     *                  platform absorbs GMP's commission (matches Paystack).
     * false:           credit net of commission (GH: verify's net_amount as-is;
     *                  NG: derived from ng-commission-rate).
     */
    @Value("${app.gmp.credit-gross:true}")              private boolean    creditGrossAmount;

    @Value("${app.gmp.ng-commission-rate:0.15}")        private BigDecimal ngCommissionRate;

    /** Informational only for GH now — verify returns the real net_amount directly. */
    @Value("${app.gmp.gh-commission-rate:0.15}")        private BigDecimal ghCommissionRate;

    /** How long a pending tx may sit unsettled before we give up and alert. */
    @Value("${app.gmp.pending-ttl-minutes:120}")        private long       pendingTtlMinutes;

    @Value("${app.gmp.http-timeout-seconds:10}")        private long       httpTimeoutSeconds;
    @Value("${app.gmp.http-retry-attempts:2}")          private long       httpRetryAttempts;

    /** Log full (redacted) provider payloads. Turn off if logs get noisy in prod. */
    @Value("${app.gmp.log-payloads:true}")              private boolean    logPayloads;

    /** Kill switch — flips both markets to "temporarily unavailable" without a redeploy. */
    @Value("${app.gmp.enabled:true}")                   private boolean    enabled;

    /** Independent kill switch for Ghana only (e.g. provider 503s). */
    @Value("${app.gmp.gh-enabled:true}")                private boolean    ghEnabled;

    // ════════════════════════════════════════════════════════════════════════
    // Constants / collaborators / state
    // ════════════════════════════════════════════════════════════════════════

    /** NG only: echoed back verbatim by GMP as narration/description — owner recovery fallback. */
    private static final String GH_DESCRIPTION_PREFIX = "Deposit for user ";
    private static final String NG_REFERENCE_PREFIX   = "order-";

    private static final String MARKET_GH = "gh";
    private static final String MARKET_NG = "ng";

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;

    /** Built once in @PostConstruct — not per request. */
    private WebClient client;
    private Duration  timeout;
    private Duration  pendingTtl;

    /**
     * key = idempotency key for the market: GH → provider transaction_id,
     * NG → tx_ref (unchanged).
     */
    private final Map<String, PendingTx> pendingTransactions = new ConcurrentHashMap<>();

    /** Guards against two threads (poller + user verify) settling the same tx. */
    private final Set<String> settlementsInFlight = ConcurrentHashMap.newKeySet();

    /** Cheap observability counters, dumped by the heartbeat log. */
    private final AtomicLong initiatedCount = new AtomicLong();
    private final AtomicLong settledCount   = new AtomicLong();
    private final AtomicLong failedCount    = new AtomicLong();
    private final AtomicLong expiredCount   = new AtomicLong();

    private record PendingTx(UUID userId,
                             BigDecimal grossAmount,
                             String currency,
                             String market,
                             String providerReference,
                             Instant createdAt) {}

    private static class GmpUnavailableException extends RuntimeException {
        GmpUnavailableException(String message) { super(message); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Startup
    // ════════════════════════════════════════════════════════════════════════

    @PostConstruct
    void init() {
        this.timeout    = Duration.ofSeconds(httpTimeoutSeconds);
        this.pendingTtl = Duration.ofMinutes(pendingTtlMinutes);

        var builder = webClientBuilder
                .baseUrl(baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + merchantId)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader("X-API-Key", apiKey.trim());
        }
        this.client = builder.build();

        log.info("""
                 ══════════════ Global Market Pay controller initialised ══════════════
                   enabled          : {}          ghEnabled: {}
                   baseUrl          : {}
                   merchantId (NG)  : {}
                   ghApiKey   (GH)  : {}   scheme: {}
                   legacy X-API-Key : {}
                   backendPublicUrl : {}
                   frontendUrl      : {}
                   ng callback      : {}
                   gh gateway       : direct Mobile Money collection (MTN/Telecel/AirtelTigo) — NO redirect
                   ng gateway       : Flutterwave redirect checkout (card,banktransfer,ussd)
                   creditGross      : {}   (false ⇒ bettor gets GH net_amount / NG ×(1−{}%))
                   minDeposit       : GHS {} | NGN {}
                   pendingTtl       : {}   httpTimeout: {}   retries(GET only): {}
                 ═════════════════════════════════════════════════════════════════════""",
                enabled, ghEnabled, baseUrl(), mask(merchantId),
                (ghApiKey == null || ghApiKey.isBlank()) ? "<NOT SET>" : mask(ghApiKey),
                ghAuthScheme,
                (apiKey == null || apiKey.isBlank()) ? "<not set>" : mask(apiKey),
                backendPublicUrl, frontendUrl, ngCallbackUrl(),
                creditGrossAmount,
                ngCommissionRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(),
                minGhsDeposit, minNgnDeposit, pendingTtl, timeout, httpRetryAttempts);

        if (backendPublicUrl == null || backendPublicUrl.isBlank()) {
            log.error("GMP CONFIG ERROR: app.gmp.backend-public-url is empty — the NG callback " +
                    "will be malformed and no NG redirect deposit will settle from the browser.");
        } else if (frontendUrl != null && backendPublicUrl.equalsIgnoreCase(frontendUrl)) {
            log.error("GMP CONFIG WARNING: app.gmp.backend-public-url == app.platform.frontend-url. " +
                    "The NG callback must point at THIS Spring app, not the web app.");
        }
        if (merchantId == null || merchantId.isBlank()) {
            log.error("GMP CONFIG ERROR: app.gmp.merchant-id is empty — every NG call will 401.");
        }
        if (ghApiKey == null || ghApiKey.isBlank()) {
            log.error("GMP CONFIG ERROR: app.gmp.gh-api-key is empty — Ghana MoMo collection calls will " +
                    "fall back to the NG Bearer merchant-id and are expected to 401. Set the secret API key.");
        }
        log.warn("GMP NOTICE: the ZaaPay hosted-checkout flow for Ghana (POST " +
                "/api/merchant/gh/payments/initiate/, redirect + callback) is RETIRED and now returns 410 " +
                "Gone. GHS deposits are direct Mobile Money collection: /api/wallet/deposit/gmp/gh/init " +
                "expects 'phoneNumber' (NOT 'customerName'), returns a transactionId with NO checkoutUrl, " +
                "and must be polled via /api/wallet/deposit/gmp/gh/verify — there is no browser redirect " +
                "and no callback for GH anymore.");
    }

    /**
     * The GH dashboard shows a base URL that already ends in "/api/merchant/", while
     * the NG page shows the bare host. All paths in this class start with
     * "/api/merchant/...", so strip a trailing copy — either config value then works.
     */
    private String baseUrl() {
        var b = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/api/merchant")) b = b.substring(0, b.length() - "/api/merchant".length());
        return b;
    }

    private String publicBase() {
        var b = backendPublicUrl == null ? "" : backendPublicUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** NG only now — GH has no callback endpoint. */
    private String ngCallbackUrl() { return publicBase() + "/app/wallet/gmp/ng/callback"; }

    private void assertEnabled() {
        if (!enabled) {
            log.warn("assertEnabled: GMP deposits are disabled via app.gmp.enabled=false");
            throw ApiException.badRequest("Deposits are temporarily unavailable. Please try again shortly.");
        }
    }

    private void assertGhanaEnabled() {
        assertEnabled();
        if (!ghEnabled) {
            log.warn("assertGhanaEnabled: Ghana deposits are disabled via app.gmp.gh-enabled=false");
            throw ApiException.badRequest("Ghana deposits are temporarily unavailable. Please try again shortly.");
        }
    }

    /** "Api-Key {key}" (preferred) — falls back to the NG credential if unset. */
    private String ghAuthHeader() {
        if (ghApiKey == null || ghApiKey.isBlank()) {
            log.error("ghAuthHeader: app.gmp.gh-api-key is NOT SET — falling back to 'Bearer {merchant-id}', " +
                    "which the provider will almost certainly reject with 401.");
            return "Bearer " + merchantId;
        }
        var scheme = (ghAuthScheme == null || ghAuthScheme.isBlank()) ? "Api-Key" : ghAuthScheme.trim();
        return scheme + " " + ghApiKey.trim();
    }

    // ════════════════════════════════════════════════════════════════════════
    // GHANA (GHS · direct Mobile Money collection · MTN / Telecel / AirtelTigo)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/gmp/gh/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initGhanaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        assertGhanaEnabled();

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minGhsDeposit) < 0) {
            log.warn("initGhanaDeposit: REJECTED below minimum userId='{}' amount={} min={}",
                    user.getId(), amount, minGhsDeposit);
            throw ApiException.badRequest("Minimum deposit is GHS " + minGhsDeposit);
        }

        // Old ZaaPay checkout clients sent customerName. That field is dead now — warn, don't fail.
        if (req.get("customerName") != null) {
            log.warn("initGhanaDeposit: IGNORING legacy field 'customerName' userId='{}' — GHS is now a " +
                    "direct MoMo collection; update the client to send 'phoneNumber'.", user.getId());
        }

        var phoneNumber = firstRequired(req, "phoneNumber", "phone_number", "msisdn");
        var network     = optionalString(req, "network");
        var description = firstNonBlank(optionalString(req, "description"), GH_DESCRIPTION_PREFIX + user.getId());

        log.info("initGhanaDeposit: ▶ userId='{}' amount=GHS {} phone='{}' network='{}'",
                user.getId(), amount, mask(phoneNumber), network == null ? "<auto>" : network);

        var response = gmpCollectGhana(amount, phoneNumber, network, description);

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initGhanaDeposit: provider rejected collect userId='{}' payload={}",
                    user.getId(), redact(response));
            failedCount.incrementAndGet();
            throw ApiException.badRequest("Global Market Pay declined the payment request.");
        }

        var transactionId = string(response, "transaction_id");
        var reference      = string(response, "reference");

        if (isBlank(transactionId)) {
            log.error("initGhanaDeposit: MISSING transaction_id userId='{}' payload={}",
                    user.getId(), redact(response));
            throw ApiException.badRequest("Global Market Pay returned no transaction reference.");
        }

        pendingTransactions.put(transactionId, new PendingTx(
                user.getId(), amount, "GHS", MARKET_GH, reference, Instant.now()));
        initiatedCount.incrementAndGet();

        log.info("initGhanaDeposit: ✔ PENDING transactionId='{}' reference='{}' userId='{}' amount=GHS {} " +
                        "(pendingSize={})",
                transactionId, reference, user.getId(), amount, pendingTransactions.size());

        var resBody = new HashMap<String, Object>();
        resBody.put("transactionId", transactionId);
        resBody.put("reference",     nullToEmpty(reference));
        resBody.put("amount",        response.getOrDefault("amount", amount.toPlainString()));
        resBody.put("currency",      response.getOrDefault("currency", "GHS"));
        resBody.put("status",        "pending");
        resBody.put("message",       "Approve the Mobile Money prompt on your phone to complete the deposit.");
        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    @GetMapping("/api/wallet/deposit/gmp/gh/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyGhanaDeposit(
            @AuthenticationPrincipal User user,
            @RequestParam("transactionId") String transactionId) {

        return doVerifyGhana(user, transactionId);
    }

    /**
     * Legacy shim: old clients verified by the same path-variable shape used under
     * the ZaaPay flow. Kept so old app builds don't hard-fail; the path variable is
     * simply treated as the provider transaction_id now.
     */
    @Deprecated
    @GetMapping("/api/wallet/deposit/gmp/gh/verify/{transactionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyGhanaDepositLegacy(
            @AuthenticationPrincipal User user,
            @PathVariable String transactionId) {

        log.warn("verifyGhanaDepositLegacy: DEPRECATED path used userId='{}' value='{}' — " +
                        "client should call /api/wallet/deposit/gmp/gh/verify?transactionId=…",
                user.getId(), transactionId);
        return doVerifyGhana(user, transactionId);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> doVerifyGhana(User user, String transactionId) {
        var pending = pendingTransactions.get(transactionId);
        if (pending != null && !pending.userId().equals(user.getId())) {
            log.warn("verifyGhanaDeposit: OWNERSHIP MISMATCH transactionId='{}' owner='{}' caller='{}'",
                    transactionId, pending.userId(), user.getId());
            throw ApiException.badRequest("Transaction does not belong to this user.");
        }

        var result = gmpVerifyGhana(transactionId);
        var status = statusOf(result);

        log.info("verifyGhanaDeposit: transactionId='{}' userId='{}' status='{}' payload={}",
                transactionId, user.getId(), status, redact(result));

        if (isSuccess(status)) {
            settleGhanaDeposit(transactionId, result, user.getId());
        } else if (isTerminalFailure(status)) {
            log.info("verifyGhanaDeposit: terminal status='{}' transactionId='{}' — dropping pending record",
                    status, transactionId);
            pendingTransactions.remove(transactionId);
            failedCount.incrementAndGet();
        }

        var resBody = new HashMap<String, Object>();
        resBody.put("transactionId", transactionId);
        resBody.put("status",        status);
        resBody.put("currency",      "GHS");
        resBody.put("channel",       nullToEmpty(string(result, "channel")));
        resBody.put("settled",       isSuccess(status));
        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    /** No push notification exists for GH anymore — the poller is the only path to settlement besides user verify. */
    @Scheduled(fixedDelayString = "${app.gmp.gh-poll-ms:7000}")
    public void pollPendingGhanaTransactions() {
        var batch = pendingTransactions.entrySet().stream()
                .filter(e -> MARKET_GH.equals(e.getValue().market()))
                .toList();
        if (batch.isEmpty()) return;

        log.debug("pollPendingGhanaTransactions: sweeping {} pending GH tx", batch.size());

        batch.forEach(e -> {
            var transactionId = e.getKey();
            var pending        = e.getValue();
            try {
                if (expireIfStale(transactionId, pending)) return;

                var result = gmpVerifyGhana(transactionId);
                var status = statusOf(result);
                log.debug("pollPendingGhanaTransactions: transactionId='{}' status='{}' age={}",
                        transactionId, status, Duration.between(pending.createdAt(), Instant.now()));

                if (isSuccess(status)) {
                    settleGhanaDeposit(transactionId, result, null);
                } else if (isTerminalFailure(status)) {
                    log.info("pollPendingGhanaTransactions: transactionId='{}' userId='{}' status='{}' — dropping",
                            transactionId, pending.userId(), status);
                    pendingTransactions.remove(transactionId);
                    failedCount.incrementAndGet();
                }
            } catch (Exception ex) {
                // Pending record survives on purpose — retried on the next tick.
                log.error("pollPendingGhanaTransactions: verify/settle FAILED transactionId='{}' userId='{}' — will retry",
                        transactionId, pending.userId(), ex);
            }
        });
    }

    private void settleGhanaDeposit(String transactionId, Map<String, Object> verifyResult, UUID expectedUserId) {
        if (!settlementsInFlight.add(transactionId)) {
            log.debug("settleGhanaDeposit: transactionId='{}' already being settled by another thread — skipping",
                    transactionId);
            return;
        }
        try {
            var pending = pendingTransactions.get(transactionId);   // read, NOT remove

            // Unlike the old ZaaPay verify payload, this endpoint echoes back NO
            // merchant_reference / narration — the pending map is the ONLY owner
            // source. If it's gone (restart, other instance, TTL race) there is no
            // recovery path; this must be reconciled by hand.
            if (pending == null) {
                log.error("settleGhanaDeposit: ✖ CANNOT RESOLVE OWNER transactionId='{}' — no pending record " +
                                "and this provider does not echo an owner reference on verify. " +
                                "MANUAL RECONCILIATION NEEDED. payload={}",
                        transactionId, redact(verifyResult));
                return;
            }
            var userId = pending.userId();

            if (expectedUserId != null && !expectedUserId.equals(userId)) {
                log.warn("settleGhanaDeposit: OWNERSHIP MISMATCH transactionId='{}' resolved='{}' caller='{}'",
                        transactionId, userId, expectedUserId);
                throw ApiException.badRequest("Transaction does not belong to this user.");
            }

            var gross = firstDecimal(verifyResult, "gross_amount", "amount");
            var net   = firstDecimal(verifyResult, "net_amount");

            if (gross == null) {
                log.warn("settleGhanaDeposit: no gross_amount in payload transactionId='{}' — falling back to " +
                        "initiated amount {}", transactionId, pending.grossAmount());
                gross = pending.grossAmount();
            }
            if (gross == null || gross.signum() <= 0) {
                log.error("settleGhanaDeposit: ✖ NO USABLE AMOUNT transactionId='{}' payload={}",
                        transactionId, redact(verifyResult));
                return;
            }

            // Check the amount matches the order before fulfilling.
            if (gross.compareTo(pending.grossAmount()) != 0) {
                log.error("settleGhanaDeposit: ✖ AMOUNT MISMATCH transactionId='{}' userId='{}' initiated={} " +
                                "verified={} — refusing to credit, MANUAL RECONCILIATION NEEDED",
                        transactionId, userId, pending.grossAmount(), gross);
                return;   // record intentionally stays pending for investigation
            }

            var currency = string(verifyResult, "currency");
            if (currency != null && !"GHS".equalsIgnoreCase(currency.trim())) {
                log.error("settleGhanaDeposit: ✖ CURRENCY MISMATCH transactionId='{}' expected=GHS got='{}' " +
                        "— refusing to credit", transactionId, currency);
                return;
            }

            BigDecimal creditAmount;
            if (creditGrossAmount) {
                creditAmount = gross;
            } else if (net != null && net.signum() > 0) {
                creditAmount = net;
            } else {
                creditAmount = gross.multiply(BigDecimal.ONE.subtract(ghCommissionRate));
                log.warn("settleGhanaDeposit: transactionId='{}' no net_amount in payload — derived net from " +
                        "gh-commission-rate {} (gross={} → {})", transactionId, ghCommissionRate, gross, creditAmount);
            }
            creditAmount = creditAmount.setScale(2, RoundingMode.HALF_UP);

            var channel = string(verifyResult, "channel");   // MTN | Telecel | AirtelTigo
            var account = string(verifyResult, "account");

            var metadata = new HashMap<String, Object>();
            metadata.put("provider",       "gmp");
            metadata.put("gateway",        "gmp-momo-collect");
            metadata.put("market",         MARKET_GH);
            metadata.put("currency",       "GHS");
            metadata.put("transactionId",  transactionId);
            metadata.put("reference",      nullToEmpty(pending.providerReference()));
            metadata.put("grossAmount",    gross.toPlainString());
            metadata.put("netAmount",      net == null ? "" : net.toPlainString());
            metadata.put("channel",        channel == null ? "unknown" : channel);
            metadata.put("account",        mask(nullToEmpty(account)));
            metadata.put("creditMode",     creditGrossAmount ? "gross" : "net");

            log.info("settleGhanaDeposit: crediting userId='{}' transactionId='{}' amount=GHS {} " +
                            "(gross={} net={} channel='{}' mode={})",
                    userId, transactionId, creditAmount, gross, net, channel,
                    creditGrossAmount ? "gross" : "net");

            creditWallet(userId, creditAmount, transactionId, metadata, "GHS");

            pendingTransactions.remove(transactionId);   // only after a confirmed credit
            settledCount.incrementAndGet();
            log.info("settleGhanaDeposit: ✔ SETTLED GHS {} userId='{}' transactionId='{}' (pendingSize={})",
                    creditAmount, userId, transactionId, pendingTransactions.size());
        } finally {
            settlementsInFlight.remove(transactionId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NIGERIA (NGN · redirect checkout)   ── UNCHANGED ──
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/gmp/ng/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initNigeriaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        assertEnabled();

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minNgnDeposit) < 0) {
            log.warn("initNigeriaDeposit: REJECTED below minimum userId='{}' amount={} min={}",
                    user.getId(), amount, minNgnDeposit);
            throw ApiException.badRequest("Minimum deposit is NGN " + minNgnDeposit);
        }

        // Docs: there is NO customer_email field anywhere — GMP handles email internally.
        var customerName = required(req, "customerName");

        // This format is load-bearing: it comes back as merchant_reference in verify
        // and is parsed by userIdFromMerchantReference() to recover the owner.
        var merchantReference = NG_REFERENCE_PREFIX + user.getId() + "-" + UUID.randomUUID();

        log.info("initNigeriaDeposit: ▶ userId='{}' amount=NGN {} customer='{}' merchantReference='{}'",
                user.getId(), amount, mask(customerName), merchantReference);

        var response = gmpInitiateNigeria(
                amount,
                customerName,
                ngCallbackUrl(),                              // must hit THIS backend
                GH_DESCRIPTION_PREFIX + user.getId(),         // narration, shown to the customer
                merchantReference);

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initNigeriaDeposit: GMP rejected initiate userId='{}' payload={}",
                    user.getId(), redact(response));
            failedCount.incrementAndGet();
            throw ApiException.badRequest("Global Market Pay declined the payment request.");
        }

        var txRef       = string(response, "tx_ref");
        var checkoutUrl = string(response, "checkout_url");

        if (isBlank(txRef)) {
            log.error("initNigeriaDeposit: MISSING tx_ref userId='{}' payload={}", user.getId(), redact(response));
            throw ApiException.badRequest("Global Market Pay returned no transaction reference.");
        }
        if (isBlank(checkoutUrl)) {
            log.error("initNigeriaDeposit: MISSING checkout_url userId='{}' txRef='{}' payload={}",
                    user.getId(), txRef, redact(response));
            throw ApiException.badRequest("Global Market Pay did not return a checkout URL.");
        }

        pendingTransactions.put(txRef, new PendingTx(
                user.getId(), amount, "NGN", MARKET_NG, merchantReference, Instant.now()));
        initiatedCount.incrementAndGet();

        log.info("initNigeriaDeposit: ✔ PENDING txRef='{}' userId='{}' amount=NGN {} checkoutUrl='{}' (pendingSize={})",
                txRef, user.getId(), amount, checkoutUrl, pendingTransactions.size());

        var resBody = new HashMap<String, Object>();
        resBody.put("txRef",       txRef);
        resBody.put("checkoutUrl", checkoutUrl);
        resBody.put("amount",      response.getOrDefault("amount", amount.toPlainString()));
        resBody.put("currency",    response.getOrDefault("currency", "NGN"));
        resBody.put("status",      "pending");
        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    /**
     * Browser redirect target from GMP. The query params use the CALLBACK vocabulary
     * (successful | failed | pending) and are NEVER trusted — we always re-verify,
     * which answers in the VERIFY vocabulary (success | failed | pending).
     *
     * Must be permitAll() in SecurityConfig — a provider redirect carries no auth.
     */
    @GetMapping("/app/wallet/gmp/ng/callback")
    public ResponseEntity<Void> nigeriaCallback(
            @RequestParam(value = "tx_ref", required = false) String txRef,
            @RequestParam(value = "status", required = false) String unverifiedStatus,
            @RequestParam(value = "transaction_id", required = false) String flutterwaveTxnId,
            @RequestParam Map<String, String> allParams) {

        log.info("nigeriaCallback: ◀ txRef='{}' unverifiedStatus='{}' fwTxnId='{}' allParams={}",
                txRef, unverifiedStatus, flutterwaveTxnId, allParams);

        if (isBlank(txRef)) {
            log.error("nigeriaCallback: ✖ NO tx_ref in redirect — cannot verify. params={}", allParams);
            return redirectToWallet("failed", "");
        }

        String uiStatus;
        try {
            var result = gmpVerifyNigeria(txRef);
            var status = statusOf(result);
            log.info("nigeriaCallback: verified txRef='{}' verifyStatus='{}' (callback claimed '{}') payload={}",
                    txRef, status, unverifiedStatus, redact(result));

            if (isSuccess(status)) {
                settleNigeriaDeposit(txRef, result, null);
                uiStatus = "success";
            } else if (isTerminalFailure(status)) {
                log.info("nigeriaCallback: terminal failure txRef='{}' — dropping pending record", txRef);
                pendingTransactions.remove(txRef);
                failedCount.incrementAndGet();
                uiStatus = "failed";
            } else {
                log.info("nigeriaCallback: still pending txRef='{}' — leaving it to the poller", txRef);
                uiStatus = "pending";
            }
        } catch (Exception ex) {
            log.error("nigeriaCallback: ✖ verify FAILED txRef='{}' — leaving to poller, showing 'pending'", txRef, ex);
            uiStatus = "pending";
        }
        return redirectToWallet(uiStatus, txRef);
    }

    private ResponseEntity<Void> redirectToWallet(String uiStatus, String txRef) {
        var redirectUrl = frontendUrl + "/app/wallet?payment=" + uiStatus
                + "&tx_ref=" + URLEncoder.encode(txRef == null ? "" : txRef, StandardCharsets.UTF_8);
        log.info("redirectToWallet: 302 → '{}'", redirectUrl);
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, redirectUrl).build();
    }

    @GetMapping("/api/wallet/deposit/gmp/ng/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyNigeriaDeposit(
            @AuthenticationPrincipal User user,
            @RequestParam("txRef") String txRef) {

        var pending = pendingTransactions.get(txRef);
        if (pending != null && !pending.userId().equals(user.getId())) {
            log.warn("verifyNigeriaDeposit: OWNERSHIP MISMATCH txRef='{}' owner='{}' caller='{}'",
                    txRef, pending.userId(), user.getId());
            throw ApiException.badRequest("Transaction does not belong to this user.");
        }

        var result = gmpVerifyNigeria(txRef);
        var status = statusOf(result);

        log.info("verifyNigeriaDeposit: txRef='{}' userId='{}' status='{}' payload={}",
                txRef, user.getId(), status, redact(result));

        if (isSuccess(status)) {
            settleNigeriaDeposit(txRef, result, user.getId());
        } else if (isTerminalFailure(status)) {
            pendingTransactions.remove(txRef);
            failedCount.incrementAndGet();
        }

        var resBody = new HashMap<String, Object>();
        resBody.put("txRef",       txRef);
        resBody.put("status",      status);
        resBody.put("currency",    "NGN");
        resBody.put("paymentType", nullToEmpty(string(result, "payment_type")));
        resBody.put("settled",     isSuccess(status));
        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    /** GMP auto-verifies NG transactions every 15s on their side; we mirror that cadence. */
    @Scheduled(fixedDelay = 15_000)
    public void pollPendingNigeriaTransactions() {
        var batch = pendingTransactions.entrySet().stream()
                .filter(e -> MARKET_NG.equals(e.getValue().market()))
                .toList();
        if (batch.isEmpty()) return;

        log.debug("pollPendingNigeriaTransactions: sweeping {} pending NG tx", batch.size());

        batch.forEach(e -> {
            var txRef   = e.getKey();
            var pending = e.getValue();
            try {
                if (expireIfStale(txRef, pending)) return;

                var result = gmpVerifyNigeria(txRef);
                var status = statusOf(result);
                log.debug("pollPendingNigeriaTransactions: txRef='{}' status='{}' age={}",
                        txRef, status, Duration.between(pending.createdAt(), Instant.now()));

                if (isSuccess(status)) {
                    settleNigeriaDeposit(txRef, result, null);
                } else if (isTerminalFailure(status)) {
                    log.info("pollPendingNigeriaTransactions: txRef='{}' userId='{}' status='{}' — dropping",
                            txRef, pending.userId(), status);
                    pendingTransactions.remove(txRef);
                    failedCount.incrementAndGet();
                }
            } catch (Exception ex) {
                log.error("pollPendingNigeriaTransactions: verify/settle FAILED txRef='{}' userId='{}' — will retry",
                        txRef, pending.userId(), ex);
            }
        });
    }

    private void settleNigeriaDeposit(String txRef, Map<String, Object> verifyResult, UUID expectedUserId) {
        if (!settlementsInFlight.add(txRef)) {
            log.debug("settleNigeriaDeposit: txRef='{}' already being settled by another thread — skipping", txRef);
            return;
        }
        try {
            var pending = pendingTransactions.get(txRef);

            var merchantReference = string(verifyResult, "merchant_reference");
            var userId = pending != null
                    ? pending.userId()
                    : userIdFromMerchantReference(merchantReference);

            if (userId == null) {
                log.error("settleNigeriaDeposit: ✖ CANNOT RESOLVE OWNER txRef='{}' merchantReference='{}' — " +
                                "MANUAL RECONCILIATION NEEDED. payload={}",
                        txRef, merchantReference, redact(verifyResult));
                return;
            }
            if (pending == null) {
                log.warn("settleNigeriaDeposit: pending record absent for txRef='{}' (restart / other instance) — " +
                        "recovered userId='{}' from merchant_reference", txRef, userId);
            }
            if (expectedUserId != null && !expectedUserId.equals(userId)) {
                log.warn("settleNigeriaDeposit: OWNERSHIP MISMATCH txRef='{}' resolved='{}' caller='{}'",
                        txRef, userId, expectedUserId);
                throw ApiException.badRequest("Transaction does not belong to this user.");
            }

            // Docs: verify "amount" = what the customer paid (gross). The 15% comes
            // out of the merchant payout, not out of this figure.
            var gross = firstDecimal(verifyResult, "amount", "gross_amount");
            if (gross == null && pending != null) {
                log.warn("settleNigeriaDeposit: no amount in payload txRef='{}' — falling back to initiated amount {}",
                        txRef, pending.grossAmount());
                gross = pending.grossAmount();
            }
            if (gross == null || gross.signum() <= 0) {
                log.error("settleNigeriaDeposit: ✖ NO USABLE AMOUNT txRef='{}' payload={}", txRef, redact(verifyResult));
                return;
            }

            // Docs: "check the amount matches your order before fulfilling".
            if (pending != null && gross.compareTo(pending.grossAmount()) != 0) {
                log.error("settleNigeriaDeposit: ✖ AMOUNT MISMATCH txRef='{}' userId='{}' initiated={} verified={} " +
                                "— refusing to credit, MANUAL RECONCILIATION NEEDED",
                        txRef, userId, pending.grossAmount(), gross);
                return;   // record intentionally stays pending for investigation
            }

            var currency = string(verifyResult, "currency");
            if (currency != null && !"NGN".equalsIgnoreCase(currency.trim())) {
                log.error("settleNigeriaDeposit: ✖ CURRENCY MISMATCH txRef='{}' expected=NGN got='{}' — refusing to credit",
                        txRef, currency);
                return;
            }

            var creditAmount = (creditGrossAmount
                    ? gross
                    : gross.multiply(BigDecimal.ONE.subtract(ngCommissionRate)))
                    .setScale(2, RoundingMode.HALF_UP);

            var paymentType = string(verifyResult, "payment_type");   // card | banktransfer | ussd

            var metadata = new HashMap<String, Object>();
            metadata.put("provider",           "gmp");
            metadata.put("gateway",            "flutterwave");
            metadata.put("market",             MARKET_NG);
            metadata.put("currency",           "NGN");
            metadata.put("txRef",              txRef);
            metadata.put("transactionId",      nullToEmpty(string(verifyResult, "transaction_id")));
            metadata.put("merchantReference",  nullToEmpty(merchantReference));
            metadata.put("grossAmount",        gross.toPlainString());
            metadata.put("paymentType",        paymentType == null ? "unknown" : paymentType);
            metadata.put("creditMode",         creditGrossAmount ? "gross" : "net");

            log.info("settleNigeriaDeposit: crediting userId='{}' txRef='{}' amount=NGN {} " +
                            "(gross={} paymentType='{}' mode={})",
                    userId, txRef, creditAmount, gross, paymentType, creditGrossAmount ? "gross" : "net");

            creditWallet(userId, creditAmount, txRef, metadata, "NGN");

            pendingTransactions.remove(txRef);
            settledCount.incrementAndGet();
            log.info("settleNigeriaDeposit: ✔ SETTLED NGN {} userId='{}' txRef='{}' (pendingSize={})",
                    creditAmount, userId, txRef, pendingTransactions.size());
        } finally {
            settlementsInFlight.remove(txRef);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Shared: pending inspection + heartbeat
    // ════════════════════════════════════════════════════════════════════════

    /** Lets the wallet screen re-attach to an in-flight deposit after a refresh. */
    @GetMapping("/api/wallet/deposit/gmp/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myPendingDeposits(
            @AuthenticationPrincipal User user) {

        var mine = new ArrayList<Map<String, Object>>();
        pendingTransactions.forEach((key, tx) -> {
            if (!tx.userId().equals(user.getId())) return;
            var row = new HashMap<String, Object>();
            row.put("key",       key);
            row.put("txRef",     key);          // NG: tx_ref, GH: transaction_id (same map, dual purpose key)
            row.put("market",    tx.market());
            row.put("currency",  tx.currency());
            row.put("amount",    tx.grossAmount().toPlainString());
            row.put("reference", nullToEmpty(tx.providerReference()));
            row.put("createdAt", tx.createdAt().toString());
            row.put("ageSeconds", Duration.between(tx.createdAt(), Instant.now()).toSeconds());
            mine.add(row);
        });
        mine.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));

        log.debug("myPendingDeposits: userId='{}' pending={}", user.getId(), mine.size());
        return ResponseEntity.ok(ApiResponse.ok(mine));
    }

    /** Periodic one-line health print — makes stuck money obvious in the logs. */
    @Scheduled(fixedDelay = 300_000)
    public void logHeartbeat() {
        var gh = pendingTransactions.values().stream().filter(t -> MARKET_GH.equals(t.market())).count();
        var ng = pendingTransactions.values().stream().filter(t -> MARKET_NG.equals(t.market())).count();
        if (gh + ng == 0 && settledCount.get() == 0 && failedCount.get() == 0) return;

        log.info("GMP heartbeat: pending[gh={} ng={}] inFlight={} | initiated={} settled={} failed={} expired={}",
                gh, ng, settlementsInFlight.size(),
                initiatedCount.get(), settledCount.get(), failedCount.get(), expiredCount.get());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Wallet crediting — mirrors PaystackController.handleDeposit()
    // ════════════════════════════════════════════════════════════════════════

    private void creditWallet(UUID userId, BigDecimal amount, String idempotencyRef,
                              Map<String, Object> metadata, String currency) {

        if (isBlank(idempotencyRef)) {
            log.error("creditWallet: ✖ refusing to credit userId='{}' amount={} without an idempotency reference",
                    userId, amount);
            throw new IllegalStateException("Refusing to credit without an idempotency reference");
        }

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, idempotencyRef, metadata);
            log.info("creditWallet: ✔ {} {} credited userId='{}' ref='{}' metadata={}",
                    currency, amount, userId, idempotencyRef, metadata);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                // Already credited on a previous pass (poller vs user verify).
                log.warn("creditWallet: DUPLICATE ref='{}' userId='{}' already processed — skipping " +
                        "(commission was attributed on the first pass)", idempotencyRef, userId);
                return;
            }
            log.error("creditWallet: ✖ wallet credit FAILED userId='{}' ref='{}' amount={} {} status={}",
                    userId, idempotencyRef, currency, amount, ex.getStatus().value(), ex);
            throw ex;
        } catch (Exception ex) {
            log.error("creditWallet: ✖ unexpected wallet failure userId='{}' ref='{}' amount={} {}",
                    userId, idempotencyRef, currency, amount, ex);
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("creditWallet: commission attributed userId='{}' deposit={} {} ref='{}'",
                    userId, currency, amount, idempotencyRef);
        } catch (Exception ex) {
            // A deposit is NEVER blocked by a referral failure.
            log.error("creditWallet: commission attribution FAILED userId='{}' ref='{}' — deposit stands, investigate",
                    userId, idempotencyRef, ex);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Status + recovery helpers
    // ════════════════════════════════════════════════════════════════════════

    /** GH verify vocabulary: success|failed|pending. NG verify: same; NG callback: successful|failed|pending. */
    private static boolean isSuccess(String status) {
        return "success".equals(status) || "successful".equals(status);
    }

    private static boolean isTerminalFailure(String status) {
        return "failed".equals(status) || "decline".equals(status)
                || "declined".equals(status) || "cancelled".equals(status) || "canceled".equals(status);
    }

    private static String statusOf(Map<String, Object> payload) {
        var status = payload == null ? null : payload.get("status");
        return status == null || status.toString().isBlank()
                ? "pending"
                : status.toString().trim().toLowerCase();
    }

    private boolean expireIfStale(String key, PendingTx pending) {
        if (pending.createdAt().plus(pendingTtl).isAfter(Instant.now())) return false;

        pendingTransactions.remove(key);
        expiredCount.incrementAndGet();
        log.error("expireIfStale: ✖ pending {} tx key='{}' userId='{}' amount={} {} exceeded {} without settling " +
                        "— a customer who never approves the MoMo prompt (GH) or never completes checkout (NG) " +
                        "stays 'pending' forever; VERIFY MANUALLY on the GMP dashboard before crediting by hand",
                pending.market(), key, pending.userId(), pending.currency(), pending.grossAmount(), pendingTtl);
        return true;
    }

    /** Parses the userId back out of "order-{userId}-{uuid}" (echoed as merchant_reference). NG only. */
    private static UUID userIdFromMerchantReference(String merchantReference) {
        if (merchantReference == null || !merchantReference.startsWith(NG_REFERENCE_PREFIX)) return null;
        var rest = merchantReference.substring(NG_REFERENCE_PREFIX.length());
        if (rest.length() < 36) return null;
        try {
            return UUID.fromString(rest.substring(0, 36));
        } catch (IllegalArgumentException ex) {
            log.warn("userIdFromMerchantReference: unparseable merchantReference='{}'", merchantReference);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // GMP API calls
    // ════════════════════════════════════════════════════════════════════════

    /** GH: direct Mobile Money collection. Sends a MoMo approval prompt to the customer's phone. */
    private Map<String, Object> gmpCollectGhana(BigDecimal amount, String phoneNumber,
                                                String network, String description) {
        var body = new HashMap<String, Object>();
        body.put("phone_number", phoneNumber);
        body.put("amount",       amount);
        if (description != null && !description.isBlank()) body.put("description", description);
        // network is optional — auto-detected from the number prefix if omitted.
        if (network != null && !network.isBlank()) body.put("network", network);
        return call("POST", "/api/merchant/api/collect/", body, "gh.collect", ghAuthHeader());
    }

    /** GH: verify by the provider-issued transaction_id from the collect response. */
    private Map<String, Object> gmpVerifyGhana(String transactionId) {
        return call("GET", "/api/merchant/api/verify/" + enc(transactionId) + "/",
                null, "gh.verify", ghAuthHeader());
    }

    private Map<String, Object> gmpInitiateNigeria(BigDecimal amount, String customerName,
                                                   String callbackUrl, String narration,
                                                   String merchantReference) {
        var body = new HashMap<String, Object>();
        body.put("amount",             amount);
        body.put("currency",           "NGN");
        body.put("customer_name",      customerName);
        body.put("callback_url",       callbackUrl);
        body.put("narration",          narration);
        body.put("merchant_reference", merchantReference);
        body.put("payment_options",    "card,banktransfer,ussd");
        // NOTE: no customer_email — GMP explicitly does not accept one.
        return call("POST", "/api/merchant/ng/payments/initiate/", body, "ng.initiate", null);
    }

    private Map<String, Object> gmpVerifyNigeria(String txRef) {
        return call("GET", "/api/merchant/ng/payments/verify/?tx_ref=" + enc(txRef), null, "ng.verify", null);
    }

    /**
     * Single HTTP entry point.
     *
     * Resilience rules:
     *   - {@code app.gmp.http-timeout-seconds} per attempt.
     *   - Retries ONLY on GET (verify). POSTs fail fast — retrying a collect/initiate
     *     after a timeout can create a SECOND provider transaction while we only
     *     track one ref (for GH specifically: a second MoMo prompt to the customer).
     *   - 4xx → ApiException (surfaces the provider's validation message; 410 means
     *     an old client hit the retired GH redirect-checkout endpoint).
     *   - 5xx / network → GmpUnavailableException (e.g. 503 = GH collections down).
     *
     * @param authHeader per-request Authorization override (GH uses "Api-Key {key}");
     *                   null keeps the WebClient default ("Bearer {merchantId}", NG).
     *
     * Every call gets a short correlation id so a request can be followed end-to-end.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, Map<String, Object> body,
                                     String opName, String authHeader) {
        var isGet   = !"POST".equalsIgnoreCase(method);
        var traceId = UUID.randomUUID().toString().substring(0, 8);
        var url     = baseUrl() + path;
        var started = System.nanoTime();

        log.info("GMP ▶ [{}] {} {} {} auth='{}'", traceId, opName, method, url,
                authHeader == null ? "default(Bearer merchant-id)" : authScheme(authHeader));
        if (body != null && logPayloads) {
            log.debug("GMP ▶ [{}] request body={}", traceId, redact(body));
        }

        WebClient.RequestHeadersSpec<?> spec = isGet
                ? client.get().uri(path)
                : client.post().uri(path)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body);

        if (authHeader != null && !authHeader.isBlank()) {
            // Explicitly set headers win over WebClient defaults.
            spec = spec.header(HttpHeaders.AUTHORIZATION, authHeader);
        }

        var mono = spec
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(respBody -> {
                                    log.error("GMP ✖ [{}] {} 4xx status={} body={} url={} {}",
                                            traceId, opName, resp.statusCode(), respBody, url,
                                            hintFor4xx(resp.statusCode().value()));
                                    return ApiException.badRequest(
                                            "Payment gateway rejected the request: " + respBody);
                                }))
                .onStatus(HttpStatusCode::is5xxServerError,
                        resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(respBody -> {
                                    log.error("GMP ✖ [{}] {} 5xx status={} body={} url={}",
                                            traceId, opName, resp.statusCode(), respBody, url);
                                    return new GmpUnavailableException(
                                            "Global Market Pay returned " + resp.statusCode());
                                }))
                .bodyToMono(Map.class)
                .timeout(timeout);

        if (isGet) {
            mono = mono.retryWhen(
                    Retry.backoff(httpRetryAttempts, Duration.ofMillis(300))
                            .filter(ex -> !(ex instanceof ApiException))
                            .doBeforeRetry(sig -> log.warn("GMP ↻ [{}] {} retry #{} after: {}",
                                    traceId, opName, sig.totalRetries() + 1,
                                    sig.failure() == null ? "?" : sig.failure().toString()))
                            .onRetryExhaustedThrow((retrySpec, signal) -> signal.failure()));
        }

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) mono.block();
        } catch (ApiException | GmpUnavailableException ex) {
            log.error("GMP ✖ [{}] {} failed after {}ms: {}", traceId, opName, elapsedMs(started), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("GMP ✖ [{}] {} UNREACHABLE after {}ms — {} {}",
                    traceId, opName, elapsedMs(started), method, url, ex);
            throw new GmpUnavailableException("Global Market Pay is currently unavailable. Please try again.");
        }

        if (result == null) {
            log.error("GMP ✖ [{}] {} returned an EMPTY body after {}ms", traceId, opName, elapsedMs(started));
            throw new GmpUnavailableException("Global Market Pay returned an empty response.");
        }

        if (logPayloads) {
            log.info("GMP ◀ [{}] {} ok {}ms payload={}", traceId, opName, elapsedMs(started), redact(result));
        } else {
            log.info("GMP ◀ [{}] {} ok {}ms status='{}'", traceId, opName, elapsedMs(started), result.get("status"));
        }
        return result;
    }

    /** Documented error codes — printed alongside the raw body so the cause is obvious in prod logs. */
    private static String hintFor4xx(int status) {
        return switch (status) {
            case 400 -> "→ missing/invalid phone_number or amount (GH), or bad amount/callback_url (NG)";
            case 401 -> "→ missing or invalid API key (check app.gmp.gh-api-key / app.gmp.merchant-id)";
            case 403 -> "→ merchant account not approved for this market";
            case 410 -> "→ retired endpoint — a client hit the old ZaaPay redirect-checkout route for GHS; " +
                    "GH is now direct MoMo collection only";
            case 429 -> "→ collection limit exceeded";
            default  -> "";
        };
    }

    /** Logs only the scheme of an Authorization header, never the credential. */
    private static String authScheme(String header) {
        var idx = header.indexOf(' ');
        return (idx <= 0 ? header : header.substring(0, idx)) + " " + mask(header);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Input / logging helpers
    // ════════════════════════════════════════════════════════════════════════

    private static String string(Map<String, Object> src, String key) {
        var value = src == null ? null : src.get(key);
        return value == null ? null : value.toString();
    }

    /** GMP returns amounts as strings ("100.00", "5000.00"); tolerate "1,000.00" too. */
    private static BigDecimal firstDecimal(Map<String, Object> src, String... keys) {
        for (var key : keys) {
            var value = src == null ? null : src.get(key);
            if (value == null) continue;
            try {
                var text = value.toString().replace(",", "").trim();
                if (!text.isEmpty()) return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                log.warn("firstDecimal: field '{}' is not numeric: {}", key, value);
            }
        }
        return null;
    }

    private static BigDecimal parseAmount(Map<String, Object> req) {
        var raw = required(req, "amount");
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            log.warn("parseAmount: invalid amount '{}'", raw);
            throw ApiException.badRequest("Invalid amount: " + raw);
        }
        if (amount.signum() <= 0) throw ApiException.badRequest("Amount must be greater than zero.");
        return amount;
    }

    private static String required(Map<String, Object> req, String field) {
        var value = req == null ? null : req.get(field);
        if (value == null || value.toString().isBlank()) {
            log.warn("required: missing field '{}' in request body", field);
            throw ApiException.badRequest("Missing required field: " + field);
        }
        return value.toString().trim();
    }

    /** Accepts the first present alias — lets older clients keep working. */
    private static String firstRequired(Map<String, Object> req, String... fields) {
        for (var field : fields) {
            var value = req == null ? null : req.get(field);
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        log.warn("firstRequired: none of {} present in request body", List.of(fields));
        throw ApiException.badRequest("Missing required field: " + fields[0]);
    }

    private static String optionalString(Map<String, Object> req, String field) {
        var value = req == null ? null : req.get(field);
        if (value == null) return null;
        var text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String a, String b) { return isBlank(a) ? b : a; }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    /** Keeps only the last 3 characters — used for phones, names and credentials in logs. */
    private static String mask(String value) {
        if (value == null || value.length() < 4) return "***";
        return "*".repeat(Math.min(value.length() - 3, 12)) + value.substring(value.length() - 3);
    }

    /** Copy of a payload with PII masked, so full provider bodies can be logged safely. */
    private static Map<String, Object> redact(Map<String, Object> payload) {
        if (payload == null) return Map.of();
        var copy = new HashMap<String, Object>(payload);
        for (var key : List.of("phone_number", "account", "customer_name", "customer_email",
                "email", "msisdn", "card", "authorization")) {
            var value = copy.get(key);
            if (value != null) copy.put(key, mask(value.toString()));
        }
        return copy;
    }
}
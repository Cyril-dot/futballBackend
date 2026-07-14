package com.speedbet.api.payment.expresspay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedbet.api.chat.AdminUpgradeChatService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ExpressPayController {

    private static final int    ADMIN_UPGRADE_FEE_GHS = 200;
    private static final String UPGRADE_INTENT_ADMIN  = "admin";
    private static final String RESULT_APPROVED       = "1";
    private static final String RESULT_PENDING        = "4";

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * Admins earn 70% of the configured platform commission on each referred deposit.
     * The actual per-admin rate is stored on the Referral entity (set during
     * upgradeToAdmin) and resolved inside ReferralService.attributeCommission().
     * This constant is for logging/documentation purposes only.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    /** Shared, reusable ObjectMapper for manual JSON parsing (see expressPayPost). */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** How long to wait for expressPay to respond before timing out. */
    private final Duration expressPayTimeout = Duration.ofSeconds(10);

    /**
     * How many times to retry on transient network failures (e.g. "Connection reset
     * by peer"). Does NOT retry on expressPay 4xx/5xx — those are mapped to a
     * RuntimeException by the onStatus handler and are therefore excluded from retry.
     */
    private final long expressPayRetryAttempts = 2;

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;

    @Value("${app.expresspay.merchant-id}")                       private String     merchantId;
    @Value("${app.expresspay.api-key}")                           private String     apiKey;
    @Value("${app.expresspay.base-url:https://expresspaygh.com}") private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:3}")              private BigDecimal minDeposit;
    @Value("${app.platform.backend-url}")                         private String     backendUrl; // used to build the post-url callback

    /**
     * Transactions we've submitted to expressPay but not yet confirmed, keyed
     * by our own order-id. expressPay has no metadata field like Paystack, so
     * we track userId/amount/intent ourselves here.
     *
     * NOTE: this is in-memory for illustration only. In production this must
     * be a database table (unique constraint on order_id) so it survives
     * restarts and works correctly across multiple app instances.
     */
    private final Map<String, PendingTx> pendingTransactions = new ConcurrentHashMap<>();

    private record PendingTx(UUID userId, BigDecimal amount, String intent, AtomicBoolean processed) {
    }

    public record CardDetails(
            String cardNumber,
            String cardHolderName,
            String cardExpiry,
            String cardCvv,
            String cardAddress,
            String cardCity,
            String cardState,
            String cardZipcode,
            String cardCountry) {
    }

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/expresspay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var orderId = UUID.randomUUID().toString();
        pendingTransactions.put(orderId, new PendingTx(user.getId(), amount, "deposit", new AtomicBoolean(false)));

        log.info("initDeposit: userId='{}' amount={} orderId='{}'", user.getId(), amount, orderId);

        var response = expressPaySubmit(amount, orderId);

        log.info("initDeposit: expressPay responded status='{}' orderId='{}' userId='{}'",
                response.get("status"), orderId, user.getId());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "token", response.get("token"),
                "orderId", orderId
        )));
    }

    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    @PostMapping("/api/user/upgrade-to-admin/expresspay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var orderId = UUID.randomUUID().toString();
        var amount  = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_GHS);
        pendingTransactions.put(orderId, new PendingTx(user.getId(), amount, UPGRADE_INTENT_ADMIN, new AtomicBoolean(false)));

        log.info("initAdminUpgrade: userId='{}' email='{}' orderId='{}'", user.getId(), user.getEmail(), orderId);

        var response = expressPaySubmit(amount, orderId);

        log.info("initAdminUpgrade: expressPay responded status='{}' orderId='{}' userId='{}'",
                response.get("status"), orderId, user.getId());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "token", response.get("token"),
                "orderId", orderId
        )));
    }

    // ─── Charge: Card (domestic + international) ────────────────────────────

    @PostMapping("/api/wallet/deposit/expresspay/charge/card")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chargeCard(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var token = require(req, "token");
        var card = new CardDetails(
                require(req, "cardNumber"),
                require(req, "cardHolderName"),
                require(req, "cardExpiry"),
                require(req, "cardCvv"),
                (String) req.get("cardAddress"),
                (String) req.get("cardCity"),
                (String) req.get("cardState"),
                (String) req.get("cardZipcode"),
                (String) req.get("cardCountry")
        );

        log.info("chargeCard: userId='{}' token='{}' country='{}'", user.getId(), mask(token), card.cardCountry());

        var result = expressPayCheckoutCard(token, card);
        return handleCheckoutResult(user, result);
    }

    // ─── Charge: Mobile Money (incl. USSD-initiated payments) ──────────────────

    @PostMapping("/api/wallet/deposit/expresspay/charge/momo")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chargeMomo(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var token           = require(req, "token");
        var mobileNumber    = require(req, "mobileNumber");
        var mobileNetwork   = require(req, "mobileNetwork"); // MTN_MM | AIRTEL_MM | TIGO_CASH | VODAFONE_CASH
        var mobileAuthToken = (String) req.get("mobileAuthToken");

        log.info("chargeMomo: userId='{}' token='{}' network='{}'", user.getId(), mask(token), mobileNetwork);

        var result = expressPayCheckoutMomo(token, mobileNumber, mobileNetwork, mobileAuthToken);
        return handleCheckoutResult(user, result);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> handleCheckoutResult(
            User user, Map<String, Object> result) {

        var resultCode = String.valueOf(result.get("result"));
        var orderId    = String.valueOf(result.get("order-id"));

        if (RESULT_APPROVED.equals(resultCode)) {
            log.info("handleCheckoutResult: APPROVED orderId='{}' userId='{}'", orderId, user.getId());
            finalizeTransaction(orderId, String.valueOf(result.get("transaction-id")));
        } else if (RESULT_PENDING.equals(resultCode)) {
            log.info("handleCheckoutResult: PENDING orderId='{}' userId='{}' — awaiting webhook callback",
                    orderId, user.getId());
        } else {
            log.warn("handleCheckoutResult: NOT APPROVED orderId='{}' userId='{}' result='{}' text='{}'",
                    orderId, user.getId(), resultCode, result.get("result-text"));
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook (post-url callback) ────────────────────────────────────────

    /**
     * expressPay invokes this URL when a pending mobile-money payment resolves
     * (this is also where USSD-completed payments surface). Unlike Paystack,
     * expressPay's callback carries no signature header, so the payload itself
     * is never trusted for crediting funds — we always re-confirm with a
     * server-to-server Query call first.
     */
    @PostMapping("/api/webhooks/expresspay")
    public ResponseEntity<String> webhook(@RequestBody Map<String, Object> body) {

        var orderId = body.get("order-id") != null ? body.get("order-id").toString() : null;
        var token   = body.get("token") != null ? body.get("token").toString() : null;

        log.info("expressPay webhook: received callback orderId='{}' token='{}'", orderId, mask(token));

        if (orderId == null || orderId.isBlank() || token == null || token.isBlank()) {
            log.warn("expressPay webhook: missing order-id or token in payload");
            return ResponseEntity.status(400).body("Missing order-id or token");
        }

        var pending = pendingTransactions.get(orderId);
        if (pending == null) {
            log.warn("expressPay webhook: unknown orderId='{}' — ignoring", orderId);
            return ResponseEntity.ok("Ignored — unknown order");
        }

        try {
            var queryResult = expressPayQuery(token);
            var resultCode   = String.valueOf(queryResult.get("result"));

            log.info("expressPay webhook: query confirmed orderId='{}' result='{}' text='{}'",
                    orderId, resultCode, queryResult.get("result-text"));

            if (RESULT_APPROVED.equals(resultCode)) {
                finalizeTransaction(orderId, String.valueOf(queryResult.get("transaction-id")));
            } else {
                log.warn("expressPay webhook: orderId='{}' not approved (result='{}') — no wallet action taken",
                        orderId, resultCode);
            }
        } catch (Exception e) {
            log.error("expressPay webhook: unexpected error confirming orderId='{}' — expressPay will retry callback",
                    orderId, e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Finalization (idempotent) ──────────────────────────────────────────

    private void finalizeTransaction(String orderId, String transactionId) {

        var pending = pendingTransactions.get(orderId);
        if (pending == null) {
            log.warn("finalizeTransaction: orderId='{}' has no pending record — skipping", orderId);
            return;
        }

        if (!pending.processed().compareAndSet(false, true)) {
            log.info("finalizeTransaction: orderId='{}' already processed — skipping duplicate", orderId);
            return;
        }

        if (UPGRADE_INTENT_ADMIN.equals(pending.intent())) {
            handleAdminUpgrade(pending.userId(), orderId, pending.amount());
        } else {
            handleDeposit(pending.userId(), orderId, pending.amount());
        }

        pendingTransactions.remove(orderId);
        log.info("finalizeTransaction: orderId='{}' finalized and removed from pending map txId='{}'",
                orderId, transactionId);
    }

    // ─── Private wallet/user handlers ─────────────────────────────────────────

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
     */
    private void handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "expresspay", "reference", ref));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'", amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return;
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
            // Never block a deposit because of a commission failure.
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate", userId, ex);
        }
    }

    /**
     * Handles an admin upgrade payment.
     *
     * Steps:
     *   1. Validates amount >= GHS 200
     *   2. Promotes user to ADMIN + initialises their referral link at 70% commission
     *   3. Records an audit transaction (expressPay already collected the funds externally)
     *   4. Creates onboarding chat with Super Admin for commission confirmation
     */
    private void handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(BigDecimal.valueOf(ADMIN_UPGRADE_FEE_GHS)) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS {} for userId='{}' ref='{}'",
                    amount, ADMIN_UPGRADE_FEE_GHS, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS " + ADMIN_UPGRADE_FEE_GHS + ".");
        }

        try {
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

        // Audit record — expressPay collected GHS 200 externally, no wallet debit needed.
        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "expresspay", "reference", ref));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        // Create onboarding chat so Super Admin can confirm/adjust the 70% commission rate.
        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── expressPay API calls ───────────────────────────────────────────────

    /** Step 1: Submit — initiates a transaction and returns a token for checkout. */
    private Map<String, Object> expressPaySubmit(BigDecimal amount, String orderId) {

        var form = new LinkedMultiValueMap<String, String>();
        form.add("merchant-id", merchantId);
        form.add("api-key", apiKey);
        form.add("currency", "GHS");
        form.add("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        form.add("order-id", orderId);
        form.add("post-url", backendUrl + "/api/webhooks/expresspay");

        log.info("expressPay submit: orderId='{}' amount={}", orderId, amount);

        var result = expressPayPost("/api/direct/submit.php", form);
        var status = asInt(result.get("status"));

        log.info("expressPay submit: orderId='{}' status={} token-present={}",
                orderId, status, result.get("token") != null);

        if (!Integer.valueOf(1).equals(status)) {
            log.error("expressPay submit: orderId='{}' failed status={} body={}", orderId, status, result);
            throw new RuntimeException("expressPay submit failed for orderId=" + orderId + " (status=" + status + ")");
        }

        return result;
    }

    /**
     * Step 2a: Checkout — Card. Handles both domestic and international cards
     * (Visa, Mastercard, Amex, Discover) — expressPay does not distinguish
     * these at the API level, so no separate "international" call is needed.
     */
    private Map<String, Object> expressPayCheckoutCard(String token, CardDetails card) {

        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", token);
        form.add("card-number", card.cardNumber());
        form.add("card-holder-name", card.cardHolderName());
        form.add("card-expiry", card.cardExpiry());
        form.add("card-cvv", card.cardCvv());
        addIfPresent(form, "card-address", card.cardAddress());
        addIfPresent(form, "card-city", card.cardCity());
        addIfPresent(form, "card-state", card.cardState());
        addIfPresent(form, "card-zipcode", card.cardZipcode());
        addIfPresent(form, "card-country", card.cardCountry());

        log.info("expressPay checkout (card): token='{}' country='{}'", mask(token), card.cardCountry());

        var result = expressPayPost("/api/direct/checkout.php", form);

        log.info("expressPay checkout (card): token='{}' result={} text='{}'",
                mask(token), result.get("result"), result.get("result-text"));

        return result;
    }

    /**
     * Step 2b: Checkout — Mobile Money. The customer may complete authorization
     * either in-app or by dialing expressPay's *246# USSD code — both surface
     * here identically, there is no separate USSD endpoint.
     */
    private Map<String, Object> expressPayCheckoutMomo(String token, String mobileNumber,
                                                       String mobileNetwork, String mobileAuthToken) {

        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", token);
        form.add("mobile-number", mobileNumber);
        form.add("mobile-network", mobileNetwork);
        addIfPresent(form, "mobile-auth-token", mobileAuthToken);

        log.info("expressPay checkout (momo): token='{}' network='{}' number='{}'",
                mask(token), mobileNetwork, maskPhone(mobileNumber));

        var result = expressPayPost("/api/direct/checkout.php", form);

        log.info("expressPay checkout (momo): token='{}' result={} text='{}'",
                mask(token), result.get("result"), result.get("result-text"));

        return result;
    }

    /**
     * Step 4: Query — authoritative status check. expressPay's post-url callback
     * carries no signature, so this MUST be called to confirm a transaction
     * before any wallet action is taken — never trust the callback body alone.
     */
    private Map<String, Object> expressPayQuery(String token) {

        var form = new LinkedMultiValueMap<String, String>();
        form.add("merchant-id", merchantId);
        form.add("api-key", apiKey);
        form.add("token", token);

        log.info("expressPay query: token='{}'", mask(token));

        var result = expressPayPost("/api/query.php", form);

        log.info("expressPay query: token='{}' result={} text='{}' transaction-id='{}'",
                mask(token), result.get("result"), result.get("result-text"), result.get("transaction-id"));

        return result;
    }

    // ─── HTTP helper ─────────────────────────────────────────────────────────

    /**
     * Posts a form to expressPay and parses the response as JSON.
     *
     * IMPORTANT: expressPay sometimes returns a 200 OK with Content-Type
     * text/html even though the body is valid JSON (or, when something is
     * genuinely wrong — bad credentials, IP not whitelisted, WAF challenge —
     * an actual HTML error page). Relying on retrieve().bodyToMono(Map.class)
     * throws UnsupportedMediaTypeException in both cases and hides the real
     * body from the logs.
     *
     * To fix this we use exchangeToMono to read the body as a raw String
     * first, regardless of the declared Content-Type, and parse it as JSON
     * ourselves. If parsing fails, we log the raw body (truncated) so the
     * actual HTML/error text is visible for diagnosis, instead of surfacing
     * only a generic UnsupportedMediaTypeException / RetryExhaustedException.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> expressPayPost(String path, MultiValueMap<String, String> form) {

        Map<String, Object> result;
        try {
            result = webClientBuilder.build()
                    .post()
                    .uri(baseUrl + path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .exchangeToMono(clientResponse -> {
                        if (clientResponse.statusCode().isError()) {
                            return clientResponse.bodyToMono(String.class).map(body -> {
                                log.error("expressPay API error: path='{}' status={} body={}",
                                        path, clientResponse.statusCode(), body);
                                throw new RuntimeException(
                                        "expressPay returned " + clientResponse.statusCode() + ": " + body);
                            });
                        }
                        // Read as raw text first — ignore the declared Content-Type, since
                        // expressPay sometimes mislabels JSON as text/html.
                        return clientResponse.bodyToMono(String.class).map(raw -> {
                            try {
                                return (Map<String, Object>) OBJECT_MAPPER.readValue(raw, Map.class);
                            } catch (Exception parseEx) {
                                log.error("expressPay returned non-JSON body: path='{}' raw='{}'",
                                        path, truncate(raw, 300));
                                throw new RuntimeException(
                                        "expressPay returned an unexpected response (not JSON): "
                                                + truncate(raw, 300));
                            }
                        });
                    })
                    // Fail fast so we never hold a request thread indefinitely.
                    .timeout(expressPayTimeout)
                    // Retry only transient network failures (e.g. connection reset). The
                    // RuntimeException thrown directly above has no wrapped cause, so
                    // genuine 4xx/5xx responses and non-JSON bodies are excluded from retry.
                    .retryWhen(Retry.max(expressPayRetryAttempts)
                            .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                    .onErrorMap(
                            ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                            ex -> {
                                log.error("expressPay API unreachable after {} retries: path='{}'",
                                        expressPayRetryAttempts, path, ex);
                                return new RuntimeException("expressPay is currently unavailable. Please try again.");
                            })
                    .block();
        } catch (RuntimeException ex) {
            log.error("expressPay API call failed: path='{}'", path, ex);
            throw ex;
        }

        if (result == null) {
            log.error("expressPay API call: path='{}' returned empty response", path);
            throw new RuntimeException("expressPay returned an empty response.");
        }

        return result;
    }

    // ─── Small helpers ───────────────────────────────────────────────────────

    private void addIfPresent(MultiValueMap<String, String> form, String key, String value) {
        if (value != null && !value.isBlank()) {
            form.add(key, value);
        }
    }

    private String require(Map<String, Object> req, String key) {
        var value = req.get(key);
        if (value == null || value.toString().isBlank()) {
            throw ApiException.badRequest("Missing required field: " + key);
        }
        return value.toString();
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String mask(String token) {
        if (token == null || token.length() < 8) return "****";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "*".repeat(Math.max(0, phone.length() - 4)) + phone.substring(phone.length() - 4);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
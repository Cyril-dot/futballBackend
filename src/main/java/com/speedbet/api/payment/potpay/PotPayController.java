package com.speedbet.api.payment.potpay;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PotPay integration controller.
 *
 * Handles both markets exposed by PotPay's merchant API:
 *   - Ghana  (GHS, mobile money via USSD "Collect" — MTN / Vodafone / AirtelTigo)
 *   - Nigeria (NGN, redirect checkout via Flutterwave — card / bank transfer / USSD)
 *
 * Auth: PotPay uses the Merchant ID itself as the bearer token
 *   Authorization: Bearer <merchantId>
 * (Not a secret API key exchange like Paystack — treat the Merchant ID as
 * sensitive anyway and never expose it to the frontend.)
 *
 * IMPORTANT — no server-to-server webhook exists for either market in the
 * documented API surface:
 *   - Ghana:   PotPay auto-verifies against the network every 30s internally,
 *              but that only updates *their* record — your server still has
 *              to call Verify to find out. We do this via a scheduled poller.
 *   - Nigeria: PotPay redirects the *customer's browser* to callback_url with
 *              status/tx_ref query params. Per PotPay's own docs those params
 *              must never be trusted directly — the callback handler here
 *              re-verifies server-side before crediting anything. A poller
 *              also runs as a safety net for customers who close the tab
 *              before the redirect completes.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PotPayController {

    // ─── Config ─────────────────────────────────────────────────────────────

    @Value("${app.potpay.merchant-id}")            private String     merchantId;
    @Value("${app.potpay.base-url}")                private String     baseUrl; // https://backendpay.tipsterhub.online
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minGhsDeposit;
    @Value("${app.platform.min-deposit-amount-ngn:1000}") private BigDecimal minNgnDeposit;
    @Value("${app.platform.frontend-url}")          private String     frontendUrl;

    private static final BigDecimal NG_COMMISSION_RATE = new BigDecimal("0.15"); // 15%

    /** Timeout for outbound calls to PotPay. */
    private final Duration potpayTimeout = Duration.ofSeconds(10);

    /** Retries on transient network failures only — never on PotPay 4xx/5xx. */
    private final long potpayRetryAttempts = 2;

    private final WalletService     walletService;
    private final WebClient.Builder webClientBuilder;

    /**
     * In-memory tracking of PotPay transactions awaiting verification.
     *
     * NOTE: replace with a persisted table (e.g. PotPayPendingTransaction JPA
     * entity + repository) before relying on this in production — an
     * in-memory map does not survive an app restart, which would strand any
     * transaction that was pending at the time of a deploy/crash.
     */
    private final Map<String, PendingTx> pendingTransactions = new ConcurrentHashMap<>();

    private record PendingTx(UUID userId, BigDecimal grossAmount, String currency,
                             String key, Instant createdAt) {}

    // ════════════════════════════════════════════════════════════════════════
    // GHANA (GHS · Mobile Money)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Starts a GHS mobile money collection. Sends a USSD approval prompt to
     * the customer's phone. Does NOT credit the wallet — that only happens
     * once Verify confirms status == "success" (via the scheduled poller,
     * or the manual verify endpoint below).
     */
    @PostMapping("/api/wallet/deposit/potpay/gh/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initGhanaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minGhsDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minGhsDeposit);

        var phoneNumber = required(req, "phoneNumber");
        var network     = (String) req.get("network"); // optional — auto-detected if omitted

        log.info("initGhanaDeposit: userId='{}' amount={} phone='{}'",
                user.getId(), amount, mask(phoneNumber));

        var response = potpayCollect(phoneNumber, amount, network,
                "Deposit for user " + user.getId());

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initGhanaDeposit: PotPay rejected collect request for userId='{}': {}",
                    user.getId(), response);
            throw new RuntimeException("PotPay declined the collection request.");
        }

        var transactionId = (String) response.get("transaction_id");
        var reference      = (String) response.get("reference");

        pendingTransactions.put(transactionId,
                new PendingTx(user.getId(), amount, "GHS", "gh", Instant.now()));

        log.info("initGhanaDeposit: pending txId='{}' reference='{}' userId='{}'",
                transactionId, reference, user.getId());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "transactionId", transactionId,
                "reference",     reference,
                "status",        "pending",
                "grossAmount",   response.get("gross_amount"),
                "netAmount",     response.get("net_amount")
        )));
    }

    /**
     * Manual verify — lets the frontend poll for a fast UX while the
     * scheduled poller (below) acts as the source of truth in the background.
     * Safe to call repeatedly; crediting is idempotent per transactionId.
     */
    @GetMapping("/api/wallet/deposit/potpay/gh/verify/{transactionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyGhanaDeposit(
            @AuthenticationPrincipal User user,
            @PathVariable String transactionId) {

        var pending = pendingTransactions.get(transactionId);
        if (pending != null && !pending.userId().equals(user.getId()))
            throw ApiException.badRequest("Transaction does not belong to this user.");

        var result = potpayVerifyGhana(transactionId);
        var status = (String) result.get("status");

        if ("success".equals(status)) {
            settleGhanaDeposit(transactionId, result);
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "transactionId", transactionId,
                "status",        status
        )));
    }

    /**
     * Background safety net: polls every pending GH transaction so a
     * deposit still completes even if the customer never returns to the app
     * to trigger a manual verify call.
     */
    @Scheduled(fixedDelay = 30_000)
    public void pollPendingGhanaTransactions() {
        pendingTransactions.entrySet().stream()
                .filter(e -> "gh".equals(e.getValue().key()))
                .forEach(e -> {
                    try {
                        var result = potpayVerifyGhana(e.getKey());
                        var status = (String) result.get("status");
                        if ("success".equals(status)) {
                            settleGhanaDeposit(e.getKey(), result);
                        } else if ("failed".equals(status) || "decline".equals(status)) {
                            log.info("pollPendingGhanaTransactions: txId='{}' ended with status='{}' — dropping",
                                    e.getKey(), status);
                            pendingTransactions.remove(e.getKey());
                        }
                        // still "pending" -> leave in map, try again next tick
                    } catch (Exception ex) {
                        log.error("pollPendingGhanaTransactions: verify failed for txId='{}'",
                                e.getKey(), ex);
                    }
                });
    }

    private void settleGhanaDeposit(String transactionId, Map<String, Object> verifyResult) {
        var pending = pendingTransactions.remove(transactionId);
        if (pending == null) {
            // Already settled by a concurrent poll/manual-verify call, or we
            // restarted and lost tracking — nothing safe to do but log it.
            log.warn("settleGhanaDeposit: no pending record for txId='{}' — skipping credit " +
                    "(already settled, or tracking was lost)", transactionId);
            return;
        }

        var netAmount = new BigDecimal(verifyResult.get("net_amount").toString());
        var reference = (String) verifyResult.get("reference");

        try {
            walletService.credit(pending.userId(), netAmount, TxKind.DEPOSIT, reference,
                    Map.of("provider", "potpay", "market", "gh",
                            "transactionId", transactionId, "reference", reference));
            log.info("settleGhanaDeposit: GHS {} credited to userId='{}' txId='{}'",
                    netAmount, pending.userId(), transactionId);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("settleGhanaDeposit: duplicate txId='{}' already processed — skipping", transactionId);
                return;
            }
            throw ex;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NIGERIA (NGN · Redirect checkout)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Starts an NGN redirect-checkout payment. Returns checkout_url for the
     * frontend to redirect the customer to.
     */
    @PostMapping("/api/wallet/deposit/potpay/ng/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initNigeriaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minNgnDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is NGN " + minNgnDeposit);

        var customerName = required(req, "customerName");
        var merchantReference = "order-" + user.getId() + "-" + UUID.randomUUID();

        log.info("initNigeriaDeposit: userId='{}' amount={} merchantReference='{}'",
                user.getId(), amount, merchantReference);

        var response = potpayInitiateNigeria(
                amount,
                customerName,
                frontendUrl + "/app/wallet/potpay/ng/callback",
                "Deposit for user " + user.getId(),
                merchantReference
        );

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initNigeriaDeposit: PotPay rejected initiate request for userId='{}': {}",
                    user.getId(), response);
            throw new RuntimeException("PotPay declined the payment request.");
        }

        var txRef = (String) response.get("tx_ref");

        pendingTransactions.put(txRef,
                new PendingTx(user.getId(), amount, "NGN", "ng", Instant.now()));

        log.info("initNigeriaDeposit: pending txRef='{}' userId='{}'", txRef, user.getId());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "txRef",       txRef,
                "checkoutUrl", response.get("checkout_url"),
                "amount",      response.get("amount"),
                "currency",    response.get("currency")
        )));
    }

    /**
     * Handles the browser redirect PotPay sends the customer back to after
     * checkout. Per PotPay's docs, the status/tx_ref query params must never
     * be trusted directly — this always re-verifies server-side before
     * crediting, then redirects on to the real frontend result page.
     */
    @GetMapping("/app/wallet/potpay/ng/callback")
    public ResponseEntity<Void> nigeriaCallback(
            @RequestParam("tx_ref") String txRef,
            @RequestParam(value = "status", required = false) String unverifiedStatus) {

        log.info("nigeriaCallback: txRef='{}' unverifiedStatus='{}' (re-verifying server-side)",
                txRef, unverifiedStatus);

        String finalStatus;
        try {
            var result = potpayVerifyNigeria(txRef);
            finalStatus = (String) result.get("status");
            if ("success".equals(finalStatus)) {
                settleNigeriaDeposit(txRef, result);
            }
        } catch (Exception ex) {
            log.error("nigeriaCallback: verify failed for txRef='{}'", txRef, ex);
            finalStatus = "unknown";
        }

        var redirectUrl = frontendUrl + "/app/wallet?payment=" + finalStatus + "&tx_ref=" + txRef;
        return ResponseEntity.status(302).header("Location", redirectUrl).build();
    }

    /** Manual verify endpoint, mirrors the Ghana one, for frontend polling. */
    @GetMapping("/api/wallet/deposit/potpay/ng/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyNigeriaDeposit(
            @AuthenticationPrincipal User user,
            @RequestParam("txRef") String txRef) {

        var pending = pendingTransactions.get(txRef);
        if (pending != null && !pending.userId().equals(user.getId()))
            throw ApiException.badRequest("Transaction does not belong to this user.");

        var result = potpayVerifyNigeria(txRef);
        var status = (String) result.get("status");

        if ("success".equals(status)) {
            settleNigeriaDeposit(txRef, result);
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "txRef",  txRef,
                "status", status
        )));
    }

    /** Safety-net poller for customers who never complete the browser redirect. */
    @Scheduled(fixedDelay = 15_000)
    public void pollPendingNigeriaTransactions() {
        pendingTransactions.entrySet().stream()
                .filter(e -> "ng".equals(e.getValue().key()))
                .forEach(e -> {
                    try {
                        var result = potpayVerifyNigeria(e.getKey());
                        var status = (String) result.get("status");
                        if ("success".equals(status)) {
                            settleNigeriaDeposit(e.getKey(), result);
                        } else if ("failed".equals(status)) {
                            log.info("pollPendingNigeriaTransactions: txRef='{}' failed — dropping", e.getKey());
                            pendingTransactions.remove(e.getKey());
                        }
                    } catch (Exception ex) {
                        log.error("pollPendingNigeriaTransactions: verify failed for txRef='{}'",
                                e.getKey(), ex);
                    }
                });
    }

    private void settleNigeriaDeposit(String txRef, Map<String, Object> verifyResult) {
        var pending = pendingTransactions.remove(txRef);
        if (pending == null) {
            log.warn("settleNigeriaDeposit: no pending record for txRef='{}' — skipping credit " +
                    "(already settled, or tracking was lost)", txRef);
            return;
        }

        var grossAmount = new BigDecimal(verifyResult.get("amount").toString());
        var netAmount = grossAmount
                .multiply(BigDecimal.ONE.subtract(NG_COMMISSION_RATE), MathContext.DECIMAL64);

        try {
            walletService.credit(pending.userId(), netAmount, TxKind.DEPOSIT, txRef,
                    Map.of("provider", "potpay", "market", "ng",
                            "txRef", txRef, "paymentType", verifyResult.getOrDefault("payment_type", "unknown")));
            log.info("settleNigeriaDeposit: NGN {} credited to userId='{}' txRef='{}'",
                    netAmount, pending.userId(), txRef);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("settleNigeriaDeposit: duplicate txRef='{}' already processed — skipping", txRef);
                return;
            }
            throw ex;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PotPay API helpers
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Object> potpayCollect(String phoneNumber, BigDecimal amount,
                                              String network, String description) {

        var body = new java.util.HashMap<String, Object>(Map.of(
                "phone_number", phoneNumber,
                "amount",       amount,
                "description",  description
        ));
        if (network != null && !network.isBlank()) body.put("network", network);

        return callPotPay("POST", "/api/merchant/api/collect/", body);
    }

    private Map<String, Object> potpayVerifyGhana(String transactionId) {
        return callPotPay("GET", "/api/merchant/api/verify/" + transactionId + "/", null);
    }

    private Map<String, Object> potpayInitiateNigeria(BigDecimal amount, String customerName,
                                                      String callbackUrl, String narration,
                                                      String merchantReference) {
        // Built as a HashMap<String, Object> (not Map.of) because Map.of infers
        // a common supertype across mixed value types (BigDecimal + String),
        // which callPotPay's Map<String, Object> parameter can't accept.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("amount", amount);
        body.put("currency", "NGN");
        body.put("customer_name", customerName);
        body.put("callback_url", callbackUrl);
        body.put("narration", narration);
        body.put("merchant_reference", merchantReference);
        body.put("payment_options", "card,banktransfer,ussd");

        return callPotPay("POST", "/api/merchant/ng/payments/initiate/", body);
    }

    private Map<String, Object> potpayVerifyNigeria(String txRef) {
        return callPotPay("GET",
                "/api/merchant/ng/payments/verify/?tx_ref=" + txRef, null);
    }

    /**
     * Shared HTTP helper for all PotPay calls.
     *
     * Resilience mirrors the Paystack controller's paystackInit helper:
     *   - 10s timeout so no thread blocks indefinitely
     *   - retries only transient network failures (never 4xx/5xx from PotPay,
     *     which surface as an unwrapped RuntimeException with no cause)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callPotPay(String method, String path, Map<String, Object> body) {

        var client = webClientBuilder.build();
        WebClient.RequestHeadersSpec<?> spec;

        if ("POST".equals(method)) {
            spec = client.post().uri(baseUrl + path)
                    .header("Authorization", "Bearer " + merchantId)
                    .header("Content-Type", "application/json")
                    .bodyValue(body);
        } else {
            spec = client.get().uri(baseUrl + path)
                    .header("Authorization", "Bearer " + merchantId);
        }

        var result = (Map<String, Object>) spec
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(respBody -> {
                                    log.error("PotPay API error: method={} path='{}' status={} body={}",
                                            method, path, clientResponse.statusCode(), respBody);
                                    return new RuntimeException(
                                            "PotPay returned " + clientResponse.statusCode() + ": " + respBody);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(potpayTimeout)
                .retryWhen(Retry.max(potpayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("PotPay API unreachable after {} retries: method={} path='{}'",
                                    potpayRetryAttempts, method, path, ex);
                            return new RuntimeException("PotPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("PotPay returned an empty response.");
        }

        return result;
    }

    // ─── Small utils ────────────────────────────────────────────────────────

    private static String required(Map<String, Object> req, String field) {
        var value = req.get(field);
        if (value == null || value.toString().isBlank())
            throw ApiException.badRequest("Missing required field: " + field);
        return value.toString();
    }

    /** Masks all but the last 3 digits of a phone number for logging. */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "*".repeat(phone.length() - 3) + phone.substring(phone.length() - 3);
    }
}
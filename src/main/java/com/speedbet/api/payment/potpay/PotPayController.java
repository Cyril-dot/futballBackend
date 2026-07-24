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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PotPayController {

    // ─── Config ─────────────────────────────────────────────────────────────

    @Value("${app.potpay.merchant-id}")            private String     merchantId;
    @Value("${app.potpay.base-url}")               private String     baseUrl; // https://backendpay.tipsterhub.online
    @Value("${app.platform.min-deposit-amount:1}") private BigDecimal minGhsDeposit;
    @Value("${app.platform.min-deposit-amount-ngn:1000}") private BigDecimal minNgnDeposit;
    @Value("${app.platform.frontend-url}")         private String     frontendUrl;

    private static final BigDecimal NG_COMMISSION_RATE = new BigDecimal("0.15"); // 15%

    private final Duration potpayTimeout = Duration.ofSeconds(10);
    private final long potpayRetryAttempts = 2;

    private final WalletService     walletService;
    private final WebClient.Builder webClientBuilder;

    private final Map<String, PendingTx> pendingTransactions = new ConcurrentHashMap<>();

    private record PendingTx(UUID userId, BigDecimal grossAmount, String currency,
                             String key, Instant createdAt) {}

    // ════════════════════════════════════════════════════════════════════════
    // GHANA (GHS · Mobile Money)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/potpay/gh/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initGhanaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        // FIX: Force exactly 2 decimal places (e.g. 10.00). PotPay rejects raw integers.
        var amount = new BigDecimal(req.get("amount").toString()).setScale(2, RoundingMode.HALF_UP);

        if (amount.compareTo(minGhsDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minGhsDeposit);

        var phoneNumber = required(req, "phoneNumber");
        var network     = (String) req.get("network"); // MTN, VDF, ATL

        log.info("initGhanaDeposit: userId='{}' amount={} phone='{}'",
                user.getId(), amount, mask(phoneNumber));

        var response = potpayCollect(phoneNumber, amount, network,
                "Deposit for user " + user.getId());

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initGhanaDeposit: PotPay rejected collect request for userId='{}': {}",
                    user.getId(), response);
            throw ApiException.badRequest("PotPay declined the collection request.");
        }

        var transactionId = (String) response.get("transaction_id");
        var reference      = (String) response.get("reference");

        if (transactionId == null || transactionId.isBlank()) {
            log.error("initGhanaDeposit: PotPay response missing 'transaction_id' for userId='{}'. Response: {}", user.getId(), response);
            throw ApiException.badRequest("PotPay accepted the request but did not return a transaction id.");
        }

        pendingTransactions.put(transactionId,
                new PendingTx(user.getId(), amount, "GHS", "gh", Instant.now()));

        log.info("initGhanaDeposit: pending txId='{}' reference='{}' userId='{}'",
                transactionId, reference, user.getId());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "transactionId", transactionId,
                "reference",     reference == null ? "" : reference,
                "status",        "pending",
                "grossAmount",   response.get("gross_amount"),
                "netAmount",     response.get("net_amount")
        )));
    }

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
                            log.info("pollPendingGhanaTransactions: txId='{}' ended with status='{}' — dropping", e.getKey(), status);
                            pendingTransactions.remove(e.getKey());
                        }
                    } catch (Exception ex) {
                        log.error("pollPendingGhanaTransactions: verify failed for txId='{}'", e.getKey(), ex);
                    }
                });
    }

    private void settleGhanaDeposit(String transactionId, Map<String, Object> verifyResult) {
        var pending = pendingTransactions.remove(transactionId);
        if (pending == null) {
            log.warn("settleGhanaDeposit: no pending record for txId='{}' — skipping credit", transactionId);
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
            log.error("initNigeriaDeposit: PotPay rejected initiate request for userId='{}': {}", user.getId(), response);
            throw ApiException.badRequest("PotPay declined the payment request.");
        }

        var txRef = (String) response.get("tx_ref");
        if (txRef == null || txRef.isBlank()) {
            log.error("initNigeriaDeposit: PotPay response missing 'tx_ref' for userId='{}'. Response: {}", user.getId(), response);
            throw ApiException.badRequest("PotPay accepted the request but did not return a transaction reference.");
        }

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

    @GetMapping("/app/wallet/potpay/ng/callback")
    public ResponseEntity<Void> nigeriaCallback(
            @RequestParam("tx_ref") String txRef,
            @RequestParam(value = "status", required = false) String unverifiedStatus) {

        log.info("nigeriaCallback: txRef='{}' unverifiedStatus='{}' (re-verifying server-side)", txRef, unverifiedStatus);

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
                        log.error("pollPendingNigeriaTransactions: verify failed for txRef='{}'", e.getKey(), ex);
                    }
                });
    }

    private void settleNigeriaDeposit(String txRef, Map<String, Object> verifyResult) {
        var pending = pendingTransactions.remove(txRef);
        if (pending == null) {
            log.warn("settleNigeriaDeposit: no pending record for txRef='{}' — skipping credit", txRef);
            return;
        }

        var grossAmount = new BigDecimal(verifyResult.get("amount").toString());
        var netAmount = grossAmount.multiply(BigDecimal.ONE.subtract(NG_COMMISSION_RATE), MathContext.DECIMAL64);

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
        return callPotPay("GET", "/api/merchant/ng/payments/verify/?tx_ref=" + txRef, null);
    }

    /**
     * Shared HTTP helper for all PotPay calls.
     * FIX: Separated 4xx and 5xx handling. 4xx errors are thrown as ApiException.badRequest
     * so the real PotPay rejection message gets passed to the React frontend.
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
                .onStatus(HttpStatusCode::is4xxClientError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(respBody -> {
                                    log.error("PotPay API 4xx error: method={} path='{}' status={} body={}",
                                            method, path, clientResponse.statusCode(), respBody);
                                    return ApiException.badRequest(
                                            "Payment gateway rejected the request: " + respBody);
                                })
                )
                .onStatus(HttpStatusCode::is5xxServerError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(respBody -> {
                                    log.error("PotPay API 5xx error: method={} path='{}' status={} body={}",
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
                        ex -> !(ex instanceof ApiException)
                                && (!(ex instanceof RuntimeException)
                                || ex.getMessage() == null
                                || ex.getClass().getSimpleName().contains("RetryExhausted")),
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

    private static String mask(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "*".repeat(phone.length() - 3) + phone.substring(phone.length() - 3);
    }
}

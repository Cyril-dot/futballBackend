package com.speedbet.api.payment.potpay;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PotPay deposits (Ghana mobile money + Nigeria redirect checkout).
 *
 * Crediting model — mirrors PaystackController.handleDeposit():
 *   1. Resolve the owning userId (pending map first, then the provider payload).
 *   2. walletService.credit(...) with a NON-NULL idempotency reference.
 *      A 409 from WalletService means "already credited" and is swallowed.
 *   3. referralService.attributeCommission(...) — never allowed to fail the deposit.
 *   4. ONLY THEN drop the pending record.
 *
 * Step 4 ordering is deliberate. The previous version removed the pending record
 * first, so any failure inside credit() permanently orphaned the deposit.
 *
 * NOTE ON DURABILITY: pendingTransactions is still an in-memory map, which means
 * it does not survive a restart and is not shared across instances. This class now
 * recovers the userId from the provider payload (merchant_reference / description)
 * so a lost map no longer means a lost deposit, but the correct long-term fix is a
 * `potpay_pending_tx` table written inside initGhanaDeposit/initNigeriaDeposit and
 * read by the pollers. See the note at the bottom of this file.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PotPayController {

    @Value("${app.potpay.merchant-id}")                   private String     merchantId;
    @Value("${app.potpay.base-url}")                      private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:1}")        private BigDecimal minGhsDeposit;
    @Value("${app.platform.min-deposit-amount-ngn:1000}") private BigDecimal minNgnDeposit;
    @Value("${app.platform.frontend-url}")                private String     frontendUrl;

    /**
     * false (default, preserves old behaviour):
     *   GH credits PotPay's net_amount, NG credits amount * (1 - NG commission).
     * true:
     *   the user is credited the full amount they paid, exactly like Paystack.
     *
     * Set app.potpay.credit-gross=true if users are complaining that less money
     * arrives than they deposited.
     */
    @Value("${app.potpay.credit-gross:false}")            private boolean    creditGrossAmount;

    @Value("${app.potpay.ng-commission-rate:0.15}")       private BigDecimal ngCommissionRate;

    /** Give up polling a transaction after this long and log it for manual reconciliation. */
    private static final Duration PENDING_TTL = Duration.ofHours(2);

    private static final String GH_DESCRIPTION_PREFIX = "Deposit for user ";
    private static final String NG_REFERENCE_PREFIX   = "order-";

    private final Duration potpayTimeout       = Duration.ofSeconds(10);
    private final long     potpayRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;

    private final Map<String, PendingTx> pendingTransactions = new ConcurrentHashMap<>();

    /** Guards against the scheduled poller and a user-triggered verify settling the same tx at once. */
    private final Set<String> settlementsInFlight = ConcurrentHashMap.newKeySet();

    private record PendingTx(UUID userId, BigDecimal grossAmount, String currency,
                             String key, Instant createdAt) {}

    /** Thrown when PotPay is unreachable / returned 5xx. Distinct from ApiException (client's fault). */
    private static class PotPayUnavailableException extends RuntimeException {
        PotPayUnavailableException(String message) { super(message); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // GHANA (GHS · Mobile Money)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/potpay/gh/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initGhanaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minGhsDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minGhsDeposit);

        var phoneNumber = required(req, "phoneNumber");
        var network     = (String) req.get("network");

        log.info("initGhanaDeposit: userId='{}' amount={} phone='{}'",
                user.getId(), amount, mask(phoneNumber));

        // description carries the userId so settlement can recover the owner even if
        // the in-memory pending map was lost (restart / different instance).
        var response = potpayCollect(phoneNumber, amount, network,
                GH_DESCRIPTION_PREFIX + user.getId());

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initGhanaDeposit: PotPay rejected collect request for userId='{}': {}", user.getId(), response);
            throw ApiException.badRequest("PotPay declined the collection request.");
        }

        var flat          = flatten(response);
        var transactionId = string(flat, "transaction_id");
        var reference     = string(flat, "reference");

        if (transactionId == null || transactionId.isBlank()) {
            log.error("initGhanaDeposit: PotPay response missing 'transaction_id' for userId='{}'. Response: {}",
                    user.getId(), response);
            throw ApiException.badRequest("PotPay accepted the request but did not return a transaction id.");
        }

        pendingTransactions.put(transactionId,
                new PendingTx(user.getId(), amount, "GHS", "gh", Instant.now()));

        log.info("initGhanaDeposit: pending txId='{}' reference='{}' userId='{}'",
                transactionId, reference, user.getId());

        var resBody = new HashMap<String, Object>();
        resBody.put("transactionId", transactionId);
        resBody.put("reference",     reference == null ? "" : reference);
        resBody.put("status",        "pending");
        resBody.put("grossAmount",   flat.getOrDefault("gross_amount", ""));
        resBody.put("netAmount",     flat.getOrDefault("net_amount", ""));

        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    @GetMapping("/api/wallet/deposit/potpay/gh/verify/{transactionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyGhanaDeposit(
            @AuthenticationPrincipal User user,
            @PathVariable String transactionId) {

        var pending = pendingTransactions.get(transactionId);
        if (pending != null && !pending.userId().equals(user.getId()))
            throw ApiException.badRequest("Transaction does not belong to this user.");

        var result = flatten(potpayVerifyGhana(transactionId));
        var status = statusOf(result);

        if ("success".equals(status)) {
            // Owner is re-derived inside settle; if the map was empty we fall back to the
            // payload, and we still refuse to credit anyone other than the true owner.
            settleGhanaDeposit(transactionId, result, user.getId());
        } else if (isTerminalFailure(status)) {
            pendingTransactions.remove(transactionId);
        }

        var resBody = new HashMap<String, Object>();
        resBody.put("transactionId", transactionId);
        resBody.put("status", status);

        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    @Scheduled(fixedDelay = 30_000)
    public void pollPendingGhanaTransactions() {
        pendingTransactions.entrySet().stream()
                .filter(e -> "gh".equals(e.getValue().key()))
                .toList()   // snapshot: settle() mutates the map
                .forEach(e -> {
                    var txId = e.getKey();
                    try {
                        if (expireIfStale(txId, e.getValue())) return;

                        var result = flatten(potpayVerifyGhana(txId));
                        var status = statusOf(result);

                        if ("success".equals(status)) {
                            settleGhanaDeposit(txId, result, null);
                        } else if (isTerminalFailure(status)) {
                            log.info("pollPendingGhanaTransactions: txId='{}' ended with status='{}' — dropping",
                                    txId, status);
                            pendingTransactions.remove(txId);
                        }
                    } catch (Exception ex) {
                        // Pending record is intentionally NOT removed — we retry next tick.
                        log.error("pollPendingGhanaTransactions: verify/settle failed for txId='{}'", txId, ex);
                    }
                });
    }

    /**
     * @param expectedUserId when non-null, the credit is refused if it does not match
     *                       the resolved owner (guards the authenticated verify endpoint).
     */
    private void settleGhanaDeposit(String transactionId, Map<String, Object> verifyResult, UUID expectedUserId) {
        if (!settlementsInFlight.add(transactionId)) {
            log.debug("settleGhanaDeposit: txId='{}' already being settled — skipping", transactionId);
            return;
        }
        try {
            var pending = pendingTransactions.get(transactionId);   // NOT remove — see class javadoc

            var userId = pending != null
                    ? pending.userId()
                    : userIdFromGhanaPayload(verifyResult);

            if (userId == null) {
                log.error("settleGhanaDeposit: cannot resolve owner for txId='{}' — MANUAL RECONCILIATION NEEDED. "
                        + "payload={}", transactionId, verifyResult);
                return;
            }
            if (expectedUserId != null && !expectedUserId.equals(userId)) {
                throw ApiException.badRequest("Transaction does not belong to this user.");
            }

            var amount = resolveGhanaCreditAmount(verifyResult, pending);
            if (amount == null || amount.signum() <= 0) {
                log.error("settleGhanaDeposit: no usable amount for txId='{}' — skipping credit. payload={}",
                        transactionId, verifyResult);
                return;
            }

            var providerReference = string(verifyResult, "reference");

            var metadata = new HashMap<String, Object>();
            metadata.put("provider",      "potpay");
            metadata.put("market",        "gh");
            metadata.put("transactionId", transactionId);
            metadata.put("reference",     providerReference == null ? "" : providerReference);

            // Idempotency key is the PotPay transaction id, never the nullable `reference`.
            creditWallet(userId, amount, transactionId, metadata, "GHS");

            pendingTransactions.remove(transactionId);   // only after a confirmed credit
            log.info("settleGhanaDeposit: GHS {} settled for userId='{}' txId='{}'",
                    amount, userId, transactionId);
        } finally {
            settlementsInFlight.remove(transactionId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NIGERIA (NGN · Redirect checkout)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/potpay/ng/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initNigeriaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minNgnDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is NGN " + minNgnDeposit);

        var customerName = required(req, "customerName");

        // Format is load-bearing: userIdFromMerchantReference() parses the userId back
        // out of this so settlement survives a lost pending map.
        var merchantReference = NG_REFERENCE_PREFIX + user.getId() + "-" + UUID.randomUUID();

        log.info("initNigeriaDeposit: userId='{}' amount={} merchantReference='{}'",
                user.getId(), amount, merchantReference);

        var response = potpayInitiateNigeria(
                amount, customerName,
                frontendUrl + "/app/wallet/potpay/ng/callback",
                "Deposit for user " + user.getId(),
                merchantReference
        );

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initNigeriaDeposit: PotPay rejected initiate request for userId='{}': {}",
                    user.getId(), response);
            throw ApiException.badRequest("PotPay declined the payment request.");
        }

        var flat  = flatten(response);
        var txRef = string(flat, "tx_ref");

        if (txRef == null || txRef.isBlank()) {
            log.error("initNigeriaDeposit: PotPay response missing 'tx_ref' for userId='{}'. Response: {}",
                    user.getId(), response);
            throw ApiException.badRequest("PotPay accepted the request but did not return a transaction reference.");
        }

        pendingTransactions.put(txRef, new PendingTx(user.getId(), amount, "NGN", "ng", Instant.now()));
        log.info("initNigeriaDeposit: pending txRef='{}' userId='{}'", txRef, user.getId());

        var resBody = new HashMap<String, Object>();
        resBody.put("txRef",       txRef);
        resBody.put("checkoutUrl", flat.get("checkout_url"));
        resBody.put("amount",      flat.get("amount"));
        resBody.put("currency",    flat.get("currency"));

        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    @GetMapping("/app/wallet/potpay/ng/callback")
    public ResponseEntity<Void> nigeriaCallback(
            @RequestParam("tx_ref") String txRef,
            @RequestParam(value = "status", required = false) String unverifiedStatus) {

        log.info("nigeriaCallback: txRef='{}' unverifiedStatus='{}'", txRef, unverifiedStatus);

        String finalStatus;
        try {
            var result = flatten(potpayVerifyNigeria(txRef));
            finalStatus = statusOf(result);
            if ("success".equals(finalStatus)) {
                settleNigeriaDeposit(txRef, result, null);
            }
        } catch (Exception ex) {
            // Pending record survives — the 15s poller will settle it shortly.
            log.error("nigeriaCallback: verify failed for txRef='{}' — leaving to poller", txRef, ex);
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

        var result = flatten(potpayVerifyNigeria(txRef));
        var status = statusOf(result);

        if ("success".equals(status)) {
            settleNigeriaDeposit(txRef, result, user.getId());
        } else if (isTerminalFailure(status)) {
            pendingTransactions.remove(txRef);
        }

        var resBody = new HashMap<String, Object>();
        resBody.put("txRef",  txRef);
        resBody.put("status", status);

        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    @Scheduled(fixedDelay = 15_000)
    public void pollPendingNigeriaTransactions() {
        pendingTransactions.entrySet().stream()
                .filter(e -> "ng".equals(e.getValue().key()))
                .toList()
                .forEach(e -> {
                    var txRef = e.getKey();
                    try {
                        if (expireIfStale(txRef, e.getValue())) return;

                        var result = flatten(potpayVerifyNigeria(txRef));
                        var status = statusOf(result);

                        if ("success".equals(status)) {
                            settleNigeriaDeposit(txRef, result, null);
                        } else if (isTerminalFailure(status)) {
                            log.info("pollPendingNigeriaTransactions: txRef='{}' ended with status='{}' — dropping",
                                    txRef, status);
                            pendingTransactions.remove(txRef);
                        }
                    } catch (Exception ex) {
                        log.error("pollPendingNigeriaTransactions: verify/settle failed for txRef='{}'", txRef, ex);
                    }
                });
    }

    private void settleNigeriaDeposit(String txRef, Map<String, Object> verifyResult, UUID expectedUserId) {
        if (!settlementsInFlight.add(txRef)) {
            log.debug("settleNigeriaDeposit: txRef='{}' already being settled — skipping", txRef);
            return;
        }
        try {
            var pending = pendingTransactions.get(txRef);

            var userId = pending != null
                    ? pending.userId()
                    : userIdFromMerchantReference(string(verifyResult, "merchant_reference"));

            if (userId == null) {
                log.error("settleNigeriaDeposit: cannot resolve owner for txRef='{}' — MANUAL RECONCILIATION NEEDED. "
                        + "payload={}", txRef, verifyResult);
                return;
            }
            if (expectedUserId != null && !expectedUserId.equals(userId)) {
                throw ApiException.badRequest("Transaction does not belong to this user.");
            }

            var gross = firstDecimal(verifyResult, "amount", "gross_amount");
            if (gross == null && pending != null) gross = pending.grossAmount();
            if (gross == null || gross.signum() <= 0) {
                log.error("settleNigeriaDeposit: no usable amount for txRef='{}' — skipping credit. payload={}",
                        txRef, verifyResult);
                return;
            }

            var netAmount = creditGrossAmount
                    ? gross
                    : gross.multiply(BigDecimal.ONE.subtract(ngCommissionRate));
            netAmount = netAmount.setScale(2, RoundingMode.HALF_UP);

            if (!creditGrossAmount && gross.compareTo(netAmount) != 0) {
                log.info("settleNigeriaDeposit: txRef='{}' gross={} commissionRate={} credited={}",
                        txRef, gross, ngCommissionRate, netAmount);
            }

            var paymentType = string(verifyResult, "payment_type");

            var metadata = new HashMap<String, Object>();
            metadata.put("provider",    "potpay");
            metadata.put("market",      "ng");
            metadata.put("txRef",       txRef);
            metadata.put("grossAmount", gross.toPlainString());
            metadata.put("paymentType", paymentType == null ? "unknown" : paymentType);

            creditWallet(userId, netAmount, txRef, metadata, "NGN");

            pendingTransactions.remove(txRef);
            log.info("settleNigeriaDeposit: NGN {} settled for userId='{}' txRef='{}'", netAmount, userId, txRef);
        } finally {
            settlementsInFlight.remove(txRef);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Wallet crediting — mirrors PaystackController.handleDeposit()
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Credits the wallet and then attributes referral commission.
     *
     * @param idempotencyRef MUST be non-null and stable per transaction. WalletService
     *                       throws 409 on a repeat, which is how double-crediting is
     *                       prevented when the poller and the verify endpoint overlap.
     */
    private void creditWallet(UUID userId, BigDecimal amount, String idempotencyRef,
                              Map<String, Object> metadata, String currency) {

        if (idempotencyRef == null || idempotencyRef.isBlank())
            throw new IllegalStateException("Refusing to credit without an idempotency reference");

        log.info("creditWallet: userId='{}' amount={} {} ref='{}'", userId, amount, currency, idempotencyRef);

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, idempotencyRef, metadata);
            log.info("creditWallet: {} {} credited to userId='{}' ref='{}'",
                    currency, amount, userId, idempotencyRef);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                // Already credited on an earlier pass — commission was attributed then too.
                log.warn("creditWallet: duplicate ref='{}' already processed — skipping", idempotencyRef);
                return;
            }
            throw ex;
        }

        // Attribute commission to the referring admin. Never block a deposit on this.
        try {
            referralService.attributeCommission(userId, amount);
            log.info("creditWallet: commission attributed for userId='{}' deposit={} ref='{}'",
                    userId, amount, idempotencyRef);
        } catch (Exception ex) {
            log.error("creditWallet: commission attribution failed for userId='{}' ref='{}' — investigate",
                    userId, idempotencyRef, ex);
        }
    }

    private BigDecimal resolveGhanaCreditAmount(Map<String, Object> verifyResult, PendingTx pending) {
        BigDecimal amount = creditGrossAmount
                ? firstDecimal(verifyResult, "gross_amount", "amount", "net_amount")
                : firstDecimal(verifyResult, "net_amount", "amount", "gross_amount");

        if (amount == null && pending != null) amount = pending.grossAmount();
        if (amount == null) return null;

        if (!creditGrossAmount) {
            var gross = firstDecimal(verifyResult, "gross_amount", "amount");
            if (gross != null && gross.compareTo(amount) != 0) {
                log.info("resolveGhanaCreditAmount: crediting PotPay net_amount={} (gross={})", amount, gross);
            }
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Drops a pending record that has outlived PENDING_TTL. Logged loudly for reconciliation. */
    private boolean expireIfStale(String key, PendingTx pending) {
        if (pending.createdAt().plus(PENDING_TTL).isAfter(Instant.now())) return false;

        pendingTransactions.remove(key);
        log.error("expireIfStale: pending {} tx key='{}' userId='{}' amount={} exceeded {} without settling "
                        + "— VERIFY MANUALLY before writing it off",
                pending.key(), key, pending.userId(), pending.grossAmount(), PENDING_TTL);
        return true;
    }

    private static boolean isTerminalFailure(String status) {
        return "failed".equals(status) || "decline".equals(status)
                || "declined".equals(status) || "cancelled".equals(status) || "canceled".equals(status);
    }

    // ── Owner recovery (used when the in-memory pending map has been lost) ───────

    /** Parses the userId back out of "order-{userId}-{uuid}". */
    private static UUID userIdFromMerchantReference(String merchantReference) {
        if (merchantReference == null || !merchantReference.startsWith(NG_REFERENCE_PREFIX)) return null;
        var rest = merchantReference.substring(NG_REFERENCE_PREFIX.length());
        if (rest.length() < 36) return null;
        try {
            return UUID.fromString(rest.substring(0, 36));
        } catch (IllegalArgumentException ex) {
            log.warn("userIdFromMerchantReference: unparseable merchant_reference='{}'", merchantReference);
            return null;
        }
    }

    /** Recovers the userId from the description we sent with the collect request. */
    private static UUID userIdFromGhanaPayload(Map<String, Object> verifyResult) {
        for (var field : new String[]{"description", "narration", "merchant_reference"}) {
            var value = string(verifyResult, field);
            if (value == null) continue;

            var idx = value.indexOf(GH_DESCRIPTION_PREFIX);
            var candidate = idx >= 0
                    ? value.substring(idx + GH_DESCRIPTION_PREFIX.length()).trim()
                    : value.trim();

            if (candidate.length() >= 36) candidate = candidate.substring(0, 36);
            try {
                return UUID.fromString(candidate);
            } catch (IllegalArgumentException ignored) {
                // try the next field
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // PotPay API helpers
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Object> potpayCollect(String phoneNumber, BigDecimal amount,
                                              String network, String description) {
        var body = new HashMap<String, Object>();
        body.put("phone_number", phoneNumber);
        body.put("amount",       amount);
        body.put("description",  description);
        if (network != null && !network.isBlank()) body.put("network", network);
        return callPotPay("POST", "/api/merchant/api/collect/", body);
    }

    private Map<String, Object> potpayVerifyGhana(String transactionId) {
        return callPotPay("GET", "/api/merchant/api/verify/" + transactionId + "/", null);
    }

    private Map<String, Object> potpayInitiateNigeria(BigDecimal amount, String customerName,
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
        return callPotPay("POST", "/api/merchant/ng/payments/initiate/", body);
    }

    private Map<String, Object> potpayVerifyNigeria(String txRef) {
        return callPotPay("GET", "/api/merchant/ng/payments/verify/?tx_ref=" + txRef, null);
    }

    /**
     * Resilience:
     *   - 10s timeout per attempt.
     *   - Retries ONLY on GET (verify). Retrying a POST collect/initiate after a timeout
     *     can create a second charge against the customer while we only ever track one
     *     transaction id — that is a real double-debit, so POSTs fail fast instead.
     *   - 4xx  -> ApiException (client's fault, never retried, surfaces the gateway message).
     *   - 5xx / network -> PotPayUnavailableException, so callers can distinguish
     *     "declined" from "couldn't reach PotPay".
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callPotPay(String method, String path, Map<String, Object> body) {
        var client = webClientBuilder.build();
        var isGet  = !"POST".equals(method);

        WebClient.RequestHeadersSpec<?> spec;
        if (isGet) {
            spec = client.get().uri(baseUrl + path)
                    .header("Authorization", "Bearer " + merchantId);
        } else {
            spec = client.post().uri(baseUrl + path)
                    .header("Authorization", "Bearer " + merchantId)
                    .header("Content-Type", "application/json")
                    .bodyValue(body);
        }

        var mono = spec
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                                .map(respBody -> {
                                    log.error("PotPay API 4xx: method={} path='{}' status={} body={}",
                                            method, path, clientResponse.statusCode(), respBody);
                                    return ApiException.badRequest(
                                            "Payment gateway rejected the request: " + respBody);
                                }))
                .onStatus(HttpStatusCode::is5xxServerError,
                        clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                                .map(respBody -> {
                                    log.error("PotPay API 5xx: method={} path='{}' status={} body={}",
                                            method, path, clientResponse.statusCode(), respBody);
                                    return new PotPayUnavailableException(
                                            "PotPay returned " + clientResponse.statusCode());
                                }))
                .bodyToMono(Map.class)
                .timeout(potpayTimeout);

        if (isGet) {
            mono = mono.retryWhen(
                    Retry.backoff(potpayRetryAttempts, Duration.ofMillis(300))
                            .filter(ex -> !(ex instanceof ApiException))
                            .onRetryExhaustedThrow((retrySpec, signal) -> signal.failure()));
        }

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) mono.block();
        } catch (ApiException | PotPayUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("PotPay unreachable: method={} path='{}'", method, path, ex);
            throw new PotPayUnavailableException("PotPay is currently unavailable. Please try again.");
        }

        if (result == null) throw new PotPayUnavailableException("PotPay returned an empty response.");
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Payload helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * PotPay sometimes nests the payload under "data". Returns a single flat view with
     * nested values winning, so callers never have to guess at the shape.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> response) {
        if (response == null) return new HashMap<>();
        var flat = new HashMap<String, Object>(response);
        var data = response.get("data");
        if (data instanceof Map<?, ?> nested) {
            flat.remove("data");
            flat.putAll((Map<String, Object>) nested);
        }
        return flat;
    }

    private static String statusOf(Map<String, Object> payload) {
        var status = string(payload, "status");
        return status == null || status.isBlank() ? "pending" : status.toLowerCase();
    }

    private static String string(Map<String, Object> src, String key) {
        var value = src.get(key);
        return value == null ? null : value.toString();
    }

    /** Returns the first key that parses as a positive-or-zero decimal, tolerating "1,000.00". */
    private static BigDecimal firstDecimal(Map<String, Object> src, String... keys) {
        for (var key : keys) {
            var value = src.get(key);
            if (value == null) continue;
            try {
                var text = value.toString().replace(",", "").trim();
                if (text.isEmpty()) continue;
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                log.warn("firstDecimal: field '{}' is not a number: {}", key, value);
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
            throw ApiException.badRequest("Invalid amount: " + raw);
        }
        if (amount.signum() <= 0) throw ApiException.badRequest("Amount must be greater than zero.");
        return amount;
    }

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

/*
 * ─── FOLLOW-UP: make pending transactions durable ───────────────────────────────
 *
 * The recovery paths above (merchant_reference / description parsing) stop a lost
 * pending map from losing money, but the pollers still cannot see transactions they
 * never held in memory. If you run more than one instance, or deploy while users are
 * mid-payment, add a table:
 *
 *   CREATE TABLE potpay_pending_tx (
 *     tx_key        VARCHAR(128) PRIMARY KEY,   -- transaction_id (GH) or tx_ref (NG)
 *     user_id       UUID        NOT NULL,
 *     gross_amount  NUMERIC(19,2) NOT NULL,
 *     currency      VARCHAR(3)  NOT NULL,
 *     market        VARCHAR(2)  NOT NULL,       -- 'gh' | 'ng'
 *     status        VARCHAR(16) NOT NULL,       -- 'pending' | 'settled' | 'failed'
 *     created_at    TIMESTAMPTZ NOT NULL,
 *     settled_at    TIMESTAMPTZ
 *   );
 *
 * Swap `pendingTransactions.put/get/remove` for repository calls and have the pollers
 * query `WHERE status = 'pending' AND market = ?`. Everything else in this class works
 * unchanged, because settlement already treats the pending record as a hint rather than
 * a precondition.
 *
 * Also confirm @EnableScheduling is present on a @Configuration class, otherwise neither
 * poller ever runs and every deposit depends on the user returning to the verify page.
 */
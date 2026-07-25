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
 * PotPay deposits — Ghana (MoMo via NaloPay) + Nigeria (redirect checkout via Flutterwave).
 *
 * Aligned with the PotPay merchant API docs:
 *
 *   GH collect:  POST {base}/api/merchant/api/collect/
 *                → { success, transaction_id, reference, status:"pending", gross_amount, net_amount, commission }
 *   GH verify:   GET  {base}/api/merchant/api/verify/{transaction_id}/
 *                → { transaction_id, gross_amount, net_amount, reference, status, description, ... }  (FLAT, no "success" field)
 *   NG initiate: POST {base}/api/merchant/ng/payments/initiate/
 *                → { success, tx_ref, transaction_id, checkout_url, amount, currency }
 *   NG verify:   GET  {base}/api/merchant/ng/payments/verify/?tx_ref=...
 *                → { tx_ref, transaction_id, status, amount, currency, merchant_reference, payment_type }  (FLAT)
 *
 *   Verify statuses:  pending | success | failed | decline (GH only)
 *   NG CALLBACK redirect uses a DIFFERENT vocabulary: successful | failed | pending.
 *   The callback status is never trusted anyway — we always re-verify server-side.
 *
 * Crediting model (mirrors PaystackController.handleDeposit):
 *   1. Resolve owning userId — pending map first, then description/merchant_reference
 *      echoed back by PotPay (both confirmed present in verify responses).
 *   2. walletService.credit with a non-null idempotency ref (transaction_id / tx_ref).
 *      409 = already credited, swallowed.
 *   3. referralService.attributeCommission — never allowed to fail the deposit.
 *   4. Remove the pending record ONLY after a confirmed credit.
 *
 * Amount policy:
 *   PotPay's commission (GH 3%, NG 15%) is deducted from the MERCHANT payout, not
 *   added to what the customer pays. With credit-gross=true (default) the bettor is
 *   credited exactly what they paid and the platform absorbs the fee — identical to
 *   the Paystack flow. Set app.potpay.credit-gross=false to pass the fee on instead
 *   (bettor gets net_amount / amount×0.85).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PotPayController {

    @Value("${app.potpay.merchant-id}")                   private String     merchantId;
    @Value("${app.potpay.base-url}")                      private String     rawBaseUrl;

    // Separate keys: app.platform.min-deposit-amount is shared with PaystackController
    // (default 300 there), which silently overrode the intended GHS 1 minimum.
    @Value("${app.potpay.min-deposit-ghs:1}")             private BigDecimal minGhsDeposit;
    @Value("${app.potpay.min-deposit-ngn:1000}")          private BigDecimal minNgnDeposit;

    @Value("${app.platform.frontend-url}")                private String     frontendUrl;

    /**
     * MUST be the public URL of THIS Spring app (e.g. the Railway URL), NOT the
     * frontend. PotPay redirects the customer's browser here after checkout; the
     * nigeriaCallback handler below verifies server-side and then 302s the customer
     * on to the frontend wallet page.
     *
     * Ensure "/app/wallet/potpay/ng/callback" is permitAll() in SecurityConfig —
     * the redirect carries no bearer token.
     */
    @Value("${app.potpay.backend-public-url}")            private String     backendPublicUrl;

    /**
     * true  (default): credit the bettor the full amount they paid; platform absorbs
     *                  PotPay's commission (matches Paystack behaviour).
     * false: credit net of PotPay's commission (GH net_amount, NG amount × (1-rate)).
     */
    @Value("${app.potpay.credit-gross:true}")             private boolean    creditGrossAmount;

    @Value("${app.potpay.ng-commission-rate:0.15}")       private BigDecimal ngCommissionRate;

    private static final Duration PENDING_TTL = Duration.ofHours(2);

    // Echoed back verbatim by PotPay in verify responses — used to recover the owner
    // when the in-memory pending map has been lost (restart / other instance).
    private static final String GH_DESCRIPTION_PREFIX = "Deposit for user ";
    private static final String NG_REFERENCE_PREFIX   = "order-";

    private final Duration potpayTimeout       = Duration.ofSeconds(10);
    private final long     potpayRetryAttempts = 2;

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;

    private final Map<String, PendingTx> pendingTransactions = new ConcurrentHashMap<>();
    private final Set<String>            settlementsInFlight = ConcurrentHashMap.newKeySet();

    private record PendingTx(UUID userId, BigDecimal grossAmount, String currency,
                             String key, Instant createdAt) {}

    private static class PotPayUnavailableException extends RuntimeException {
        PotPayUnavailableException(String message) { super(message); }
    }

    /**
     * The dashboard shows different "base URLs" per market (the GH page includes
     * "/api/merchant/" in it). Paths in this class already start with /api/merchant/...
     * so strip any trailing copy from the configured value — either config value works.
     */
    private String baseUrl() {
        var b = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/api/merchant")) b = b.substring(0, b.length() - "/api/merchant".length());
        return b;
    }

    // ════════════════════════════════════════════════════════════════════════
    // GHANA (GHS · MoMo USSD collect)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/potpay/gh/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initGhanaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minGhsDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minGhsDeposit);

        var phoneNumber = required(req, "phoneNumber");
        var network     = (String) req.get("network");   // MTN | VDF | ATL, optional (auto-detected)

        log.info("initGhanaDeposit: userId='{}' amount={} phone='{}' network='{}'",
                user.getId(), amount, mask(phoneNumber), network);

        var response = potpayCollect(phoneNumber, amount, network,
                GH_DESCRIPTION_PREFIX + user.getId());

        // Collect DOES have a "success" flag (verify does not).
        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initGhanaDeposit: PotPay rejected collect for userId='{}': {}", user.getId(), response);
            throw ApiException.badRequest("PotPay declined the collection request.");
        }

        var transactionId = string(response, "transaction_id");
        var reference     = string(response, "reference");

        if (transactionId == null || transactionId.isBlank()) {
            log.error("initGhanaDeposit: missing 'transaction_id' for userId='{}'. Response: {}",
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
        resBody.put("grossAmount",   response.getOrDefault("gross_amount", ""));
        resBody.put("netAmount",     response.getOrDefault("net_amount", ""));

        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    @GetMapping("/api/wallet/deposit/potpay/gh/verify/{transactionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyGhanaDeposit(
            @AuthenticationPrincipal User user,
            @PathVariable String transactionId) {

        var pending = pendingTransactions.get(transactionId);
        if (pending != null && !pending.userId().equals(user.getId()))
            throw ApiException.badRequest("Transaction does not belong to this user.");

        var result = potpayVerifyGhana(transactionId);
        var status = statusOf(result);
        log.info("verifyGhanaDeposit: txId='{}' status='{}' rawPayload={}", transactionId, status, result);

        if (isSuccess(status)) {
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
                .toList()
                .forEach(e -> {
                    var txId = e.getKey();
                    try {
                        if (expireIfStale(txId, e.getValue())) return;

                        var result = potpayVerifyGhana(txId);
                        var status = statusOf(result);

                        if (isSuccess(status)) {
                            settleGhanaDeposit(txId, result, null);
                        } else if (isTerminalFailure(status)) {
                            log.info("pollPendingGhanaTransactions: txId='{}' status='{}' — dropping", txId, status);
                            pendingTransactions.remove(txId);
                        }
                    } catch (Exception ex) {
                        // Pending record survives — retried next tick.
                        log.error("pollPendingGhanaTransactions: verify/settle failed txId='{}'", txId, ex);
                    }
                });
    }

    private void settleGhanaDeposit(String transactionId, Map<String, Object> verifyResult, UUID expectedUserId) {
        if (!settlementsInFlight.add(transactionId)) return;
        try {
            var pending = pendingTransactions.get(transactionId);   // read, NOT remove

            var userId = pending != null
                    ? pending.userId()
                    : userIdFromGhanaPayload(verifyResult);

            if (userId == null) {
                log.error("settleGhanaDeposit: cannot resolve owner for txId='{}' — MANUAL RECONCILIATION NEEDED. payload={}",
                        transactionId, verifyResult);
                return;
            }
            if (expectedUserId != null && !expectedUserId.equals(userId))
                throw ApiException.badRequest("Transaction does not belong to this user.");

            // Docs: gross_amount = what the customer paid, net_amount = gross − 3% commission.
            var gross = firstDecimal(verifyResult, "gross_amount");
            var net   = firstDecimal(verifyResult, "net_amount");
            var amount = creditGrossAmount
                    ? (gross != null ? gross : net)
                    : (net != null ? net : gross);
            if (amount == null && pending != null) amount = pending.grossAmount();
            if (amount == null || amount.signum() <= 0) {
                log.error("settleGhanaDeposit: no usable amount for txId='{}' — payload={}", transactionId, verifyResult);
                return;
            }
            amount = amount.setScale(2, RoundingMode.HALF_UP);

            var providerReference = string(verifyResult, "reference");

            var metadata = new HashMap<String, Object>();
            metadata.put("provider",      "potpay");
            metadata.put("market",        "gh");
            metadata.put("transactionId", transactionId);
            metadata.put("reference",     providerReference == null ? "" : providerReference);
            metadata.put("grossAmount",   gross == null ? "" : gross.toPlainString());
            metadata.put("netAmount",     net == null ? "" : net.toPlainString());

            creditWallet(userId, amount, transactionId, metadata, "GHS");

            pendingTransactions.remove(transactionId);   // only after confirmed credit
            log.info("settleGhanaDeposit: GHS {} settled userId='{}' txId='{}'", amount, userId, transactionId);
        } finally {
            settlementsInFlight.remove(transactionId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NIGERIA (NGN · redirect checkout)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/potpay/ng/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initNigeriaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minNgnDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is NGN " + minNgnDeposit);

        var customerName = required(req, "customerName");

        // Format is load-bearing: echoed back as merchant_reference in verify,
        // and parsed by userIdFromMerchantReference() to recover the owner.
        var merchantReference = NG_REFERENCE_PREFIX + user.getId() + "-" + UUID.randomUUID();

        log.info("initNigeriaDeposit: userId='{}' amount={} merchantReference='{}'",
                user.getId(), amount, merchantReference);

        // Callback must hit THIS backend — PotPay's redirect carries the result there,
        // nigeriaCallback verifies server-side, then forwards the customer to the frontend.
        var response = potpayInitiateNigeria(
                amount, customerName,
                backendPublicUrl + "/app/wallet/potpay/ng/callback",
                "Deposit for user " + user.getId(),
                merchantReference
        );

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initNigeriaDeposit: PotPay rejected initiate for userId='{}': {}", user.getId(), response);
            throw ApiException.badRequest("PotPay declined the payment request.");
        }

        var txRef       = string(response, "tx_ref");
        var checkoutUrl = string(response, "checkout_url");

        if (txRef == null || txRef.isBlank()) {
            log.error("initNigeriaDeposit: missing 'tx_ref' for userId='{}'. Response: {}", user.getId(), response);
            throw ApiException.badRequest("PotPay accepted the request but did not return a transaction reference.");
        }
        if (checkoutUrl == null || checkoutUrl.isBlank()) {
            log.error("initNigeriaDeposit: missing 'checkout_url' for userId='{}'. Response: {}", user.getId(), response);
            throw ApiException.badRequest("PotPay did not return a checkout URL.");
        }

        pendingTransactions.put(txRef, new PendingTx(user.getId(), amount, "NGN", "ng", Instant.now()));
        log.info("initNigeriaDeposit: pending txRef='{}' userId='{}'", txRef, user.getId());

        var resBody = new HashMap<String, Object>();
        resBody.put("txRef",       txRef);
        resBody.put("checkoutUrl", checkoutUrl);
        resBody.put("amount",      response.get("amount"));
        resBody.put("currency",    response.get("currency"));
        return ResponseEntity.ok(ApiResponse.ok(resBody));
    }

    /**
     * Browser redirect target from PotPay. Query params use the CALLBACK vocabulary
     * (successful|failed|pending) and are never trusted — we always re-verify, which
     * returns the VERIFY vocabulary (success|failed|pending).
     * Must be permitAll() — no auth on a provider redirect.
     */
    @GetMapping("/app/wallet/potpay/ng/callback")
    public ResponseEntity<Void> nigeriaCallback(
            @RequestParam("tx_ref") String txRef,
            @RequestParam(value = "status", required = false) String unverifiedStatus,
            @RequestParam(value = "transaction_id", required = false) String flutterwaveTxnId) {

        log.info("nigeriaCallback: txRef='{}' unverifiedStatus='{}' fwTxnId='{}'",
                txRef, unverifiedStatus, flutterwaveTxnId);

        String uiStatus;
        try {
            var result = potpayVerifyNigeria(txRef);
            var status = statusOf(result);
            log.info("nigeriaCallback: verified txRef='{}' status='{}' rawPayload={}", txRef, status, result);

            if (isSuccess(status)) {
                settleNigeriaDeposit(txRef, result, null);
                uiStatus = "success";
            } else if (isTerminalFailure(status)) {
                pendingTransactions.remove(txRef);
                uiStatus = "failed";
            } else {
                uiStatus = "pending";   // poller keeps watching it
            }
        } catch (Exception ex) {
            log.error("nigeriaCallback: verify failed txRef='{}' — leaving to poller", txRef, ex);
            uiStatus = "pending";
        }

        var redirectUrl = frontendUrl + "/app/wallet?payment=" + uiStatus
                + "&tx_ref=" + java.net.URLEncoder.encode(txRef, java.nio.charset.StandardCharsets.UTF_8);
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
        var status = statusOf(result);
        log.info("verifyNigeriaDeposit: txRef='{}' status='{}' rawPayload={}", txRef, status, result);

        if (isSuccess(status)) {
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

                        var result = potpayVerifyNigeria(txRef);
                        var status = statusOf(result);

                        if (isSuccess(status)) {
                            settleNigeriaDeposit(txRef, result, null);
                        } else if (isTerminalFailure(status)) {
                            log.info("pollPendingNigeriaTransactions: txRef='{}' status='{}' — dropping", txRef, status);
                            pendingTransactions.remove(txRef);
                        }
                    } catch (Exception ex) {
                        log.error("pollPendingNigeriaTransactions: verify/settle failed txRef='{}'", txRef, ex);
                    }
                });
    }

    private void settleNigeriaDeposit(String txRef, Map<String, Object> verifyResult, UUID expectedUserId) {
        if (!settlementsInFlight.add(txRef)) return;
        try {
            var pending = pendingTransactions.get(txRef);

            var userId = pending != null
                    ? pending.userId()
                    : userIdFromMerchantReference(string(verifyResult, "merchant_reference"));

            if (userId == null) {
                log.error("settleNigeriaDeposit: cannot resolve owner for txRef='{}' — MANUAL RECONCILIATION NEEDED. payload={}",
                        txRef, verifyResult);
                return;
            }
            if (expectedUserId != null && !expectedUserId.equals(userId))
                throw ApiException.badRequest("Transaction does not belong to this user.");

            // Docs: verify "amount" = what the customer paid (gross). PotPay deducts
            // its 15% from the merchant payout, not from this figure.
            var gross = firstDecimal(verifyResult, "amount");
            if (gross == null && pending != null) gross = pending.grossAmount();
            if (gross == null || gross.signum() <= 0) {
                log.error("settleNigeriaDeposit: no usable amount for txRef='{}' — payload={}", txRef, verifyResult);
                return;
            }

            // Docs: verify amount before fulfilling. If it drifted from what we initiated, stop.
            if (pending != null && gross.compareTo(pending.grossAmount()) != 0) {
                log.error("settleNigeriaDeposit: AMOUNT MISMATCH txRef='{}' initiated={} verified={} — refusing to credit, MANUAL RECONCILIATION NEEDED",
                        txRef, pending.grossAmount(), gross);
                return;   // record stays pending for investigation
            }

            var creditAmount = creditGrossAmount
                    ? gross
                    : gross.multiply(BigDecimal.ONE.subtract(ngCommissionRate));
            creditAmount = creditAmount.setScale(2, RoundingMode.HALF_UP);

            var paymentType = string(verifyResult, "payment_type");   // card | banktransfer | ussd

            var metadata = new HashMap<String, Object>();
            metadata.put("provider",    "potpay");
            metadata.put("market",      "ng");
            metadata.put("txRef",       txRef);
            metadata.put("grossAmount", gross.toPlainString());
            metadata.put("paymentType", paymentType == null ? "unknown" : paymentType);

            creditWallet(userId, creditAmount, txRef, metadata, "NGN");

            pendingTransactions.remove(txRef);
            log.info("settleNigeriaDeposit: NGN {} settled userId='{}' txRef='{}'", creditAmount, userId, txRef);
        } finally {
            settlementsInFlight.remove(txRef);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Wallet crediting — mirrors PaystackController.handleDeposit()
    // ════════════════════════════════════════════════════════════════════════

    private void creditWallet(UUID userId, BigDecimal amount, String idempotencyRef,
                              Map<String, Object> metadata, String currency) {

        if (idempotencyRef == null || idempotencyRef.isBlank())
            throw new IllegalStateException("Refusing to credit without an idempotency reference");

        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, idempotencyRef, metadata);
            log.info("creditWallet: {} {} credited userId='{}' ref='{}'", currency, amount, userId, idempotencyRef);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("creditWallet: duplicate ref='{}' already processed — skipping", idempotencyRef);
                return;   // commission was attributed on the first pass
            }
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("creditWallet: commission attributed userId='{}' deposit={} ref='{}'", userId, amount, idempotencyRef);
        } catch (Exception ex) {
            // Never block a deposit on a commission failure.
            log.error("creditWallet: commission attribution failed userId='{}' ref='{}' — investigate",
                    userId, idempotencyRef, ex);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Status + recovery helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Verify vocabulary is success|failed|pending|decline; callback uses successful|failed|pending. */
    private static boolean isSuccess(String status) {
        return "success".equals(status) || "successful".equals(status);
    }

    private static boolean isTerminalFailure(String status) {
        return "failed".equals(status) || "decline".equals(status) || "declined".equals(status);
    }

    private static String statusOf(Map<String, Object> payload) {
        var status = payload == null ? null : payload.get("status");
        return status == null || status.toString().isBlank()
                ? "pending" : status.toString().trim().toLowerCase();
    }

    private boolean expireIfStale(String key, PendingTx pending) {
        if (pending.createdAt().plus(PENDING_TTL).isAfter(Instant.now())) return false;
        pendingTransactions.remove(key);
        log.error("expireIfStale: pending {} tx key='{}' userId='{}' amount={} exceeded {} without settling — VERIFY MANUALLY",
                pending.key(), key, pending.userId(), pending.grossAmount(), PENDING_TTL);
        return true;
    }

    /** Parses the userId back out of "order-{userId}-{uuid}" (echoed as merchant_reference). */
    private static UUID userIdFromMerchantReference(String merchantReference) {
        if (merchantReference == null || !merchantReference.startsWith(NG_REFERENCE_PREFIX)) return null;
        var rest = merchantReference.substring(NG_REFERENCE_PREFIX.length());
        if (rest.length() < 36) return null;
        try {
            return UUID.fromString(rest.substring(0, 36));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Recovers the userId from the description echoed back in the GH verify response. */
    private static UUID userIdFromGhanaPayload(Map<String, Object> verifyResult) {
        var description = verifyResult == null ? null : verifyResult.get("description");
        if (description == null) return null;

        var text = description.toString();
        var idx  = text.indexOf(GH_DESCRIPTION_PREFIX);
        if (idx < 0) return null;

        var candidate = text.substring(idx + GH_DESCRIPTION_PREFIX.length()).trim();
        if (candidate.length() > 36) candidate = candidate.substring(0, 36);
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
        return callPotPay("GET", "/api/merchant/api/verify/"
                + java.net.URLEncoder.encode(transactionId, java.nio.charset.StandardCharsets.UTF_8) + "/", null);
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
        return callPotPay("GET", "/api/merchant/ng/payments/verify/?tx_ref="
                + java.net.URLEncoder.encode(txRef, java.nio.charset.StandardCharsets.UTF_8), null);
    }

    /**
     * Resilience:
     *   - 10s timeout per attempt.
     *   - Retries ONLY on GET (verify). Retrying POST /collect/ after a timeout can
     *     send the customer a SECOND USSD prompt while we track only one
     *     transaction_id — a real double-debit risk, so POSTs fail fast.
     *   - 4xx → ApiException (surfaces PotPay's validation message, e.g. bad network code).
     *   - 5xx / network → PotPayUnavailableException.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callPotPay(String method, String path, Map<String, Object> body) {
        var client = webClientBuilder.build();
        var isGet  = !"POST".equals(method);
        var url    = baseUrl() + path;

        WebClient.RequestHeadersSpec<?> spec;
        if (isGet) {
            spec = client.get().uri(url)
                    .header("Authorization", "Bearer " + merchantId);
        } else {
            spec = client.post().uri(url)
                    .header("Authorization", "Bearer " + merchantId)
                    .header("Content-Type", "application/json")
                    .bodyValue(body);
        }

        var mono = spec
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                                .map(respBody -> {
                                    log.error("PotPay 4xx: {} {} status={} body={}",
                                            method, url, clientResponse.statusCode(), respBody);
                                    return ApiException.badRequest("Payment gateway rejected the request: " + respBody);
                                }))
                .onStatus(HttpStatusCode::is5xxServerError,
                        clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                                .map(respBody -> {
                                    log.error("PotPay 5xx: {} {} status={} body={}",
                                            method, url, clientResponse.statusCode(), respBody);
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
            log.error("PotPay unreachable: {} {}", method, url, ex);
            throw new PotPayUnavailableException("PotPay is currently unavailable. Please try again.");
        }

        if (result == null) throw new PotPayUnavailableException("PotPay returned an empty response.");
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Input helpers
    // ════════════════════════════════════════════════════════════════════════

    private static String string(Map<String, Object> src, String key) {
        var value = src == null ? null : src.get(key);
        return value == null ? null : value.toString();
    }

    /** Docs return amounts as strings ("100.00", "5000.00"); tolerate "1,000.00" too. */
    private static BigDecimal firstDecimal(Map<String, Object> src, String... keys) {
        for (var key : keys) {
            var value = src == null ? null : src.get(key);
            if (value == null) continue;
            try {
                var text = value.toString().replace(",", "").trim();
                if (!text.isEmpty()) return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                log.warn("firstDecimal: field '{}' not numeric: {}", key, value);
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
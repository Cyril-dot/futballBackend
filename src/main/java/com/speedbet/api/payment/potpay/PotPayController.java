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
 * PotPay deposits — Ghana (direct Mobile Money collect) + Nigeria (redirect checkout
 * via Flutterwave).
 *
 * ── UPDATED (GH) ─────────────────────────────────────────────────────────────
 * GH no longer uses a hosted redirect checkout page (ZaaPay/Flutterwave) or the old
 * USSD-push collect API — both are retired; the old initiate endpoint now returns
 * 410 Gone for GHS. GH collections are now a direct Mobile Money "collect" call:
 * we send the customer's MoMo number + amount, PotPay pushes an approval prompt to
 * their handset, and we poll verify until it clears. There is no checkout_url, no
 * callback_url, and no browser redirect for GH anymore — the merchant builds its
 * own UI and polls.
 *
 * GH auth is unchanged from the last update: an API key with the `Api-Key` scheme
 * (Bearer/Token also accepted by PotPay, but we keep Api-Key), NOT the merchant id
 * / Bearer scheme that NG still uses.
 *
 * Aligned with the current PotPay merchant API docs:
 *
 *   GH collect: POST {base}/api/merchant/api/collect/            [Authorization: Api-Key {gh-api-key}]
 *               body: { phone_number, amount, description? }
 *               → { success, transaction_id, reference, status, amount, currency, type, message }
 *   GH verify:  GET  {base}/api/merchant/api/verify/{transaction_id}/
 *               → flat: { transaction_id, account, channel, gross_amount, net_amount, status, currency }
 *
 *   NG initiate: POST {base}/api/merchant/ng/payments/initiate/   [Authorization: Bearer {merchant-id}]  (unchanged)
 *                → { success, tx_ref, transaction_id, checkout_url, amount, currency }
 *   NG verify:   GET  {base}/api/merchant/ng/payments/verify/?tx_ref=...
 *                → flat: { tx_ref, transaction_id, status, amount, currency, merchant_reference, payment_type }
 *
 *   GH verify statuses:  pending | success | failed.
 *   NG verify statuses:  pending | success | failed  (kept "decline" handling defensively —
 *                        an artifact of an older, now-unused GH vocabulary).
 *   NG CALLBACK redirect uses a DIFFERENT vocabulary: successful | failed | pending. The
 *   callback status is never trusted anyway — we always re-verify server-side. GH has no
 *   callback at all now.
 *
 * Crediting model (mirrors PaystackController.handleDeposit):
 *   1. Resolve owning userId.
 *      - NG: pending map first, then merchant_reference echoed back by PotPay
 *        ("order-{userId}-{uuid}").
 *      - GH: pending map ONLY — the new collect/verify API does not accept or echo
 *        back a merchant reference, so if the pending record is lost (restart / other
 *        instance) the owner cannot be recovered automatically. See the log line in
 *        settleGhanaDeposit() for manual reconciliation in that case.
 *   2. walletService.credit with a non-null idempotency ref (transaction_id / tx_ref).
 *      409 = already credited, swallowed.
 *   3. referralService.attributeCommission — never allowed to fail the deposit.
 *   4. Remove the pending record ONLY after a confirmed credit.
 *
 * Amount policy:
 *   NG: PotPay's 15% commission is deducted from the MERCHANT payout, not added to what
 *   the customer pays, so we compute net = gross × (1 - ngCommissionRate) ourselves.
 *   GH: PotPay now computes and returns the net figure directly (net_amount in the verify
 *   response) — no rate math needed on our side any more.
 *   With credit-gross=true (default) the bettor is credited exactly what they paid
 *   (gross) and the platform absorbs the fee, identical to the Paystack flow. Set
 *   app.potpay.credit-gross=false to credit the net amount instead.
 *
 * NG SIDE IS UNCHANGED — same endpoints, same fields, same callback, same auth
 * (Bearer merchantId), same commission config.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PotPayController {

    @Value("${app.potpay.merchant-id}")                   private String     merchantId;   // NG auth — unchanged
    @Value("${app.potpay.gh-api-key}")                     private String     ghApiKey;     // GH auth
    @Value("${app.potpay.base-url}")                      private String     rawBaseUrl;

    // Separate keys: app.platform.min-deposit-amount is shared with PaystackController
    // (default 300 there), which silently overrode the intended GHS 1 minimum.
    @Value("${app.potpay.min-deposit-ghs:1}")             private BigDecimal minGhsDeposit;
    @Value("${app.potpay.min-deposit-ngn:1000}")          private BigDecimal minNgnDeposit;

    @Value("${app.platform.frontend-url}")                private String     frontendUrl;

    /**
     * MUST be the public URL of THIS Spring app (e.g. the Railway URL), NOT the
     * frontend. Only used by NG now — PotPay redirects the customer's browser here
     * after NG checkout; nigeriaCallback verifies server-side and then 302s the
     * customer on to the frontend wallet page. GH has no redirect/callback any more.
     *
     * Ensure "/app/wallet/potpay/ng/callback" is permitAll() in SecurityConfig — this
     * redirect carries no bearer token. The old "/app/wallet/potpay/gh/callback" route
     * can be removed from SecurityConfig; it is no longer registered here.
     */
    @Value("${app.potpay.backend-public-url}")            private String     backendPublicUrl;

    /**
     * true  (default): credit the bettor the full amount they paid; platform absorbs
     *                  PotPay's commission (matches Paystack behaviour).
     * false: credit net of PotPay's commission — NG: amount × (1 - ng-rate) computed
     *        here; GH: net_amount as returned directly by PotPay's verify response.
     */
    @Value("${app.potpay.credit-gross:true}")             private boolean    creditGrossAmount;

    @Value("${app.potpay.ng-commission-rate:0.15}")       private BigDecimal ngCommissionRate;

    private static final Duration PENDING_TTL = Duration.ofHours(2);

    // Echoed back verbatim by PotPay in NG verify responses — used to recover the
    // owner when the in-memory pending map has been lost (restart / other instance).
    // NOT available for GH under the new collect/verify API.
    private static final String NG_REFERENCE_PREFIX = "order-";

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

    private String ghAuthHeader() { return "Api-Key " + ghApiKey; }

    private String ngAuthHeader() { return "Bearer " + merchantId; }

    // ════════════════════════════════════════════════════════════════════════
    // GHANA (GHS · direct Mobile Money collect — no redirect/checkout)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/potpay/gh/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initGhanaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minGhsDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minGhsDeposit);

        var phoneNumber = required(req, "phoneNumber");

        log.info("initGhanaDeposit: userId='{}' amount={} phoneNumber='{}'",
                user.getId(), amount, phoneNumber);

        var response = potpayCollectGhana(amount, phoneNumber, "Deposit for user " + user.getId());

        if (!Boolean.TRUE.equals(response.get("success"))) {
            log.error("initGhanaDeposit: PotPay rejected collect for userId='{}': {}", user.getId(), response);
            throw ApiException.badRequest("PotPay declined the payment request.");
        }

        var transactionId = string(response, "transaction_id");
        var reference      = string(response, "reference");

        if (transactionId == null || transactionId.isBlank()) {
            log.error("initGhanaDeposit: missing 'transaction_id' for userId='{}'. Response: {}",
                    user.getId(), response);
            throw ApiException.badRequest("PotPay accepted the request but did not return a transaction id.");
        }

        // Keyed by transaction_id — matches the /gh/verify/{transactionId} path param.
        // This is the ONLY place the userId is recorded for GH; the collect/verify API
        // does not accept or echo back a merchant reference, so if this record is lost
        // (restart / other instance) the owner cannot be auto-recovered.
        pendingTransactions.put(transactionId,
                new PendingTx(user.getId(), amount, "GHS", "gh", Instant.now()));

        log.info("initGhanaDeposit: pending txId='{}' reference='{}' userId='{}' — prompt sent to '{}'",
                transactionId, reference, user.getId(), phoneNumber);

        var resBody = new HashMap<String, Object>();
        resBody.put("transactionId", transactionId);
        resBody.put("reference",     reference == null ? "" : reference);
        resBody.put("status",        "pending");
        resBody.put("amount",        response.getOrDefault("amount", ""));
        resBody.put("message",       response.getOrDefault("message",
                "Payment request sent. Approve the prompt on your phone."));

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

    /**
     * No callback endpoint for GH any more — there's no redirect/browser leg to catch.
     * The frontend is expected to poll verifyGhanaDeposit (or wait on this poller)
     * every 5–10s per the docs until status leaves "pending".
     */
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

            // No merchant-reference fallback for GH under the new API — pending map is
            // the only source of truth for the owner.
            var userId = pending != null ? pending.userId() : null;

            if (userId == null) {
                log.error("settleGhanaDeposit: cannot resolve owner for txId='{}' (no pending record — "
                                + "restart/other-instance loss and GH's collect/verify API has no merchant "
                                + "reference to recover from) — MANUAL RECONCILIATION NEEDED. payload={}",
                        transactionId, verifyResult);
                return;
            }
            if (expectedUserId != null && !expectedUserId.equals(userId))
                throw ApiException.badRequest("Transaction does not belong to this user.");

            // New flat verify shape: gross_amount (what the customer paid) + net_amount
            // (what we actually receive, fee already deducted by PotPay).
            var gross = firstDecimal(verifyResult, "gross_amount");
            if (gross == null && pending != null) gross = pending.grossAmount();
            if (gross == null || gross.signum() <= 0) {
                log.error("settleGhanaDeposit: no usable gross_amount for txId='{}' — payload={}", transactionId, verifyResult);
                return;
            }

            // Verify amount before fulfilling — if it drifted from what we initiated, stop
            // and leave it pending for investigation (mirrors the NG safeguard).
            if (pending != null && gross.compareTo(pending.grossAmount()) != 0) {
                log.error("settleGhanaDeposit: AMOUNT MISMATCH txId='{}' initiated={} verified={} — refusing to credit, MANUAL RECONCILIATION NEEDED",
                        transactionId, pending.grossAmount(), gross);
                return;
            }

            var net = firstDecimal(verifyResult, "net_amount");
            if (net == null) {
                // Should not happen per current docs — verify always returns net_amount.
                // Fall back to crediting gross rather than blocking the deposit outright.
                log.error("settleGhanaDeposit: verify response missing 'net_amount' for txId='{}' — "
                        + "falling back to gross for net-credit calc. payload={}", transactionId, verifyResult);
                net = gross;
            }

            var creditAmount = (creditGrossAmount ? gross : net).setScale(2, RoundingMode.HALF_UP);

            var account = string(verifyResult, "account");   // customer MoMo number
            var channel = string(verifyResult, "channel");   // MTN | Telecel | AirtelTigo

            var metadata = new HashMap<String, Object>();
            metadata.put("provider",      "potpay");
            metadata.put("market",        "gh");
            metadata.put("transactionId", transactionId);
            metadata.put("account",       account == null ? "" : account);
            metadata.put("channel",       channel == null ? "unknown" : channel);
            metadata.put("grossAmount",   gross.toPlainString());
            metadata.put("netAmount",     net.toPlainString());

            creditWallet(userId, creditAmount, transactionId, metadata, "GHS");

            pendingTransactions.remove(transactionId);   // only after confirmed credit
            log.info("settleGhanaDeposit: GHS {} settled userId='{}' txId='{}'", creditAmount, userId, transactionId);
        } finally {
            settlementsInFlight.remove(transactionId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NIGERIA (NGN · redirect checkout) — UNCHANGED
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/wallet/deposit/potpay/ng/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initNigeriaDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minNgnDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is NGN " + minNgnDeposit);

        var customerName = required(req, "customerName");

        var merchantReference = NG_REFERENCE_PREFIX + user.getId() + "-" + UUID.randomUUID();

        log.info("initNigeriaDeposit: userId='{}' amount={} merchantReference='{}'",
                user.getId(), amount, merchantReference);

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

    /** Verify vocabulary is success|failed|pending; NG callback uses successful|failed|pending.
     *  "decline" kept defensively — an artifact of an old, now-unused GH vocabulary. */
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

    /** Parses the userId back out of "order-{userId}-{uuid}" (echoed as merchant_reference
     *  by NG only — GH's collect/verify API has no equivalent field). */
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

    // ════════════════════════════════════════════════════════════════════════
    // PotPay API helpers
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Object> potpayCollectGhana(BigDecimal amount, String phoneNumber, String description) {
        var body = new HashMap<String, Object>();
        body.put("phone_number", phoneNumber);
        body.put("amount",       amount);
        body.put("description",  description);
        return callPotPay("POST", "/api/merchant/api/collect/", body, ghAuthHeader());
    }

    private Map<String, Object> potpayVerifyGhana(String transactionId) {
        return callPotPay("GET", "/api/merchant/api/verify/"
                        + java.net.URLEncoder.encode(transactionId, java.nio.charset.StandardCharsets.UTF_8) + "/",
                null, ghAuthHeader());
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
        return callPotPay("POST", "/api/merchant/ng/payments/initiate/", body, ngAuthHeader());
    }

    private Map<String, Object> potpayVerifyNigeria(String txRef) {
        return callPotPay("GET", "/api/merchant/ng/payments/verify/?tx_ref="
                        + java.net.URLEncoder.encode(txRef, java.nio.charset.StandardCharsets.UTF_8),
                null, ngAuthHeader());
    }

    /**
     * Resilience:
     *   - 10s timeout per attempt.
     *   - Retries ONLY on GET (verify). Retrying POST after a timeout can trigger a
     *     SECOND MoMo prompt / checkout session while we track only one tx ref — a
     *     real double-charge risk, so POSTs (collect / initiate) fail fast.
     *   - 4xx → ApiException (surfaces PotPay's validation message).
     *   - 5xx / network → PotPayUnavailableException.
     *
     * authHeader is passed in per-call since GH and NG use different schemes
     * (GH: "Api-Key {key}", NG: "Bearer {merchantId}").
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callPotPay(String method, String path, Map<String, Object> body, String authHeader) {
        var client = webClientBuilder.build();
        var isGet  = !"POST".equals(method);
        var url    = baseUrl() + path;

        WebClient.RequestHeadersSpec<?> spec;
        if (isGet) {
            spec = client.get().uri(url)
                    .header("Authorization", authHeader);
        } else {
            spec = client.post().uri(url)
                    .header("Authorization", authHeader)
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

    /** Docs return amounts as strings ("50.00", "48.50"); tolerate "1,000.00" too. */
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
}
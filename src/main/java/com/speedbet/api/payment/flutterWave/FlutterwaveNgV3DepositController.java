package com.speedbet.api.payment.flutterWave;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nigerian Naira (NGN) deposits via Flutterwave API **v3** — the rail
 * LuckyStake's NigeriaDeposit frontend is wired to (v4 retired for NG on
 * the owner's order, 2026-10-15).
 *
 * Methods (all amounts in MAJOR units — ₦2,000 is sent as 2000):
 *
 *   1. Bank transfer (dynamic virtual account)
 *      POST /api/wallet/deposit/flutterwave/ng-bank/init/bank-transfer
 *        -> POST /v3/charges?type=bank_transfer
 *        -> pay-in details come back in meta.authorization
 *           (transfer_account / transfer_bank / account_expiration /
 *            transfer_note / transfer_amount)
 *
 *   2. USSD
 *      POST /api/wallet/deposit/flutterwave/ng-ussd/init
 *        -> POST /v3/charges?type=ussd (account_bank = the customer's bank code)
 *        -> dial string comes back in meta.authorization.note
 *      GET  /api/wallet/deposit/flutterwave/ng-ussd/banks
 *        -> GET /v3/banks/NG
 *
 *   3. Card / Pay-with-Bank and OPay (hosted checkout)
 *      POST /api/wallet/deposit/flutterwave/ng-hosted/init  { amount, method: "card" | "opay" }
 *        -> POST /v3/payments with payment_options "card,account" or "opay"
 *        -> returns { reference, redirectUrl } — the browser goes to
 *           Flutterwave's hosted page and returns to
 *           {frontendUrl}/deposit?method=ngv3 with Flutterwave appending
 *           status / tx_ref / transaction_id. The return page polls the
 *           verify endpoint below; the redirect params themselves are
 *           NEVER trusted for crediting.
 *
 *   4. Verify / credit-on-confirm (frontend polling)
 *      GET /api/wallet/deposit/flutterwave/ng/verify?ref={tx_ref}
 *        -> GET /v3/transactions/verify_by_reference?tx_ref=...
 *        -> credits ONLY when data.status == "successful", currency is
 *           NGN, the charge belongs to the calling user (meta.userId or
 *           the init-time pending record) and the verified amount covers
 *           the requested amount. Crediting goes through the shared
 *           handleVerifiedDeposit(), which is idempotent on tx_ref.
 *
 * Settlement also lands via webhook: Flutterwave calls the single
 * registered router URL (/api/webhooks/flutterwave), which routes every
 * NGN charge.completed event to FlutterwaveNgDepositController's
 * processWebhook() — same verify-then-credit path, same idempotency, so
 * whichever of webhook / verify-poll arrives first credits exactly once.
 * Every init below stamps meta.userId, which is what that webhook path
 * requires to identify the owner.
 *
 * Auth to Flutterwave is the v3 static secret key (Bearer) configured as
 * app.flutterwave.secret-key / FLUTTERWAVE_SECRET_KEY, against
 * app.flutterwave.base-url (default https://api.flutterwave.com/v3).
 * No OAuth, no v4 client credentials.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveNgV3DepositController extends AbstractFlutterwaveDepositController {

    static final String EXPECTED_CURRENCY = "NGN";
    static final String PROVIDER_TAG      = "flutterwave_ng_v3";

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.platform.min-deposit-amount-ngn:2000}")
    private BigDecimal minDeposit;

    /**
     * Init-time record of who asked for each tx_ref and how much they
     * asked for. Lets the verify endpoint prove ownership and amount even
     * if Flutterwave's verify payload omits meta, and stops one user
     * polling (and crediting) another user's reference. In-memory only —
     * a restart loses it, in which case verify falls back to the verified
     * payload's meta.userId, and the webhook path is unaffected.
     */
    private record PendingNg(UUID userId, BigDecimal amount, String method) {}
    private final Map<String, PendingNg> pending = new ConcurrentHashMap<>();

    @Override protected WalletService walletService()         { return walletService; }
    @Override protected ReferralService referralService()     { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper objectMapper()           { return objectMapper; }

    // ─── 1. Bank transfer (dynamic virtual account) ──────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/ng-bank/init/bank-transfer")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initBankTransfer(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req);
        validateMin(amount);

        var txRef = "SPB-NGT-" + UUID.randomUUID();
        log.info("initBankTransfer(NG v3): userId='{}' amount={} txRef='{}'", user.getId(), amount, txRef);

        var body = new HashMap<String, Object>();
        body.put("tx_ref", txRef);
        body.put("amount", amount);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("email", user.getEmail());
        body.put("fullname", fullName(user));
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            body.put("phone_number", user.getPhone());
        }
        body.put("is_permanent", false);
        body.put("bank_transfer_options", Map.of("expires", 3600));
        body.put("meta", Map.of("userId", user.getId().toString()));

        var response = charge("bank_transfer", body);

        var meta = (Map<String, Object>) response.get("meta");
        var auth = meta != null ? (Map<String, Object>) meta.get("authorization") : null;
        if (auth == null || auth.get("transfer_account") == null) {
            log.error("initBankTransfer(NG v3): no meta.authorization in Flutterwave response for txRef='{}': {}",
                    txRef, response);
            throw new RuntimeException("Flutterwave did not return transfer account details. Please try again.");
        }

        pending.put(txRef, new PendingNg(user.getId(), amount, "bank_transfer"));

        var result = new LinkedHashMap<String, Object>();
        result.put("reference", txRef);
        result.put("accountNumber", String.valueOf(auth.get("transfer_account")));
        result.put("bankName", auth.get("transfer_bank") != null ? String.valueOf(auth.get("transfer_bank")) : "");
        result.put("expiresAt", auth.get("account_expiration") != null ? String.valueOf(auth.get("account_expiration")) : "");
        result.put("note", auth.get("transfer_note") != null ? String.valueOf(auth.get("transfer_note")) : "Transfer the exact amount shown");
        // transfer_amount is what the customer must actually send (it can
        // differ from the requested amount when fees are passed on).
        result.put("amount", auth.get("transfer_amount") != null
                ? new BigDecimal(auth.get("transfer_amount").toString())
                : amount);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── 2. USSD ────────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/ng-ussd/init")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initUssd(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req);
        validateMin(amount);

        var bankCode = req.get("bankCode");
        if (bankCode == null || bankCode.toString().isBlank()) {
            throw ApiException.badRequest("bankCode is required. Call GET /api/wallet/deposit/flutterwave/ng-ussd/banks for the list.");
        }

        var txRef = "SPB-NGU-" + UUID.randomUUID();
        log.info("initUssd(NG v3): userId='{}' amount={} bankCode='{}' txRef='{}'",
                user.getId(), amount, bankCode, txRef);

        var body = new HashMap<String, Object>();
        body.put("account_bank", bankCode.toString().trim());
        body.put("amount", amount);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("email", user.getEmail());
        body.put("tx_ref", txRef);
        body.put("fullname", fullName(user));
        var phone = req.get("phoneNumber") != null ? req.get("phoneNumber").toString() : user.getPhone();
        if (phone != null && !phone.isBlank()) {
            body.put("phone_number", phone);
        }
        body.put("meta", Map.of("userId", user.getId().toString()));

        var response = charge("ussd", body);
        var data = (Map<String, Object>) response.get("data");
        var meta = (Map<String, Object>) response.get("meta");
        var auth = meta != null ? (Map<String, Object>) meta.get("authorization") : null;

        pending.put(txRef, new PendingNg(user.getId(), amount, "ussd"));

        String note = null;
        if (auth != null && auth.get("note") != null) {
            note = String.valueOf(auth.get("note"));
        } else if (data != null && data.get("payment_code") != null) {
            note = "Dial your bank's USSD code and enter payment code " + data.get("payment_code");
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("reference", txRef);
        result.put("chargeId", data != null && data.get("id") != null ? String.valueOf(data.get("id")) : "");
        result.put("note", note != null ? note : "Dial your bank's USSD code to complete payment");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/api/wallet/deposit/flutterwave/ng-ussd/banks")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Object>> listBanks() {
        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder().build()
                    .get()
                    .uri(baseUrl + "/banks/NG")
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).map(respBody -> {
                                log.error("Flutterwave banks(NG) error: status={} body={}",
                                        clientResponse.statusCode(), respBody);
                                return new FlutterwaveApiException(
                                        "Flutterwave banks returned " + clientResponse.statusCode() + ": " + respBody);
                            }))
                    .bodyToMono(Map.class)
                    .timeout(flwTimeout)
                    .retryWhen(Retry.max(flwRetryAttempts)
                            .filter(ex -> !(ex instanceof FlutterwaveApiException)))
                    .block();
        } catch (FlutterwaveApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("listBanks(NG v3): Flutterwave unreachable", e);
            throw new RuntimeException("Flutterwave is currently unavailable. Please try again.");
        }

        Object data = result != null ? result.get("data") : null;
        return ResponseEntity.ok(ApiResponse.ok(data != null ? data : java.util.List.of()));
    }

    // ─── 3. Hosted checkout (Card / Pay-with-Bank, OPay) ────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/ng-hosted/init")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initHosted(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = parseAmount(req);
        validateMin(amount);

        var method = req.get("method") != null ? req.get("method").toString().trim().toLowerCase() : "card";
        if (!method.equals("card") && !method.equals("opay")) {
            throw ApiException.badRequest("method must be 'card' or 'opay'");
        }

        var txRef = "SPB-NGH-" + UUID.randomUUID();
        log.info("initHosted(NG v3): userId='{}' amount={} method='{}' txRef='{}'",
                user.getId(), amount, method, txRef);

        var customer = new LinkedHashMap<String, Object>();
        customer.put("email", user.getEmail());
        customer.put("name", fullName(user));
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            customer.put("phonenumber", user.getPhone());
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("tx_ref", txRef);
        body.put("amount", amount);
        body.put("currency", EXPECTED_CURRENCY);
        // Straight back to the frontend return page — it polls the verify
        // endpoint (authenticated) and nothing is credited from the
        // redirect params themselves.
        body.put("redirect_url", frontendUrl + "/deposit?method=ngv3");
        body.put("customer", customer);
        body.put("customizations", Map.of(
                "title", "LuckyStake",
                "description", "Wallet deposit"));
        body.put("payment_options", method.equals("opay") ? "opay" : "card,account");
        body.put("meta", Map.of("userId", user.getId().toString()));

        var response = createPaymentLink(body);
        var data = (Map<String, Object>) response.get("data");
        var link = data != null ? data.get("link") : null;
        if (link == null || link.toString().isBlank()) {
            log.error("initHosted(NG v3): no data.link in Flutterwave response for txRef='{}': {}", txRef, response);
            throw new RuntimeException("Flutterwave did not return a checkout link. Please try again.");
        }

        pending.put(txRef, new PendingNg(user.getId(), amount, method));

        var result = new LinkedHashMap<String, Object>();
        result.put("reference", txRef);
        result.put("redirectUrl", link.toString());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * POST /v3/payments — same HTTP/retry/timeout discipline as the base
     * class's charge() helper, minus the ?type= query param.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createPaymentLink(Map<String, Object> body) {
        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder().build()
                    .post()
                    .uri(baseUrl + "/payments")
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).map(respBody -> {
                                log.error("Flutterwave payments error: status={} body={}",
                                        clientResponse.statusCode(), respBody);
                                return new FlutterwaveApiException(
                                        "Flutterwave returned " + clientResponse.statusCode() + ": " + respBody);
                            }))
                    .bodyToMono(Map.class)
                    .timeout(flwTimeout)
                    .retryWhen(Retry.max(flwRetryAttempts)
                            .filter(ex -> !(ex instanceof FlutterwaveApiException)))
                    .block();
        } catch (FlutterwaveApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("createPaymentLink(NG v3): Flutterwave unreachable", e);
            throw new RuntimeException("Flutterwave is currently unavailable. Please try again.");
        }

        if (result == null) {
            throw new RuntimeException("Flutterwave returned an empty response.");
        }
        if ("error".equals(String.valueOf(result.get("status")))) {
            var message = String.valueOf(result.getOrDefault("message", "Flutterwave declined the request"));
            throw new RuntimeException("Flutterwave error: " + message);
        }
        return result;
    }

    // ─── 4. Verify (frontend polling; credits on confirmed success) ─────────

    @GetMapping("/api/wallet/deposit/flutterwave/ng/verify")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String ref) {

        var data = verifyTransactionByReference(ref);
        if (data == null) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "pending", "credited", false)));
        }

        var status = String.valueOf(data.get("status"));
        if ("failed".equals(status) || "cancelled".equals(status)) {
            pending.remove(ref);
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "failed", "credited", false)));
        }
        if (!"successful".equals(status)) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "pending", "credited", false)));
        }

        if (!EXPECTED_CURRENCY.equals(String.valueOf(data.get("currency")))) {
            log.error("verify(NG v3): currency mismatch ref='{}' got='{}'", ref, data.get("currency"));
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "failed", "credited", false)));
        }

        // Ownership: the verified payload's meta.userId, or our init-time
        // pending record. The charge must belong to the calling user.
        UUID ownerId = null;
        var meta = (Map<String, Object>) data.get("meta");
        if (meta != null && meta.get("userId") != null) {
            try { ownerId = UUID.fromString(meta.get("userId").toString()); } catch (Exception ignored) { /* fall through to pending record */ }
        }
        var pend = pending.get(ref);
        if (ownerId == null && pend != null) {
            ownerId = pend.userId();
        }
        if (ownerId == null || !ownerId.equals(user.getId())) {
            log.warn("verify(NG v3): ref='{}' does not belong to userId='{}' — not crediting", ref, user.getId());
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "pending", "credited", false)));
        }

        var amount = new BigDecimal(data.get("amount").toString());
        if (pend != null && amount.compareTo(pend.amount()) < 0) {
            log.warn("verify(NG v3): ref='{}' verified amount {} is below requested {} — not crediting",
                    ref, amount, pend.amount());
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "pending", "credited", false)));
        }

        handleVerifiedDeposit(ownerId, ref, amount, EXPECTED_CURRENCY, PROVIDER_TAG);
        pending.remove(ref);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "succeeded", "credited", true)));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private BigDecimal parseAmount(Map<String, Object> req) {
        var val = req.get("amount");
        if (val == null) throw ApiException.badRequest("'amount' is required");
        try { return new BigDecimal(val.toString()); }
        catch (NumberFormatException e) { throw ApiException.badRequest("'amount' must be a valid number"); }
    }

    private void validateMin(BigDecimal amount) {
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest("Minimum deposit is NGN " + minDeposit.toPlainString());
        }
    }

    /** Same name-building as the legacy NG controllers. */
    private static String fullName(User user) {
        var first = user.getFirstName();
        var last  = user.getLastName();
        var name  = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
        if (!name.isBlank()) {
            return name;
        }
        var email = user.getEmail();
        return email != null ? email.split("@")[0] : "Customer";
    }
}

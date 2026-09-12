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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Nigerian Naira (NGN) deposits via Flutterwave v4.
 *
 * Supports all four NGN-compatible v4 payment methods:
 *
 *   1. Pay with Bank Account  — Mono redirect (direct debit)
 *      POST /api/wallet/deposit/flutterwave/v4/ng-bank/init
 *
 *   2. Pay with Bank Transfer — dynamic virtual account (PWBT)
 *      POST /api/wallet/deposit/flutterwave/v4/ng-bank/init/bank-transfer
 *
 *   3. USSD                   — offline dial-to-pay, NGN only
 *      POST /api/wallet/deposit/flutterwave/v4/ng-ussd/init
 *
 *   4. OPay                   — OPay wallet redirect, NGN only
 *      POST /api/wallet/deposit/flutterwave/v4/ng-opay/init
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  CRITICAL FIX — WHY DEPOSITS WERE NOT CREDITING
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  The abstract base class (AbstractFlutterwaveV4DepositController) had the
 *  WRONG webhook verification algorithm. It implemented Svix HMAC-SHA256
 *  over "{svix-id}.{svix-timestamp}.{body}" — but Flutterwave v4 does NOT
 *  use Svix at all.
 *
 *  Per the official Flutterwave v4 docs (developer.flutterwave.com/docs/webhooks):
 *
 *    Header:    flutterwave-signature
 *    Algorithm: HMAC-SHA256(secretHash, rawBody) → base64
 *    Compare:   base64 result == flutterwave-signature header value directly
 *    Secret:    the plain-text "Secret hash" you typed into the dashboard
 *               (NOT a whsec_... Svix key — just your plain secret string)
 *
 *  Because of the wrong algorithm EVERY webhook was rejected with 401,
 *  no deposits were ever credited via webhook, and the reconciler's
 *  GET /charges/{id} path was also broken (Flutterwave's lookup endpoint
 *  was returning errors for many charges).
 *
 *  This controller overrides processWebhook() directly rather than using
 *  the abstract base's processV4Webhook(), implementing the correct algorithm
 *  inline so it is not dependent on the base class being fixed first.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  HOW THE CORRECT ALGORITHM WORKS (from official docs)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  1. Read the raw request body bytes (do NOT parse/re-serialize JSON).
 *  2. Compute: HMAC-SHA256(key=secretHash, data=rawBody)
 *  3. Base64-encode the result.
 *  4. Compare that base64 string to the `flutterwave-signature` header.
 *  5. If they match → webhook is genuine → process it.
 *
 *  The secret hash is just your plain dashboard value, e.g.
 *  "BetBrosDepositUpdateForGhanaiansAndWeActive" from application.properties:
 *    app.flutterwave.webhook-hash=${FLUTTERWAVE_WEBHOOK_HASH:}
 *
 *  Node.js reference (from official docs):
 *    const hash = crypto.createHmac('sha256', secretHash)
 *                       .update(rawBody)
 *                       .digest('base64');
 *    return hash === signature;
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  application.properties keys needed by THIS class
 * ═══════════════════════════════════════════════════════════════════════════
 *  app.flutterwave.webhook-hash          — already present in your file
 *  app.platform.min-deposit-amount-ngn   — already present
 *  app.platform.backend-public-url       — already present
 *  app.platform.frontend-url             — already present
 *  app.flutterwave.v4.base-url           — already present (abstract base)
 *  app.flutterwave.v4.client-id          — already present (abstract base)
 *  app.flutterwave.v4.client-secret      — already present (abstract base)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveNgBankV4DepositController extends AbstractFlutterwaveV4DepositController {

    // ── Constants ─────────────────────────────────────────────────────────────

    static final String EXPECTED_CURRENCY = "NGN";
    static final String PROVIDER_TAG      = "flutterwave_ng_bank_v4";
    static final String TXREF_PREFIX      = "NGBV4-"; // 6 + 32 hex = 38 chars (v4 limit: 6–42)
    static final String PWBT_PREFIX       = "NGPW-";  // 5 + 32 hex = 37 chars

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final WalletService                   walletService;
    private final ReferralService                 referralService;
    private final WebClient.Builder               webClientBuilder;
    private final ObjectMapper                    objectMapper;
    private final FlutterwaveV4PendingChargeStore pendingChargeStore;

    // ── Config ────────────────────────────────────────────────────────────────

    @Value("${app.platform.min-deposit-amount-ngn:10000}")
    private BigDecimal minDeposit;

    @Value("${app.platform.backend-public-url}")
    private String backendPublicUrl;

    @Value("${app.platform.frontend-url}")
    private String frontendUrl;

    /**
     * The plain-text secret hash set in the Flutterwave dashboard under
     * Settings → Webhooks → "Secret hash".
     * In your application.properties: app.flutterwave.webhook-hash=${FLUTTERWAVE_WEBHOOK_HASH:}
     * This is used for the CORRECT HMAC-SHA256 verification, NOT any Svix scheme.
     */
    @Value("${app.flutterwave.webhook-hash}")
    private String webhookSecretHash;

    // ── Abstract base wiring ──────────────────────────────────────────────────

    @Override protected WalletService                   walletService()      { return walletService; }
    @Override protected ReferralService                 referralService()    { return referralService; }
    @Override protected WebClient.Builder               webClientBuilder()   { return webClientBuilder; }
    @Override protected ObjectMapper                    objectMapper()       { return objectMapper; }
    @Override protected FlutterwaveV4PendingChargeStore pendingChargeStore() { return pendingChargeStore; }

    @Override public String expectedCurrency() { return EXPECTED_CURRENCY; }
    @Override public String providerTag()      { return PROVIDER_TAG; }

    // =========================================================================
    // 1. PAY WITH BANK ACCOUNT  (Mono redirect)
    // =========================================================================

    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initBankAccountDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount    = parseAmount(req);
        validateMin(amount);

        String reference   = TXREF_PREFIX + randomHex();
        String redirectUrl = backendPublicUrl + "/api/wallet/deposit/flutterwave/v4/ng-bank/redirect";

        log.info("initBankAccountDeposit: userId='{}' amount={} ref='{}'",
                user.getId(), amount, reference);

        Map<String, Object> response = orchestratorCharge(
                buildBankAccountBody(amount, user, reference, redirectUrl));

        Map<String, Object> data  = unwrapData(response, reference, "bank_account");
        String chargeId           = data.get("id").toString();
        String redirectAuth       = extractRedirectUrl(data);
        String nextActionType     = extractNextActionType(data);

        cachePendingCharge(reference, chargeId, user.getId(), amount);

        if (redirectAuth == null) {
            log.warn("initBankAccountDeposit: no redirect URL in next_action for ref='{}' chargeId='{}'",
                    reference, chargeId);
        }

        log.info("initBankAccountDeposit: chargeId='{}' status='{}' nextAction='{}' userId='{}'",
                chargeId, data.get("status"), nextActionType, user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("reference",      reference);
        result.put("chargeId",       chargeId);
        result.put("redirectUrl",    redirectAuth != null ? redirectAuth : "");
        result.put("nextActionType", nextActionType != null ? nextActionType : "redirect_url");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // 2. PAY WITH BANK TRANSFER (PWBT — dynamic virtual account)
    // =========================================================================

    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/init/bank-transfer")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initBankTransferDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount = parseAmount(req);
        validateMin(amount);

        String reference  = PWBT_PREFIX + randomHex();
        String customerId = ensureCustomer(user);

        log.info("initBankTransferDeposit: userId='{}' amount={} ref='{}'",
                user.getId(), amount, reference);

        Map<String, Object> vaBody = new LinkedHashMap<>();
        vaBody.put("reference",    reference);
        vaBody.put("customer_id",  customerId);
        vaBody.put("amount",       amount);
        vaBody.put("currency",     EXPECTED_CURRENCY);
        vaBody.put("account_type", "dynamic");
        vaBody.put("narration",    firstName(user) + " " + lastName(user));

        String token = getAccessToken();
        Map<String, Object> vaResult;
        try {
            vaResult = (Map<String, Object>) webClientBuilder.build()
                    .post()
                    .uri(baseUrl + "/virtual-accounts")
                    .header("Authorization",     "Bearer " + token)
                    .header("Content-Type",      "application/json")
                    .header("X-Idempotency-Key", reference)
                    .bodyValue(vaBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception ex) {
            log.error("initBankTransferDeposit: call failed for ref='{}' — {}", reference, ex.getMessage(), ex);
            throw ApiException.badRequest("Could not create virtual account. Please try again.");
        }

        if (vaResult == null || !"success".equals(vaResult.get("status"))) {
            log.error("initBankTransferDeposit: bad response for ref='{}' — {}", reference, vaResult);
            throw ApiException.badRequest("Could not create virtual account. Please try again.");
        }

        Map<String, Object> vaData = (Map<String, Object>) vaResult.get("data");
        String vanId = vaData.getOrDefault("id", reference).toString();
        cachePendingCharge(reference, vanId, user.getId(), amount);

        log.info("initBankTransferDeposit: vanId='{}' accountNumber='{}' bank='{}' userId='{}'",
                vanId, vaData.get("account_number"), vaData.get("account_bank_name"), user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("reference",     reference);
        result.put("accountNumber", vaData.getOrDefault("account_number", ""));
        result.put("bankName",      vaData.getOrDefault("account_bank_name", ""));
        result.put("expiresAt",     vaData.getOrDefault("account_expiration_datetime", ""));
        result.put("note",          vaData.getOrDefault("note", "Transfer the exact amount shown"));
        result.put("amount",        amount);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // 3. USSD
    // =========================================================================

    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-ussd/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initUssdDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount = parseAmount(req);
        validateMin(amount);

        Object bankCodeRaw = req.get("bankCode");
        if (bankCodeRaw == null || bankCodeRaw.toString().isBlank()) {
            throw ApiException.badRequest(
                    "bankCode is required. Call GET /api/wallet/deposit/flutterwave/v4/ng-ussd/banks for the list.");
        }
        String bankCode = bankCodeRaw.toString().trim();

        // "NGBV4-USSD-" = 12 + 30 hex = 42 chars exactly (v4 max)
        String reference = TXREF_PREFIX + "USSD-" + randomHex().substring(0, 30);

        log.info("initUssdDeposit: userId='{}' amount={} bankCode='{}' ref='{}'",
                user.getId(), amount, bankCode, reference);

        Map<String, Object> response = orchestratorCharge(
                buildUssdBody(amount, user, bankCode, reference));

        Map<String, Object> data = unwrapData(response, reference, "ussd");
        String chargeId          = data.get("id").toString();
        String note              = extractPaymentInstruction(data);

        cachePendingCharge(reference, chargeId, user.getId(), amount);

        log.info("initUssdDeposit: chargeId='{}' note='{}' userId='{}'", chargeId, note, user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("reference", reference);
        result.put("chargeId",  chargeId);
        result.put("note",      note != null ? note : "Dial your bank's USSD code to complete payment");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng-ussd/banks")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Object>> listUssdBanks() {
        String token = getAccessToken();
        Object result = webClientBuilder.build()
                .get()
                .uri(baseUrl + "/banks?country=NG")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type",  "application/json")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // 4. OPAY
    // =========================================================================

    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-opay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initOpayDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount = parseAmount(req);
        validateMin(amount);

        // "NGBV4-OPAY-" = 12 + 30 hex = 42 chars exactly (v4 max)
        String reference   = TXREF_PREFIX + "OPAY-" + randomHex().substring(0, 30);
        String redirectUrl = backendPublicUrl + "/api/wallet/deposit/flutterwave/v4/ng-opay/redirect";

        log.info("initOpayDeposit: userId='{}' amount={} ref='{}'", user.getId(), amount, reference);

        Map<String, Object> response = orchestratorCharge(
                buildOpayBody(amount, user, reference, redirectUrl));

        Map<String, Object> data = unwrapData(response, reference, "opay");
        String chargeId          = data.get("id").toString();
        String redirectAuth      = extractRedirectUrl(data);

        cachePendingCharge(reference, chargeId, user.getId(), amount);

        log.info("initOpayDeposit: chargeId='{}' redirectUrl='{}' userId='{}'",
                chargeId, redirectAuth, user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("reference",   reference);
        result.put("chargeId",    chargeId);
        result.put("redirectUrl", redirectAuth != null ? redirectAuth : "");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // WEBHOOK — correct Flutterwave v4 signature verification
    //
    // This replaces the abstract base's processV4Webhook() entirely.
    // The base was using Svix HMAC which is completely wrong for Flutterwave.
    //
    // CORRECT ALGORITHM (from developer.flutterwave.com/docs/webhooks):
    //   hash = HMAC-SHA256(key=webhookSecretHash, data=rawBody) → base64
    //   valid = (hash == flutterwave-signature header)
    // =========================================================================

    @PostMapping("/api/webhooks/flutterwave/v4/ng")
    public ResponseEntity<String> webhook(
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] rawBody) {

        // ── Step 1: Verify signature ──────────────────────────────────────────

        String signature = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("flutterwave-signature".equalsIgnoreCase(entry.getKey())) {
                signature = entry.getValue();
                break;
            }
        }

        if (signature == null || signature.isBlank()) {
            log.warn("webhook(NG v4): missing flutterwave-signature header — headers present: {}",
                    headers.keySet());
            return ResponseEntity.status(401).body("Missing signature");
        }

        if (!verifyFlutterwaveSignature(rawBody, signature)) {
            log.warn("webhook(NG v4): signature mismatch — request rejected");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // ── Step 2: Parse body ────────────────────────────────────────────────

        Map<String, Object> event;
        try {
            //noinspection unchecked
            event = (Map<String, Object>) objectMapper.readValue(
                    new String(rawBody, StandardCharsets.UTF_8), Map.class);
        } catch (Exception ex) {
            log.error("webhook(NG v4): failed to parse body", ex);
            return ResponseEntity.status(400).body("Invalid body");
        }

        // ── Step 3: Validate event type ───────────────────────────────────────

        String eventType = String.valueOf(event.get("type"));
        if (!"charge.completed".equals(eventType)) {
            log.info("webhook(NG v4): ignoring event type='{}'", eventType);
            return ResponseEntity.ok("Ignored — not charge.completed");
        }

        // ── Step 4: Extract data ──────────────────────────────────────────────

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.get("data");
        if (data == null) {
            log.warn("webhook(NG v4): missing data field — top-level keys: {}", event.keySet());
            return ResponseEntity.status(400).body("Missing data");
        }

        // ── Step 5: Currency check ────────────────────────────────────────────

        String currency = String.valueOf(data.get("currency"));
        if (!EXPECTED_CURRENCY.equalsIgnoreCase(currency)) {
            log.info("webhook(NG v4): ignoring currency='{}' (expected {})", currency, EXPECTED_CURRENCY);
            return ResponseEntity.ok("Ignored — different currency");
        }

        // ── Step 6: Get reference and look up pending charge ──────────────────

        Object refObj     = data.get("reference");
        Object chargeIdObj = data.get("id");
        if (refObj == null || refObj.toString().isBlank()) {
            log.error("webhook(NG v4): missing reference — data keys: {}", data.keySet());
            return ResponseEntity.status(400).body("Missing reference");
        }

        String ref      = refObj.toString();
        String chargeId = chargeIdObj != null ? chargeIdObj.toString() : "unknown";

        AbstractFlutterwaveV4DepositController.PendingV4Charge pending = getPendingCharge(ref);
        if (pending == null) {
            log.error("webhook(NG v4): no pending charge row for ref='{}' — not ours or already settled", ref);
            // Return 200 so Flutterwave stops retrying a reference we can never satisfy
            return ResponseEntity.ok("Unknown reference");
        }

        // ── Step 7: Check status ──────────────────────────────────────────────

        String status = String.valueOf(data.getOrDefault("status", "unknown"));

        if (!isSuccess(status)) {
            if (isTerminalFailure(status)) {
                pendingChargeStore().markFailed(ref, status);
                log.info("webhook(NG v4): terminal failure status='{}' for ref='{}'", status, ref);
            } else {
                log.info("webhook(NG v4): non-terminal status='{}' for ref='{}' — waiting", status, ref);
            }
            return ResponseEntity.ok("Acknowledged — status: " + status);
        }

        // ── Step 8: Re-verify via GET /charges/{id} ───────────────────────────
        //
        // Docs say: always re-query to confirm amount/currency/status before
        // giving value. If the lookup fails we fall back to the signed payload
        // (since the webhook IS the signed assertion from Flutterwave) but we
        // cross-check the amount against what we stored at init time.

        BigDecimal creditAmount;
        boolean reVerified = false;

        try {
            Map<String, Object> verified = getCharge(chargeId);
            @SuppressWarnings("unchecked")
            Map<String, Object> vData    = (Map<String, Object>) verified.getOrDefault("data", Map.of());

            String vStatus   = String.valueOf(vData.getOrDefault("status", "unknown"));
            String vCurrency = String.valueOf(vData.get("currency"));

            if (!isSuccess(vStatus)) {
                log.warn("webhook(NG v4): re-verification says status='{}' for ref='{}' — not crediting",
                        vStatus, ref);
                if (isTerminalFailure(vStatus)) {
                    pendingChargeStore().markFailed(ref, vStatus);
                }
                return ResponseEntity.ok("Not crediting — re-verification status: " + vStatus);
            }

            if (!EXPECTED_CURRENCY.equalsIgnoreCase(vCurrency)) {
                log.error("webhook(NG v4): currency mismatch on re-verify: got '{}' expected '{}' ref='{}'",
                        vCurrency, EXPECTED_CURRENCY, ref);
                return ResponseEntity.status(400).body("Currency mismatch on verification");
            }

            creditAmount = new BigDecimal(String.valueOf(vData.get("amount")));
            reVerified   = true;
            log.info("webhook(NG v4): re-verified chargeId='{}' amount={} ref='{}'",
                    chargeId, creditAmount, ref);

        } catch (Exception ex) {
            // Flutterwave's GET /charges/{id} is known to return 500 for valid charges.
            // Fall back to the signed payload since the webhook itself is our signed proof.
            log.warn("webhook(NG v4): re-verify failed for chargeId='{}' — falling back to signed payload. {}",
                    chargeId, ex.getMessage());

            Object rawAmount = data.get("amount");
            if (rawAmount == null) {
                log.error("webhook(NG v4): re-verify failed AND payload has no amount for ref='{}' — cannot credit", ref);
                return ResponseEntity.status(500).body("Verification unavailable, will retry");
            }

            creditAmount = new BigDecimal(String.valueOf(rawAmount));

            // Safety: don't credit MORE than what was requested at init time
            if (pending.amount() != null && creditAmount.compareTo(pending.amount()) > 0) {
                log.error("webhook(NG v4): payload amount={} exceeds requested={} for ref='{}' — rejecting",
                        creditAmount, pending.amount(), ref);
                return ResponseEntity.status(400).body("Amount exceeds requested");
            }
        }

        // ── Step 9: Credit wallet ─────────────────────────────────────────────

        try {
            handleVerifiedDeposit(
                    pending.userId(), ref, creditAmount, EXPECTED_CURRENCY, PROVIDER_TAG);

            String via = reVerified ? "webhook" : "webhook_payload_fallback";
            pendingChargeStore().markCredited(ref, via);

            log.info("webhook(NG v4): credited userId='{}' amount={} NGN ref='{}' via='{}'",
                    pending.userId(), creditAmount, ref, via);

            return ResponseEntity.ok("OK");

        } catch (ApiException ex) {
            log.error("webhook(NG v4): credit failed for ref='{}' — {}", ref, ex.getMessage(), ex);
            return ResponseEntity.status(400).body("Credit error: " + ex.getMessage());
        } catch (Exception ex) {
            // Return 500 so Flutterwave retries
            log.error("webhook(NG v4): unexpected error crediting ref='{}' — will retry", ref, ex);
            return ResponseEntity.status(500).body("Processing error");
        }
    }

    // =========================================================================
    // VERIFY — safe polling endpoint for frontend
    // =========================================================================

    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String reference) {

        Map<String, Object> result = verifyAndCredit(
                user.getId(), reference, EXPECTED_CURRENCY, PROVIDER_TAG);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // REDIRECT callbacks — UX only, no crediting
    // =========================================================================

    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/redirect")
    public ResponseEntity<Void> bankAccountRedirect(
            @RequestParam(value = "reference", required = false) String reference,
            @RequestParam(value = "status",    required = false) String status) {

        log.info("bankAccountRedirect: ref='{}' status='{}'", reference, status);
        return buildFrontendRedirect("ngbank-v4", reference);
    }

    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng-opay/redirect")
    public ResponseEntity<Void> opayRedirect(
            @RequestParam(value = "reference", required = false) String reference,
            @RequestParam(value = "status",    required = false) String status) {

        log.info("opayRedirect: ref='{}' status='{}'", reference, status);
        return buildFrontendRedirect("ngopay-v4", reference);
    }

    // =========================================================================
    // SIGNATURE VERIFICATION — correct Flutterwave v4 algorithm
    // =========================================================================

    /**
     * Verifies a Flutterwave v4 webhook signature.
     *
     * Official algorithm (developer.flutterwave.com/docs/webhooks):
     *   hash = HMAC-SHA256(key=secretHash, data=rawBody) → base64
     *   valid = (hash == flutterwave-signature header value)
     *
     * The secret hash is the PLAIN TEXT value you entered in the Flutterwave
     * dashboard under Settings → Webhooks → "Secret hash". It is NOT a
     * whsec_... Svix key. It is NOT used as HMAC input over a concatenated
     * string. The HMAC key is the secret, the HMAC data is the raw body.
     */
    private boolean verifyFlutterwaveSignature(byte[] rawBody, String signatureHeader) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecretHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawBody);
            String computedBase64 = Base64.getEncoder().encodeToString(computed);

            // Constant-time comparison to prevent timing attacks
            boolean match = MessageDigest.isEqual(
                    computedBase64.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));

            if (!match) {
                log.debug("webhook signature mismatch: computed='{}' received='{}'",
                        computedBase64, signatureHeader);
            }
            return match;

        } catch (Exception ex) {
            log.error("webhook signature computation failed", ex);
            return false;
        }
    }

    // =========================================================================
    // Private — request body builders
    // =========================================================================

    private static Map<String, Object> buildBankAccountBody(
            BigDecimal amount, User user, String reference, String redirectUrl) {

        Map<String, Object> paymentMethod = new LinkedHashMap<>();
        paymentMethod.put("type",         "bank_account");
        paymentMethod.put("bank_account", new LinkedHashMap<>()); // empty — confirmed v4 shape

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount",         amount);
        body.put("currency",       EXPECTED_CURRENCY);
        body.put("reference",      reference);
        body.put("redirect_url",   redirectUrl);    // top-level — NOT inside payment_method
        body.put("payment_method", paymentMethod);
        body.put("customer",       buildCustomer(user));
        return body;
    }

    private static Map<String, Object> buildUssdBody(
            BigDecimal amount, User user, String bankCode, String reference) {

        Map<String, Object> ussd = new LinkedHashMap<>();
        ussd.put("account_bank", bankCode);

        Map<String, Object> paymentMethod = new LinkedHashMap<>();
        paymentMethod.put("type", "ussd");
        paymentMethod.put("ussd", ussd);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount",         amount);
        body.put("currency",       EXPECTED_CURRENCY);
        body.put("reference",      reference);
        body.put("payment_method", paymentMethod);
        body.put("customer",       buildCustomer(user));
        return body;
    }

    private static Map<String, Object> buildOpayBody(
            BigDecimal amount, User user, String reference, String redirectUrl) {

        Map<String, Object> paymentMethod = new LinkedHashMap<>();
        paymentMethod.put("type", "opay");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount",         amount);
        body.put("currency",       EXPECTED_CURRENCY);
        body.put("reference",      reference);
        body.put("redirect_url",   redirectUrl);
        body.put("payment_method", paymentMethod);
        body.put("customer",       buildCustomer(user));
        return body;
    }

    private static Map<String, Object> buildCustomer(User user) {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("first", firstName(user));
        name.put("last",  lastName(user));

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("email", user.getEmail());
        customer.put("name",  name);
        return customer;
    }

    // =========================================================================
    // Private — response extraction
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapData(
            Map<String, Object> response, String reference, String method) {

        if (response == null) {
            throw ApiException.badRequest("No response from payment provider. Please try again.");
        }
        Object dataObj = response.get("data");
        if (!(dataObj instanceof Map)) {
            log.error("unwrapData({}): missing data object for ref='{}' — response: {}",
                    method, reference, response);
            throw ApiException.badRequest("Payment initiation failed — no charge data returned.");
        }
        Map<String, Object> data = (Map<String, Object>) dataObj;
        if (data.get("id") == null) {
            log.error("unwrapData({}): data.id is null for ref='{}' — data: {}", method, reference, data);
            throw ApiException.badRequest("Payment initiation failed — charge id missing.");
        }
        return data;
    }

    /**
     * Confirmed v4 path: data.next_action.redirect_url.url
     * The redirect_url value is an OBJECT { "url": "..." }, NOT a plain string.
     */
    @SuppressWarnings("unchecked")
    private static String extractRedirectUrl(Map<String, Object> data) {
        Object naObj = data.get("next_action");
        if (!(naObj instanceof Map)) return null;

        Map<String, Object> na = (Map<String, Object>) naObj;

        Object ruObj = na.get("redirect_url");
        if (ruObj instanceof Map) {
            Object url = ((Map<String, Object>) ruObj).get("url");
            if (url != null) return url.toString();
        }

        // Fallback for older beta responses
        Object authUrl = na.get("auth_url");
        return authUrl != null ? authUrl.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static String extractNextActionType(Map<String, Object> data) {
        Object naObj = data.get("next_action");
        if (!(naObj instanceof Map)) return null;
        Object type = ((Map<String, Object>) naObj).get("type");
        return type != null ? type.toString() : null;
    }

    /**
     * Confirmed v4 path: data.next_action.payment_instruction.note
     */
    @SuppressWarnings("unchecked")
    private static String extractPaymentInstruction(Map<String, Object> data) {
        Object naObj = data.get("next_action");
        if (!(naObj instanceof Map)) return null;

        Map<String, Object> na = (Map<String, Object>) naObj;
        Object piObj = na.get("payment_instruction");
        if (!(piObj instanceof Map)) return null;

        Object note = ((Map<String, Object>) piObj).get("note");
        return note != null ? note.toString() : null;
    }

    // =========================================================================
    // Private — Flutterwave customer creation (PWBT only)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String ensureCustomer(User user) {
        Map<String, Object> nameMap = new LinkedHashMap<>();
        nameMap.put("first", firstName(user));
        nameMap.put("last",  lastName(user));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", user.getEmail());
        body.put("name",  nameMap);

        String token = getAccessToken();
        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder.build()
                    .post()
                    .uri(baseUrl + "/customers")
                    .header("Authorization",     "Bearer " + token)
                    .header("Content-Type",      "application/json")
                    .header("X-Idempotency-Key", user.getId().toString())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception ex) {
            log.error("ensureCustomer: call failed for userId='{}' — {}", user.getId(), ex.getMessage(), ex);
            throw new RuntimeException("Could not create payment customer record. Please try again.");
        }

        if (result == null) throw new RuntimeException("Null response when creating Flutterwave customer");

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        if (data == null || data.get("id") == null) {
            throw new RuntimeException("Flutterwave customer response missing id: " + result);
        }
        return data.get("id").toString();
    }

    // =========================================================================
    // Private — misc utilities
    // =========================================================================

    private BigDecimal parseAmount(Map<String, Object> req) {
        Object val = req.get("amount");
        if (val == null) throw ApiException.badRequest("'amount' is required");
        try { return new BigDecimal(val.toString()); }
        catch (NumberFormatException e) { throw ApiException.badRequest("'amount' must be a valid number"); }
    }

    private void validateMin(BigDecimal amount) {
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest("Minimum deposit is NGN " + minDeposit.toPlainString());
        }
    }

    private ResponseEntity<Void> buildFrontendRedirect(String method, String reference) {
        URI target = UriComponentsBuilder.fromUriString(frontendUrl + "/deposit")
                .queryParam("method", method)
                .queryParamIfPresent("reference", Optional.ofNullable(reference))
                .build(true).toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

    private static String randomHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String firstName(User user) {
        String first = user.getFirstName();
        if (first != null && !first.isBlank()) return first;
        String email = user.getEmail();
        return email != null ? email.split("@")[0] : "Customer";
    }

    private static String lastName(User user) {
        String last = user.getLastName();
        return (last != null && !last.isBlank()) ? last : "User";
    }
}
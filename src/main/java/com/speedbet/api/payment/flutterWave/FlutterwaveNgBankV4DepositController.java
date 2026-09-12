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

import java.math.BigDecimal;
import java.net.URI;
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
 * ── How this fits the abstract base ─────────────────────────────────────────
 *
 *   getAccessToken()      — lives on AbstractFlutterwaveV4DepositController.
 *                           Uses app.flutterwave.v4.client-id / client-secret /
 *                           token-url. No separate token service needed.
 *
 *   orchestratorCharge()  — also on the abstract base. Handles auth, retry,
 *                           timeout, and non-success error surfacing. This class
 *                           only builds the payment_method body.
 *
 *   baseUrl               — @Value("${app.flutterwave.v4.base-url:...}") on
 *                           the abstract base. In your properties file this is
 *                           FLUTTERWAVE_V4_BASE_URL, defaulting to
 *                           https://f4bexperience.flutterwave.com.
 *
 *   getPendingCharge()    — returns PendingV4Charge (the inner record on the
 *                           abstract base), NOT the entity. Accessors are
 *                           record-style: pending.userId(), pending.chargeId().
 *
 * ── application.properties keys used by this class only ─────────────────────
 *
 *   app.platform.min-deposit-amount-ngn   — already present: ${MIN_DEPOSIT_AMOUNT_NGN:10000}
 *   app.platform.backend-public-url       — already present: hardcoded to Railway URL
 *   app.platform.frontend-url             — already present: ${FRONTEND_URL:http://localhost:5173}
 *
 *   All other keys (v4 base-url, client-id, client-secret, token-url,
 *   webhook-svix-secret, reconciler settings) are already in your file
 *   and are consumed by the abstract base, not this class.
 *
 * ── Fixes vs. previous revision ─────────────────────────────────────────────
 *
 *   - FlutterwaveV4TokenService removed — base has getAccessToken()
 *   - orchestratorCharge() re-implementation removed — base already has it
 *   - resolveUserIdFromStore() uses PendingV4Charge (the record) — fixes
 *     both the type error and the "condition always null" inspection warning
 *   - payment_method.type = "bank_account" (was "bank")
 *   - bank_account inner object is empty {} — redirect_url is top-level
 *   - next_action redirect URL at data.next_action.redirect_url.url (object)
 *   - banksUrl is a @Value field, not a local variable
 *   - unused HttpServletRequest removed
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveNgBankV4DepositController extends AbstractFlutterwaveV4DepositController {

    // ── Constants ─────────────────────────────────────────────────────────────

    static final String EXPECTED_CURRENCY = "NGN";
    static final String PROVIDER_TAG      = "flutterwave_ng_bank_v4";

    /**
     * "NGBV4-" (6) + 32 hex = 38 chars.  v4 limit: 6–42.
     */
    static final String TXREF_PREFIX = "NGBV4-";

    /**
     * "NGPW-" (5) + 32 hex = 37 chars.  v4 limit: 6–42.
     */
    static final String PWBT_PREFIX = "NGPW-";

    // ── Dependencies (all wired by @RequiredArgsConstructor) ──────────────────

    private final WalletService                   walletService;
    private final ReferralService                 referralService;
    private final WebClient.Builder               webClientBuilder;
    private final ObjectMapper                    objectMapper;
    private final FlutterwaveV4PendingChargeStore pendingChargeStore;

    // ── Config (@Value) ───────────────────────────────────────────────────────

    /**
     * Bound to app.platform.min-deposit-amount-ngn.
     * Already in application.properties: ${MIN_DEPOSIT_AMOUNT_NGN:10000}
     */
    @Value("${app.platform.min-deposit-amount-ngn:10000}")
    private BigDecimal minDeposit;

    /**
     * Bound to app.platform.backend-public-url.
     * Already in application.properties:
     *   app.platform.backend-public-url=https://futballbackend-production-67b0.up.railway.app
     */
    @Value("${app.platform.backend-public-url}")
    private String backendPublicUrl;

    /**
     * Bound to app.platform.frontend-url.
     * Already in application.properties: ${FRONTEND_URL:http://localhost:5173}
     */
    @Value("${app.platform.frontend-url}")
    private String frontendUrl;

    // ── Abstract base wiring ──────────────────────────────────────────────────

    @Override protected WalletService                   walletService()      { return walletService; }
    @Override protected ReferralService                 referralService()    { return referralService; }
    @Override protected WebClient.Builder               webClientBuilder()   { return webClientBuilder; }
    @Override protected ObjectMapper                    objectMapper()       { return objectMapper; }
    @Override protected FlutterwaveV4PendingChargeStore pendingChargeStore() { return pendingChargeStore; }

    @Override public String expectedCurrency() { return EXPECTED_CURRENCY; }
    @Override public String providerTag()      { return PROVIDER_TAG; }

    // =========================================================================
    // 1. PAY WITH BANK ACCOUNT  (Mono redirect — direct debit)
    //    payment_method.type = "bank_account",  bank_account = {}  (empty)
    //    redirect_url at TOP LEVEL of charge body
    //    next_action.type = "redirect_url"
    //    next_action.redirect_url.url = customer-facing auth URL (object, not string)
    // =========================================================================

    /**
     * Initiates a Pay-with-Bank-Account charge via the v4 Orchestrator.
     *
     * Request:  POST body { "amount": 5000 }
     * Response: { "reference", "chargeId", "redirectUrl", "nextActionType" }
     *
     * Frontend redirects the customer to redirectUrl (Mono's auth page).
     * After authorization, Flutterwave redirects to our /redirect endpoint.
     * The webhook (svix-signed) and background reconciler settle the charge.
     */
    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initBankAccountDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount    = parseAmount(req);
        validateMin(amount);

        String reference   = TXREF_PREFIX + randomHex();
        String redirectUrl = backendPublicUrl
                + "/api/wallet/deposit/flutterwave/v4/ng-bank/redirect";

        log.info("initBankAccountDeposit: userId='{}' amount={} ref='{}'",
                user.getId(), amount, reference);

        // orchestratorCharge() is on the abstract base.
        // It handles OAuth2 token, retry, timeout, and non-success error surfacing.
        Map<String, Object> response = orchestratorCharge(
                buildBankAccountBody(amount, user, reference, redirectUrl));

        Map<String, Object> data  = unwrapData(response, reference, "bank_account");
        String chargeId           = data.get("id").toString();
        String redirectAuth       = extractRedirectUrl(data);
        String nextActionType     = extractNextActionType(data);

        // CRITICAL: persist before returning.
        // v4 has no lookup-by-reference — without this row the charge is unrecoverable.
        cachePendingCharge(reference, chargeId, user.getId(), amount);

        if (redirectAuth == null) {
            log.warn("initBankAccountDeposit: no redirect URL in next_action " +
                    "for ref='{}' chargeId='{}'", reference, chargeId);
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
    // 2. PAY WITH BANK TRANSFER — PWBT (dynamic virtual account)
    //    Separate endpoint: POST baseUrl/virtual-accounts
    //    Customer makes a manual bank transfer into the generated account.
    //    No charge endpoint — credit arrives via webhook only.
    // =========================================================================

    /**
     * Creates a dynamic NGN virtual account for the customer to transfer into.
     *
     * Request:  POST body { "amount": 5000 }
     * Response: { "reference", "accountNumber", "bankName",
     *             "expiresAt", "note", "amount" }
     *
     * Frontend displays the account number and bank name. Customer transfers
     * the exact amount. Default expiry: 1 hour (3600 seconds).
     * Credit arrives via charge.completed webhook.
     */
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

        // baseUrl is @Value("${app.flutterwave.v4.base-url:...}") on the abstract base.
        // In your properties: FLUTTERWAVE_V4_BASE_URL defaults to https://f4bexperience.flutterwave.com
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
            log.error("initBankTransferDeposit: virtual account call failed for ref='{}' — {}",
                    reference, ex.getMessage(), ex);
            throw ApiException.badRequest("Could not create virtual account. Please try again.");
        }

        if (vaResult == null || !"success".equals(vaResult.get("status"))) {
            log.error("initBankTransferDeposit: bad response for ref='{}' — {}", reference, vaResult);
            throw ApiException.badRequest("Could not create virtual account. Please try again.");
        }

        Map<String, Object> vaData = (Map<String, Object>) vaResult.get("data");
        String vanId = vaData.getOrDefault("id", reference).toString();

        // Use the virtual-account id as chargeId placeholder.
        // The real charge id arrives later in the webhook payload.
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
    //    payment_method.type = "ussd",  ussd.account_bank = bankCode
    //    next_action.type = "payment_instruction"
    //    next_action.payment_instruction.note = USSD dial string
    //    NGN only
    // =========================================================================

    /**
     * Initiates a USSD charge via the v4 Orchestrator.
     *
     * Request:  POST body { "amount": 5000, "bankCode": "044" }
     *   bankCode: GET /api/wallet/deposit/flutterwave/v4/ng-ussd/banks for the full list.
     *   Common: 044 = Access Bank, 058 = GTBank, 011 = First Bank,
     *           057 = Zenith Bank, 050 = EcoBank, 063 = Access (Diamond).
     *
     * Response: { "reference", "chargeId", "note" }
     *   note: dial string to show the customer, e.g. "Please dial *1414# ..."
     *
     * Frontend shows the note. Webhook fires when customer dials and enters PIN.
     */
    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-ussd/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initUssdDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount = parseAmount(req);
        validateMin(amount);

        Object bankCodeRaw = req.get("bankCode");
        if (bankCodeRaw == null || bankCodeRaw.toString().isBlank()) {
            throw ApiException.badRequest(
                    "bankCode is required. " +
                    "Call GET /api/wallet/deposit/flutterwave/v4/ng-ussd/banks for the list.");
        }
        String bankCode = bankCodeRaw.toString().trim();

        // "NGBV4-USSD-" = 12 chars + 30 hex = 42 chars exactly (v4 max is 42)
        String reference = TXREF_PREFIX + "USSD-" + randomHex().substring(0, 30);

        log.info("initUssdDeposit: userId='{}' amount={} bankCode='{}' ref='{}'",
                user.getId(), amount, bankCode, reference);

        Map<String, Object> response = orchestratorCharge(
                buildUssdBody(amount, user, bankCode, reference));

        Map<String, Object> data = unwrapData(response, reference, "ussd");
        String chargeId          = data.get("id").toString();
        String note              = extractPaymentInstruction(data);

        cachePendingCharge(reference, chargeId, user.getId(), amount);

        log.info("initUssdDeposit: chargeId='{}' note='{}' userId='{}'",
                chargeId, note, user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("reference", reference);
        result.put("chargeId",  chargeId);
        result.put("note",      note != null ? note : "Dial your bank's USSD code to complete payment");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Returns the list of banks supported for USSD in Nigeria.
     * Frontend shows this list; the customer picks a bank and its code is sent
     * as "bankCode" in the USSD init request.
     */
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
    //    payment_method.type = "opay"  (no nested object needed)
    //    next_action.type = "redirect_url"
    //    next_action.redirect_url.url = OPay authorization URL
    //    NGN only
    // =========================================================================

    /**
     * Initiates an OPay charge via the v4 Orchestrator.
     *
     * Request:  POST body { "amount": 5000 }
     * Response: { "reference", "chargeId", "redirectUrl" }
     *
     * Frontend redirects customer to redirectUrl (OPay's interface).
     * Webhook fires on completion.
     */
    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-opay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initOpayDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount = parseAmount(req);
        validateMin(amount);

        // "NGBV4-OPAY-" = 12 chars + 30 hex = 42 chars exactly (v4 max)
        String reference   = TXREF_PREFIX + "OPAY-" + randomHex().substring(0, 30);
        String redirectUrl = backendPublicUrl
                + "/api/wallet/deposit/flutterwave/v4/ng-opay/redirect";

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
    // Webhook — one URL for all NGN v4 payment methods
    // =========================================================================

    /**
     * Receives charge.completed events from Flutterwave for all NGN v4 methods.
     *
     * v4 webhooks are Svix-signed (svix-id, svix-timestamp, svix-signature headers).
     * The abstract base's processV4Webhook() handles signature verification using
     *   app.flutterwave.v4.webhook-svix-secret = BetBrosDepositUpdateForGhanaiansAndWeActive
     * which is already in your application.properties.
     *
     * The full header map is forwarded — the base class needs all three svix
     * headers to compute the HMAC. Do NOT bind to a single named header here.
     */
    @PostMapping("/api/webhooks/flutterwave/v4/ng")
    public ResponseEntity<String> webhook(
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] rawBody) {
        return processV4Webhook(
                headers, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG,
                this::resolveUserIdFromStore);
    }

    // =========================================================================
    // Verify — safe polling endpoint for frontend
    // =========================================================================

    /**
     * Polls Flutterwave GET /charges/{chargeId} for the current status and
     * credits the wallet if the charge has succeeded. Idempotent — safe to
     * call on a 3–5 second interval. Works for bank_account, ussd, and opay.
     * PWBT virtual accounts are settled by webhook only.
     */
    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String reference) {

        Map<String, Object> result = verifyAndCredit(
                user.getId(), reference, EXPECTED_CURRENCY, PROVIDER_TAG);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // Redirect callbacks — UX only, no crediting
    // =========================================================================

    /**
     * Browser lands here after the customer authorizes on Mono's page.
     * Forwards to the frontend deposit page so it can start polling /verify.
     * Nothing about crediting depends on the customer landing here —
     * the webhook and reconciler run independently.
     */
    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/redirect")
    public ResponseEntity<Void> bankAccountRedirect(
            @RequestParam(value = "reference", required = false) String reference,
            @RequestParam(value = "status",    required = false) String status) {

        log.info("bankAccountRedirect: ref='{}' status='{}'", reference, status);
        return buildFrontendRedirect("ngbank-v4", reference);
    }

    /**
     * Browser lands here after OPay authorization completes.
     * Same UX pattern: forward to frontend to poll /verify.
     */
    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng-opay/redirect")
    public ResponseEntity<Void> opayRedirect(
            @RequestParam(value = "reference", required = false) String reference,
            @RequestParam(value = "status",    required = false) String status) {

        log.info("opayRedirect: ref='{}' status='{}'", reference, status);
        return buildFrontendRedirect("ngopay-v4", reference);
    }

    // =========================================================================
    // Private — request body builders
    // =========================================================================

    /**
     * Orchestrator body for Pay with Bank Account (Mono).
     *
     * Confirmed v4 shape from docs:
     *   payment_method.type         = "bank_account"
     *   payment_method.bank_account = {}   (intentionally empty — no nested fields)
     *   redirect_url                = top-level field (NOT inside payment_method)
     */
    private static Map<String, Object> buildBankAccountBody(
            BigDecimal amount, User user, String reference, String redirectUrl) {

        Map<String, Object> paymentMethod = new LinkedHashMap<>();
        paymentMethod.put("type",         "bank_account");
        paymentMethod.put("bank_account", new LinkedHashMap<>());  // empty — confirmed

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount",         amount);
        body.put("currency",       EXPECTED_CURRENCY);
        body.put("reference",      reference);
        body.put("redirect_url",   redirectUrl);    // top-level — confirmed
        body.put("payment_method", paymentMethod);
        body.put("customer",       buildCustomer(user));
        return body;
    }

    /**
     * Orchestrator body for USSD.
     *
     * Confirmed v4 shape:
     *   payment_method.type              = "ussd"
     *   payment_method.ussd.account_bank = bankCode  (e.g. "044")
     */
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

    /**
     * Orchestrator body for OPay.
     *
     * Confirmed v4 shape:
     *   payment_method.type = "opay"   (no nested object needed)
     */
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

    /**
     * Inline customer object used by all three Orchestrator bodies.
     * The Orchestrator accepts customer details directly — no separate
     * POST /customers step required for one-time charges.
     */
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

    /**
     * Extracts and validates the "data" object from a Flutterwave v4 response.
     *
     * orchestratorCharge() on the base already throws a RuntimeException for
     * any non-success top-level status. This is a belt-and-braces check for
     * unexpected shapes (e.g. success status but missing data.id).
     *
     * v4 success shape: { "status": "success", "data": { "id": "chg_XXX", ... } }
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapData(
            Map<String, Object> response, String reference, String method) {

        if (response == null) {
            throw ApiException.badRequest("No response from payment provider. Please try again.");
        }
        Object dataObj = response.get("data");
        if (!(dataObj instanceof Map)) {
            log.error("unwrapData({}): missing data object for ref='{}' — full response: {}",
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
     * Extracts the customer-facing redirect URL from a charge response.
     *
     * Confirmed v4 path for bank_account and opay:
     *   data.next_action.type             = "redirect_url"
     *   data.next_action.redirect_url.url = "https://..."
     *
     * IMPORTANT: redirect_url is an OBJECT { "url": "..." }, not a plain string.
     * This was the root extraction bug in the original controller.
     */
    @SuppressWarnings("unchecked")
    private static String extractRedirectUrl(Map<String, Object> data) {
        Object naObj = data.get("next_action");
        if (!(naObj instanceof Map)) return null;

        Map<String, Object> na = (Map<String, Object>) naObj;

        // Confirmed v4 path: next_action.redirect_url.url  (redirect_url is an object)
        Object ruObj = na.get("redirect_url");
        if (ruObj instanceof Map) {
            Object url = ((Map<String, Object>) ruObj).get("url");
            if (url != null) return url.toString();
        }

        // Fallback for older beta responses that used a plain string auth_url
        Object authUrl = na.get("auth_url");
        return authUrl != null ? authUrl.toString() : null;
    }

    /**
     * Extracts the next_action.type string from a charge response.
     * e.g. "redirect_url", "payment_instruction", "requires_pin"
     */
    @SuppressWarnings("unchecked")
    private static String extractNextActionType(Map<String, Object> data) {
        Object naObj = data.get("next_action");
        if (!(naObj instanceof Map)) return null;
        Object type = ((Map<String, Object>) naObj).get("type");
        return type != null ? type.toString() : null;
    }

    /**
     * Extracts the USSD dial instruction note from a charge response.
     *
     * Confirmed v4 path:
     *   data.next_action.type                     = "payment_instruction"
     *   data.next_action.payment_instruction.note = "Please dial *1414# ..."
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
    // Private — webhook userId resolution
    // =========================================================================

    /**
     * Resolves the owning userId from the durable pending-charge row.
     *
     * Return type MUST be AbstractFlutterwaveV4DepositController.PendingV4Charge
     * (the inner record on the abstract base), NOT FlutterwaveV4PendingCharge
     * (the JPA entity). Getting this type wrong caused the "Incompatible types"
     * and "condition always null" errors in the previous revision.
     *
     * Record accessors (not getters): pending.userId(), pending.chargeId(), etc.
     */
    private UUID resolveUserIdFromStore(String reference) {
        AbstractFlutterwaveV4DepositController.PendingV4Charge pending =
                getPendingCharge(reference);
        if (pending == null) {
            log.error("resolveUserIdFromStore: no pending charge row for ref='{}'", reference);
            return null;
        }
        return pending.userId();
    }

    // =========================================================================
    // Private — Flutterwave customer creation (PWBT only)
    // =========================================================================

    /**
     * Creates a Flutterwave v4 customer and returns the customer id (cus_XXX).
     *
     * Required for PWBT virtual account creation which takes a customer_id.
     * The /customers endpoint is idempotent by email in v4, so calling per
     * deposit is safe but wasteful. In production, store the returned id on
     * the User entity (e.g. flutterwaveCustomerId field) to skip this call
     * for returning users.
     */
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
            log.error("ensureCustomer: call failed for userId='{}' — {}",
                    user.getId(), ex.getMessage(), ex);
            throw new RuntimeException("Could not create payment customer record. Please try again.");
        }

        if (result == null) {
            throw new RuntimeException("Null response when creating Flutterwave customer");
        }
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
        try {
            return new BigDecimal(val.toString());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'amount' must be a valid number");
        }
    }

    private void validateMin(BigDecimal amount) {
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest(
                    "Minimum deposit is NGN " + minDeposit.toPlainString());
        }
    }

    private ResponseEntity<Void> buildFrontendRedirect(String method, String reference) {
        URI target = UriComponentsBuilder.fromUriString(frontendUrl + "/deposit")
                .queryParam("method", method)
                .queryParamIfPresent("reference", Optional.ofNullable(reference))
                .build(true)
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

    /**
     * 32 random hex characters.
     * TXREF_PREFIX (6) + randomHex() = 38 chars — within v4's 6–42 char limit.
     */
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
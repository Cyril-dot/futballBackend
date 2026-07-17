package com.speedbet.api.payment.flutterWave;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Nigerian Naira (NGN) deposits via Flutterwave's v4 Orchestrator API,
 * "Pay with Bank" equivalent (redirect/auth_url-based, mirroring the v3
 * "mono" charge but through /orchestration/direct-charges).
 *
 * Sibling to {@link FlutterwaveNgBankDepositController} (the v3 version)
 * and {@link FlutterwaveGhV4DepositController} (the v4 Ghana MoMo
 * controller, whose reference-length and userId-resolution fixes are
 * applied here too — see the two FIX notes below).
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  IMPORTANT — read {@link AbstractFlutterwaveV4DepositController}'s class
 *  javadoc before deploying this. v4 is public beta, the production base
 *  URL needs confirming with Flutterwave, and this controller specifically
 *  still has TWO unconfirmed points (flagged inline below):
 *    - the payment_method.type discriminator/shape for a bank-redirect
 *      charge (no equivalent to Ghana's confirmed GET /mobile-networks
 *      check exists for this yet)
 *    - which field in the charge response carries the customer-facing
 *      redirect/auth URL
 *  Everything else (reference format, userId resolution, customer object
 *  shape) now follows the pattern confirmed working in
 *  {@link FlutterwaveGhV4DepositController}.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — reference length.
 *
 *  The previous revision used:
 *    "SPB-NGB4" + ":" + <36-char userId UUID> + ":" + <36-char random UUID>
 *  — about 85 characters. Flutterwave's v4 orchestrator API rejects any
 *  `reference` outside 6–42 characters (see FlutterwaveGhV4DepositController's
 *  class javadoc for the exact validation error this produces). Every
 *  charge on this endpoint would have failed at orchestratorCharge() with
 *  a 400 before this fix, identically to the GH v4 bug.
 *
 *  Reference is now "NGBV4-" (6 chars) + 32 hex chars = 38 chars total,
 *  safely inside the 6–42 window.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — userId resolution.
 *
 *  Embedding userId in the reference string is no longer necessary (and no
 *  longer fits, per the fix above): {@link AbstractFlutterwaveV4DepositController}
 *  already caches userId against the reference in `pendingCharges` at
 *  charge-initiation time via cachePendingCharge(). The webhook now resolves
 *  userId via getPendingCharge(ref) — see resolveUserIdFromCache() below —
 *  instead of parsing it out of the reference string. userIdFromReference()
 *  and the REF_DELIM constant from the previous revision are removed.
 *
 *  NOTE: this means the reference -> userId mapping now lives ONLY in the
 *  in-memory pendingCharges cache. If the app restarts between charge
 *  initiation and the webhook arriving, that mapping is lost and the
 *  webhook will reject with 400 — a known limitation of the in-memory
 *  cache (see that class's javadoc: "In a multi-instance deployment
 *  replace this with Redis or a DB table").
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Flow:
 *   1. POST .../init
 *        -> calls POST /orchestration/direct-charges with a bank-redirect
 *           payment_method, passing redirect_url pointing back at THIS
 *           controller's /redirect endpoint
 *        -> caches reference -> Flutterwave charge id + userId via
 *           cachePendingCharge() (this is now the ONLY place that mapping
 *           lives — see FIX note above)
 *        -> returns the reference + redirect/auth URL to the frontend
 *   2. Customer completes authorization at that URL. Flutterwave redirects
 *      the browser back to our /redirect endpoint — UX hop only, does NOT
 *      credit the wallet.
 *   3. Frontend polls GET .../verify?ref=... until credited=true, OR
 *   4. Flutterwave sends a webhook -> processV4Webhook() re-verifies via
 *      GET /charges/{id} and credits — whichever of (3)/(4) lands first
 *      wins; handleVerifiedDeposit() is idempotent on ref.
 *
 * ─── application.properties keys needed ──────────────────────────────────
 *   See AbstractFlutterwaveV4DepositController for the shared v4 keys
 *   (client-id, client-secret, base-url, token-url, webhook-hash).
 *   This controller additionally needs:
 *     app.platform.min-deposit-amount-ngn (default: 20000)
 *     app.platform.backend-public-url
 *     app.platform.frontend-url
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveNgBankV4DepositController extends AbstractFlutterwaveV4DepositController {

    static final String EXPECTED_CURRENCY = "NGN";
    static final String PROVIDER_TAG      = "flutterwave_ng_bank_v4";
    static final String TXREF_PREFIX      = "NGBV4-";

    private final WalletService     walletService;
    private final ReferralService   referralService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    @Value("${app.platform.min-deposit-amount-ngn:20000}")
    private BigDecimal minDeposit;

    /**
     * Publicly reachable base URL of THIS backend, used to build the
     * redirect_url Flutterwave calls back after bank authorization.
     * Distinct from frontendUrl, which is where we forward the browser to
     * *after* the customer lands back on our /redirect endpoint.
     */
    @Value("${app.platform.backend-public-url}")
    private String backendPublicUrl;

    @Value("${app.platform.frontend-url}")
    private String frontendUrl;

    @Override protected WalletService     walletService()     { return walletService; }
    @Override protected ReferralService   referralService()   { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper      objectMapper()      { return objectMapper; }

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req,
            HttpServletRequest servletRequest) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0) {
            throw ApiException.badRequest("Minimum deposit is NGN " + minDeposit);
        }

        var phoneNumber = req.get("phoneNumber") != null
                ? req.get("phoneNumber").toString()
                : user.getPhone();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw ApiException.badRequest("phoneNumber is required");
        }

        // FIX: Flutterwave v4 requires `reference` to be 6–42 characters.
        // Short, opaque, random token — userId is resolved via the
        // pendingCharges cache instead of being embedded here (see class
        // javadoc FIX notes).
        var reference = TXREF_PREFIX + UUID.randomUUID().toString().replace("-", "");

        log.info("initDeposit(NG-Bank v4): userId='{}' amount={} reference='{}'",
                user.getId(), amount, reference);

        var redirectUrl = backendPublicUrl + "/api/wallet/deposit/flutterwave/v4/ng-bank/redirect";

        var body = chargeBody(amount, user, phoneNumber, reference, redirectUrl);

        Map<String, Object> response;
        Map<String, Object> data;
        String chargeId;
        try {
            response = orchestratorCharge(body);

            @SuppressWarnings("unchecked")
            var d = (Map<String, Object>) response.get("data");
            data = d;
            if (data == null || data.get("id") == null) {
                throw new RuntimeException("Flutterwave did not return a charge id.");
            }
            chargeId = data.get("id").toString();
        } catch (RuntimeException ex) {
            log.error("initDeposit(NG-Bank v4): Flutterwave charge failed for userId='{}' reference='{}' — {}",
                    user.getId(), reference, ex.getMessage(), ex);
            throw ApiException.badRequest(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Payment initiation failed. Please try again.");
        }

        // Only place the reference -> userId mapping now lives (see FIX note above).
        cachePendingCharge(reference, chargeId, user.getId(), amount);

        var redirectAuthUrl = extractRedirectUrl(data);
        if (redirectAuthUrl == null) {
            log.warn("initDeposit(NG-Bank v4): could not find a redirect/auth URL in charge response, " +
                    "reference='{}', chargeId='{}' — inspect the raw response and fix extractRedirectUrl()",
                    reference, chargeId);
        }

        log.info("initDeposit(NG-Bank v4): Flutterwave chargeId='{}' status='{}' for userId='{}' reference='{}'",
                chargeId, data.get("status"), user.getId(), reference);

        var result = new HashMap<String, Object>();
        result.put("reference", reference);
        result.put("chargeId", chargeId);
        result.put("redirectUrl", redirectAuthUrl);
        result.put("raw", response);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Standalone NG-Bank v4 webhook endpoint. Kept for direct testing
     * (e.g. curl), but Flutterwave itself should be pointed at a router
     * URL instead if you're running this alongside other v4/v3
     * controllers — only one webhook URL can be registered per account.
     *
     * userId is resolved via the pendingCharges cache (populated in
     * initDeposit() above) rather than parsed out of the reference string —
     * see class javadoc FIX note.
     */
    @PostMapping("/api/webhooks/flutterwave/v4/ng-bank")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {
        return processV4Webhook(verifHash, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG,
                this::resolveUserIdFromCache);
    }

    // ─── Verify / status poll (safe to credit — see abstract class javadoc) ───

    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @RequestParam("ref") String reference) {

        var result = verifyAndCredit(user.getId(), reference, EXPECTED_CURRENCY, PROVIDER_TAG);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Redirect callback (browser hop, UX only) ──────────────────────────────

    /**
     * Same cosmetic role as the v3 controller's /redirect endpoint: just
     * decides which waiting/result screen to show while the frontend polls
     * /verify (or waits on the webhook). Query params here are whatever
     * Flutterwave's v4 bank-redirect flow appends — unconfirmed against a
     * real sandbox redirect yet, so we tolerate a missing "reference" param
     * and just send the customer to a generic "confirming" state.
     */
    @GetMapping("/api/wallet/deposit/flutterwave/v4/ng-bank/redirect")
    public ResponseEntity<Void> redirect(
            @RequestParam(value = "reference", required = false) String reference,
            @RequestParam(value = "status", required = false) String status) {

        log.info("redirect(NG-Bank v4): reference='{}' status='{}' — forwarding to frontend to poll /verify",
                reference, status);

        var target = UriComponentsBuilder.fromUriString(frontendUrl + "/deposit")
                .queryParam("method", "ngbank-v4")
                .queryParamIfPresent("reference", Optional.ofNullable(reference))
                .build(true)
                .toUri();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .build();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Resolves the owning userId for a reference via the in-memory
     * pendingCharges cache populated at charge-initiation time. Returns
     * null (causing the webhook to reject with 400) if the mapping isn't
     * found — e.g. a restart happened between init and webhook delivery.
     * See class javadoc FIX note: move pendingCharges to Redis/a DB table
     * to make this durable across restarts.
     */
    private UUID resolveUserIdFromCache(String reference) {
        var pending = getPendingCharge(reference);
        if (pending == null) {
            log.error("resolveUserIdFromCache(NG-Bank v4): no cached charge found for reference='{}' " +
                    "(app restart between init and webhook? consider a durable cache)", reference);
            return null;
        }
        return pending.userId();
    }

    /**
     * UNCONFIRMED against real v4 docs/sandbox — best guess mirroring v3's
     * "mono" charge body shape, adapted to the orchestrator's unified
     * payment_method envelope. The customer object shape (name.first/last,
     * phone.country_code/number) matches what's confirmed working in
     * FlutterwaveGhV4DepositController; the payment_method.type discriminator
     * and its nested bank/redirect fields are still unconfirmed — verify
     * against sandbox and adjust before relying on this in production.
     */
    private static Map<String, Object> chargeBody(
            BigDecimal amount, User user, String phoneNumber, String reference, String redirectUrl) {

        var name = new LinkedHashMap<String, Object>();
        name.put("first", firstName(user));
        name.put("last", lastName(user));

        var phone = new LinkedHashMap<String, Object>();
        phone.put("country_code", "234");
        phone.put("number", normalizeLocalPhone(phoneNumber));

        var customer = new LinkedHashMap<String, Object>();
        customer.put("email", user.getEmail());
        customer.put("name", name);
        customer.put("phone", phone);

        var bank = new LinkedHashMap<String, Object>();
        bank.put("redirect_url", redirectUrl); // TODO: confirm actual nested field name in v4 sandbox

        var paymentMethod = new LinkedHashMap<String, Object>();
        paymentMethod.put("type", "bank"); // TODO: confirm actual discriminator in v4 sandbox
        paymentMethod.put("bank", bank);

        var body = new LinkedHashMap<String, Object>();
        body.put("amount", amount);
        body.put("currency", EXPECTED_CURRENCY);
        body.put("reference", reference);
        body.put("payment_method", paymentMethod);
        body.put("customer", customer);
        body.put("redirect_url", redirectUrl);

        return body;
    }

    /**
     * Attempts a few plausible field names for the customer-facing
     * auth/redirect URL. UNCONFIRMED — replace with the real field once
     * you've inspected a live sandbox charge response.
     */
    @SuppressWarnings("unchecked")
    private static String extractRedirectUrl(Map<String, Object> data) {
        var direct = data.get("redirect_url");
        if (direct != null) return direct.toString();

        var nextAction = data.get("next_action");
        if (nextAction instanceof Map<?, ?> na) {
            var url = ((Map<String, Object>) na).get("redirect_url");
            if (url != null) return url.toString();
            var authUrl = ((Map<String, Object>) na).get("auth_url");
            if (authUrl != null) return authUrl.toString();
        }

        var authUrl = data.get("auth_url");
        return authUrl != null ? authUrl.toString() : null;
    }

    /** Strips a leading "234"/"+234"/"0" prefix, since the country code is sent separately. */
    private static String normalizeLocalPhone(String phone) {
        var trimmed = phone.trim();
        if (trimmed.startsWith("+234")) return trimmed.substring(4);
        if (trimmed.startsWith("234"))  return trimmed.substring(3);
        if (trimmed.startsWith("0"))    return trimmed.substring(1);
        return trimmed;
    }

    private static String firstName(User user) {
        var first = user.getFirstName();
        if (first != null && !first.isBlank()) return first;
        var email = user.getEmail();
        return email != null ? email.split("@")[0] : "Customer";
    }

    private static String lastName(User user) {
        var last = user.getLastName();
        return last != null ? last : "";
    }
}
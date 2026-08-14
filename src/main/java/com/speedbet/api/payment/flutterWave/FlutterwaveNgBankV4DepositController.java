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
 * Sibling to {@link FlutterwaveNgBankDepositController} (v3) and
 * {@link FlutterwaveGhV4DepositController} (v4 Ghana MoMo, whose fixes are
 * applied here too).
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  IMPORTANT — read {@link AbstractFlutterwaveV4DepositController}'s class
 *  javadoc before deploying. Beyond the shared v4 caveats, this controller
 *  has TWO of its own unconfirmed points (flagged inline):
 *    - the payment_method.type discriminator/shape for a bank-redirect
 *      charge (no equivalent to Ghana's confirmed /mobile-networks check)
 *    - which response field carries the customer-facing redirect/auth URL
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — webhook signature header.
 *
 *  v4 does not send v3's `verif-hash` header. Binding to that one name meant
 *  every delivery failed authentication and the webhook credited nothing —
 *  confirmed in production logs on the GH v4 endpoint, and this controller
 *  had the identical bug. The webhook below now forwards the whole header
 *  map to the shared handler. See the abstract class's FIX note for the two
 *  temporary config flags used to identify the real header name.
 *
 *  This matters more here than for MoMo: the bank-redirect flow is the most
 *  webhook-dependent of the lot, because the customer authorizes on their
 *  bank's site and may close the tab rather than return through /redirect.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — durable pending charges + background reconciler.
 *
 *  Pending charges are now {@link FlutterwaveV4PendingCharge} rows rather
 *  than an in-memory map (a restart between init and webhook used to destroy
 *  the only handle we had on the charge), and
 *  {@link FlutterwaveV4DepositReconciler} polls every still-PENDING charge
 *  until it settles. That polling is what guarantees an abandoned-tab
 *  deposit still lands.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — reference length.
 *
 *  The old format ("SPB-NGB4" + ":" + userId UUID + ":" + random UUID, ~85
 *  chars) exceeded Flutterwave v4's 6–42 character limit on `reference`, so
 *  every charge on this endpoint failed at orchestratorCharge() with a 400 —
 *  identically to the GH v4 bug. Now "NGBV4-" + 32 hex = 38 chars, and the
 *  userId is resolved from the persisted pending-charge row instead of being
 *  parsed out of the reference string.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Flow:
 *   1. POST .../init
 *        -> POST /orchestration/direct-charges with a bank-redirect
 *           payment_method, passing redirect_url pointing at this
 *           controller's /redirect endpoint
 *        -> PERSISTS reference -> charge id + userId via cachePendingCharge().
 *           Without this row the charge is unrecoverable.
 *        -> returns the reference + redirect/auth URL to the frontend
 *   2. Customer authorizes at that URL. Flutterwave redirects the browser to
 *      our /redirect endpoint — UX hop only, does NOT credit.
 *   3. Frontend polls GET .../verify?ref=... , OR
 *   4. Flutterwave sends a webhook -> processV4Webhook() re-verifies and
 *      credits, OR
 *   5. The reconciler polls this controller's PENDING rows in the background.
 *   Whichever lands first wins; handleVerifiedDeposit() is idempotent on ref.
 *
 * ─── application.properties keys needed ──────────────────────────────────
 *   See AbstractFlutterwaveV4DepositController for the shared v4 keys and
 *   the reconcile/webhook-diagnostic keys. This controller additionally
 *   needs:
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

    private final WalletService                   walletService;
    private final ReferralService                 referralService;
    private final WebClient.Builder               webClientBuilder;
    private final ObjectMapper                    objectMapper;
    private final FlutterwaveV4PendingChargeStore pendingChargeStore;

    @Value("${app.platform.min-deposit-amount-ngn:20000}")
    private BigDecimal minDeposit;

    /**
     * Publicly reachable base URL of THIS backend, used to build the
     * redirect_url Flutterwave calls back after bank authorization.
     * Distinct from frontendUrl, which is where we forward the browser
     * *after* it lands back on our /redirect endpoint.
     */
    @Value("${app.platform.backend-public-url}")
    private String backendPublicUrl;

    @Value("${app.platform.frontend-url}")
    private String frontendUrl;

    @Override protected WalletService     walletService()     { return walletService; }
    @Override protected ReferralService   referralService()   { return referralService; }
    @Override protected WebClient.Builder webClientBuilder()  { return webClientBuilder; }
    @Override protected ObjectMapper      objectMapper()      { return objectMapper; }

    /** Durable pending-charge store — replaced the in-memory map. */
    @Override protected FlutterwaveV4PendingChargeStore pendingChargeStore() { return pendingChargeStore; }

    /**
     * Exposed to the base class because the reconciler runs without a request
     * context. providerTag() MUST match what's passed to verifyAndCredit()
     * and processV4Webhook() below.
     */
    @Override public String expectedCurrency() { return EXPECTED_CURRENCY; }
    @Override public String providerTag()      { return PROVIDER_TAG; }

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

        // Flutterwave v4 requires `reference` to be 6–42 characters. Short,
        // opaque, random — userId is persisted against it below, not embedded.
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

        // CRITICAL: the only handle we'll ever have on this charge. Carries the
        // userId for the webhook, the chargeId for /verify, and queues it for
        // the reconciler so it credits even if the customer never returns
        // through /redirect and the webhook never arrives.
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
     * Standalone NG-Bank v4 webhook endpoint. Kept for direct testing (curl),
     * but Flutterwave should be pointed at a router URL if you're running
     * this alongside other v4/v3 controllers — one webhook URL per account.
     *
     * Takes the FULL header map rather than a named @RequestHeader: v4 does
     * not send v3's "verif-hash", and binding to that name is what made every
     * delivery fail authentication.
     */
    @PostMapping("/api/webhooks/flutterwave/v4/ng-bank")
    public ResponseEntity<String> webhook(
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] rawBody) {
        return processV4Webhook(headers, rawBody, EXPECTED_CURRENCY, PROVIDER_TAG,
                this::resolveUserIdFromStore);
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
     * Same cosmetic role as the v3 controller's /redirect: decides which
     * waiting/result screen to show while the frontend polls /verify. Query
     * params are whatever Flutterwave's v4 bank-redirect flow appends —
     * unconfirmed against a real sandbox redirect, so a missing "reference"
     * param is tolerated and the customer goes to a generic "confirming" state.
     *
     * Nothing about crediting depends on the customer arriving here: if they
     * close the tab at their bank, the charge is still PENDING in the store
     * and the reconciler settles it.
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
     * Resolves the owning userId for a reference from the persisted
     * pending-charge row. Returns null (webhook rejects with 400) only if no
     * such row exists — the reference was never ours or was pruned after
     * settling, not merely that we restarted.
     */
    private UUID resolveUserIdFromStore(String reference) {
        var pending = getPendingCharge(reference);
        if (pending == null) {
            log.error("resolveUserIdFromStore(NG-Bank v4): no pending charge row for reference='{}'", reference);
            return null;
        }
        return pending.userId();
    }

    /**
     * UNCONFIRMED against real v4 docs/sandbox — best guess mirroring v3's
     * "mono" charge body, adapted to the orchestrator's unified
     * payment_method envelope. The customer object shape matches what's
     * confirmed working in FlutterwaveGhV4DepositController; the
     * payment_method.type discriminator and its nested fields are still
     * guesses — verify against sandbox before relying on this.
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
        bank.put("redirect_url", redirectUrl); // TODO: confirm nested field name in v4 sandbox

        var paymentMethod = new LinkedHashMap<String, Object>();
        paymentMethod.put("type", "bank"); // TODO: confirm discriminator in v4 sandbox
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
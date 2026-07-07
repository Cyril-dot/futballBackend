package com.speedbet.api.payment.flutterWave;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Shared plumbing for Flutterwave v4 (Orchestrator API) deposit controllers.
 *
 * Sibling to {@link AbstractFlutterwaveDepositController}, which covers the
 * older v3 charge/webhook shape. This class exists separately rather than
 * folding v4 support into that class because v3 and v4 differ in nearly
 * every mechanical detail:
 *
 *   - Auth:      v3 uses a static secret key as a Bearer token. v4 uses
 *                OAuth2 client_credentials — a short-lived access token
 *                fetched from Flutterwave's identity server and cached
 *                here until it's close to expiry.
 *   - Charging:  v3 POSTs /charges?type={chargeType} with a bespoke body
 *                per charge type. v4 POSTs a single unified
 *                /orchestration/direct-charges endpoint with a
 *                payment_method.type discriminator.
 *   - Lookup:    v3 supports GET /transactions/verify_by_reference?tx_ref=
 *                so any controller can look up a transaction purely from
 *                the tx_ref it generated. v4 has no equivalent — the only
 *                way to check a charge later is GET /charges/{id}, and
 *                {id} is a Flutterwave-assigned string only available
 *                from the original charge response. That's why this class
 *                keeps a local reference -> charge id cache (see
 *                {@link #cachePendingCharge}) rather than relying on
 *                Flutterwave to look things up by our own reference later.
 *   - Identity:  v3 echoes back custom `meta` (e.g. meta.userId) on the
 *                verified transaction, so the v3 abstract class trusts
 *                that round-trip. Flutterwave's v4 docs don't confirm the
 *                same guarantee for the orchestrator flow, so v4
 *                controllers are expected to encode the userId in their
 *                own reference string instead and extract it themselves
 *                — see the userIdFromReference parameter on
 *                {@link #processV4Webhook}.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  CAVEATS — confirm against Flutterwave's v4 sandbox before going live
 * ══════════════════════════════════════════════════════════════════════════
 *   - Flutterwave's v4 API is in public beta as of early 2026; v3 remains
 *     their documented stable production path for most integrations.
 *   - The production base URL for v4 was not consistently documented
 *     across Flutterwave's own sources at the time this was written —
 *     app.flutterwave.v4.base-url exists specifically so it can be
 *     corrected without a code change once confirmed with Flutterwave.
 *   - The v4 webhook payload shape (field names, the verif-hash header
 *     still applying unchanged) is inferred from Flutterwave's v3
 *     behavior plus partial v4 docs, not a confirmed v4 webhook sample.
 *     Log and inspect the first few real webhook deliveries in sandbox
 *     before trusting this in production.
 */
@Slf4j
public abstract class AbstractFlutterwaveV4DepositController {

    protected final Duration flwTimeout = Duration.ofSeconds(10);
    protected final long     flwRetryAttempts = 2;

    protected abstract WalletService     walletService();
    protected abstract ReferralService   referralService();
    protected abstract WebClient.Builder webClientBuilder();
    protected abstract ObjectMapper      objectMapper();

    @Value("${app.flutterwave.v4.client-id}")
    protected String clientId;

    @Value("${app.flutterwave.v4.client-secret}")
    protected String clientSecret;

    // NOTE: sandbox default — MUST be confirmed/overridden for production.
    // See class javadoc: Flutterwave's documented production base URL for
    // v4 was inconsistent across their own sources as of this writing.
    @Value("${app.flutterwave.v4.base-url:https://developersandbox-api.flutterwave.com}")
    protected String baseUrl;

    @Value("${app.flutterwave.v4.token-url:https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token}")
    protected String tokenUrl;

    /**
     * Same static secret-hash mechanism as v3 (Settings > Webhooks in the
     * Flutterwave dashboard is account-wide, not per API version) — reuses
     * the identical property key as {@link AbstractFlutterwaveDepositController}
     * so both can be configured from a single value if you're on one
     * Flutterwave dashboard. Override with a distinct
     * app.flutterwave.v4.webhook-hash property if Flutterwave ever issues
     * a separate hash for v4 events.
     */
    @Value("${app.flutterwave.webhook-hash}")
    protected String webhookHash;

    // ─── OAuth2 token cache ────────────────────────────────────────────────────

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String  cachedAccessToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    // ─── Pending charge cache (reference -> Flutterwave charge id) ────────────

    /**
     * Keyed by OUR reference (whatever string the controller generates,
     * e.g. "SPB-GH-V4-<userId>-<uuid>"). Populated at charge-initiation
     * time and consulted by /verify polling endpoints and the webhook
     * handler alike, since v4 has no "look up by our reference" API of
     * its own. In a multi-instance deployment replace this with Redis or
     * a DB table.
     */
    private final ConcurrentHashMap<String, PendingV4Charge> pendingCharges = new ConcurrentHashMap<>();

    protected record PendingV4Charge(String chargeId, UUID userId, BigDecimal amount) {}

    protected void cachePendingCharge(String reference, String chargeId, UUID userId, BigDecimal amount) {
        pendingCharges.put(reference, new PendingV4Charge(chargeId, userId, amount));
    }

    protected PendingV4Charge getPendingCharge(String reference) {
        return pendingCharges.get(reference);
    }

    protected void clearPendingCharge(String reference) {
        pendingCharges.remove(reference);
    }

    // ─── Webhook auth (identical mechanism to v3) ──────────────────────────────

    protected boolean verifyWebhookHash(String incomingHash) {
        if (incomingHash == null || incomingHash.isBlank()) {
            log.warn("Flutterwave v4 webhook: missing verif-hash header");
            return false;
        }
        try {
            return MessageDigest.isEqual(
                    incomingHash.getBytes(StandardCharsets.UTF_8),
                    webhookHash.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Flutterwave v4 webhook: error comparing verif-hash", e);
            return false;
        }
    }

    // ─── OAuth2 token management ────────────────────────────────────────────────

    /**
     * Returns a cached access token, refreshing it via the client_credentials
     * grant if missing or within 60s of expiry. Flutterwave's docs reviewed
     * didn't specify a fixed token lifetime, so absent an expires_in field
     * on the token response we fall back to a conservative 9-minute assumed
     * lifetime.
     */
    @SuppressWarnings("unchecked")
    protected String getAccessToken() {
        if (cachedAccessToken != null && Instant.now().isBefore(cachedTokenExpiry.minusSeconds(60))) {
            return cachedAccessToken;
        }

        tokenLock.lock();
        try {
            if (cachedAccessToken != null && Instant.now().isBefore(cachedTokenExpiry.minusSeconds(60))) {
                return cachedAccessToken;
            }

            log.info("getAccessToken(v4): refreshing OAuth2 token");

            Map<String, Object> tokenResponse;
            try {
                tokenResponse = (Map<String, Object>) webClientBuilder().build()
                        .post()
                        .uri(tokenUrl)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .bodyValue("client_id=" + clientId
                                + "&client_secret=" + clientSecret
                                + "&grant_type=client_credentials")
                        .retrieve()
                        .onStatus(status -> status.isError(), clientResponse ->
                                clientResponse.bodyToMono(String.class).map(body -> {
                                    log.error("Flutterwave v4 token request error: status={} body={}",
                                            clientResponse.statusCode(), body);
                                    return new FlutterwaveApiException(
                                            "Flutterwave token request failed: " + body);
                                }))
                        .bodyToMono(Map.class)
                        .timeout(flwTimeout)
                        .retryWhen(Retry.max(flwRetryAttempts)
                                .filter(ex -> !(ex instanceof FlutterwaveApiException)))
                        .block();
            } catch (FlutterwaveApiException e) {
                throw e;
            } catch (Exception e) {
                log.error("getAccessToken(v4): identity server unreachable after {} retries",
                        flwRetryAttempts, e);
                throw new RuntimeException("Flutterwave authentication is currently unavailable.");
            }

            if (tokenResponse == null || tokenResponse.get("access_token") == null) {
                throw new RuntimeException("Flutterwave token response missing access_token.");
            }

            var expiresIn = tokenResponse.get("expires_in");
            long ttlSeconds = expiresIn != null ? Long.parseLong(expiresIn.toString()) : 540;

            cachedAccessToken = tokenResponse.get("access_token").toString();
            cachedTokenExpiry = Instant.now().plusSeconds(ttlSeconds);

            return cachedAccessToken;
        } finally {
            tokenLock.unlock();
        }
    }

    // ─── Shared HTTP helpers ────────────────────────────────────────────────────

    /**
     * POSTs to /orchestration/direct-charges with the given body. Country
     * controllers build the payment_method-specific payload (mobile money
     * needs country_code/network/phone_number, card needs encrypted card
     * fields, etc.) and pass it in here so the auth/retry/timeout plumbing
     * lives in one place.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> orchestratorCharge(Map<String, Object> body) {
        var token = getAccessToken();
        log.info("orchestratorCharge(v4): reference='{}'", body.get("reference"));

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder().build()
                    .post()
                    .uri(baseUrl + "/orchestration/direct-charges")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", UUID.randomUUID().toString())
                    .header("X-Idempotency-Key", UUID.randomUUID().toString())
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).map(respBody -> {
                                log.error("Flutterwave v4 orchestrator charge error: status={} body={}",
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
            log.error("orchestratorCharge(v4): Flutterwave unreachable after {} retries", flwRetryAttempts, e);
            throw new RuntimeException("Flutterwave is currently unavailable. Please try again.");
        }

        if (result == null) {
            throw new RuntimeException("Flutterwave returned an empty response.");
        }

        var status = String.valueOf(result.get("status"));
        if (!"success".equalsIgnoreCase(status)) {
            var message = String.valueOf(result.getOrDefault("message", "Flutterwave declined the request"));
            log.error("orchestratorCharge(v4): status='{}' — {}", status, message);
            throw new RuntimeException("Flutterwave error: " + message);
        }

        return result;
    }

    /** GET /charges/{chargeId} — the only way to re-check a v4 charge's status later. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> getCharge(String chargeId) {
        var token = getAccessToken();

        try {
            var result = (Map<String, Object>) webClientBuilder().build()
                    .get()
                    .uri(baseUrl + "/charges/" + chargeId)
                    .header("Authorization", "Bearer " + token)
                    .header("X-Trace-Id", UUID.randomUUID().toString())
                    .header("X-Idempotency-Key", UUID.randomUUID().toString())
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).map(respBody -> {
                                log.error("Flutterwave v4 get charge error: chargeId='{}' status={} body={}",
                                        chargeId, clientResponse.statusCode(), respBody);
                                return new FlutterwaveApiException(
                                        "Flutterwave returned " + clientResponse.statusCode() + ": " + respBody);
                            }))
                    .bodyToMono(Map.class)
                    .timeout(flwTimeout)
                    .retryWhen(Retry.max(flwRetryAttempts)
                            .filter(ex -> !(ex instanceof FlutterwaveApiException)))
                    .block();

            if (result == null) {
                throw new RuntimeException("Flutterwave returned an empty charge response.");
            }
            return result;
        } catch (FlutterwaveApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("getCharge(v4): Flutterwave unreachable after {} retries, chargeId='{}'",
                    flwRetryAttempts, chargeId, e);
            throw new RuntimeException("Flutterwave is currently unavailable. Please try again.");
        }
    }

    // ─── Shared verify-and-credit (polling fallback) ───────────────────────────

    /**
     * Shared body for every v4 controller's /verify endpoint.
     *
     * Unlike v3's statusResponse() (which is deliberately read-only because
     * v3's redirect-based flows can be replayed/forged by the client), this
     * DOES credit on success. That's safe here for the same reason it's
     * safe in the Moolre USSD controller: the only client input is our own
     * opaque reference string, and every status this method acts on comes
     * from Flutterwave's live API (getCharge), never from anything the
     * client asserts directly. Crediting is idempotent on ref regardless of
     * whether this path or the webhook reaches it first.
     */
    protected Map<String, Object> verifyAndCredit(
            UUID requestingUserId, String reference, String expectedCurrency, String providerTag) {

        var pending = getPendingCharge(reference);
        if (pending == null) {
            throw ApiException.badRequest("Payment session not found. Please start a new deposit.");
        }
        if (!pending.userId().equals(requestingUserId)) {
            throw ApiException.forbidden("This payment reference does not belong to your account.");
        }

        var result = getCharge(pending.chargeId());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.get("data");
        var status = data != null ? String.valueOf(data.get("status")) : "unknown";

        if ("pending".equalsIgnoreCase(status) || "processing".equalsIgnoreCase(status)) {
            return Map.of("credited", false, "status", status,
                    "message", "Payment is still pending. Please approve the prompt on your phone.");
        }

        if (!"succeeded".equalsIgnoreCase(status) && !"successful".equalsIgnoreCase(status)) {
            return Map.of("credited", false, "status", status, "message", "Payment failed or was cancelled.");
        }

        var currency = String.valueOf(data.get("currency"));
        if (!expectedCurrency.equalsIgnoreCase(currency)) {
            log.error("verifyAndCredit(v4) [{}]: currency mismatch expected='{}' got='{}' ref='{}'",
                    providerTag, expectedCurrency, currency, reference);
            throw ApiException.badRequest("Unexpected currency on transaction.");
        }

        var amount = new BigDecimal(String.valueOf(data.get("amount")));
        var credited = handleVerifiedDeposit(pending.userId(), reference, amount, expectedCurrency, providerTag);
        clearPendingCharge(reference);

        return Map.of("credited", credited, "status", "succeeded",
                "message", credited
                        ? "Payment verified. " + expectedCurrency + " " + amount + " has been added to your wallet."
                        : "Payment was already processed.");
    }

    // ─── Shared webhook processing ──────────────────────────────────────────────

    /**
     * Shared body for every v4 controller's webhook endpoint.
     *
     * @param userIdFromReference extracts the userId encoded in this
     *                             controller's own reference format (v4
     *                             doesn't reliably echo back custom meta
     *                             the way v3 does, so we don't depend on it)
     */
    @SuppressWarnings("unchecked")
    protected org.springframework.http.ResponseEntity<String> processV4Webhook(
            String verifHash, byte[] rawBody,
            String expectedCurrency, String providerTag,
            Function<String, UUID> userIdFromReference) {

        if (!verifyWebhookHash(verifHash)) {
            log.warn("Flutterwave v4 webhook [{}]: invalid or missing verif-hash", providerTag);
            return org.springframework.http.ResponseEntity.status(401).body("Invalid signature");
        }

        Map<String, Object> event;
        try {
            event = (Map<String, Object>) objectMapper()
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            log.error("Flutterwave v4 webhook [{}]: failed to parse body", providerTag, e);
            return org.springframework.http.ResponseEntity.status(400).body("Invalid body");
        }

        var data = (Map<String, Object>) event.get("data");
        if (data == null) {
            log.warn("Flutterwave v4 webhook [{}]: missing data field", providerTag);
            return org.springframework.http.ResponseEntity.status(400).body("Missing data");
        }

        var currency = String.valueOf(data.get("currency"));
        if (!expectedCurrency.equalsIgnoreCase(currency)) {
            log.info("Flutterwave v4 webhook [{}]: ignoring currency='{}' (expected {})",
                    providerTag, currency, expectedCurrency);
            return org.springframework.http.ResponseEntity.ok("Ignored — different currency");
        }

        var reference = data.get("reference");
        var chargeId  = data.get("id");
        if (reference == null || reference.toString().isBlank() || chargeId == null || chargeId.toString().isBlank()) {
            log.error("Flutterwave v4 webhook [{}]: missing reference or charge id", providerTag);
            return org.springframework.http.ResponseEntity.status(400).body("Missing reference or charge id");
        }

        var ref = reference.toString();
        UUID userId = userIdFromReference.apply(ref);
        if (userId == null) {
            log.error("Flutterwave v4 webhook [{}]: cannot parse userId from reference='{}'", providerTag, ref);
            return org.springframework.http.ResponseEntity.status(400).body("Invalid reference format");
        }

        // Never trust the webhook payload's status/amount directly —
        // re-verify server-side before crediting.
        Map<String, Object> verified;
        try {
            verified = getCharge(chargeId.toString());
        } catch (RuntimeException ex) {
            log.error("Flutterwave v4 webhook [{}]: re-verification failed for chargeId='{}' — will retry",
                    providerTag, chargeId, ex);
            return org.springframework.http.ResponseEntity.status(500).body("Verification failed, will retry");
        }

        var verifiedData = (Map<String, Object>) verified.get("data");
        var verifiedStatus = verifiedData != null ? String.valueOf(verifiedData.get("status")) : "unknown";

        if (!"succeeded".equalsIgnoreCase(verifiedStatus) && !"successful".equalsIgnoreCase(verifiedStatus)) {
            log.info("Flutterwave v4 webhook [{}]: verified status='{}' for ref='{}' — not crediting yet",
                    providerTag, verifiedStatus, ref);
            return org.springframework.http.ResponseEntity.ok("Ignored — not yet successful");
        }

        var verifiedCurrency = String.valueOf(verifiedData.get("currency"));
        if (!expectedCurrency.equalsIgnoreCase(verifiedCurrency)) {
            log.error("Flutterwave v4 webhook [{}]: verified currency mismatch='{}' for ref='{}'",
                    providerTag, verifiedCurrency, ref);
            return org.springframework.http.ResponseEntity.status(400).body("Currency mismatch on verification");
        }

        try {
            var amount = new BigDecimal(String.valueOf(verifiedData.get("amount")));
            handleVerifiedDeposit(userId, ref, amount, expectedCurrency, providerTag);
            clearPendingCharge(ref);
        } catch (ApiException e) {
            log.error("Flutterwave v4 webhook [{}]: bad request — {}", providerTag, e.getMessage(), e);
            return org.springframework.http.ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Flutterwave v4 webhook [{}]: unexpected error — will retry", providerTag, e);
            return org.springframework.http.ResponseEntity.status(500).body("Processing error");
        }

        return org.springframework.http.ResponseEntity.ok("OK");
    }

    // ─── Shared deposit crediting (identical contract to v3's) ─────────────────

    protected boolean handleVerifiedDeposit(UUID userId, String ref, BigDecimal amount,
                                             String currency, String provider) {
        log.info("handleVerifiedDeposit(v4): userId='{}' amount={} currency='{}' ref='{}' provider='{}'",
                userId, amount, currency, ref, provider);

        try {
            walletService().credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", provider, "reference", ref, "currency", currency));
            log.info("handleVerifiedDeposit(v4): {} {} credited to userId='{}' ref='{}'",
                    currency, amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleVerifiedDeposit(v4): duplicate ref='{}' already processed — skipping", ref);
                return false;
            }
            throw ex;
        }

        try {
            referralService().attributeCommission(userId, amount);
            log.info("handleVerifiedDeposit(v4): commission attributed for userId='{}' deposit={} {}",
                    userId, currency, amount);
        } catch (Exception ex) {
            log.error("handleVerifiedDeposit(v4): commission attribution failed for userId='{}' — investigate",
                    userId, ex);
        }

        return true;
    }
}
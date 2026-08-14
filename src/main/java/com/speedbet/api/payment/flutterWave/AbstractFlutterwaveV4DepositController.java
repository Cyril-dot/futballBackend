package com.speedbet.api.payment.flutterWave;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 *                persists a reference -> charge id record (see
 *                {@link #cachePendingCharge}) rather than relying on
 *                Flutterwave to look things up by our own reference later.
 *   - Identity:  v3 echoes back custom `meta` (e.g. meta.userId) on the
 *                verified transaction, so the v3 abstract class trusts
 *                that round-trip. v4 doesn't confirm the same guarantee,
 *                so v4 controllers resolve the userId from the persisted
 *                pending-charge row instead — see the userIdFromReference
 *                parameter on {@link #processV4Webhook}.
 *   - Webhooks:  v3 authenticates with a static secret in a `verif-hash`
 *                header. v4 does NOT send that header — see the FIX note
 *                below. This was the single biggest source of uncredited
 *                deposits in production.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — the webhook signature header.
 *
 *  Production logs showed EVERY inbound v4 webhook being rejected:
 *
 *    WARN Flutterwave v4 webhook: missing verif-hash header
 *    WARN Flutterwave v4 webhook [flutterwave_gh_v4]: invalid or missing verif-hash
 *
 *  — not once did a delivery get past the check, so the webhook credited
 *  exactly zero deposits. The previous revision assumed v4 reused v3's
 *  `verif-hash` header; the class javadoc flagged that as inferred rather
 *  than confirmed, and it was wrong.
 *
 *  Rather than guess a replacement, {@link #verifyWebhookHash} now takes the
 *  whole header map, matches case-insensitively against a list of candidate
 *  names, and offers two escape hatches while you identify the real one:
 *
 *    app.flutterwave.v4.webhook-log-headers=true
 *        logs the header names on every delivery so you can spot the
 *        signature header against a REAL payload. Turn off once known —
 *        headers can carry secrets.
 *
 *    app.flutterwave.v4.webhook-allow-unsigned=true
 *        processes a delivery we can't authenticate. Bounded risk: the
 *        payload is still never trusted — {@link #processV4Webhook}
 *        re-verifies against GET /charges/{id} and credits only what
 *        Flutterwave itself confirms succeeded. An attacker would have to
 *        guess a live chargeId belonging to a genuinely-paid charge. Still
 *        a real widening of attack surface: leave it on only until the
 *        header is identified, then set it back to false.
 *
 *  Once you see the real header in the logs, add it to
 *  {@link #SIGNATURE_HEADER_CANDIDATES} and disable both flags.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — /verify no longer leaks Flutterwave 500s.
 *
 *  When Flutterwave's GET /charges/{id} returned a 10500 INTERNAL_SERVER_ERROR,
 *  getCharge() rethrew and nothing in verifyAndCredit() caught it, so a
 *  frontend polling every ~5s got a stack trace every time. A failed lookup
 *  says nothing about whether the customer paid, so it's now reported as
 *  "still confirming" and the row stays PENDING for the reconciler to keep
 *  working in the background.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (earlier revision) — pending charges are DURABLE, and there is a
 *  background RECONCILER so deposits always land.
 *
 *  `pendingCharges` used to be a ConcurrentHashMap. Two consequences, both
 *  of which cost real money:
 *
 *    1. A restart/deploy between charge init and webhook delivery lost the
 *       reference -> (chargeId, userId) mapping. The webhook then 400'd and
 *       the customer, already debited by their telco, was never credited.
 *       /verify was equally dead — it needs the Flutterwave chargeId, which
 *       existed nowhere else.
 *    2. Nothing polled. A webhook that never arrived meant a silently lost
 *       deposit until someone raised a ticket.
 *
 *  Now: {@link FlutterwaveV4PendingCharge} rows are persisted via
 *  {@link FlutterwaveV4PendingChargeStore}, and
 *  {@link FlutterwaveV4DepositReconciler} calls
 *  {@link #reconcilePendingCharges()} on a fixed delay to poll every
 *  still-PENDING charge until it reaches a terminal state.
 *
 *  All credit paths funnel into {@link #handleVerifiedDeposit}, which is
 *  idempotent on the reference (WalletService throws 409 on a duplicate ref,
 *  caught here and treated as already-processed), so whichever of webhook /
 *  verify / reconciler arrives first wins and the rest no-op.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  CAVEATS — still unconfirmed against Flutterwave
 * ══════════════════════════════════════════════════════════════════════════
 *   - v4 is public beta as of early 2026; v3 remains their documented stable
 *     production path for most integrations.
 *   - The production base URL was inconsistent across Flutterwave's own
 *     sources — app.flutterwave.v4.base-url exists so it can be corrected
 *     without a code change. Confirm which host they actually want you on;
 *     an unexpected host is one candidate explanation for sporadic 10500s.
 *   - The webhook PAYLOAD shape (field names under `data`) is still inferred
 *     from v3 plus partial v4 docs. The header fix above gets deliveries
 *     past authentication; if they then fail on "Missing data" or
 *     "Missing reference or charge id", the body shape is the next thing to
 *     correct against a real sample.
 *   - The status vocabulary in {@link #SUCCESS_STATUSES} and
 *     {@link #TERMINAL_FAILURE_STATUSES} is best-effort. An unrecognised
 *     status is treated as still-pending, so the worst case is a charge
 *     polling until TTL rather than a wrong credit.
 */
@Slf4j
public abstract class AbstractFlutterwaveV4DepositController {

    protected final Duration flwTimeout = Duration.ofSeconds(10);
    protected final long     flwRetryAttempts = 2;

    /** Statuses that mean "money is in". Compared case-insensitively. */
    protected static final Set<String> SUCCESS_STATUSES =
            Set.of("succeeded", "successful", "success", "completed");

    /**
     * Statuses that mean "this will never succeed" — stop polling.
     * Anything NOT in either set is treated as still in flight, so an
     * unknown status costs us extra polls, never a bad credit.
     */
    protected static final Set<String> TERMINAL_FAILURE_STATUSES =
            Set.of("failed", "cancelled", "canceled", "declined", "expired",
                   "reversed", "voided", "abandoned", "rejected", "error");

    /**
     * Candidate header names that might carry the webhook signature, tried
     * in order, case-insensitively. "verif-hash" is v3's name and is kept
     * first in case Flutterwave ever starts sending it on v4 too; the rest
     * are plausible guesses. See the class javadoc FIX note — production
     * logs proved v4 sends none of these, so identify the real one via
     * app.flutterwave.v4.webhook-log-headers and add it HERE.
     */
    protected static final List<String> SIGNATURE_HEADER_CANDIDATES = List.of(
            "verif-hash",
            "flutterwave-signature",
            "x-flutterwave-signature",
            "webhook-signature",
            "x-webhook-signature",
            "signature",
            "x-signature");

    protected abstract WalletService     walletService();
    protected abstract ReferralService   referralService();
    protected abstract WebClient.Builder webClientBuilder();
    protected abstract ObjectMapper      objectMapper();

    /** Durable replacement for the old in-memory pendingCharges map. */
    protected abstract FlutterwaveV4PendingChargeStore pendingChargeStore();

    /** e.g. "GHS" — used by the reconciler, which has no request context. */
    public abstract String expectedCurrency();

    /** e.g. "flutterwave_gh_v4" — routes reconciler work to this controller. */
    public abstract String providerTag();

    @Value("${app.flutterwave.v4.client-id}")
    protected String clientId;

    @Value("${app.flutterwave.v4.client-secret}")
    protected String clientSecret;

    // NOTE: sandbox default — MUST be confirmed/overridden for production.
    @Value("${app.flutterwave.v4.base-url:https://developersandbox-api.flutterwave.com}")
    protected String baseUrl;

    @Value("${app.flutterwave.v4.token-url:https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token}")
    protected String tokenUrl;

    /** Max pending charges polled per provider, per reconciler pass. */
    @Value("${app.flutterwave.v4.reconcile.batch-size:50}")
    protected int reconcileBatchSize;

    /**
     * How long a charge may stay PENDING before it's marked EXPIRED and
     * polling stops. Generous by default — a customer can leave a MoMo
     * prompt sitting on their lock screen, or a bank-redirect tab open,
     * for a long time.
     */
    @Value("${app.flutterwave.v4.reconcile.ttl-minutes:45}")
    protected long pendingTtlMinutes;

    /**
     * Same static secret-hash mechanism as v3 (Settings > Webhooks in the
     * Flutterwave dashboard is account-wide, not per API version) — reuses
     * the identical property key as {@link AbstractFlutterwaveDepositController}
     * so both can be configured from a single value if you're on one
     * dashboard. Override with a distinct app.flutterwave.v4.webhook-hash
     * property if Flutterwave ever issues a separate hash for v4 events.
     */
    @Value("${app.flutterwave.webhook-hash}")
    protected String webhookHash;

    /**
     * TEMPORARY diagnostic. Logs the header NAMES on every inbound webhook
     * so you can identify which one carries the signature against a real
     * delivery. Turn off once known — header maps can contain secrets and
     * this writes them to your log aggregator.
     */
    @Value("${app.flutterwave.v4.webhook-log-headers:false}")
    protected boolean logWebhookHeaders;

    /**
     * When true, a webhook whose signature we cannot verify is processed
     * anyway. See the class javadoc FIX note for the risk analysis — short
     * version: the payload is still never trusted, every credit is gated on
     * Flutterwave's own GET /charges/{id}. Leave on only until the real
     * signature header is identified.
     */
    @Value("${app.flutterwave.v4.webhook-allow-unsigned:false}")
    protected boolean allowUnsignedWebhooks;

    // ─── OAuth2 token cache ────────────────────────────────────────────────────

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String  cachedAccessToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    // ─── Pending charge persistence (reference -> Flutterwave charge id) ──────

    /**
     * Lightweight read view of a persisted pending charge, so callers don't
     * handle a detached JPA entity. Field names ({@code chargeId()},
     * {@code userId()}, {@code amount()}) are unchanged from the old
     * in-memory record, so existing call sites compile as-is.
     */
    protected record PendingV4Charge(String reference, String chargeId, UUID userId,
                                     BigDecimal amount, String currency, String providerTag,
                                     FlutterwaveV4ChargeStatus status) {

        public boolean isPending()  { return status == FlutterwaveV4ChargeStatus.PENDING; }
        public boolean isCredited() { return status == FlutterwaveV4ChargeStatus.CREDITED; }
    }

    /**
     * Persists the reference -> (chargeId, userId) mapping at charge-init
     * time. This row is the ONLY thing that makes the charge recoverable:
     * v4 offers no lookup-by-our-reference, so without the stored chargeId
     * neither the webhook, /verify, nor the reconciler can ever check the
     * charge again. Call this BEFORE returning to the client.
     */
    protected void cachePendingCharge(String reference, String chargeId, UUID userId, BigDecimal amount) {
        pendingChargeStore().create(reference, chargeId, userId, amount,
                expectedCurrency(), providerTag());
    }

    protected PendingV4Charge getPendingCharge(String reference) {
        return pendingChargeStore().find(reference).orElse(null);
    }

    /**
     * @deprecated rows are no longer deleted — they're marked terminal so the
     * reconciler stops polling them and the history stays auditable. Retained
     * so older call sites keep working.
     */
    @Deprecated
    protected void clearPendingCharge(String reference) {
        pendingChargeStore().markCredited(reference, "legacy");
    }

    // ─── Webhook auth ──────────────────────────────────────────────────────────

    /**
     * Authenticates an inbound webhook against the configured static hash.
     *
     * Takes the WHOLE header map rather than a single named header because
     * v4's signature header name is not what v3's was and was not confirmed
     * anywhere in Flutterwave's docs — see the class javadoc FIX note.
     * Matching is case-insensitive: HTTP header names are case-insensitive
     * per RFC 9110, and the map Spring hands us may or may not be.
     */
    protected boolean verifyWebhookHash(Map<String, String> headers) {
        var lower = new HashMap<String, String>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null) lower.put(k.toLowerCase(), v);
            });
        }

        if (logWebhookHeaders) {
            log.warn("Flutterwave v4 webhook: inbound header names = {} — identify the signature " +
                     "header, add it to SIGNATURE_HEADER_CANDIDATES, then disable " +
                     "app.flutterwave.v4.webhook-log-headers", lower.keySet());
        }

        for (var candidate : SIGNATURE_HEADER_CANDIDATES) {
            var value = lower.get(candidate);
            if (value == null || value.isBlank()) continue;

            if (MessageDigest.isEqual(
                    value.getBytes(StandardCharsets.UTF_8),
                    webhookHash.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }

            // Header present but value wrong — worth distinguishing from
            // "header absent", because it usually means the configured hash
            // is stale or is the v3 one rather than the v4 one.
            log.warn("Flutterwave v4 webhook: header '{}' present but did not match the configured " +
                     "hash — check app.flutterwave.webhook-hash against the dashboard", candidate);
        }

        if (allowUnsignedWebhooks) {
            log.warn("Flutterwave v4 webhook: no recognised signature header (saw {}) — processing " +
                     "anyway because webhook-allow-unsigned=true. The charge is still re-verified " +
                     "with Flutterwave before any credit.", lower.keySet());
            return true;
        }

        log.warn("Flutterwave v4 webhook: no recognised signature header (saw {}) — rejecting. " +
                 "If this is every delivery, the signature header name is wrong: enable " +
                 "app.flutterwave.v4.webhook-log-headers to find it.", lower.keySet());
        return false;
    }

    // ─── OAuth2 token management ────────────────────────────────────────────────

    /**
     * Returns a cached access token, refreshing it via the client_credentials
     * grant if missing or within 60s of expiry. Flutterwave's docs reviewed
     * didn't specify a fixed token lifetime, so absent an expires_in field
     * we fall back to a conservative 9-minute assumed lifetime.
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
     * controllers build the payment_method-specific payload and pass it in
     * here so the auth/retry/timeout plumbing lives in one place.
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

    /**
     * GET /charges/{chargeId} — the only way to re-check a v4 charge later.
     *
     * NOTE: Flutterwave has been observed returning 10500 INTERNAL_SERVER_ERROR
     * from this endpoint for certain charges. In production logs those were
     * consistently very small amounts (GHS 1) while larger charges on the same
     * account resolved fine, which points at a server-side floor rather than
     * anything wrong with the request. Callers must treat a throw from here as
     * "unknown", never as "failed" — a lookup error says nothing about whether
     * the customer actually paid.
     */
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

    /** Pulls the "data" object out of a v4 response, or an empty map. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> dataOf(Map<String, Object> response) {
        if (response == null) return Map.of();
        var data = response.get("data");
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
    }

    // ─── Shared verify-and-credit (frontend polling path) ──────────────────────

    /**
     * Shared body for every v4 controller's /verify endpoint.
     *
     * Unlike v3's statusResponse() (deliberately read-only, because v3's
     * redirect flows can be replayed by the client), this DOES credit on
     * success. Safe for the same reason the Moolre USSD controller's is: the
     * only client input is our own opaque reference, and every status acted
     * on comes from Flutterwave's live API, never from the client.
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

        // Already settled by the webhook or reconciler — don't re-hit
        // Flutterwave, just report the outcome.
        if (pending.isCredited()) {
            return Map.of("credited", false, "status", "succeeded",
                    "message", "Payment was already processed.");
        }
        if (pending.status() == FlutterwaveV4ChargeStatus.FAILED) {
            return Map.of("credited", false, "status", "failed",
                    "message", "Payment failed or was cancelled.");
        }

        // FIX: a Flutterwave-side error here used to propagate out to the
        // client as a 500 with a stack trace, on every poll. A failed lookup
        // is not a failed payment — report "still confirming" and leave the
        // row PENDING for the reconciler.
        Map<String, Object> result;
        try {
            result = getCharge(pending.chargeId());
        } catch (RuntimeException ex) {
            log.warn("verifyAndCredit(v4) [{}]: lookup failed for ref='{}' — reporting as pending. {}",
                    providerTag, reference, ex.getMessage());
            return Map.of("credited", false, "status", "pending",
                    "message", "We're still confirming your payment. This can take a moment.");
        }

        var data   = dataOf(result);
        var status = String.valueOf(data.getOrDefault("status", "unknown"));

        if (!isSuccess(status)) {
            if (isTerminalFailure(status)) {
                pendingChargeStore().markFailed(reference, status);
                return Map.of("credited", false, "status", status,
                        "message", "Payment failed or was cancelled.");
            }
            // Still in flight. Leave PENDING so the reconciler keeps watching
            // even if the customer closes the app right now.
            return Map.of("credited", false, "status", status,
                    "message", "Payment is still pending. Please approve the prompt on your phone.");
        }

        var currency = String.valueOf(data.get("currency"));
        if (!expectedCurrency.equalsIgnoreCase(currency)) {
            log.error("verifyAndCredit(v4) [{}]: currency mismatch expected='{}' got='{}' ref='{}'",
                    providerTag, expectedCurrency, currency, reference);
            throw ApiException.badRequest("Unexpected currency on transaction.");
        }

        var amount = new BigDecimal(String.valueOf(data.get("amount")));
        var credited = handleVerifiedDeposit(pending.userId(), reference, amount, expectedCurrency, providerTag);
        pendingChargeStore().markCredited(reference, "verify");

        return Map.of("credited", credited, "status", "succeeded",
                "message", credited
                        ? "Payment verified. " + expectedCurrency + " " + amount + " has been added to your wallet."
                        : "Payment was already processed.");
    }

    // ─── Background reconciliation (the safety net) ────────────────────────────

    /**
     * Polls every PENDING charge for this provider whose backoff window has
     * elapsed, and settles the ones Flutterwave now reports as terminal.
     * Invoked by {@link FlutterwaveV4DepositReconciler} on a fixed delay.
     *
     * This is what guarantees the customer gets credited when the webhook
     * never shows up — which, per the signature-header FIX note above, was
     * 100% of deliveries at one point. Deliberately conservative:
     *   - only Flutterwave's own live GET /charges/{id} is trusted;
     *   - an unrecognised status is treated as still-pending, never credited;
     *   - a transport error just reschedules — the row stays PENDING;
     *   - crediting reuses the idempotent handleVerifiedDeposit path, so a
     *     race with an in-flight webhook cannot double-credit.
     *
     * Each row is handled in isolation: one poisoned charge can't stall the
     * queue behind it.
     *
     * @return number of charges that reached a terminal state this pass
     */
    public int reconcilePendingCharges() {
        var now    = Instant.now();
        var cutoff = now.minusSeconds(pendingTtlMinutes * 60);
        var due    = pendingChargeStore().findDue(
                providerTag(), now, PageRequest.of(0, Math.max(1, reconcileBatchSize)));

        if (due.isEmpty()) return 0;

        log.debug("reconcilePendingCharges[{}]: {} charge(s) due", providerTag(), due.size());

        var settled = 0;
        for (var reference : due) {
            try {
                if (reconcileOne(reference, cutoff)) settled++;
            } catch (Exception ex) {
                log.error("reconcilePendingCharges[{}]: unexpected error on ref='{}'",
                        providerTag(), reference, ex);
                pendingChargeStore().reschedule(reference, "error", safeMessage(ex));
            }
        }
        return settled;
    }

    /** @return true if this charge reached a terminal state. */
    private boolean reconcileOne(String reference, Instant expiryCutoff) {
        var pending = getPendingCharge(reference);
        if (pending == null || !pending.isPending()) {
            return false; // settled by webhook/verify in the meantime
        }

        // Give up eventually rather than polling a dead charge forever.
        if (pendingChargeStore().isOlderThan(reference, expiryCutoff)) {
            log.warn("reconcileOne[{}]: ref='{}' still pending after {}min — marking EXPIRED. " +
                            "If the customer reports being debited, check chargeId='{}' in the " +
                            "Flutterwave dashboard manually.",
                    providerTag(), reference, pendingTtlMinutes, pending.chargeId());
            pendingChargeStore().markExpired(reference);
            return true;
        }

        Map<String, Object> result;
        try {
            result = getCharge(pending.chargeId());
        } catch (RuntimeException ex) {
            // Transport/auth/500 failure — says nothing about the charge.
            // Keep it PENDING and try again after the backoff.
            log.warn("reconcileOne[{}]: lookup failed for ref='{}' — will retry. {}",
                    providerTag(), reference, ex.getMessage());
            pendingChargeStore().reschedule(reference, "lookup_failed", safeMessage(ex));
            return false;
        }

        var data   = dataOf(result);
        var status = String.valueOf(data.getOrDefault("status", "unknown"));

        if (isTerminalFailure(status)) {
            log.info("reconcileOne[{}]: ref='{}' terminal failure status='{}'",
                    providerTag(), reference, status);
            pendingChargeStore().markFailed(reference, status);
            return true;
        }

        if (!isSuccess(status)) {
            // Still in flight, or a status we don't recognise — same treatment,
            // because guessing here would mean crediting on an unknown state.
            pendingChargeStore().reschedule(reference, status, null);
            return false;
        }

        var currency = String.valueOf(data.get("currency"));
        if (!expectedCurrency().equalsIgnoreCase(currency)) {
            log.error("reconcileOne[{}]: currency mismatch on ref='{}' expected='{}' got='{}' — NOT crediting",
                    providerTag(), reference, expectedCurrency(), currency);
            pendingChargeStore().markFailed(reference, "currency_mismatch:" + currency);
            return true;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(data.get("amount")));
        } catch (NumberFormatException ex) {
            log.error("reconcileOne[{}]: unparseable amount '{}' on ref='{}' — NOT crediting",
                    providerTag(), data.get("amount"), reference);
            pendingChargeStore().reschedule(reference, status, "unparseable amount");
            return false;
        }

        // Credit what Flutterwave says was collected, not what we asked for —
        // but shout if they differ, because that's a partial payment or a bug.
        if (pending.amount() != null && pending.amount().compareTo(amount) != 0) {
            log.warn("reconcileOne[{}]: amount differs for ref='{}' requested={} settled={} — " +
                            "crediting the settled amount",
                    providerTag(), reference, pending.amount(), amount);
        }

        handleVerifiedDeposit(pending.userId(), reference, amount, expectedCurrency(), providerTag());
        pendingChargeStore().markCredited(reference, "reconciler");

        log.info("reconcileOne[{}]: RECOVERED deposit via polling — ref='{}' userId='{}' amount={} {} " +
                        "(webhook never credited this one)",
                providerTag(), reference, pending.userId(), amount, expectedCurrency());
        return true;
    }

    protected static boolean isSuccess(String status) {
        return status != null && SUCCESS_STATUSES.contains(status.trim().toLowerCase());
    }

    protected static boolean isTerminalFailure(String status) {
        return status != null && TERMINAL_FAILURE_STATUSES.contains(status.trim().toLowerCase());
    }

    private static String safeMessage(Exception ex) {
        var msg = ex.getMessage();
        if (msg == null) return ex.getClass().getSimpleName();
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }

    // ─── Shared webhook processing ──────────────────────────────────────────────

    /**
     * Shared body for every v4 controller's webhook endpoint.
     *
     * Takes the full header map (not a single named header) — see the class
     * javadoc FIX note on the signature header.
     *
     * @param userIdFromReference resolves the owning userId for a reference.
     *                             v4 doesn't reliably echo back custom meta
     *                             the way v3 does, so controllers look this
     *                             up from their persisted pending-charge row
     *                             rather than trusting the payload.
     */
    @SuppressWarnings("unchecked")
    protected org.springframework.http.ResponseEntity<String> processV4Webhook(
            Map<String, String> headers, byte[] rawBody,
            String expectedCurrency, String providerTag,
            Function<String, UUID> userIdFromReference) {

        if (!verifyWebhookHash(headers)) {
            log.warn("Flutterwave v4 webhook [{}]: rejected — signature not verified", providerTag);
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
            // Now that deliveries get past auth, this is the next place a
            // wrong payload-shape assumption will surface. Log the top-level
            // keys so the real shape is one delivery away from being known.
            log.warn("Flutterwave v4 webhook [{}]: missing 'data' field — top-level keys were {}",
                    providerTag, event.keySet());
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
        if (reference == null || reference.toString().isBlank()
                || chargeId == null || chargeId.toString().isBlank()) {
            log.error("Flutterwave v4 webhook [{}]: missing reference or charge id — data keys were {}",
                    providerTag, data.keySet());
            return org.springframework.http.ResponseEntity.status(400).body("Missing reference or charge id");
        }

        var ref = reference.toString();
        UUID userId = userIdFromReference.apply(ref);
        if (userId == null) {
            // The pending-charge row is durable, so this means the reference
            // was never ours (or was pruned long ago). 400 rather than 500 so
            // Flutterwave stops retrying something we can never satisfy.
            log.error("Flutterwave v4 webhook [{}]: no pending charge for reference='{}'", providerTag, ref);
            return org.springframework.http.ResponseEntity.status(400).body("Unknown reference");
        }

        // Never trust the payload's status/amount — re-verify server-side.
        Map<String, Object> verified;
        try {
            verified = getCharge(chargeId.toString());
        } catch (RuntimeException ex) {
            // 500 asks Flutterwave to retry, and the reconciler will pick this
            // up regardless — the row is still PENDING.
            log.error("Flutterwave v4 webhook [{}]: re-verification failed for chargeId='{}' — will retry",
                    providerTag, chargeId, ex);
            return org.springframework.http.ResponseEntity.status(500).body("Verification failed, will retry");
        }

        var verifiedData   = dataOf(verified);
        var verifiedStatus = String.valueOf(verifiedData.getOrDefault("status", "unknown"));

        if (!isSuccess(verifiedStatus)) {
            if (isTerminalFailure(verifiedStatus)) {
                pendingChargeStore().markFailed(ref, verifiedStatus);
            }
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
            pendingChargeStore().markCredited(ref, "webhook");
        } catch (ApiException e) {
            log.error("Flutterwave v4 webhook [{}]: bad request — {}", providerTag, e.getMessage(), e);
            return org.springframework.http.ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            // Row stays PENDING — Flutterwave retries AND the reconciler will
            // catch it. Two independent recovery paths.
            log.error("Flutterwave v4 webhook [{}]: unexpected error — will retry", providerTag, e);
            return org.springframework.http.ResponseEntity.status(500).body("Processing error");
        }

        return org.springframework.http.ResponseEntity.ok("OK");
    }

    // ─── Shared deposit crediting (identical contract to v3's) ─────────────────

    /**
     * Idempotent on {@code ref}. This is the single choke point every credit
     * path (webhook, /verify, reconciler) goes through, which is precisely
     * what makes it safe to have three of them racing.
     *
     * REQUIRES_NEW so a rollback here can't take out the caller's transaction
     * (and vice versa) — the pending-charge bookkeeping and the wallet credit
     * are deliberately independent.
     *
     * NOTE: public, not protected, so Spring's proxy-based AOP can actually
     * apply @Transactional. As a protected method it was being self-called
     * within the class and the annotation was silently doing nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean handleVerifiedDeposit(UUID userId, String ref, BigDecimal amount,
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
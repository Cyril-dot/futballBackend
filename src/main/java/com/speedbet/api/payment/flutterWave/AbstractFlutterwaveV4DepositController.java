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
 *                that round-trip. Flutterwave's v4 docs don't confirm the
 *                same guarantee for the orchestrator flow, so v4
 *                controllers resolve the userId from the persisted
 *                pending-charge record instead — see the
 *                userIdFromReference parameter on
 *                {@link #processV4Webhook}.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  CHANGE (this revision) — pending charges are now DURABLE, and there is a
 *  background RECONCILER so deposits always land.
 *
 *  Previously `pendingCharges` was a ConcurrentHashMap. Two consequences,
 *  both of which cost real money:
 *
 *    1. A restart/deploy between charge init and webhook delivery lost the
 *       reference -> (chargeId, userId) mapping. The webhook then 400'd
 *       ("Invalid reference format") and the customer was debited by their
 *       telco but never credited. /verify was equally dead — it needs the
 *       Flutterwave chargeId, which only ever existed in that map.
 *    2. Nothing polled. If the webhook was simply never delivered (dropped
 *       retry, our endpoint 500ing, single-webhook-URL misrouting) and the
 *       customer closed the app before the frontend polled /verify, the
 *       deposit was silently lost until someone raised a ticket.
 *
 *  Now: {@link FlutterwaveV4PendingCharge} rows are persisted via
 *  {@link FlutterwaveV4PendingChargeStore}, and
 *  {@link FlutterwaveV4DepositReconciler} calls
 *  {@link #reconcilePendingCharges()} on a fixed delay to poll every still-
 *  PENDING charge against Flutterwave's live GET /charges/{id} until it
 *  reaches a terminal state. This ALSO makes the store multi-instance safe,
 *  which the old map never was.
 *
 *  The webhook and /verify paths are behaviourally unchanged and remain the
 *  fast paths. All three funnel into {@link #handleVerifiedDeposit}, which is
 *  idempotent on the reference (WalletService throws 409 on a duplicate ref,
 *  caught here and treated as already-processed), so whichever arrives first
 *  wins and the rest no-op.
 *
 *  Subclasses must now additionally implement {@link #pendingChargeStore()},
 *  {@link #expectedCurrency()} and {@link #providerTag()}.
 * ══════════════════════════════════════════════════════════════════════════
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
 *     before trusting this in production. NOTE: the reconciler below is
 *     what makes this caveat survivable — even if the webhook shape is
 *     wrong and every delivery is rejected, polling still credits.
 *   - The terminal-status vocabulary in {@link #TERMINAL_FAILURE_STATUSES}
 *     and {@link #SUCCESS_STATUSES} is best-effort. An unrecognised status
 *     is treated as still-pending, so the worst case is a charge polling
 *     until the TTL expires rather than a wrong credit.
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
    // See class javadoc: Flutterwave's documented production base URL for
    // v4 was inconsistent across their own sources as of this writing.
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
     * prompt sitting on their lock screen for a long time.
     */
    @Value("${app.flutterwave.v4.reconcile.ttl-minutes:45}")
    protected long pendingTtlMinutes;

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

        public boolean isPending() { return status == FlutterwaveV4ChargeStatus.PENDING; }
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
     * so existing call sites keep working; prefer letting the credit paths
     * call {@code markCredited} themselves.
     */
    @Deprecated
    protected void clearPendingCharge(String reference) {
        pendingChargeStore().markCredited(reference, "legacy");
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

    /** Pulls the "data" object out of a v4 response, or an empty map. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> dataOf(Map<String, Object> response) {
        if (response == null) return Map.of();
        var data = response.get("data");
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
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
     * whether this path, the webhook, or the reconciler reaches it first.
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

        // Already settled by the webhook or the reconciler — don't re-hit
        // Flutterwave, just report the outcome.
        if (pending.isCredited()) {
            return Map.of("credited", false, "status", "succeeded",
                    "message", "Payment was already processed.");
        }
        if (pending.status() == FlutterwaveV4ChargeStatus.FAILED) {
            return Map.of("credited", false, "status", "failed",
                    "message", "Payment failed or was cancelled.");
        }

        var result = getCharge(pending.chargeId());
        var data   = dataOf(result);
        var status = String.valueOf(data.getOrDefault("status", "unknown"));

        if (!isSuccess(status)) {
            if (isTerminalFailure(status)) {
                pendingChargeStore().markFailed(reference, status);
                return Map.of("credited", false, "status", status,
                        "message", "Payment failed or was cancelled.");
            }
            // Still in flight. Leave the row PENDING so the reconciler keeps
            // watching it even if the customer closes the app right now.
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
     * never shows up. It is deliberately conservative:
     *   - only Flutterwave's own live GET /charges/{id} is trusted;
     *   - an unrecognised status is treated as still-pending, never credited;
     *   - a transport error just reschedules — the row stays PENDING;
     *   - crediting reuses the same idempotent handleVerifiedDeposit path,
     *     so a race with an in-flight webhook cannot double-credit.
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
            return false; // settled by webhook/verify in the meantime — nothing to do
        }

        // Give up eventually rather than polling a dead charge forever.
        if (pendingChargeStore().isOlderThan(reference, expiryCutoff)) {
            log.warn("reconcileOne[{}]: ref='{}' still pending after {}min — marking EXPIRED. " +
                            "If the customer reports being debited, check this charge in the " +
                            "Flutterwave dashboard manually (chargeId='{}').",
                    providerTag(), reference, pendingTtlMinutes, pending.chargeId());
            pendingChargeStore().markExpired(reference);
            return true;
        }

        Map<String, Object> result;
        try {
            result = getCharge(pending.chargeId());
        } catch (RuntimeException ex) {
            // Transport/auth failure — this says nothing about the charge.
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
            // Still in flight (or a status we don't recognise — same treatment,
            // because guessing here would mean crediting on an unknown state).
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

        // Credit what Flutterwave says was actually collected, not what we
        // asked for — but shout if they differ, because that's either a
        // partial payment or a bug worth knowing about.
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
     * Still the primary, fast credit path — the reconciler exists only to
     * catch what this misses.
     *
     * @param userIdFromReference resolves the owning userId for a reference.
     *                             v4 doesn't reliably echo back custom meta
     *                             the way v3 does, so controllers look this
     *                             up from their persisted pending-charge row
     *                             rather than trusting the payload.
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
            // Now genuinely unexpected: the pending-charge row is durable, so
            // this means the reference was never ours (or was pruned long ago).
            // 400 rather than 500 so Flutterwave stops retrying a request we
            // can never satisfy.
            log.error("Flutterwave v4 webhook [{}]: no pending charge for reference='{}'", providerTag, ref);
            return org.springframework.http.ResponseEntity.status(400).body("Unknown reference");
        }

        // Never trust the webhook payload's status/amount directly —
        // re-verify server-side before crediting.
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
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
package com.speedbet.api.payment.flutterWave;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Shared plumbing for Flutterwave deposit controllers.
 *
 * Design mirrors {@code PaystackController}:
 *   init endpoint -> call provider -> return payment instructions to frontend
 *   webhook       -> verify auth -> verify transaction server-side -> credit wallet
 *                     -> attribute referral commission (never blocks the deposit)
 *
 * Subclasses so far:
 *   - FlutterwaveGhDepositController     (GHS, Mobile Money)
 *   - FlutterwaveNgDepositController     (NGN, Bank USSD)
 *   - FlutterwaveNgBankDepositController (NGN, "Pay with Bank" / mono charge,
 *     redirect/auth_url-based rather than USSD-code-based)
 *
 * Key differences from Paystack, called out explicitly because they're easy
 * to get wrong when porting logic across providers:
 *
 *   1. Amounts are in MAJOR units (e.g. "1500" == GHS 1500.00), NOT minor
 *      units like Paystack's pesewas/kobo. Do not multiply by 100.
 *
 *   2. Webhook auth is a static shared-secret string comparison against the
 *      "verif-hash" header (the value you set under Settings > Webhooks in
 *      the Flutterwave dashboard) — it is NOT an HMAC of the body like
 *      Paystack's x-paystack-signature. This is weaker than HMAC (it can't
 *      detect tampering of the body itself), so we additionally re-verify
 *      the transaction directly against Flutterwave's API before crediting
 *      anything. Never trust amount/status straight from the webhook body.
 *
 *   3. Flutterwave supports only ONE webhook URL per dashboard account.
 *      A single router controller (FlutterwaveWebhookRouterController)
 *      sits in front of all country/method controllers, inspects
 *      data.currency in the incoming payload, and delegates to the right
 *      one via the shared processWebhook() method below. Register ONLY the
 *      router's URL — /api/webhooks/flutterwave — in the Flutterwave
 *      dashboard. The per-controller /api/webhooks/flutterwave/{gh,ng,
 *      ng-bank} endpoints still exist and work standalone (useful for
 *      manual testing / curl), but Flutterwave itself should only ever
 *      call the router URL. Routing is by currency only — both NGN
 *      controllers' webhook events land on FlutterwaveNgDepositController
 *      regardless of whether the charge was USSD or Pay-with-Bank, since
 *      processWebhook() only needs currency + tx id to verify and credit.
 *
 *   4. For redirect-based charge types (GH Mobile Money's OTP/captcha
 *      redirect, NG Pay-with-Bank's auth_url redirect), the browser
 *      round-trip back to our server is a UX convenience ONLY — it decides
 *      which waiting/result screen the frontend shows next. It must never
 *      be trusted to credit a wallet, since a redirect can be replayed,
 *      skipped, or forged by the client. Crediting always happens through
 *      processWebhook() -> verifyTransaction(), never through a redirect
 *      handler. See statusResponse()/verifyTransactionByReference() below
 *      for the read-only "what's the status right now" polling endpoint
 *      the frontend uses while it waits for that webhook.
 */
@Slf4j
public abstract class AbstractFlutterwaveDepositController {

    /** How long to wait for Flutterwave to respond before timing out. */
    protected final Duration flwTimeout = Duration.ofSeconds(10);

    /**
     * How many times to retry on transient network failures (e.g. connection
     * reset, TCP timeout). Does NOT retry on Flutterwave 4xx/5xx responses —
     * those are mapped to FlutterwaveApiException by the onStatus handler and
     * are explicitly excluded from the retry predicate.
     */
    protected final long flwRetryAttempts = 2;

    protected abstract WalletService walletService();
    protected abstract ReferralService referralService();
    protected abstract WebClient.Builder webClientBuilder();
    protected abstract ObjectMapper objectMapper();

    @Value("${app.flutterwave.secret-key}")
    protected String secretKey;

    @Value("${app.flutterwave.base-url}")
    protected String baseUrl;

    /** The static secret string configured under Flutterwave dashboard > Settings > Webhooks. */
    @Value("${app.flutterwave.webhook-hash}")
    protected String webhookHash;

    @Value("${app.platform.frontend-url}")
    protected String frontendUrl;

    // ─── Webhook auth ───────────────────────────────────────────────────────

    /**
     * Constant-time comparison of the incoming "verif-hash" header against
     * our configured secret. Constant-time to avoid leaking the secret via
     * response-timing side channels.
     */
    protected boolean verifyWebhookHash(String incomingHash) {
        if (incomingHash == null || incomingHash.isBlank()) {
            log.warn("Flutterwave webhook: missing verif-hash header");
            return false;
        }
        try {
            var a = incomingHash.getBytes(StandardCharsets.UTF_8);
            var b = webhookHash.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(a, b);
        } catch (Exception e) {
            log.error("Flutterwave webhook: error comparing verif-hash", e);
            return false;
        }
    }

    // ─── Transaction verify (defense against a spoofed/forged webhook body) ───

    /**
     * Calls GET /transactions/{id}/verify and returns the "data" object.
     * This is the source of truth — the webhook body only tells us "something
     * happened to transaction {id}"; we don't trust its amount/status/currency
     * until Flutterwave's API confirms them directly.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> verifyTransaction(long flwTransactionId) {
        log.info("verifyTransaction: verifying flwTransactionId={}", flwTransactionId);

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder().build()
                    .get()
                    .uri(baseUrl + "/transactions/" + flwTransactionId + "/verify")
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).map(body -> {
                                log.error("Flutterwave verify error: status={} body={}",
                                        clientResponse.statusCode(), body);
                                return new FlutterwaveApiException(
                                        "Flutterwave verify returned " + clientResponse.statusCode() + ": " + body);
                            }))
                    .bodyToMono(Map.class)
                    .timeout(flwTimeout)
                    .retryWhen(Retry.max(flwRetryAttempts)
                            .filter(ex -> !(ex instanceof FlutterwaveApiException)))
                    .block();
        } catch (FlutterwaveApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("verifyTransaction: Flutterwave unreachable after {} retries, flwTransactionId={}",
                    flwRetryAttempts, flwTransactionId, e);
            throw new RuntimeException("Flutterwave is currently unavailable. Please try again.");
        }

        if (result == null || !"success".equals(String.valueOf(result.get("status")))) {
            log.error("verifyTransaction: unexpected verify response for flwTransactionId={}: {}",
                    flwTransactionId, result);
            throw new RuntimeException("Unable to verify Flutterwave transaction.");
        }

        var data = (Map<String, Object>) result.get("data");
        if (data == null) {
            log.error("verifyTransaction: verify response missing 'data' for flwTransactionId={}", flwTransactionId);
            throw new RuntimeException("Malformed Flutterwave verify response.");
        }

        log.info("verifyTransaction: flwTransactionId={} verifiedStatus='{}' verifiedAmount={} verifiedCurrency='{}'",
                flwTransactionId, data.get("status"), data.get("amount"), data.get("currency"));

        return data;
    }

    // ─── Status-by-reference lookup (read-only polling, no crediting) ──────────

    /**
     * Calls GET /transactions/verify_by_reference?tx_ref={ref} and returns the
     * "data" object, or null if Flutterwave has no transaction for that ref
     * (e.g. the customer never completed the charge). Unlike
     * {@link #verifyTransaction(long)} this looks up by OUR tx_ref rather than
     * Flutterwave's numeric id, which is what the frontend has on hand right
     * after init — it doesn't get Flutterwave's transaction id until the
     * webhook or redirect arrives.
     *
     * This is read-only: it is safe to call from a GET /status endpoint that
     * a client polls repeatedly, and it never credits a wallet. Only
     * processWebhook() does that. If a client polls status and sees
     * "successful" before our webhook has landed and credited yet, that's
     * expected — the frontend should keep showing "confirming" until the
     * webhook completes; we don't credit twice because handleVerifiedDeposit()
     * is idempotent on ref regardless of which path reaches it first.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> verifyTransactionByReference(String txRef) {
        log.info("verifyTransactionByReference: looking up txRef='{}'", txRef);

        Map<String, Object> result;
        try {
            var encodedRef = URLEncoder.encode(txRef, StandardCharsets.UTF_8);
            result = (Map<String, Object>) webClientBuilder().build()
                    .get()
                    .uri(baseUrl + "/transactions/verify_by_reference?tx_ref=" + encodedRef)
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).map(body -> {
                                log.warn("Flutterwave verify_by_reference error: txRef='{}' status={} body={}",
                                        txRef, clientResponse.statusCode(), body);
                                return new FlutterwaveApiException(
                                        "Flutterwave verify_by_reference returned " + clientResponse.statusCode() + ": " + body);
                            }))
                    .bodyToMono(Map.class)
                    .timeout(flwTimeout)
                    .retryWhen(Retry.max(flwRetryAttempts)
                            .filter(ex -> !(ex instanceof FlutterwaveApiException)))
                    .block();
        } catch (FlutterwaveApiException e) {
            // Most commonly a 404 because the customer hasn't completed the
            // charge yet (e.g. still sitting on the auth_url page) — that's a
            // normal "still pending" state for a status poll, not a hard
            // failure, so we return null rather than throwing.
            log.info("verifyTransactionByReference: txRef='{}' not found or errored yet — treating as pending", txRef);
            return null;
        } catch (Exception e) {
            log.error("verifyTransactionByReference: Flutterwave unreachable after {} retries, txRef='{}'",
                    flwRetryAttempts, txRef, e);
            return null;
        }

        if (result == null || !"success".equals(String.valueOf(result.get("status")))) {
            return null;
        }

        return (Map<String, Object>) result.get("data");
    }

    /**
     * Builds the small JSON body the frontend's status-poll endpoints expect:
     * {@code {"status": "successful" | "failed" | "pending"}}. Country
     * controllers call this from their own {@code GET .../status?ref=...}
     * endpoint — see FlutterwaveNgBankDepositController for the intended
     * shape once wired up. Never credits anything; purely informational.
     */
    protected ResponseEntity<Map<String, Object>> statusResponse(String txRef) {
        var data = verifyTransactionByReference(txRef);
        if (data == null) {
            return ResponseEntity.ok(Map.of("status", "pending"));
        }

        var flwStatus = String.valueOf(data.get("status"));
        var normalized = "successful".equals(flwStatus) ? "successful"
                : ("failed".equals(flwStatus) || "cancelled".equals(flwStatus)) ? "failed"
                : "pending";

        return ResponseEntity.ok(Map.of("status", normalized, "data", data));
    }

    // ─── Shared charge() HTTP helper ────────────────────────────────────────

    /**
     * POSTs to /charges?type={chargeType} with the given body and returns the
     * full response map. Country controllers build the body (USSD needs
     * account_bank, Ghana Mobile Money needs network/phone_number, Pay with
     * Bank needs redirect_url, etc.) and pass it in here so the HTTP/retry/
     * timeout plumbing lives in one place.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> charge(String chargeType, Map<String, Object> body) {
        log.info("charge: chargeType='{}' tx_ref='{}'", chargeType, body.get("tx_ref"));

        Map<String, Object> result;
        try {
            result = (Map<String, Object>) webClientBuilder().build()
                    .post()
                    .uri(baseUrl + "/charges?type=" + chargeType)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).map(respBody -> {
                                log.error("Flutterwave charge error: chargeType='{}' status={} body={}",
                                        chargeType, clientResponse.statusCode(), respBody);
                                return new FlutterwaveApiException(
                                        "Flutterwave returned " + clientResponse.statusCode() + ": " + respBody);
                            }))
                    .bodyToMono(Map.class)
                    // Fail fast: don't hold a thread longer than flwTimeout.
                    .timeout(flwTimeout)
                    // Retry on transient network failures only. FlutterwaveApiException
                    // (thrown by onStatus for real 4xx/5xx responses) is excluded so we
                    // never blindly retry a deliberate rejection (e.g. insufficient funds,
                    // invalid network code).
                    .retryWhen(Retry.max(flwRetryAttempts)
                            .filter(ex -> !(ex instanceof FlutterwaveApiException)))
                    .block();
        } catch (FlutterwaveApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("charge: Flutterwave unreachable after {} retries, chargeType='{}'",
                    flwRetryAttempts, chargeType, e);
            throw new RuntimeException("Flutterwave is currently unavailable. Please try again.");
        }

        if (result == null) {
            throw new RuntimeException("Flutterwave returned an empty response.");
        }

        log.info("charge: chargeType='{}' Flutterwave status='{}' message='{}'",
                chargeType, result.get("status"), result.get("message"));

        if ("error".equals(String.valueOf(result.get("status")))) {
            var message = String.valueOf(result.getOrDefault("message", "Flutterwave declined the request"));
            log.error("charge: chargeType='{}' Flutterwave status=error — {}", chargeType, message);
            throw new RuntimeException("Flutterwave error: " + message);
        }

        return result;
    }

    // ─── Shared webhook processing ──────────────────────────────────────────

    /**
     * Shared body for every controller's webhook endpoint, and for the
     * FlutterwaveWebhookRouterController that Flutterwave itself calls.
     *
     * Steps: verify the verif-hash header -> parse the event -> ignore
     * anything that isn't charge.completed -> re-verify the transaction
     * directly against Flutterwave's API (never trust the webhook body) ->
     * confirm currency matches what this controller expects -> credit the
     * wallet.
     *
     * @param verifHash        the "verif-hash" request header
     * @param rawBody          the raw request body bytes
     * @param expectedCurrency e.g. "GHS" or "NGN" — a mismatch is treated as
     *                         an error, since it usually means the webhook
     *                         router sent this payload to the wrong handler
     * @param providerTag      e.g. "flutterwave_gh", "flutterwave_ng", or
     *                         "flutterwave_ng_bank" — stored on the wallet
     *                         transaction for auditing
     */
    @SuppressWarnings("unchecked")
    protected ResponseEntity<String> processWebhook(String verifHash, byte[] rawBody,
                                                      String expectedCurrency, String providerTag) {
        if (!verifyWebhookHash(verifHash)) {
            log.warn("Flutterwave webhook [{}]: invalid or missing verif-hash", providerTag);
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            var event = (Map<String, Object>) objectMapper()
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = String.valueOf(event.get("event"));
            log.info("Flutterwave webhook [{}]: received event='{}'", providerTag, eventType);

            if (!"charge.completed".equals(eventType)) {
                log.info("Flutterwave webhook [{}]: ignoring event='{}'", providerTag, eventType);
                return ResponseEntity.ok("Ignored");
            }

            var webhookData = (Map<String, Object>) event.get("data");
            if (webhookData == null || webhookData.get("id") == null) {
                log.error("Flutterwave webhook [{}]: missing data.id in payload", providerTag);
                return ResponseEntity.status(400).body("Missing transaction id");
            }

            var flwTransactionId = Long.parseLong(webhookData.get("id").toString());

            // Do NOT trust webhookData directly — re-verify against Flutterwave's API.
            var verified = verifyTransaction(flwTransactionId);

            var status   = String.valueOf(verified.get("status"));
            var currency = String.valueOf(verified.get("currency"));

            if (!"successful".equals(status)) {
                log.info("Flutterwave webhook [{}]: flwTransactionId={} verified status='{}' — not crediting",
                        providerTag, flwTransactionId, status);
                return ResponseEntity.ok("Not successful, ignored");
            }

            if (!expectedCurrency.equals(currency)) {
                log.error("Flutterwave webhook [{}]: flwTransactionId={} unexpected currency='{}' (expected {})",
                        providerTag, flwTransactionId, currency, expectedCurrency);
                return ResponseEntity.status(400).body("Unexpected currency");
            }

            var meta = (Map<String, Object>) verified.get("meta");
            if (meta == null || meta.get("userId") == null) {
                log.error("Flutterwave webhook [{}]: verified data missing meta.userId, flwTransactionId={}",
                        providerTag, flwTransactionId);
                return ResponseEntity.status(400).body("Missing userId in meta");
            }

            var userId = UUID.fromString(meta.get("userId").toString());
            var ref    = String.valueOf(verified.get("tx_ref"));
            var amount = new BigDecimal(verified.get("amount").toString());

            handleVerifiedDeposit(userId, ref, amount, expectedCurrency, providerTag);

        } catch (ApiException e) {
            log.error("Flutterwave webhook [{}]: bad request — {}", providerTag, e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Flutterwave webhook [{}]: unexpected error — will retry", providerTag, e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Shared deposit crediting ───────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes referral
     * commission to their referrer (if any). Idempotent on ref: a duplicate
     * webhook delivery for an already-processed ref is logged and skipped
     * rather than double-crediting.
     */
    protected void handleVerifiedDeposit(UUID userId, String ref, BigDecimal amount,
                                          String currency, String provider) {
        log.info("handleVerifiedDeposit: userId='{}' amount={} currency='{}' ref='{}' provider='{}'",
                userId, amount, currency, ref, provider);

        try {
            walletService().credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", provider, "reference", ref, "currency", currency));
            log.info("handleVerifiedDeposit: {} {} credited to userId='{}' ref='{}'",
                    currency, amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleVerifiedDeposit: duplicate ref='{}' already processed — skipping", ref);
                return;
            }
            throw ex;
        }

        // Attribute commission to referring admin. Never block a deposit because
        // of a commission failure — same guarantee as the Paystack integration.
        try {
            referralService().attributeCommission(userId, amount);
            log.info("handleVerifiedDeposit: commission attributed for userId='{}' deposit={} {}",
                    userId, currency, amount);
        } catch (Exception ex) {
            log.error("handleVerifiedDeposit: commission attribution failed for userId='{}' — investigate",
                    userId, ex);
        }
    }
}
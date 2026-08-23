package com.speedbet.api.payment.akwapay;

import com.speedbet.api.chat.AdminUpgradeChatService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AkwaPay payment integration.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * GATEWAY CHANGE (2026-08-23) — READ THIS IF YOU ARE DEBUGGING A REGRESSION
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * AkwaPay switched their default gateway from Moolre to Flutterwave v4. This
 * changes NOTHING about the API contract this controller talks to — the same
 * /v1/payment_intents endpoint, same auth, same webhook shape. But two
 * concrete behavioural differences matter here:
 *
 *   1. THE OTP FLOW ({@link #submitOtp}) MAY NO LONGER FIRE FOR MOMO.
 *      Flutterwave v4 mobile money uses next_action.type = "payment_instruction"
 *      (a push prompt), NOT "submit_otp". submitOtp is kept in place in case
 *      routing ever falls back to a gateway that still uses it.
 *
 *   2. next_action.type MAY VARY MORE THAN BEFORE.
 *      The frontend already branches on next_action.type. Don't add logic that
 *      inspects hint or ussd_fallback string content; treat those as opaque.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REFERENCE FORMAT — HYPHENS NOT UNDERSCORES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Flutterwave uses our reference as tx_ref. Flutterwave's tx_ref validation
 * rejects underscores — only alphanumeric characters and hyphens are accepted.
 * References use hyphens as separators:
 *
 *     sbdep-<32-hex userId>-<8-hex nonce>     wallet deposit
 *     sbadm-<32-hex userId>-<8-hex nonce>     admin upgrade
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY customer.email IS SYNTHETIC (PER-ATTEMPT)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Flutterwave v4 deduplicates customers by email. Reusing the real user email
 * causes "Customer already exists" on any second attempt while a prior intent
 * is still unresolved. We use plus-addressing to make the email unique per
 * attempt: kojo@gmail.com → kojo+1724449830123@gmail.com. The real email is
 * preserved in metadata for audit.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RELIABILITY GUARANTEE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Webhooks have never fired in production. Every credit has come from the
 * reconciliation sweep ({@link #reconcilePendingIntents}). Treat the sweep as
 * the primary mechanism. Both paths dedupe via WalletService.credit() (409 on
 * duplicate reference) so no double-credit is possible whichever wins the race.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WEBHOOK PAYLOAD
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   {
 *     "id":         "evt_<chargePublicId>",
 *     "type":       "payment_intent.succeeded",
 *     "sequence":   <int>,
 *     "created_at": "<ISO-8601>",
 *     "data": {
 *       "intent_id": "<pi_...>",
 *       "amount":    <integer pesewas>,
 *       "currency":  "GHS",
 *       "reference": "<your reference string>",
 *       "status":    "succeeded"
 *     }
 *   }
 *
 * Delivery is at-least-once. Deduped inside WalletService.credit() via the
 * reference (409 → skip).
 */
@Slf4j
@EnableScheduling
@RestController
@RequiredArgsConstructor
public class AkwaPayController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // Hyphens only — Flutterwave rejects underscores in tx_ref.
    private static final String REF_PREFIX_DEPOSIT = "sbdep-";
    private static final String REF_PREFIX_ADMIN   = "sbadm-";

    private final Duration akwapayTimeout      = Duration.ofSeconds(15);
    private final long     akwapayRetryAttempts = 2;

    private static final long     SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final Duration SWEEP_HEAD_START            = Duration.ofSeconds(5);
    private static final long     SWEEP_INTERVAL_MS           = 5_000;

    private static final Duration TIER_HOT_UNTIL  = Duration.ofMinutes(2);
    private static final Duration TIER_WARM_UNTIL = Duration.ofMinutes(10);
    private static final Duration TIER_COOL_UNTIL = Duration.ofMinutes(60);

    private static final Duration POLL_EVERY_HOT  = Duration.ofSeconds(5);
    private static final Duration POLL_EVERY_WARM = Duration.ofSeconds(30);
    private static final Duration POLL_EVERY_COOL = Duration.ofMinutes(2);
    private static final Duration POLL_EVERY_COLD = Duration.ofMinutes(10);

    private static final Duration ABANDON_AFTER = Duration.ofHours(24);

    private static final Map<String, String> GH_NETWORK_PREFIXES = new LinkedHashMap<>();
    static {
        for (var p : new String[]{"024", "025", "053", "054", "055", "059"}) GH_NETWORK_PREFIXES.put(p, "MTN");
        for (var p : new String[]{"020", "050"})                             GH_NETWORK_PREFIXES.put(p, "TELECEL");
        for (var p : new String[]{"026", "027", "056", "057"})               GH_NETWORK_PREFIXES.put(p, "AIRTELTIGO");
    }

    private final WalletService                  walletService;
    private final UserService                    userService;
    private final AdminUpgradeChatService        adminUpgradeChatService;
    private final ReferralService                referralService;
    private final AkwaPayPendingIntentRepository pendingIntents;
    private final WebClient.Builder              webClientBuilder;
    private final ObjectMapper                   objectMapper;

    @Value("${app.akwapay.secret-key}")              private String     secretKey;
    @Value("${app.akwapay.webhook-secret}")          private String     webhookSecret;
    @Value("${app.akwapay.base-url}")                private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")           private String     frontendUrl;

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/akwapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        var reference = buildReference(REF_PREFIX_DEPOSIT, user.getId());

        var phone        = req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req.get("network") == null ? null : req.get("network").toString();
        var network      = resolveNetwork(requestedNet, phone);

        log.info("initDeposit: userId='{}' amount={} pesewas={} ref='{}' requestedNetwork='{}' resolvedNetwork='{}'",
                user.getId(), amount, amountPesewas, reference, requestedNet, network);

        var response = akwapayCreateIntent(
                amountPesewas,
                reference,
                user.getEmail(),
                phone,
                network,
                frontendUrl + "/wallet?payment=success",
                Map.of("userId", user.getId().toString(), "purpose", "deposit")
        );

        var intentId = String.valueOf(response.get("id"));
        recordPending(reference, intentId, user.getId(), amount, false);

        log.info("initDeposit: intent='{}' status='{}' next_action='{}' for userId='{}'",
                intentId, response.get("status"), nextActionType(response), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    @PostMapping("/api/user/upgrade-to-admin/akwapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var reference = buildReference(REF_PREFIX_ADMIN, user.getId());

        var phone        = req == null || req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req == null || req.get("network") == null ? null : req.get("network").toString();
        var network      = resolveNetwork(requestedNet, phone);

        log.info("initAdminUpgrade: userId='{}' email='{}' ref='{}' requestedNetwork='{}' resolvedNetwork='{}'",
                user.getId(), user.getEmail(), reference, requestedNet, network);

        var upgradeAmountGhs = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_PESEWAS)
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

        var response = akwapayCreateIntent(
                ADMIN_UPGRADE_FEE_PESEWAS,
                reference,
                user.getEmail(),
                phone,
                network,
                frontendUrl + "/app/upgrade?payment=success",
                Map.of(
                        "userId",        user.getId().toString(),
                        "upgradeIntent", UPGRADE_INTENT_ADMIN
                )
        );

        var intentId = String.valueOf(response.get("id"));
        recordPending(reference, intentId, user.getId(), upgradeAmountGhs, true);

        log.info("initAdminUpgrade: intent='{}' status='{}' for userId='{}'",
                intentId, response.get("status"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    private void recordPending(String reference, String intentId, UUID userId,
                               BigDecimal amountGhs, boolean adminUpgrade) {
        try {
            pendingIntents.save(new AkwaPayPendingIntent(
                    reference, intentId, userId, amountGhs, adminUpgrade, Instant.now(), 0, null));
            log.info("recordPending: ref='{}' intent='{}' persisted — sweep will reconcile if the webhook is lost",
                    reference, intentId);
        } catch (Exception e) {
            log.error("recordPending: FAILED to persist ref='{}' intent='{}' userId='{}' amount={} — " +
                            "this payment can now only be credited by webhook or by hand. Investigate.",
                    reference, intentId, userId, amountGhs, e);
        }
    }

    // ─── Status probe (read only) ─────────────────────────────────────────────

    @GetMapping("/api/wallet/deposit/akwapay/status/{intentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user,
            @PathVariable String intentId) {

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intentId)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("AkwaPay status probe error: status={} body={}", r.statusCode(), body);
                            return new RuntimeException("AkwaPay returned " + r.statusCode());
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");

        log.info("status: userId='{}' intent='{}' status='{}'",
                user.getId(), intentId, result.get("status"));

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── OTP submission ───────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/akwapay/otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var intentId     = req.get("intentId")     == null ? "" : req.get("intentId").toString().trim();
        var clientSecret = req.get("clientSecret") == null ? "" : req.get("clientSecret").toString().trim();
        var otp          = req.get("otp")          == null ? "" : req.get("otp").toString().trim();

        if (intentId.isBlank() || clientSecret.isBlank() || otp.isBlank())
            throw ApiException.badRequest("intentId, clientSecret and otp are all required");

        log.info("submitOtp: userId='{}' intent='{}'", user.getId(), intentId);

        var akwapayRoot = baseUrl.replaceAll("/v1.*$", "");

        URI validateUri = UriComponentsBuilder
                .fromUriString(akwapayRoot + "/v1/checkout/" + intentId + "/validate")
                .queryParam("cs", clientSecret)
                .build()
                .toUri();

        log.info("submitOtp: forwarding to AkwaPay uri='{}'", validateUri);

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .post()
                .uri(validateUri)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("otp", otp))
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("submitOtp: AkwaPay error status={} body={} intent='{}'",
                                    r.statusCode(), body, intentId);
                            return new RuntimeException(
                                    "AkwaPay returned " + r.statusCode() + ": " + body);
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response for OTP validation.");

        log.info("submitOtp: intent='{}' akwapay_result='{}' — push prompt now in flight if OTP was accepted",
                intentId, result);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/akwapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature",   required = false) String signature,
            @RequestHeader(value = "X-AkwaPay-Event-Type",  required = false) String headerEventType,
            @RequestHeader(value = "X-AkwaPay-Delivery-Id", required = false) String deliveryId,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("AkwaPay webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("AkwaPay webhook: missing X-AkwaPay-Signature header, delivery='{}'", deliveryId);
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("AkwaPay webhook: invalid signature, delivery='{}' eventType='{}' — " +
                            "check AKWAPAY_WEBHOOK_SECRET matches the whsec_ for this endpoint",
                    deliveryId, headerEventType);
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventId   = String.valueOf(event.get("id"));
            var eventType = String.valueOf(event.get("type"));
            var sequence  = event.get("sequence");

            log.info("AkwaPay webhook: event='{}' type='{}' sequence={} delivery='{}'",
                    eventId, eventType, sequence, deliveryId);

            if (!"payment_intent.succeeded".equals(eventType)) {
                log.info("AkwaPay webhook: ignoring event type='{}' (event='{}')", eventType, eventId);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("AkwaPay webhook: no data block on event='{}'", eventId);
                return ResponseEntity.status(400).body("Missing data");
            }

            var reference = data.get("reference") == null ? null : data.get("reference").toString();
            var intentId  = data.get("intent_id") == null ? "" : data.get("intent_id").toString();

            if (reference == null || reference.isBlank()) {
                log.error("AkwaPay webhook: no reference on event='{}' intent='{}'", eventId, intentId);
                return ResponseEntity.status(400).body("Missing reference");
            }

            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            var parsed = parseReference(reference);
            if (parsed == null) {
                log.warn("AkwaPay webhook: unrecognised reference '{}' on event='{}' intent='{}' " +
                                "amount={} — returning 200 so AkwaPay stops retrying an unroutable event. " +
                                "If this is a real customer payment, credit it manually.",
                        reference, eventId, intentId, amount);
                return ResponseEntity.ok("Ignored: foreign reference");
            }

            if (parsed.adminUpgrade()) {
                handleAdminUpgrade(parsed.userId(), reference, amount, intentId);
            } else {
                handleDeposit(parsed.userId(), reference, amount, intentId);
            }

            deletePending(reference, "settled by webhook");

        } catch (ApiException e) {
            log.error("AkwaPay webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("AkwaPay webhook: unexpected error — will be retried", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Reconciliation sweep ─────────────────────────────────────────────────

    @Scheduled(fixedDelay = 5_000)
    public void reconcilePendingIntents() {
        var cutoff = Instant.now().minus(SWEEP_HEAD_START);

        var stale = pendingIntents.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        if (stale.isEmpty()) return;

        var due = stale.stream().filter(i -> isDue(i, Instant.now())).toList();
        if (due.isEmpty()) return;

        log.info("reconcile: {} of {} pending intent(s) due this tick", due.size(), stale.size());

        for (var intent : due) {
            try {
                reconcileOne(intent);
            } catch (Exception e) {
                log.error("reconcile: unexpected error for ref='{}' intent='{}' — will retry next tick",
                        intent.getReference(), intent.getIntentId(), e);
            }
        }
    }

    private boolean isDue(AkwaPayPendingIntent intent, Instant now) {
        var last = intent.getLastCheckedAt();
        if (last == null) return true;
        return last.plus(pollIntervalFor(intent, now)).isBefore(now);
    }

    private Duration pollIntervalFor(AkwaPayPendingIntent intent, Instant now) {
        var age = Duration.between(intent.getCreatedAt(), now);
        if (age.compareTo(TIER_HOT_UNTIL)  < 0) return POLL_EVERY_HOT;
        if (age.compareTo(TIER_WARM_UNTIL) < 0) return POLL_EVERY_WARM;
        if (age.compareTo(TIER_COOL_UNTIL) < 0) return POLL_EVERY_COOL;
        return POLL_EVERY_COLD;
    }

    private void reconcileOne(AkwaPayPendingIntent intent) {
        var ref = intent.getReference();

        if (intent.getCreatedAt().isBefore(Instant.now().minus(ABANDON_AFTER))) {
            log.warn("reconcile: abandoning ref='{}' intent='{}' after {}h with no settlement",
                    ref, intent.getIntentId(), ABANDON_AFTER.toHours());
            deletePending(ref, "abandoned after " + ABANDON_AFTER.toHours() + "h");
            return;
        }

        try {
            intent.markChecked(Instant.now());
            pendingIntents.save(intent);
        } catch (Exception e) {
            log.warn("reconcile: could not stamp lastCheckedAt for ref='{}': {}", ref, e.getMessage());
        }

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intent.getIntentId())
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("reconcile: AkwaPay status check error for ref='{}' status={} body={}",
                                    ref, r.statusCode(), body);
                            return new RuntimeException("AkwaPay returned " + r.statusCode());
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .onErrorResume(e -> {
                    log.warn("reconcile: status check failed for ref='{}' intent='{}' — will retry next sweep: {}",
                            ref, intent.getIntentId(), e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (result == null) return;

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase();
        log.info("reconcile: ref='{}' intent='{}' akwapayStatus='{}' attempt={}",
                ref, intent.getIntentId(), akwapayStatus, intent.getAttempts());

        switch (akwapayStatus) {
            case "succeeded" -> {
                log.info("reconcile: ref='{}' succeeded on sweep — applying credit now", ref);
                if (intent.isAdminUpgrade()) {
                    handleAdminUpgrade(intent.getUserId(), ref, intent.getAmountGhs(), intent.getIntentId());
                } else {
                    handleDeposit(intent.getUserId(), ref, intent.getAmountGhs(), intent.getIntentId());
                }
                deletePending(ref, "settled by sweep");
            }

            case "failed", "declined", "cancelled", "expired" -> {
                log.warn("reconcile: ref='{}' intent='{}' terminal status='{}' — no credit applied",
                        ref, intent.getIntentId(), akwapayStatus);
                deletePending(ref, "terminal status " + akwapayStatus);
            }

            default ->
                    log.info("reconcile: ref='{}' status='{}' — still in flight, next check in {}s",
                            ref, akwapayStatus, pollIntervalFor(intent, Instant.now()).toSeconds());
        }
    }

    private void deletePending(String reference, String why) {
        try {
            if (pendingIntents.existsById(reference)) {
                pendingIntents.deleteById(reference);
                log.info("deletePending: ref='{}' removed from pending ledger ({})", reference, why);
            }
        } catch (Exception e) {
            log.warn("deletePending: could not remove ref='{}' ({}) — harmless, sweep will re-check and skip: {}",
                    reference, why, e.getMessage());
        }
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String intentId) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}' intent='{}'",
                userId, amount, ref, intentId);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "akwapay", "reference", ref, "intentId", intentId));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'", amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return;
            }
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit={} adminRate={}",
                    userId, amount, ADMIN_COMMISSION_RATE);
        } catch (Exception ex) {
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate",
                    userId, ex);
        }
    }

    private void handleAdminUpgrade(UUID userId, String ref, BigDecimal amount, String intentId) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}' intent='{}'",
                userId, amount, ref, intentId);

        if (amount.compareTo(BigDecimal.valueOf(200)) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'",
                    amount, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            userService.upgradeToAdmin(userId, ref);
            log.info("handleAdminUpgrade: userId='{}' promoted to ADMIN with {}% commission ref='{}'",
                    userId,
                    ADMIN_COMMISSION_RATE.multiply(BigDecimal.valueOf(100)).toPlainString(),
                    ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleAdminUpgrade: duplicate ref='{}' — skipping", ref);
                return;
            }
            throw ex;
        }

        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "akwapay", "reference", ref, "intentId", intentId));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── Network resolution ───────────────────────────────────────────────────

    private String resolveNetwork(String requested, String phone) {
        if (requested != null && !requested.isBlank()) {
            var normalized = requested.trim().toUpperCase();
            log.info("resolveNetwork: using client-selected network '{}'", normalized);
            return normalized;
        }

        var detected = detectNetworkFromPhone(phone);
        if (detected.isPresent()) {
            log.info("resolveNetwork: auto-detected network '{}' from phone prefix", detected.get());
            return detected.get();
        }

        log.warn("resolveNetwork: could not resolve a network from phone='{}' and no network was selected",
                phone == null ? "null" : "<redacted>");
        throw ApiException.badRequest(
                "We couldn't tell which network that number is on. Please choose MTN, Telecel, or AirtelTigo.");
    }

    private Optional<String> detectNetworkFromPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();

        var digits = phone.replaceAll("[^\\d]", "");

        String local;
        if (digits.startsWith("233") && digits.length() == 12) {
            local = "0" + digits.substring(3);
        } else if (digits.length() == 10 && digits.startsWith("0")) {
            local = digits;
        } else {
            return Optional.empty();
        }

        return Optional.ofNullable(GH_NETWORK_PREFIXES.get(local.substring(0, 3)));
    }

    // ─── AkwaPay API helper ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> akwapayCreateIntent(int amountPesewas,
                                                    String reference,
                                                    String email,
                                                    String phone,
                                                    String network,
                                                    String returnUrl,
                                                    Map<String, Object> metadata) {

        // Unique-per-attempt email using plus-addressing so Flutterwave's
        // POST /customers never sees the same address twice. Flutterwave
        // deduplicates customers by email — reusing the real address causes
        // "Customer already exists" while any prior intent is still unresolved.
        // kojo@gmail.com → kojo+1724449830123@gmail.com
        String syntheticEmail;
        if (email != null && email.contains("@")) {
            int atIdx = email.indexOf("@");
            syntheticEmail = email.substring(0, atIdx)
                    + "+" + System.currentTimeMillis()
                    + email.substring(atIdx);
        } else {
            syntheticEmail = reference.replaceAll("[^a-zA-Z0-9]", "")
                    + System.currentTimeMillis()
                    + "@customers.akwapay.com";
        }

        log.info("akwapayCreateIntent: ref='{}' syntheticEmail='{}'", reference, syntheticEmail);

        var customer = new HashMap<String, Object>();
        customer.put("email", syntheticEmail);
        if (phone != null && !phone.isBlank()) customer.put("phone", phone);

        var body = new HashMap<String, Object>();
        body.put("amount",     amountPesewas);
        body.put("currency",   "GHS");
        body.put("reference",  reference);
        body.put("return_url", returnUrl);
        body.put("metadata",   metadata);
        body.put("customer",   customer);
        body.put("method",     "mobile_money");
        body.put("network",    network.toUpperCase());

        var idempotencyKey = UUID.randomUUID().toString();

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/payment_intents")
                .header("Authorization",   "Bearer " + secretKey)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type",    "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(errBody -> {
                                    log.error("AkwaPay API error: status={} ref='{}' body={}",
                                            clientResponse.statusCode(), reference, errBody);

                                    int code = clientResponse.statusCode().value();
                                    if (code >= 400 && code < 500) {
                                        String userMessage;
                                        try {
                                            @SuppressWarnings("unchecked")
                                            var parsed = (Map<String, Object>)
                                                    objectMapper.readValue(errBody, Map.class);
                                            @SuppressWarnings("unchecked")
                                            var error = (Map<String, Object>) parsed.get("error");
                                            var msg = error != null ? (String) error.get("message") : null;
                                            userMessage = (msg != null && !msg.isBlank())
                                                    ? msg
                                                    : "Payment was rejected. Please check your details and try again.";
                                        } catch (Exception parseEx) {
                                            userMessage = "Payment was rejected. Please check your details and try again.";
                                        }
                                        return (Throwable) ApiException.badRequest(userMessage);
                                    }

                                    return new RuntimeException(
                                            "AkwaPay returned " + clientResponse.statusCode() + ": " + errBody);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .retryWhen(Retry.max(akwapayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("AkwaPay API unreachable after {} retries", akwapayRetryAttempts, ex);
                            return new RuntimeException("AkwaPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("AkwaPay returned an empty response.");
        }

        var status = String.valueOf(result.get("status"));
        log.info("akwapayCreateIntent: intent='{}' status='{}' ref='{}'",
                result.get("id"), status, reference);

        if (result.get("error") != null) {
            log.error("akwapayCreateIntent: error on ref='{}' — {}", reference, result.get("error"));
            throw new RuntimeException("AkwaPay error: " + result.get("error"));
        }

        if ("failed".equals(status)) {
            log.error("akwapayCreateIntent: intent created but already failed, ref='{}'", reference);
            throw new RuntimeException("Payment could not be started. Please try again.");
        }

        return result;
    }

    private String nextActionType(Map<String, Object> response) {
        var na = response.get("next_action");
        if (!(na instanceof Map<?, ?> m)) return "none";
        return String.valueOf(m.get("type"));
    }

    // ─── Reference encoding / decoding ────────────────────────────────────────

    private String buildReference(String prefix, UUID userId) {
        var nonce = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        // Hyphens only — Flutterwave rejects underscores in tx_ref.
        return prefix
                + userId.toString().replace("-", "")
                + "-"
                + String.format("%8s", nonce).replace(' ', '0');
    }

    private ParsedRef parseReference(String reference) {
        boolean adminUpgrade;
        if (reference.startsWith(REF_PREFIX_DEPOSIT))      adminUpgrade = false;
        else if (reference.startsWith(REF_PREFIX_ADMIN))   adminUpgrade = true;
        else return null;

        var rest = reference.substring(REF_PREFIX_DEPOSIT.length());
        if (rest.length() < 32) return null;

        var hex = rest.substring(0, 32);
        try {
            var userId = UUID.fromString(
                    hex.substring(0, 8)  + "-" +
                            hex.substring(8, 12) + "-" +
                            hex.substring(12, 16) + "-" +
                            hex.substring(16, 20) + "-" +
                            hex.substring(20, 32));
            return new ParsedRef(userId, adminUpgrade);
        } catch (IllegalArgumentException e) {
            log.warn("parseReference: malformed userId segment in ref='{}'", reference);
            return null;
        }
    }

    private record ParsedRef(UUID userId, boolean adminUpgrade) {}

    // ─── Signature verification ───────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String header) {
        try {
            String t = null, v1 = null;
            for (var part : header.split(",")) {
                var kv = part.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0]))       t  = kv[1].trim();
                else if ("v1".equals(kv[0])) v1 = kv[1].trim();
            }

            if (t == null || v1 == null) {
                log.warn("AkwaPay webhook: malformed signature header — missing 't' or 'v1' part");
                return false;
            }

            long timestamp;
            try {
                timestamp = Long.parseLong(t);
            } catch (NumberFormatException e) {
                log.warn("AkwaPay webhook: non-numeric timestamp in signature header");
                return false;
            }

            var age = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (age > SIGNATURE_TOLERANCE_SECONDS) {
                log.warn("AkwaPay webhook: signature timestamp {}s old (tolerance {}s) — replay rejected",
                        age, SIGNATURE_TOLERANCE_SECONDS);
                return false;
            }

            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);

            var expected = HexFormat.of().formatHex(mac.doFinal());

            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("AkwaPay webhook: signature verification threw unexpectedly", e);
            return false;
        }
    }
}
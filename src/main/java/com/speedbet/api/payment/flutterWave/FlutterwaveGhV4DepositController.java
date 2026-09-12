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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ghana Cedi (GHS) Mobile Money deposits via Flutterwave v4 Orchestrator.
 *
 * Supports MTN, AirtelTigo, and Vodafone/Telecel MoMo — push-notification
 * flow. Customer approves on their phone; no redirect needed.
 *
 * Endpoints:
 *   POST /api/wallet/deposit/flutterwave/gh/v4/init
 *     body:    { amount, phoneNumber?, network }
 *     returns: { txRef, message }
 *
 *   POST /api/wallet/deposit/flutterwave/gh/v4/verify
 *     body:    { txRef }
 *     returns: { credited, status, message }
 *
 *   POST /api/webhooks/flutterwave/v4/gh
 *     Flutterwave charge.completed event for GHS charges.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  CRITICAL FIX — same root cause as the NG controller
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  The abstract base class used a Svix HMAC-SHA256 scheme
 *  (over "{svix-id}.{svix-timestamp}.{body}") that Flutterwave does not use.
 *  Every GHS webhook was being rejected with 401, so NO GHS deposits were
 *  ever credited automatically.
 *
 *  Correct algorithm from developer.flutterwave.com/docs/webhooks:
 *    Header:    flutterwave-signature
 *    Algorithm: HMAC-SHA256(key=secretHash, data=rawBody) → base64
 *    Compare:   computedBase64 == flutterwave-signature header value
 *    Secret:    plain-text value from Flutterwave dashboard → Settings →
 *               Webhooks → "Secret hash" field. Same value as
 *               app.flutterwave.webhook-hash in application.properties.
 *
 *  This controller implements the correct algorithm directly in its own
 *  webhook() method instead of delegating to the abstract base's
 *  processV4Webhook(), so it is not dependent on the base being fixed.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  application.properties keys used by this class
 * ═══════════════════════════════════════════════════════════════════════════
 *  app.flutterwave.webhook-hash            — plain-text secret from dashboard
 *  app.platform.min-deposit-amount-ghs     — default 1 (already in your file)
 *  app.flutterwave.v4.base-url             — on abstract base
 *  app.flutterwave.v4.client-id            — on abstract base
 *  app.flutterwave.v4.client-secret        — on abstract base
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveGhV4DepositController extends AbstractFlutterwaveV4DepositController {

    // ── Constants ─────────────────────────────────────────────────────────────

    static final String EXPECTED_CURRENCY = "GHS";
    static final String PROVIDER_TAG      = "flutterwave_gh_v4";
    static final String TXREF_PREFIX      = "GHV4-";  // 5 + 32 hex = 37 chars (v4 limit: 6–42)

    /** Accepted network names — includes common aliases customers might send. */
    private static final Set<String> VALID_NETWORKS = Set.of(
            "MTN", "AIRTELTIGO", "VODAFONE", "TELECEL",
            // Legacy aliases that map to the same networks
            "TIGO", "AIRTEL");

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final WalletService                   walletService;
    private final ReferralService                 referralService;
    private final WebClient.Builder               webClientBuilder;
    private final ObjectMapper                    objectMapper;
    private final FlutterwaveV4PendingChargeStore pendingChargeStore;

    // ── Config ────────────────────────────────────────────────────────────────

    /**
     * Minimum GHS deposit. In application.properties:
     *   app.platform.min-deposit-amount-ghs=1
     */
    @Value("${app.platform.min-deposit-amount-ghs:1}")
    private BigDecimal minDeposit;

    /**
     * Plain-text webhook secret hash — same value as NG controller.
     * In application.properties: app.flutterwave.webhook-hash=${FLUTTERWAVE_WEBHOOK_HASH:}
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
    // INIT — initiate a Mobile Money charge
    // =========================================================================

    /**
     * Initiates a GHS Mobile Money charge via the v4 Orchestrator.
     *
     * Request body: { "amount": 100, "phoneNumber": "0241234567", "network": "MTN" }
     *   phoneNumber — optional if user.getPhone() is set on the User entity
     *   network     — MTN | AIRTELTIGO | VODAFONE (and legacy aliases TIGO/AIRTEL/TELECEL)
     *
     * Response: { "txRef": "GHV4-...", "message": "Approve on your phone..." }
     *
     * Flow: customer receives a push notification on their phone and enters
     * their MoMo PIN. No redirect step needed. The webhook (or reconciler)
     * credits the wallet when the customer approves.
     */
    @PostMapping("/api/wallet/deposit/flutterwave/gh/v4/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        BigDecimal amount = parseAmount(req);
        validateMin(amount);

        // Phone — fallback to user profile if not in body
        String phoneNumber = req.get("phoneNumber") != null
                ? req.get("phoneNumber").toString().trim()
                : (user.getPhone() != null ? user.getPhone().trim() : null);
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw ApiException.badRequest(
                    "phoneNumber is required (or set a phone number on your profile).");
        }

        // Network validation
        Object networkRaw = req.get("network");
        if (networkRaw == null || networkRaw.toString().isBlank()) {
            throw ApiException.badRequest(
                    "network is required: MTN, AIRTELTIGO, or VODAFONE.");
        }
        String network = networkRaw.toString().trim().toUpperCase();
        if (!VALID_NETWORKS.contains(network)) {
            throw ApiException.badRequest(
                    "Invalid network '" + network + "'. Use MTN, AIRTELTIGO, or VODAFONE.");
        }
        // Normalise legacy aliases to canonical v4 names
        network = switch (network) {
            case "TIGO", "AIRTEL" -> "AIRTELTIGO";
            case "TELECEL"        -> "VODAFONE";
            default               -> network;
        };

        // "GHV4-" = 5 + 32 hex = 37 chars (within v4's 6–42 limit)
        String txRef = TXREF_PREFIX + randomHex();

        log.info("initDeposit(GH v4): userId='{}' amount={} network='{}' ref='{}'",
                user.getId(), amount, network, txRef);

        Map<String, Object> body = buildMomoBody(amount, user, phoneNumber, network, txRef);
        Map<String, Object> response = orchestratorCharge(body);

        Map<String, Object> data    = unwrapData(response, txRef, "mobile_money");
        String chargeId             = data.get("id").toString();
        String instruction          = extractInstruction(data);

        // CRITICAL: persist before returning. v4 has no lookup-by-reference.
        cachePendingCharge(txRef, chargeId, user.getId(), amount);

        log.info("initDeposit(GH v4): chargeId='{}' status='{}' userId='{}'",
                chargeId, data.get("status"), user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("txRef",   txRef);
        result.put("message", instruction != null
                ? instruction
                : "Please approve the payment prompt on your phone.");
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // VERIFY — safe frontend polling endpoint
    // =========================================================================

    /**
     * Polls Flutterwave GET /charges/{chargeId} for the current status and
     * credits the wallet if the charge has succeeded. Safe to call repeatedly —
     * handleVerifiedDeposit() is idempotent on txRef.
     *
     * Request body: { "txRef": "GHV4-..." }
     * Response:     { "credited": bool, "status": string, "message": string }
     */
    @PostMapping("/api/wallet/deposit/flutterwave/gh/v4/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        Object txRefRaw = req.get("txRef");
        if (txRefRaw == null || txRefRaw.toString().isBlank()) {
            throw ApiException.badRequest("txRef is required");
        }
        String txRef = txRefRaw.toString().trim();

        Map<String, Object> result = verifyAndCredit(
                user.getId(), txRef, EXPECTED_CURRENCY, PROVIDER_TAG);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // =========================================================================
    // WEBHOOK — correct Flutterwave v4 signature verification
    //
    // Does NOT delegate to the abstract base's processV4Webhook() because the
    // base used the wrong Svix algorithm. This implements the correct algorithm
    // directly — same as FlutterwaveNgBankV4DepositController.
    //
    // CORRECT ALGORITHM (developer.flutterwave.com/docs/webhooks):
    //   hash = HMAC-SHA256(key=webhookSecretHash, data=rawBody) → base64
    //   valid = (hash == flutterwave-signature header value)
    // =========================================================================

    @PostMapping("/api/webhooks/flutterwave/v4/gh")
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
            log.warn("webhook(GH v4): missing flutterwave-signature header — headers: {}",
                    headers.keySet());
            return ResponseEntity.status(401).body("Missing signature");
        }

        if (!verifyFlutterwaveSignature(rawBody, signature)) {
            log.warn("webhook(GH v4): signature mismatch — request rejected");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // ── Step 2: Parse body ────────────────────────────────────────────────

        Map<String, Object> event;
        try {
            //noinspection unchecked
            event = (Map<String, Object>) objectMapper.readValue(
                    new String(rawBody, StandardCharsets.UTF_8), Map.class);
        } catch (Exception ex) {
            log.error("webhook(GH v4): failed to parse body", ex);
            return ResponseEntity.status(400).body("Invalid body");
        }

        // ── Step 3: Only handle charge.completed ──────────────────────────────

        String eventType = String.valueOf(event.get("type"));
        if (!"charge.completed".equals(eventType)) {
            log.info("webhook(GH v4): ignoring event type='{}'", eventType);
            return ResponseEntity.ok("Ignored — not charge.completed");
        }

        // ── Step 4: Extract and validate data ─────────────────────────────────

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.get("data");
        if (data == null) {
            log.warn("webhook(GH v4): missing data field — top-level keys: {}", event.keySet());
            return ResponseEntity.status(400).body("Missing data");
        }

        String currency = String.valueOf(data.get("currency"));
        if (!EXPECTED_CURRENCY.equalsIgnoreCase(currency)) {
            log.info("webhook(GH v4): ignoring currency='{}' (expected {})", currency, EXPECTED_CURRENCY);
            return ResponseEntity.ok("Ignored — different currency");
        }

        // ── Step 5: Look up the pending charge ────────────────────────────────

        Object refObj      = data.get("reference");
        Object chargeIdObj = data.get("id");
        if (refObj == null || refObj.toString().isBlank()) {
            log.error("webhook(GH v4): missing reference — data keys: {}", data.keySet());
            return ResponseEntity.status(400).body("Missing reference");
        }

        String txRef    = refObj.toString();
        String chargeId = chargeIdObj != null ? chargeIdObj.toString() : "unknown";

        AbstractFlutterwaveV4DepositController.PendingV4Charge pending = getPendingCharge(txRef);
        if (pending == null) {
            log.warn("webhook(GH v4): no pending charge for ref='{}' — not ours or already settled", txRef);
            // 200 so Flutterwave stops retrying something we can never satisfy
            return ResponseEntity.ok("Unknown reference");
        }

        // Already credited (idempotency guard at the store level, but check early)
        if (pending.isCredited()) {
            log.info("webhook(GH v4): ref='{}' already credited — ignoring duplicate delivery", txRef);
            return ResponseEntity.ok("Already credited");
        }

        // ── Step 6: Check status in payload ──────────────────────────────────

        String status = String.valueOf(data.getOrDefault("status", "unknown"));

        if (!isSuccess(status)) {
            if (isTerminalFailure(status)) {
                pendingChargeStore().markFailed(txRef, status);
                log.info("webhook(GH v4): terminal failure status='{}' for ref='{}'", status, txRef);
            } else {
                log.info("webhook(GH v4): non-terminal status='{}' for ref='{}' — waiting", status, txRef);
            }
            return ResponseEntity.ok("Acknowledged — status: " + status);
        }

        // ── Step 7: Re-verify via GET /charges/{id} ───────────────────────────
        //
        // Per docs: always re-query before giving value.
        // If lookup fails, fall back to the signed payload with amount cross-check.

        BigDecimal creditAmount;
        boolean reVerified = false;

        try {
            Map<String, Object> verified = getCharge(chargeId);
            @SuppressWarnings("unchecked")
            Map<String, Object> vData    = (Map<String, Object>) verified.getOrDefault("data", Map.of());

            String vStatus   = String.valueOf(vData.getOrDefault("status", "unknown"));
            String vCurrency = String.valueOf(vData.get("currency"));

            if (!isSuccess(vStatus)) {
                log.warn("webhook(GH v4): re-verify status='{}' for ref='{}' — not crediting", vStatus, txRef);
                if (isTerminalFailure(vStatus)) pendingChargeStore().markFailed(txRef, vStatus);
                return ResponseEntity.ok("Not crediting — re-verification status: " + vStatus);
            }

            if (!EXPECTED_CURRENCY.equalsIgnoreCase(vCurrency)) {
                log.error("webhook(GH v4): currency mismatch on re-verify: got='{}' ref='{}'", vCurrency, txRef);
                return ResponseEntity.status(400).body("Currency mismatch on verification");
            }

            creditAmount = new BigDecimal(String.valueOf(vData.get("amount")));
            reVerified   = true;
            log.info("webhook(GH v4): re-verified chargeId='{}' amount={} ref='{}'",
                    chargeId, creditAmount, txRef);

        } catch (Exception ex) {
            // GET /charges/{id} is known to return 500 for valid charges.
            // The signed webhook payload is our fallback.
            log.warn("webhook(GH v4): re-verify failed for chargeId='{}' — using signed payload. {}",
                    chargeId, ex.getMessage());

            Object rawAmount = data.get("amount");
            if (rawAmount == null) {
                log.error("webhook(GH v4): re-verify failed AND payload missing amount for ref='{}'", txRef);
                return ResponseEntity.status(500).body("Verification unavailable, will retry");
            }

            creditAmount = new BigDecimal(String.valueOf(rawAmount));

            if (pending.amount() != null && creditAmount.compareTo(pending.amount()) > 0) {
                log.error("webhook(GH v4): payload amount={} > requested={} for ref='{}' — rejecting",
                        creditAmount, pending.amount(), txRef);
                return ResponseEntity.status(400).body("Amount exceeds requested");
            }
        }

        // ── Step 8: Credit wallet ─────────────────────────────────────────────

        try {
            handleVerifiedDeposit(
                    pending.userId(), txRef, creditAmount, EXPECTED_CURRENCY, PROVIDER_TAG);

            String via = reVerified ? "webhook" : "webhook_payload_fallback";
            pendingChargeStore().markCredited(txRef, via);

            log.info("webhook(GH v4): credited userId='{}' amount={} GHS ref='{}' via='{}'",
                    pending.userId(), creditAmount, txRef, via);

            return ResponseEntity.ok("OK");

        } catch (ApiException ex) {
            log.error("webhook(GH v4): credit failed for ref='{}' — {}", txRef, ex.getMessage(), ex);
            return ResponseEntity.status(400).body("Credit error: " + ex.getMessage());
        } catch (Exception ex) {
            log.error("webhook(GH v4): unexpected error crediting ref='{}' — will retry", txRef, ex);
            return ResponseEntity.status(500).body("Processing error");
        }
    }

    // =========================================================================
    // SIGNATURE VERIFICATION
    //
    // Identical algorithm to FlutterwaveNgBankV4DepositController.
    // Extracted into its own method here (not shared via base class) so each
    // controller is self-contained and not affected by base class changes.
    // =========================================================================

    /**
     * Verifies a Flutterwave webhook signature.
     *
     * Official v4 algorithm (developer.flutterwave.com/docs/webhooks):
     *   computed = Base64(HMAC-SHA256(key=secretHash, data=rawBody))
     *   valid    = (computed == flutterwave-signature header)
     */
    private boolean verifyFlutterwaveSignature(byte[] rawBody, String signatureHeader) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecretHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed       = mac.doFinal(rawBody);
            String computedBase64 = Base64.getEncoder().encodeToString(computed);

            boolean match = MessageDigest.isEqual(
                    computedBase64.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));

            if (!match) {
                log.debug("webhook(GH v4) signature mismatch: computed='{}' received='{}'",
                        computedBase64, signatureHeader);
            }
            return match;
        } catch (Exception ex) {
            log.error("webhook(GH v4): signature computation failed", ex);
            return false;
        }
    }

    // =========================================================================
    // Private — request body builder
    // =========================================================================

    /**
     * Orchestrator body for Ghana Mobile Money.
     *
     * CONFIRMED v4 shape from official docs
     * (developer.flutterwave.com/docs/payment-orchestrator-flow):
     *
     *   "payment_method": {
     *     "type": "mobile_money",
     *     "mobile_money": {
     *       "country_code":  "233",
     *       "network":       "MTN",
     *       "phone_number":  "9012345678"
     *     }
     *   }
     *
     * The previous shape nested phone inside a phone:{} object, which was
     * WRONG and caused a 500 from the Flutterwave orchestrator on every
     * GHS charge. country_code and phone_number are flat fields directly
     * on mobile_money — no nesting.
     *
     * No redirect_url needed — MoMo is a push-notification flow.
     */
    private static Map<String, Object> buildMomoBody(
            BigDecimal amount, User user, String phoneNumber, String network, String txRef) {

        Map<String, Object> mobileMoney = new LinkedHashMap<>();
        mobileMoney.put("country_code",  "233");
        mobileMoney.put("network",       network);
        mobileMoney.put("phone_number",  normalizeGhPhone(phoneNumber));

        Map<String, Object> paymentMethod = new LinkedHashMap<>();
        paymentMethod.put("type",         "mobile_money");
        paymentMethod.put("mobile_money", mobileMoney);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount",         amount);
        body.put("currency",       EXPECTED_CURRENCY);
        body.put("reference",      txRef);
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
            Map<String, Object> response, String txRef, String method) {

        if (response == null) {
            throw ApiException.badRequest("No response from payment provider. Please try again.");
        }
        Object dataObj = response.get("data");
        if (!(dataObj instanceof Map)) {
            log.error("unwrapData({}): missing data for ref='{}' — response: {}", method, txRef, response);
            throw ApiException.badRequest("Payment initiation failed — no charge data returned.");
        }
        Map<String, Object> data = (Map<String, Object>) dataObj;
        if (data.get("id") == null) {
            log.error("unwrapData({}): data.id null for ref='{}' — data: {}", method, txRef, data);
            throw ApiException.badRequest("Payment initiation failed — charge id missing.");
        }
        return data;
    }

    /**
     * Extracts the customer-facing instruction from the charge response.
     *
     * For MoMo the relevant field is usually:
     *   data.next_action.type = "payment_instruction"
     *   data.next_action.payment_instruction.note = "Approve on your phone..."
     */
    @SuppressWarnings("unchecked")
    private static String extractInstruction(Map<String, Object> data) {
        Object naObj = data.get("next_action");
        if (!(naObj instanceof Map)) return null;

        Map<String, Object> na = (Map<String, Object>) naObj;

        Object piObj = na.get("payment_instruction");
        if (piObj instanceof Map) {
            Object note = ((Map<String, Object>) piObj).get("note");
            if (note != null) return note.toString();
        }

        // Fallback: message field at the charge level
        Object msg = data.get("message");
        return msg != null ? msg.toString() : null;
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
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit.toPlainString());
        }
    }

    /**
     * Strips Ghana country prefix so the number component is sent separately.
     * Ghana country_code is "233"; number is the local 9-digit form.
     * Examples: +233241234567 → 241234567 | 0241234567 → 241234567
     */
    private static String normalizeGhPhone(String phone) {
        String trimmed = phone.replaceAll("\\s+", "");
        if (trimmed.startsWith("+233")) return trimmed.substring(4);
        if (trimmed.startsWith("233"))  return trimmed.substring(3);
        if (trimmed.startsWith("0"))    return trimmed.substring(1);
        return trimmed;
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
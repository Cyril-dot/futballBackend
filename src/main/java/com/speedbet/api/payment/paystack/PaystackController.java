package com.speedbet.api.payment.paystack;

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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaystackController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    /**
     * Commission rate applied to every deposit for affiliate attribution.
     * Admins earn 70% of the configured platform commission on each referred deposit.
     * The actual per-admin rate is stored on the Referral entity (set during
     * upgradeToAdmin) and resolved inside ReferralService.attributeCommission().
     * This constant is for logging/documentation purposes only.
     */
    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    private final WalletService           walletService;
    private final UserService             userService;
    private final AdminUpgradeChatService adminUpgradeChatService;
    private final ReferralService         referralService;
    private final WebClient.Builder       webClientBuilder;
    private final ObjectMapper            objectMapper;

    @Value("${app.paystack.secret-key}")             private String     secretKey;
    @Value("${app.paystack.base-url}")               private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")           private String     frontendUrl;

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    @PostMapping("/api/wallet/deposit/paystack/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        log.info("initDeposit: userId='{}' amount={} pesewas={}", user.getId(), amount, amountPesewas);

        var response = paystackInit(
                user.getEmail(),
                amountPesewas,
                frontendUrl + "/app/wallet?payment=success",
                Map.of("userId", user.getId().toString())
        );

        log.info("initDeposit: Paystack responded status='{}' for userId='{}'",
                response.get("status"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    @PostMapping("/api/user/upgrade-to-admin/paystack/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        log.info("initAdminUpgrade: userId='{}' email='{}'", user.getId(), user.getEmail());

        var response = paystackInit(
                user.getEmail(),
                ADMIN_UPGRADE_FEE_PESEWAS,
                frontendUrl + "/app/upgrade?payment=success",
                Map.of(
                        "userId",        user.getId().toString(),
                        "upgradeIntent", UPGRADE_INTENT_ADMIN
                )
        );

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/paystack")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Paystack webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Paystack webhook: missing x-paystack-signature header");
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("Paystack webhook: invalid signature received");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            @SuppressWarnings("unchecked")
            var event = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);

            var eventType = event.get("event").toString();
            log.info("Paystack webhook: received event='{}'", eventType);

            if (!"charge.success".equals(eventType)) {
                log.info("Paystack webhook: ignoring event='{}'", eventType);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");

            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) data.get("metadata");

            if (metadata == null || metadata.get("userId") == null) {
                log.error("Paystack webhook: missing userId in metadata, ref='{}'",
                        data.get("reference"));
                return ResponseEntity.status(400).body("Missing userId in metadata");
            }

            var userId        = UUID.fromString(metadata.get("userId").toString());
            var ref           = data.get("reference").toString();
            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
            var upgradeIntent = metadata.getOrDefault("upgradeIntent", "").toString();

            if (UPGRADE_INTENT_ADMIN.equals(upgradeIntent)) {
                handleAdminUpgrade(userId, ref, amount);
            } else {
                handleDeposit(userId, ref, amount);
            }

        } catch (ApiException e) {
            log.error("Paystack webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Paystack webhook: unexpected error — will retry", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    /**
     * Credits the depositing user's wallet, then attributes commission
     * to their referrer (if they were referred).
     *
     * Commission structure:
     *   The referring admin earns a percentage of every deposit made by users
     *   they referred. The rate is stored on the Referral entity and defaults
     *   to 70% of the platform commission. Resolution is handled entirely inside
     *   ReferralService.attributeCommission() — this method just triggers it.
     *
     * Flow:
     *   deposit amount → walletService.credit (user wallet)
     *                  → referralService.attributeCommission (admin affiliate wallet)
     */
    private void handleDeposit(UUID userId, String ref, BigDecimal amount) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}'", userId, amount, ref);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "paystack", "reference", ref));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'",
                    amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return;
            }
            throw ex;
        }

        // ── Attribute commission to referring admin based on commission structure ──
        // The admin's rate (default 70%) is resolved from the Referral entity inside
        // ReferralService. No rate logic lives here — just trigger attribution.
        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit='{}' adminRate={}",
                    userId, amount, ADMIN_COMMISSION_RATE);
        } catch (Exception ex) {
            // Never block a deposit because of a commission failure
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate",
                    userId, ex);
        }
    }

    /**
     * Handles an admin upgrade payment.
     *
     * Steps:
     *   1. Validates amount >= GHS 200
     *   2. Promotes user to ADMIN + initialises their referral link at 70% commission
     *   3. Records an audit transaction (Paystack already collected the funds externally)
     *   4. Creates onboarding chat with Super Admin for commission confirmation
     *
     * Commission structure note:
     *   The new admin's default commission rate is set to 70% inside
     *   UserService.upgradeToAdmin(). Super Admin can adjust the rate via the
     *   onboarding chat created in step 4.
     */
    private void handleAdminUpgrade(UUID userId, String ref, BigDecimal amount) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}'", userId, amount, ref);

        if (amount.compareTo(BigDecimal.valueOf(200)) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'",
                    amount, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            // upgradeToAdmin sets the new admin's commission rate to 70% on the Referral entity
            userService.upgradeToAdmin(userId, ref);
            log.info("handleAdminUpgrade: userId='{}' promoted to ADMIN with {}% commission ref='{}'",
                    userId, ADMIN_COMMISSION_RATE.multiply(BigDecimal.valueOf(100)).toPlainString(), ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleAdminUpgrade: duplicate ref='{}' — skipping", ref);
                return;
            }
            throw ex;
        }

        // Audit record — Paystack collected GHS 200 externally, no wallet debit needed
        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "paystack", "reference", ref));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        // Create onboarding chat so Super Admin can confirm/adjust the 70% commission rate
        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── Paystack API helper ──────────────────────────────────────────────────

    /**
     * Calls Paystack /transaction/initialize and returns the FULL response map:
     *   { "status": true, "message": "...", "data": { "authorization_url": "...", "reference": "..." } }
     *
     * Errors (4xx/5xx, network timeouts, bad secret key) are surfaced as
     * exceptions rather than silently returning status=false.
     *
     * The frontend unwraps the nested `data` object itself:
     *   const inner = raw?.data ?? raw;
     *   const authUrl = inner.authorization_url;
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paystackInit(String email, int amountPesewas,
                                             String callbackUrl,
                                             Map<String, Object> metadata) {

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/transaction/initialize")
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "email",        email,
                        "amount",       amountPesewas,
                        "currency",     "GHS",
                        "callback_url", callbackUrl,
                        "metadata",     metadata
                ))
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("Paystack API error: status={} body={}",
                                            clientResponse.statusCode(), body);
                                    return new RuntimeException(
                                            "Paystack returned " + clientResponse.statusCode() + ": " + body);
                                })
                )
                .bodyToMono(Map.class)
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("Paystack API unreachable", ex);
                            return new RuntimeException("Paystack is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) {
            throw new RuntimeException("Paystack returned an empty response.");
        }

        log.info("paystackInit: Paystack status='{}' message='{}'",
                result.get("status"), result.get("message"));

        if (Boolean.FALSE.equals(result.get("status"))) {
            var message = result.getOrDefault("message", "Paystack declined the request").toString();
            log.error("paystackInit: Paystack status=false — {}", message);
            throw new RuntimeException("Paystack error: " + message);
        }

        return result;
    }

    // ─── Signature verification ───────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String signature) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            var hash = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return hash.equals(signature);
        } catch (Exception e) {
            log.error("Paystack webhook: signature verification error", e);
            return false;
        }
    }
}
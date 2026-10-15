package com.speedbet.api.payment.shinobipay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * ShinobiPay direct MoMo deposits.
 * <p>
 * Flow: init → customer gets the MoMo prompt on their phone → they approve
 * with their PIN → status poll or webhook settles → wallet credited.
 */
@RestController
@RequiredArgsConstructor
public class ShinobiPayDepositController {

    private static final Logger log = LoggerFactory.getLogger(ShinobiPayDepositController.class);
    private static final BigDecimal MIN_DEPOSIT = new BigDecimal("1");

    private final ShinobiPayService shinobiPay;
    private final ShinobiPayDepositRepository depositRepo;
    private final WalletService walletService;
    private final ObjectMapper mapper = new ObjectMapper();

    // ── Step 1: init — body { amount, phone } ──────────────────────────────
    @PostMapping("/api/wallet/deposit/shinobipay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> init(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        if (!shinobiPay.isConfigured()) throw ApiException.badRequest("Mobile money deposits are not available right now.");

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(req.get("amount"))).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw ApiException.badRequest("Enter a valid amount.");
        }
        if (amount.compareTo(MIN_DEPOSIT) < 0) throw ApiException.badRequest("Minimum deposit is GHS 1.");

        String phone = req.get("phone") == null ? null : req.get("phone").toString().trim();
        if (ShinobiPayService.localForm(phone) == null) throw ApiException.badRequest("Enter a valid Ghana mobile money number.");

        long pesewas = amount.multiply(new BigDecimal("100")).longValueExact();
        String reference = ShinobiPayService.buildReference("7up-");

        JsonNode intent = shinobiPay.createIntent(pesewas, reference, phone, user.getEmail());

        ShinobiPayDeposit dep = ShinobiPayDeposit.builder()
                .userId(user.getId())
                .reference(reference)
                .intentId(intent.get("id").asText())
                .amountPesewas(pesewas)
                .amountGhs(amount)
                .phone(ShinobiPayService.localForm(phone))
                .status("PENDING")
                .build();
        depositRepo.save(dep);

        log.info("[ShinobiPay][init] userId='{}' ref='{}' amountGHS={} intentId='{}'",
                user.getId(), reference, amount, intent.get("id").asText());

        Map<String, Object> out = new HashMap<>();
        out.put("reference", reference);
        out.put("status", "PENDING");
        out.put("message", "Approve the prompt on your phone with your MoMo PIN.");
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ── Step 2: poll status — settles the wallet on success ────────────────
    @GetMapping("/api/wallet/deposit/shinobipay/status/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user,
            @PathVariable String reference) {

        ShinobiPayDeposit dep = depositRepo.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("Deposit not found."));
        if (!dep.getUserId().equals(user.getId())) throw ApiException.notFound("Deposit not found.");

        if ("PENDING".equals(dep.getStatus())) {
            JsonNode intent = shinobiPay.getIntent(dep.getIntentId());
            Boolean ok = ShinobiPayService.isSucceeded(intent);
            if (Boolean.TRUE.equals(ok)) {
                settle(dep);
            } else if (Boolean.FALSE.equals(ok)) {
                dep.setStatus("FAILED");
                depositRepo.save(dep);
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("reference", dep.getReference());
        out.put("status", dep.getStatus());
        out.put("amount", dep.getAmountGhs());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    // ── Webhook — ShinobiPay calls this on settlement ───────────────────────
    @PostMapping("/api/webhooks/shinobipay")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-AkwaPay-Signature", required = false) String signature) {

        if (!shinobiPay.verifySignature(rawBody, signature)) {
            log.warn("[ShinobiPay][webhook] invalid signature");
            return ResponseEntity.status(401).body(Map.of("ok", false));
        }

        try {
            JsonNode event = mapper.readTree(rawBody);
            JsonNode data = event.has("data") ? event.get("data") : event;
            String reference = data.has("reference") ? data.get("reference").asText() : null;
            if (reference == null) return ResponseEntity.ok(Map.of("ok", true));

            depositRepo.findByReference(reference).ifPresent(dep -> {
                if (!"PENDING".equals(dep.getStatus())) return;
                Boolean ok = ShinobiPayService.isSucceeded(data);
                if (Boolean.TRUE.equals(ok)) {
                    settle(dep);
                } else if (Boolean.FALSE.equals(ok)) {
                    dep.setStatus("FAILED");
                    depositRepo.save(dep);
                }
            });
        } catch (Exception e) {
            log.error("[ShinobiPay][webhook] handler failed: {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Credits the wallet exactly once (idempotent via provider ref). */
    @Transactional
    public void settle(ShinobiPayDeposit dep) {
        String providerRef = "shinobipay:" + dep.getReference();
        try {
            walletService.credit(dep.getUserId(), dep.getAmountGhs(), TxKind.DEPOSIT,
                    providerRef, Map.of("provider", "shinobipay", "intentId", dep.getIntentId()));
        } catch (ApiException e) {
            // Already settled (idempotency guard) — just mark it.
            log.info("[ShinobiPay][settle] already settled ref='{}'", dep.getReference());
        }
        dep.setStatus("SUCCEEDED");
        depositRepo.save(dep);
        log.info("[ShinobiPay][settle] credited userId='{}' amountGHS={} ref='{}'",
                dep.getUserId(), dep.getAmountGhs(), dep.getReference());
    }
}

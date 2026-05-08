package com.speedbet.api.payment.stripe;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

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
public class StripeController {

    private final WalletService walletService;

    @Value("${app.stripe.secret-key}") private String secretKey;
    @Value("${app.stripe.webhook-secret}") private String webhookSecret;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;

    @PostMapping("/api/wallet/deposit/stripe/intent")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createIntent(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        // In production, call Stripe SDK here
        // For now, return a structured mock response for testing
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "clientSecret", "pi_demo_" + UUID.randomUUID().toString().replace("-", "") + "_secret_demo",
            "amount", amount,
            "currency", "usd",
            "status", "requires_payment_method"
        )));
    }

    @PostMapping("/api/webhooks/stripe")
    public ResponseEntity<String> webhook(
            @RequestHeader("Stripe-Signature") String sigHeader,
            @RequestBody String payload) {

        if (!verifyStripeSignature(payload, sigHeader)) {
            log.warn("Invalid Stripe webhook signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            var event = new com.fasterxml.jackson.databind.ObjectMapper().readValue(payload, Map.class);
            var eventType = event.get("type").toString();

            if ("payment_intent.succeeded".equals(eventType)) {
                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) event.get("data");
                @SuppressWarnings("unchecked")
                var intent = (Map<String, Object>) data.get("object");
                @SuppressWarnings("unchecked")
                var metadata = (Map<String, Object>) intent.get("metadata");
                if (metadata != null && metadata.get("userId") != null) {
                    var userId = UUID.fromString(metadata.get("userId").toString());
                    var amountCents = Long.parseLong(intent.get("amount").toString());
                    var amount = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
                    var intentId = intent.get("id").toString();
                    walletService.credit(userId, amount, TxKind.DEPOSIT, intentId,
                        Map.of("provider", "stripe", "intentId", intentId));
                }
            }
        } catch (Exception e) {
            log.error("Stripe webhook processing error", e);
        }
        return ResponseEntity.ok("OK");
    }

    private boolean verifyStripeSignature(String payload, String sigHeader) {
        try {
            // Parse t=timestamp,v1=signature from header
            String timestamp = null, v1sig = null;
            for (var part : sigHeader.split(",")) {
                if (part.startsWith("t=")) timestamp = part.substring(2);
                if (part.startsWith("v1=")) v1sig = part.substring(3);
            }
            if (timestamp == null || v1sig == null) return false;

            var signedPayload = timestamp + "." + payload;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            return expected.equals(v1sig);
        } catch (Exception e) {
            return false;
        }
    }
}

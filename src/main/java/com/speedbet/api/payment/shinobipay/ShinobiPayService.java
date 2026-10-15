package com.speedbet.api.payment.shinobipay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedbet.api.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ShinobiPay (rebranded AkwaPay) client for direct Ghana MoMo deposits.
 * Creating a payment intent IS the phone prompt — the customer approves with
 * their MoMo PIN. No hosted checkout step.
 */
@Service
public class ShinobiPayService {

    private static final Logger log = LoggerFactory.getLogger(ShinobiPayService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.shinobipay.base-url:https://akwapayapi.onrender.com/v1}")
    private String baseUrl;

    @Value("${app.shinobipay.secret-key:}")
    private String secretKey;

    @Value("${app.shinobipay.webhook-secret:}")
    private String webhookSecret;

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    private void requireConfigured() {
        if (!isConfigured()) throw ApiException.badRequest("Mobile money deposits are not available right now.");
    }

    /** 0-prefixed local Ghana form, or null. */
    public static String localForm(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.startsWith("233") && d.length() == 12) return "0" + d.substring(3);
        if (d.length() == 10 && d.startsWith("0")) return d;
        return null;
    }

    /** 233-prefixed international form ShinobiPay expects. */
    public static String intlForm(String phone) {
        String local = localForm(phone);
        return local == null ? null : "233" + local.substring(1);
    }

    public static String detectNetwork(String phone) {
        String local = localForm(phone);
        if (local == null) return null;
        String p = local.substring(0, 3);
        if ("024025053054055059".contains(p)) return "MTN";
        if ("020050".contains(p)) return "TELECEL";
        if ("026027056057".contains(p)) return "AIRTELTIGO";
        return null;
    }

    public static String buildReference(String prefix) {
        String src = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
        return prefix + src.substring(0, 32) + "-" + src.substring(32, 40);
    }

    /**
     * Creates a payment intent (sends the MoMo prompt to the customer's phone).
     * @return the raw intent JSON from ShinobiPay
     */
    public JsonNode createIntent(long amountPesewas, String reference, String phone, String email) {
        requireConfigured();
        String intl = intlForm(phone);
        if (intl == null) throw ApiException.badRequest("Enter a valid Ghana mobile money number.");
        String network = detectNetwork(phone);
        if (network == null) throw ApiException.badRequest("Could not detect the network for that number.");

        Map<String, Object> customer = new HashMap<>();
        customer.put("phone", intl);
        if (email != null && !email.isBlank()) customer.put("email", email);

        Map<String, Object> body = new HashMap<>();
        body.put("amount", amountPesewas);
        body.put("currency", "GHS");
        body.put("reference", reference);
        body.put("method", "mobile_money");
        body.put("network", network);
        body.put("customer", customer);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + secretKey);
        headers.set("Idempotency-Key", reference);

        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    baseUrl + "/payment_intents", HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
            JsonNode json = mapper.readTree(res.getBody());
            if (json.has("error")) throw ApiException.badRequest("Payment could not be started. Please try again.");
            if (!json.has("id")) throw ApiException.badRequest("Payment could not be started. Please try again.");
            return json;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ShinobiPay] createIntent failed: {}", e.getMessage());
            throw ApiException.badRequest("Mobile money is currently unavailable. Please try again.");
        }
    }

    public JsonNode getIntent(String intentId) {
        requireConfigured();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + secretKey);
        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    baseUrl + "/payment_intents/" + intentId, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            return mapper.readTree(res.getBody());
        } catch (Exception e) {
            log.error("[ShinobiPay] getIntent failed: {}", e.getMessage());
            throw ApiException.badRequest("Could not check payment status. Please try again.");
        }
    }

    /** succeeded → true; failed/canceled → false; anything else → null (still pending). */
    public static Boolean isSucceeded(JsonNode intent) {
        if (intent == null || !intent.has("status")) return null;
        String s = intent.get("status").asText("").toLowerCase();
        if ("succeeded".equals(s)) return Boolean.TRUE;
        if ("failed".equals(s) || "canceled".equals(s) || "cancelled".equals(s)) return Boolean.FALSE;
        return null;
    }

    /**
     * Verifies the X-AkwaPay-Signature header: t=&lt;unix&gt;,v1=HMAC_SHA256(secret, "&lt;t&gt;.&lt;raw body&gt;"),
     * 300s tolerance.
     */
    public boolean verifySignature(String rawBody, String header) {
        try {
            if (header == null || webhookSecret == null || webhookSecret.isBlank()) return false;
            String t = null, v1 = null;
            for (String part : header.split(",")) {
                int idx = part.indexOf('=');
                if (idx < 0) continue;
                String k = part.substring(0, idx).trim();
                String v = part.substring(idx + 1).trim();
                if ("t".equals(k)) t = v;
                if ("v1".equals(k)) v1 = v;
            }
            if (t == null || v1 == null) return false;
            long ts = Long.parseLong(t);
            if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 300) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.digest();
            byte[] actual;
            try {
                actual = hexToBytes(v1);
            } catch (Exception e) {
                return false;
            }
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}

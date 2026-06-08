package com.speedbet.api.wallet;

import com.speedbet.api.config.ArkeselSmsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArkeselSmsService {

    private final ArkeselSmsConfig smsConfig;
    private final RestTemplate     restTemplate;

    /**
     * Send a plain text SMS via Arkesel V1.
     * V1 uses a GET request with query parameters.
     *
     * @param phoneNumber recipient – local (0XXXXXXXXX) or international (233XXXXXXXXX), both accepted
     * @param message     SMS body (160 chars = 1 page)
     */
    public void sendSms(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("SMS skipped – no phone number provided");
            return;
        }

        String normalised = normaliseGhanaNumber(phoneNumber);

        if (smsConfig.isSandbox()) {
            log.info("[SANDBOX] SMS to {} (normalised from {}) | Message: {}", normalised, phoneNumber, message);
            return;
        }

        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(smsConfig.getBaseUrl() + "/sms/api")
                    .queryParam("action",  "send-sms")
                    .queryParam("api_key", smsConfig.getApiKey())
                    .queryParam("to",      normalised)
                    .queryParam("from",    smsConfig.getSenderId())
                    .queryParam("sms",     message)
                    .build(true)   // true = values already encoded – avoids double-encoding spaces in message
                    .toUri();

            log.info("Arkesel request → to={} from={}", normalised, smsConfig.getSenderId());

            String response = restTemplate.getForObject(uri, String.class);

            log.info("Arkesel raw response for {}: {}", normalised, response);

            if (response != null && response.toUpperCase().contains("OK")) {
                log.info("SMS sent successfully to {} via Arkesel V1", normalised);
            } else {
                log.error("Arkesel SMS V1 failed for {} – response: {}", normalised, response);
            }

        } catch (Exception e) {
            // Never let SMS failure crash the main withdrawal flow
            log.error("Arkesel SMS V1 exception for {}: {}", normalised, e.getMessage(), e);
        }
    }

    /**
     * Normalises a Ghana phone number to international format without '+'.
     * 0XXXXXXXXX  → 233XXXXXXXXX
     * 233XXXXXXXXX → unchanged
     * +233XXXXXXXXX → 233XXXXXXXXX
     */
    private String normaliseGhanaNumber(String phone) {
        String digits = phone.trim().replaceAll("[\\s\\-()]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.startsWith("0")) {
            digits = "233" + digits.substring(1);
        }
        return digits;
    }
}
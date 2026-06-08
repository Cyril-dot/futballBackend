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

    public void sendSms(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("SMS skipped – no phone number provided");
            return;
        }

        String normalised = normaliseGhanaNumber(phoneNumber);

        log.info("sendSms called – original='{}' normalised='{}' sandbox={}",
                phoneNumber, normalised, smsConfig.isSandbox());

        if (smsConfig.isSandbox()) {
            log.info("[SANDBOX] SMS to {} | Message: {}", normalised, message);
            return;
        }

        try {
            // build(false) → let Spring encode the values properly
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(smsConfig.getBaseUrl() + "/sms/api")
                    .queryParam("action",  "send-sms")
                    .queryParam("api_key", smsConfig.getApiKey())
                    .queryParam("to",      normalised)
                    .queryParam("from",    smsConfig.getSenderId())
                    .queryParam("sms",     message)
                    .build(false)  // ← FIX: let Spring URL-encode the values
                    .encode()
                    .toUri();

            log.info("Arkesel request → to={} from={} url={}", normalised, smsConfig.getSenderId(), uri);

            String response = restTemplate.getForObject(uri, String.class);

            log.info("Arkesel raw response for {}: {}", normalised, response);

            if (response != null && response.toUpperCase().contains("OK")) {
                log.info("SMS sent successfully to {} via Arkesel V1", normalised);
            } else {
                log.error("Arkesel SMS failed for {} – response: {}", normalised, response);
            }

        } catch (Exception e) {
            log.error("Arkesel SMS exception for {}: {}", normalised, e.getMessage(), e);
        }
    }

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
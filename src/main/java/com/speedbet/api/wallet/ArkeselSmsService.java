package com.speedbet.api.sms;

import com.speedbet.api.config.ArkeselSmsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

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
     * @param phoneNumber recipient in international format e.g. 233XXXXXXXXX
     * @param message     SMS body (160 chars = 1 page)
     */
    public void sendSms(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("SMS skipped – no phone number provided");
            return;
        }

        if (smsConfig.isSandbox()) {
            log.info("[SANDBOX] SMS to {} | Message: {}", phoneNumber, message);
            return;
        }

        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(smsConfig.getBaseUrl() + "/sms/api")
                    .queryParam("action",  "send-sms")
                    .queryParam("api_key", smsConfig.getApiKey())
                    .queryParam("to",      phoneNumber)
                    .queryParam("from",    smsConfig.getSenderId())
                    .queryParam("sms",     message)
                    .build()
                    .toUriString();

            String response = restTemplate.getForObject(url, String.class);

            if (response != null && response.toLowerCase().contains("ok")) {
                log.info("SMS sent successfully to {} via Arkesel V1", phoneNumber);
            } else {
                log.error("Arkesel SMS V1 failed for {} – response: {}", phoneNumber, response);
            }

        } catch (Exception e) {
            // Never let SMS failure crash the main withdrawal flow
            log.error("Arkesel SMS V1 exception for {}: {}", phoneNumber, e.getMessage(), e);
        }
    }
}
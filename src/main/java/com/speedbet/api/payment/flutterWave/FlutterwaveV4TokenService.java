package com.speedbet.api.payment.flutterWave;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;

/**
 * Fetches and caches a short-lived OAuth2 access token from Flutterwave's
 * identity provider, required for all Flutterwave v4 API calls.
 *
 * v4 dropped static API keys in favour of OAuth2 client_credentials.
 * Every outbound Flutterwave call must carry:
 *   Authorization: Bearer <access_token>
 *
 * Token lifetime is typically 300 seconds. This service caches the token
 * and refreshes it automatically 30 seconds before expiry.
 *
 * Required application.properties keys:
 *   flutterwave.v4.token-url     = https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token
 *   flutterwave.v4.client-id     = <from your Flutterwave sandbox/production dashboard>
 *   flutterwave.v4.client-secret = <from your Flutterwave sandbox/production dashboard>
 */
@Slf4j
@Service
public class FlutterwaveV4TokenService {

    @Value("${flutterwave.v4.token-url}")
    private String tokenUrl;

    @Value("${flutterwave.v4.client-id}")
    private String clientId;

    @Value("${flutterwave.v4.client-secret}")
    private String clientSecret;

    private final WebClient.Builder webClientBuilder;

    public FlutterwaveV4TokenService(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    private volatile String  cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * Returns a valid access token, refreshing from Flutterwave if the
     * cached token has expired or is within 30 seconds of expiry.
     */
    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        return refreshToken();
    }

    @SuppressWarnings("unchecked")
    private String refreshToken() {
        log.info("FlutterwaveV4TokenService: refreshing access token from {}", tokenUrl);

        Map<String, Object> result;
        try {
            result = webClientBuilder.build()
                    .post()
                    .uri(tokenUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(BodyInserters.fromFormData("client_id",     clientId)
                                       .with("client_secret", clientSecret)
                                       .with("grant_type",    "client_credentials"))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception ex) {
            log.error("FlutterwaveV4TokenService: token request failed — {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to obtain Flutterwave v4 access token: " + ex.getMessage(), ex);
        }

        if (result == null || result.get("access_token") == null) {
            log.error("FlutterwaveV4TokenService: unexpected token response — {}", result);
            throw new RuntimeException("Flutterwave v4 token response missing access_token field");
        }

        cachedToken = result.get("access_token").toString();

        var expiresInRaw = result.getOrDefault("expires_in", "300").toString();
        long ttlSeconds  = Long.parseLong(expiresInRaw) - 30; // 30s safety buffer
        tokenExpiry      = Instant.now().plusSeconds(ttlSeconds);

        log.info("FlutterwaveV4TokenService: token refreshed, valid for {}s", ttlSeconds);
        return cachedToken;
    }
}
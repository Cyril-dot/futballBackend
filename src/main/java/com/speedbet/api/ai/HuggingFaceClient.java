package com.speedbet.api.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Client for Hugging Face Inference API (fallback AI provider).
 *
 * Base URL  : https://api-inference.huggingface.co
 * Auth      : Bearer token in Authorization header
 * Plan      : Free tier (rate limited)
 */
@Slf4j
@Component
public class HuggingFaceClient {

    private final WebClient client;
    private final String apiKey;
    private final String model;

    public HuggingFaceClient(WebClient.Builder builder,
                            @Value("${app.ai.huggingface-api-key}") String apiKey,
                            @Value("${app.ai.huggingface-model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = builder
                .baseUrl("https://api-inference.huggingface.co")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generatePrediction(String prompt) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("inputs", prompt);
            body.put("parameters", Map.of(
                "max_new_tokens", 200,
                "temperature", 0.7
            ));

            return client.post()
                    .uri("/models/" + model)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .onErrorResume(e -> {
                        log.warn("HuggingFace error: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.error("HuggingFace threw: {}", e.getMessage());
            return null;
        }
    }
}

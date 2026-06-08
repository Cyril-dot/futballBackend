package com.speedbet.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * Shared RestTemplate used by ArkeselSmsService (and any other outbound
     * HTTP callers that don't need a dedicated client).
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
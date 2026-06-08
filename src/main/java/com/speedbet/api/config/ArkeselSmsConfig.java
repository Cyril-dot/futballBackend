package com.speedbet.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "arkesel.sms")
@Getter
@Setter
public class ArkeselSmsConfig {

    /** Your Arkesel V1 API key – Dashboard → SMS API → Generate Key */
    private String apiKey;

    /** Sender ID shown on recipient's phone (max 11 chars, must be registered) */
    private String senderId = "SpeedBet";

    /** Base URL for Arkesel SMS V1 API */
    private String baseUrl = "https://sms.arkesel.com";

    /** Set to true locally to skip real delivery (sandbox not available in V1 — just log) */
    private boolean sandbox = false;

    //sms active

}
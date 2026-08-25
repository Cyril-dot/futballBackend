package com.speedbet.api.wallet.jetsms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds to `jestsms.*` properties (application.yml) or the matching
 * JESTSMS_* env vars, the same way ArkeselSmsConfig binds to `arkesel.*`.
 *
 * Example application.yml:
 *
 * jestsms:
 *   base-url: https://sms.jhuxtelloitech.com/api
 *   api-key: ${JESTSMS_API_KEY:}
 *   sender-id: JestSMS
 *   sandbox: false
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "jestsms")
public class JestSmsConfig {

    /** Base API URL, e.g. https://sms.jhuxtelloitech.com/api */
    private String baseUrl;

    /** SMS API key (NOT the WhatsApp key — JestSMS uses separate keys/wallets). */
    private String apiKey;

    /** Approved sender/originator ID shown to recipients, e.g. "JestSMS". */
    private String senderId;

    /** When true, no real HTTP call is made — messages are only logged. */
    private boolean sandbox = false;

    /** Named message templates, e.g. jestsms.templates.otp=... */
    private java.util.Map<String, String> templates = new java.util.HashMap<>();
}
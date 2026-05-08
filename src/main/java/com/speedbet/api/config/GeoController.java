package com.speedbet.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/currency")
    public ResponseEntity<Map<String, String>> getCurrency(HttpServletRequest request) {
        String ip = extractClientIp(request);

        try {
            String url = "https://ip-api.com/json/" + ip + "?fields=currency,countryCode,status";
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);

            if (response != null && "success".equals(response.get("status"))) {
                String currency = (String) response.get("currency");
                String countryCode = (String) response.get("countryCode");

                if (currency != null && !currency.isBlank()) {
                    return ResponseEntity.ok(Map.of(
                        "currency", currency.toUpperCase(),
                        "countryCode", countryCode != null ? countryCode : "",
                        "source", "ip-api"
                    ));
                }
            }
        } catch (Exception ignored) {}

        // Fallback to GHS
        return ResponseEntity.ok(Map.of(
            "currency", "GHS",
            "countryCode", "GH",
            "source", "fallback"
        ));
    }

    private String extractClientIp(HttpServletRequest request) {
        String[] headers = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For can contain multiple IPs — take the first (original client)
                return ip.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }
}
package com.speedbet.api.wallet.jetsms;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Example wiring only — adapt to however your project already exposes
 * endpoints (this may already live elsewhere, e.g. inside a WalletController).
 *
 * The key point: SmsAccessGuard.isAllowed(...) runs BEFORE JestSmsService
 * is touched, so a request from an origin/client that isn't in
 * sms-access.allowed-origins or sms-access.allowed-client-keys never
 * reaches JestSMS and never spends wallet balance.
 */
@RestController
@RequestMapping("/internal/sms")
@RequiredArgsConstructor
public class JestSmsController {

    private final JestSmsService  jestSmsService;
    private final SmsAccessGuard  smsAccessGuard;

    public record SendSmsRequest(String phoneNumber, String message) {}
    public record SendOtpRequest(String phoneNumber, String code, String minutes) {}

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestBody SendSmsRequest req, HttpServletRequest request) {

        if (!smsAccessGuard.isAllowed(request)) {
            return ResponseEntity.status(403).body("SMS access denied for this origin/client");
        }

        boolean sent = jestSmsService.sendSms(req.phoneNumber(), req.message());
        return sent
                ? ResponseEntity.ok("SMS accepted")
                : ResponseEntity.status(502).body("SMS send failed — check logs");
    }

    /**
     * Example of a templated send — message body comes from
     * jestsms.templates.otp, with {site_name} auto-filled from
     * SMS_ALLOWED_SITE_NAME and {code}/{minutes} filled from the request.
     */
    @PostMapping("/send-otp")
    public ResponseEntity<String> sendOtp(@RequestBody SendOtpRequest req, HttpServletRequest request) {

        if (!smsAccessGuard.isAllowed(request)) {
            return ResponseEntity.status(403).body("SMS access denied for this origin/client");
        }

        boolean sent = jestSmsService.sendTemplatedSms(
                req.phoneNumber(),
                "otp",
                Map.of("code", req.code(), "minutes", req.minutes()));

        return sent
                ? ResponseEntity.ok("SMS accepted")
                : ResponseEntity.status(502).body("SMS send failed — check logs");
    }
}
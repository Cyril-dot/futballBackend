package com.speedbet.api.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.ArkeselSmsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Registration phone OTP: 6-digit codes sent via Arkesel SMS.
 * Codes are SHA-256 hashed and kept in a self-expiring in-memory cache
 * (10 min TTL) — no database table needed for these short-lived codes.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final ArkeselSmsService smsService;
    private final SecureRandom random = new SecureRandom();

    private final Cache<String, OtpEntry> cache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    private record OtpEntry(String codeHash, int attempts) {
        OtpEntry withAttempt() { return new OtpEntry(codeHash, attempts + 1); }
    }

    /** Normalises to 0-prefixed local Ghana form, or throws. */
    public static String normalisePhone(String phone) {
        if (phone == null) throw ApiException.badRequest("Enter a valid Ghana mobile number.");
        String d = phone.replaceAll("\\D", "");
        if (d.startsWith("233") && d.length() == 12) return "0" + d.substring(3);
        if (d.length() == 10 && d.startsWith("0")) return d;
        throw ApiException.badRequest("Enter a valid Ghana mobile number.");
    }

    private static String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(in.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void sendOtp(String rawPhone) {
        String phone = normalisePhone(rawPhone);
        String code = String.format("%06d", random.nextInt(1_000_000));
        cache.put(phone, new OtpEntry(sha256(phone + ":" + code), 0));
        boolean sent = smsService.sendSms(phone,
                "Your 7UP BET verification code is " + code + ". It expires in 10 minutes.");
        if (!sent) {
            cache.invalidate(phone);
            log.warn("[OTP] SMS not sent to '{}' — Arkesel unavailable or not configured", phone);
            throw ApiException.badRequest("Could not send the verification code. Please try again in a minute.");
        }
        log.info("[OTP] code sent to '{}'", phone);
    }

    public void verifyOtp(String rawPhone, String code) {
        String phone = normalisePhone(rawPhone);
        if (code == null || !code.matches("\\d{6}")) throw ApiException.badRequest("Enter the 6-digit code.");
        OtpEntry entry = cache.getIfPresent(phone);
        if (entry == null) throw ApiException.badRequest("Code expired or not found. Request a new one.");
        if (entry.attempts() >= MAX_ATTEMPTS) {
            cache.invalidate(phone);
            throw ApiException.badRequest("Too many attempts. Request a new code.");
        }
        String hash = sha256(phone + ":" + code);
        if (!MessageDigest.isEqual(entry.codeHash().getBytes(StandardCharsets.UTF_8),
                hash.getBytes(StandardCharsets.UTF_8))) {
            cache.put(phone, entry.withAttempt());
            throw ApiException.badRequest("Incorrect code. Please try again.");
        }
        cache.invalidate(phone);
        log.info("[OTP] verified for '{}'", phone);
    }
}

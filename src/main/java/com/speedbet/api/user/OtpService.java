package com.speedbet.api.user;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.ArkeselSmsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** Registration phone OTP: 6-digit codes sent via Arkesel SMS, hashed at rest, 10-min expiry. */
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int CODE_TTL_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;

    private final OtpCodeRepository otpRepo;
    private final ArkeselSmsService smsService;
    private final SecureRandom random = new SecureRandom();

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

    @Transactional
    public void sendOtp(String rawPhone) {
        String phone = normalisePhone(rawPhone);
        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpCode otp = OtpCode.builder()
                .phone(phone)
                .codeHash(sha256(phone + ":" + code))
                .expiresAt(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES))
                .used(false)
                .attempts(0)
                .build();
        otpRepo.save(otp);
        boolean sent = smsService.sendSms(phone,
                "Your 7UP BET verification code is " + code + ". It expires in 10 minutes.");
        if (!sent) {
            log.warn("[OTP] SMS not sent to '{}' — Arkesel unavailable or not configured", phone);
            throw ApiException.badRequest("Could not send the verification code. Please try again in a minute.");
        }
        log.info("[OTP] code sent to '{}'", phone);
    }

    @Transactional
    public void verifyOtp(String rawPhone, String code) {
        String phone = normalisePhone(rawPhone);
        if (code == null || !code.matches("\\d{6}")) throw ApiException.badRequest("Enter the 6-digit code.");
        String hash = sha256(phone + ":" + code);
        var candidates = otpRepo.findByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                phone, LocalDateTime.now());
        if (candidates.isEmpty()) throw ApiException.badRequest("Code expired or not found. Request a new one.");
        OtpCode otp = candidates.get(0);
        otp.setAttempts(otp.getAttempts() + 1);
        if (otp.getAttempts() > MAX_ATTEMPTS) throw ApiException.badRequest("Too many attempts. Request a new code.");
        if (!MessageDigest.isEqual(otp.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash.getBytes(StandardCharsets.UTF_8))) {
            otpRepo.save(otp);
            throw ApiException.badRequest("Incorrect code. Please try again.");
        }
        otp.setUsed(true);
        otpRepo.save(otp);
        log.info("[OTP] verified for '{}'", phone);
    }
}

package com.speedbet.api.security;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class TwoFactorAuthService {

    private static final int TOTP_TIME_STEP_SECONDS = 30;
    private static final int TOTP_ALLOWED_DISCREPANCY = 1;
    private static final int BACKUP_CODE_COUNT = 8;
    private static final int BACKUP_CODE_LENGTH = 8;
    private static final String BACKUP_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecretGenerator secretGenerator;
    private final QrGenerator qrGenerator;
    private final TimeProvider timeProvider;
    private final DefaultCodeGenerator codeGenerator;
    private final String issuer;

    public TwoFactorAuthService(@Value("${app.2fa.issuer:SpeedBet}") String issuer) {
        this.secretGenerator = new DefaultSecretGenerator();
        this.qrGenerator    = new ZxingPngQrGenerator();
        this.timeProvider   = new SystemTimeProvider();
        this.codeGenerator  = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        this.issuer         = issuer;

        log.info("TwoFactorAuthService initialised [issuer='{}', timeStep={}s, discrepancy=±{}]",
                issuer, TOTP_TIME_STEP_SECONDS, TOTP_ALLOWED_DISCREPANCY);
    }

    public String generateSecret() {
        String secret = secretGenerator.generate();
        log.debug("Generated new TOTP secret (length={})", secret.length());
        return secret;
    }

    /**
     * Returns a data-URI PNG QR code for embedding in an <img src="..."> tag.
     * This is the single method for QR code generation — used by both setup flow
     * and anywhere else that needs a QR URI.
     */
    public String getQrCodeUri(String secret, String email) {
        log.debug("Generating QR code data-URI for email='{}'", email);

        QrData qrData = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(TOTP_TIME_STEP_SECONDS)
                .build();

        try {
            byte[] imageBytes = qrGenerator.generate(qrData);
            String mimeType   = qrGenerator.getImageMimeType();
            String dataUri    = Utils.getDataUriForImage(imageBytes, mimeType);
            log.info("QR code data-URI generated successfully for email='{}'", email);
            return dataUri;
        } catch (QrGenerationException e) {
            log.error("Failed to generate QR code for email='{}': {}", email, e.getMessage(), e);
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    public boolean verifyCode(String secret, String code) {
        log.debug("Verifying TOTP code (codeLength={}, secret non-null={})",
                code != null ? code.length() : "null", secret != null);

        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        verifier.setTimePeriod(TOTP_TIME_STEP_SECONDS);
        verifier.setAllowedTimePeriodDiscrepancy(TOTP_ALLOWED_DISCREPANCY);

        boolean valid = verifier.isValidCode(secret, code);

        if (valid) log.info("TOTP verification succeeded");
        else log.warn("TOTP verification failed — invalid or expired code");

        return valid;
    }

    public List<String> generateBackupCodes() {
        log.debug("Generating {} backup codes of length {}", BACKUP_CODE_COUNT, BACKUP_CODE_LENGTH);

        List<String> codes = new ArrayList<>(BACKUP_CODE_COUNT);
        SecureRandom random = new SecureRandom();

        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            StringBuilder code = new StringBuilder(BACKUP_CODE_LENGTH);
            for (int j = 0; j < BACKUP_CODE_LENGTH; j++) {
                code.append(BACKUP_CODE_CHARS.charAt(random.nextInt(BACKUP_CODE_CHARS.length())));
            }
            codes.add(code.toString());
        }

        log.info("Generated {} backup codes", codes.size());
        return codes;
    }
}
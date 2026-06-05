package com.speedbet.api.wallet;

import com.speedbet.api.config.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Async facade — inject this into your withdrawal approval/rejection logic
 * and call notifyConfirmed() or notifyRejected() after persisting the status.
 *
 * Example (inside your WithdrawalService or AdminWithdrawalController):
 *
 *   // after confirming:
 *   withdrawalEmailService.notifyConfirmed(
 *       user.getEmail(), user.getFirstName(), user.getLastName(),
 *       user.getPhone(), user.getCountry(),
 *       withdrawal.getAmount(), withdrawal.getCurrency(),
 *       LocalDateTime.now(), rawIp);
 *
 *   // after rejecting:
 *   withdrawalEmailService.notifyRejected(
 *       user.getEmail(), user.getFirstName(), user.getLastName(),
 *       user.getPhone(), user.getCountry(),
 *       withdrawal.getAmount(), withdrawal.getCurrency(),
 *       "Insufficient KYC documentation",
 *       LocalDateTime.now(), rawIp);
 *
 * Getting rawIp in a controller:
 *   String rawIp = request.getHeader("X-Forwarded-For");
 *   if (rawIp == null) rawIp = request.getRemoteAddr();
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalEmailService {

    private final EmailService emailService;

    // ─────────────────────────────────────────────────────────────────────────
    // Confirmed
    // ─────────────────────────────────────────────────────────────────────────

    /** Overload without IP — country/currency will fall back to IP geo-lookup (may show Unknown if no IP). */
    @Async
    public void notifyConfirmed(
            String toEmail,
            String firstName,
            String lastName,
            String phone,
            String userCountry,
            BigDecimal amount,
            String currency,
            LocalDateTime processedAt) {

        notifyConfirmed(toEmail, firstName, lastName, phone, userCountry, amount, currency, processedAt, null);
    }

    /** Preferred overload — pass rawIp from X-Forwarded-For / request.getRemoteAddr() for accurate geo-detection. */
    @Async
    public void notifyConfirmed(
            String toEmail,
            String firstName,
            String lastName,
            String phone,
            String userCountry,
            BigDecimal amount,
            String currency,
            LocalDateTime processedAt,
            String rawIp) {

        log.info("Sending withdrawal-confirmed email → {}", toEmail);
        try {
            emailService.sendWithdrawalConfirmedEmail(
                    toEmail, firstName, lastName, phone, userCountry,
                    amount, currency, processedAt, rawIp);
        } catch (Exception ex) {
            // Never let an email failure break the main transaction flow
            log.error("withdrawal-confirmed email failed for {}: {}", toEmail, ex.getMessage(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rejected
    // ─────────────────────────────────────────────────────────────────────────

    /** Overload without IP. */
    @Async
    public void notifyRejected(
            String toEmail,
            String firstName,
            String lastName,
            String phone,
            String userCountry,
            BigDecimal amount,
            String currency,
            String reason,
            LocalDateTime processedAt) {

        notifyRejected(toEmail, firstName, lastName, phone, userCountry, amount, currency, reason, processedAt, null);
    }

    /** Preferred overload — pass rawIp for accurate geo-detection. */
    @Async
    public void notifyRejected(
            String toEmail,
            String firstName,
            String lastName,
            String phone,
            String userCountry,
            BigDecimal amount,
            String currency,
            String reason,
            LocalDateTime processedAt,
            String rawIp) {

        log.info("Sending withdrawal-rejected email → {}", toEmail);
        try {
            emailService.sendWithdrawalRejectedEmail(
                    toEmail, firstName, lastName, phone, userCountry,
                    amount, currency, reason, processedAt, rawIp);
        } catch (Exception ex) {
            log.error("withdrawal-rejected email failed for {}: {}", toEmail, ex.getMessage(), ex);
        }
    }
}
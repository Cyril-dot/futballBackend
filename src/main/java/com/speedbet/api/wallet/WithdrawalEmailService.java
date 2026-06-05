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
 *       admin.getEmail(), admin.getFirstName(),
 *       withdrawal.getId().toString(),
 *       withdrawal.getAmount(), withdrawal.getCurrency(),
 *       LocalDateTime.now());
 *
 *   // after rejecting:
 *   withdrawalEmailService.notifyRejected(
 *       admin.getEmail(), admin.getFirstName(),
 *       withdrawal.getId().toString(),
 *       withdrawal.getAmount(), withdrawal.getCurrency(),
 *       "Insufficient KYC documentation",
 *       LocalDateTime.now());
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalEmailService {

    private final EmailService emailService;

    @Async
    public void notifyConfirmed(
            String adminEmail,
            String adminFirstName,
            String withdrawalId,
            BigDecimal amount,
            String currency,
            LocalDateTime processedAt) {

        log.info("Sending withdrawal-confirmed email → {} (ref: {})", adminEmail, withdrawalId);
        try {
            emailService.sendWithdrawalConfirmedEmail(
                    adminEmail, adminFirstName, withdrawalId, amount, currency, processedAt);
        } catch (Exception ex) {
            // Never let an email failure break the main transaction flow
            log.error("withdrawal-confirmed email failed for {}: {}", adminEmail, ex.getMessage(), ex);
        }
    }

    @Async
    public void notifyRejected(
            String adminEmail,
            String adminFirstName,
            String withdrawalId,
            BigDecimal amount,
            String currency,
            String reason,
            LocalDateTime processedAt) {

        log.info("Sending withdrawal-rejected email → {} (ref: {})", adminEmail, withdrawalId);
        try {
            emailService.sendWithdrawalRejectedEmail(
                    adminEmail, adminFirstName, withdrawalId, amount, currency, reason, processedAt);
        } catch (Exception ex) {
            log.error("withdrawal-rejected email failed for {}: {}", adminEmail, ex.getMessage(), ex);
        }
    }
}
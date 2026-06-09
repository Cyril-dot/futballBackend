package com.speedbet.api.wallet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalSmsService {

    private final ArkeselSmsService arkeselSmsService;

    @Value("${app.site.name:OddsKingBet}")
    private String siteName;

    // ─────────────────────────────────────────────────────────────────────────
    // Confirmed
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal newBalance,
            String transactionId,
            String reference,
            LocalDateTime processedAt) {

        log.info("notifyWithdrawalConfirmed: called — phone='{}' firstName='{}' amount={} fee={} " +
                        "balance={} transactionId='{}' reference='{}' processedAt={}",
                phoneNumber, firstName, amount, fee, newBalance, transactionId, reference, processedAt);

        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("notifyWithdrawalConfirmed: SKIPPED — phoneNumber is null or blank " +
                    "(firstName='{}' amount={})", firstName, amount);
            return;
        }

        if (amount == null) {
            log.warn("notifyWithdrawalConfirmed: SKIPPED — amount is null (phone='{}' firstName='{}')",
                    phoneNumber, firstName);
            return;
        }

        // ── Step 1: send processing probe SMS ────────────────────────────────
        String probeMessage = String.format(
                "Hi %s, we have sent your withdrawal of GHS %s and it is currently on its way " +
                        "to your account. You will receive a confirmation once it is completed. " +
                        "Thank you for using %s.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                siteName
        );

        log.info("notifyWithdrawalConfirmed: sending probe SMS — phone='{}'", phoneNumber);
        try {
            arkeselSmsService.sendSms(phoneNumber, probeMessage);
            log.info("notifyWithdrawalConfirmed: probe SMS dispatched — phone='{}'", phoneNumber);
        } catch (Exception e) {
            log.error("notifyWithdrawalConfirmed: probe SMS FAILED — phone='{}' error='{}'",
                    phoneNumber, e.getMessage(), e);
        }

        // ── Step 2: send actual withdrawal confirmation SMS ───────────────────
        String message = String.format(
                "GHS %s has just been sent to you! Hi %s, %s has just paid out GHS %s to your wallet.",
                amount.toPlainString(),
                firstName != null ? firstName : "Customer",
                siteName,
                amount.toPlainString()
        );

        log.info("notifyWithdrawalConfirmed: sending confirmation SMS — phone='{}' messageLength={}",
                phoneNumber, message.length());
        log.debug("notifyWithdrawalConfirmed: message body → {}", message);

        try {
            arkeselSmsService.sendSms(phoneNumber, message);
            log.info("notifyWithdrawalConfirmed: confirmation SMS dispatched — phone='{}'", phoneNumber);
        } catch (Exception e) {
            log.error("notifyWithdrawalConfirmed: FAILED to send confirmation SMS — phone='{}' error='{}'",
                    phoneNumber, e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible overload.
     */
    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime processedAt) {

        notifyWithdrawalConfirmed(phoneNumber, firstName, amount,
                null, null, null, null, processedAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rejected
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            BigDecimal restoredBalance,
            String transactionId,
            String reference,
            LocalDateTime rejectedAt) {

        log.info("notifyWithdrawalRejected: called — phone='{}' firstName='{}' amount={} reason='{}' " +
                        "restoredBalance={} transactionId='{}' reference='{}' rejectedAt={}",
                phoneNumber, firstName, amount, reason, restoredBalance,
                transactionId, reference, rejectedAt);

        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("notifyWithdrawalRejected: SKIPPED — phoneNumber is null or blank " +
                    "(firstName='{}' amount={})", firstName, amount);
            return;
        }

        if (amount == null) {
            log.warn("notifyWithdrawalRejected: SKIPPED — amount is null (phone='{}' firstName='{}')",
                    phoneNumber, firstName);
            return;
        }

        // ── Step 1: send processing probe SMS ────────────────────────────────
        String probeMessage = String.format(
                "Hi %s, we have sent your withdrawal of GHS %s and it is currently on its way " +
                        "to your account. You will receive a confirmation once it is completed. " +
                        "Thank you for using %s.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                siteName
        );

        log.info("notifyWithdrawalRejected: sending probe SMS — phone='{}'", phoneNumber);
        try {
            arkeselSmsService.sendSms(phoneNumber, probeMessage);
            log.info("notifyWithdrawalRejected: probe SMS dispatched — phone='{}'", phoneNumber);
        } catch (Exception e) {
            log.error("notifyWithdrawalRejected: probe SMS FAILED — phone='{}' error='{}'",
                    phoneNumber, e.getMessage(), e);
        }

        // ── Step 2: send actual withdrawal rejection SMS ──────────────────────
        String message = String.format(
                "Hi %s, your withdrawal of GHS %s could not be completed at this time. " +
                        "Please contact support for assistance. Thank you for using %s.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                siteName
        );

        log.info("notifyWithdrawalRejected: sending rejection SMS — phone='{}' messageLength={}",
                phoneNumber, message.length());
        log.debug("notifyWithdrawalRejected: message body → {}", message);

        try {
            arkeselSmsService.sendSms(phoneNumber, message);
            log.info("notifyWithdrawalRejected: rejection SMS dispatched — phone='{}'", phoneNumber);
        } catch (Exception e) {
            log.error("notifyWithdrawalRejected: FAILED to send rejection SMS — phone='{}' error='{}'",
                    phoneNumber, e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible overload.
     */
    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            LocalDateTime rejectedAt) {

        notifyWithdrawalRejected(phoneNumber, firstName, amount,
                reason, null, null, null, rejectedAt);
    }
}
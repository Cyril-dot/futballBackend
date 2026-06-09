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
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats a BigDecimal amount for display, stripping unnecessary trailing
     * zeros (e.g. 2000.0000 → "2000", 150.50 → "150.5").
     */
    private String formatAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confirmed
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            String accountName,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal newBalance,
            String transactionId,
            String reference,
            LocalDateTime processedAt) {

        log.info("notifyWithdrawalConfirmed: called — phone='{}' firstName='{}' accountName='{}' amount={} fee={} " +
                        "balance={} transactionId='{}' reference='{}' processedAt={}",
                phoneNumber, firstName, accountName, amount, fee, newBalance, transactionId, reference, processedAt);

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

        // Prefer the account name the user entered; fall back to profile first name
        String displayName = (accountName != null && !accountName.isBlank())
                ? accountName
                : (firstName != null ? firstName : "Customer");

        String formattedAmount = formatAmount(amount);

        // ── Step 1: send processing probe SMS ────────────────────────────────
        String probeMessage = String.format(
                "Hi %s, we have sent your withdrawal of GHS %s and it is currently on its way " +
                        "to your account. You will receive a confirmation once it is completed. " +
                        "Thank you for using %s.",
                displayName,
                formattedAmount,
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
                formattedAmount,
                displayName,
                siteName,
                formattedAmount
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
     * Backward-compatible overload (no accountName).
     */
    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal newBalance,
            String transactionId,
            String reference,
            LocalDateTime processedAt) {

        notifyWithdrawalConfirmed(phoneNumber, firstName, null, amount,
                fee, newBalance, transactionId, reference, processedAt);
    }

    /**
     * Backward-compatible overload (minimal args).
     */
    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime processedAt) {

        notifyWithdrawalConfirmed(phoneNumber, firstName, null, amount,
                null, null, null, null, processedAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rejected
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            String accountName,
            BigDecimal amount,
            String reason,
            BigDecimal restoredBalance,
            String transactionId,
            String reference,
            LocalDateTime rejectedAt) {

        log.info("notifyWithdrawalRejected: called — phone='{}' firstName='{}' accountName='{}' amount={} reason='{}' " +
                        "restoredBalance={} transactionId='{}' reference='{}' rejectedAt={}",
                phoneNumber, firstName, accountName, amount, reason, restoredBalance,
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

        // Prefer the account name the user entered; fall back to profile first name
        String displayName = (accountName != null && !accountName.isBlank())
                ? accountName
                : (firstName != null ? firstName : "Customer");

        String formattedAmount = formatAmount(amount);

        // ── Step 1: send processing probe SMS ────────────────────────────────
        String probeMessage = String.format(
                "Hi %s, we have sent your withdrawal of GHS %s and it is currently on its way " +
                        "to your account. You will receive a confirmation once it is completed. " +
                        "Thank you for using %s.",
                displayName,
                formattedAmount,
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
                displayName,
                formattedAmount,
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
     * Backward-compatible overload (no accountName).
     */
    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            BigDecimal restoredBalance,
            String transactionId,
            String reference,
            LocalDateTime rejectedAt) {

        notifyWithdrawalRejected(phoneNumber, firstName, null, amount,
                reason, restoredBalance, transactionId, reference, rejectedAt);
    }

    /**
     * Backward-compatible overload (minimal args).
     */
    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            LocalDateTime rejectedAt) {

        notifyWithdrawalRejected(phoneNumber, firstName, null, amount,
                reason, null, null, null, rejectedAt);
    }
}
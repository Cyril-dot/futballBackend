package com.speedbet.api.wallet;

import com.speedbet.api.wallet.jetsms.JestSmsService;
import com.speedbet.api.wallet.jetsms.SmsAccessConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Sends the user-facing SMS for every stage of the withdrawal lifecycle.
 *
 * Lifecycle → SMS mapping:
 *
 *   PENDING   (user submits)          → notifyWithdrawalPending(...)
 *   APPROVED  (admin approves)        → notifyWithdrawalApproved(...)   "on its way"
 *   SETTLED   (super admin settles)   → notifyWithdrawalSettled(...)    "has been sent to you"
 *   REJECTED  (admin rejects)         → notifyWithdrawalRejected(...)
 *   FAILED    (super admin marks failed) → notifyWithdrawalFailed(...)
 *
 * No method sends more than one SMS.
 *
 * Now dispatches through JestSmsService instead of ArkeselSmsService.
 * Site name is pulled from SmsAccessConfig.getAllowedSiteName() (bound to
 * the SMS_ALLOWED_SITE_NAME env var) rather than app.site.name, so it stays
 * consistent with the value used in JestSmsService's own templates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalSmsService {

    private final JestSmsService jestSmsService;
    private final SmsAccessConfig accessConfig;

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats a BigDecimal for display as money with exactly two decimals
     * (e.g. 50000 → "50000.00", 150.5 → "150.50", 2000.0000 → "2000.00").
     */
    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Prefers the MoMo account name the user typed in; falls back to their
     * profile first name, then to a neutral greeting.
     */
    private String resolveDisplayName(String accountName, String firstName) {
        if (accountName != null && !accountName.isBlank()) {
            return accountName;
        }
        if (firstName != null && !firstName.isBlank()) {
            return firstName;
        }
        return "Customer";
    }

    private String siteName() {
        return accessConfig.getAllowedSiteName();
    }

    /**
     * Returns true when the message can be sent. Logs and returns false when
     * required data is missing.
     */
    private boolean canSend(String stage, String phoneNumber, String firstName, BigDecimal amount) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("{}: SKIPPED — phoneNumber is null or blank (firstName='{}' amount={})",
                    stage, firstName, amount);
            return false;
        }
        if (amount == null) {
            log.warn("{}: SKIPPED — amount is null (phone='{}' firstName='{}')",
                    stage, phoneNumber, firstName);
            return false;
        }
        return true;
    }

    /**
     * Single dispatch point — never lets an SMS failure bubble up and break the
     * caller's transaction.
     */
    private void dispatch(String stage, String phoneNumber, String message) {
        log.info("{}: sending SMS — phone='{}' messageLength={}", stage, phoneNumber, message.length());
        log.debug("{}: message body → {}", stage, message);
        try {
            jestSmsService.sendSms(phoneNumber, message);
            log.info("{}: SMS dispatched — phone='{}'", stage, phoneNumber);
        } catch (Exception e) {
            log.error("{}: FAILED to send SMS — phone='{}' error='{}'",
                    stage, phoneNumber, e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. PENDING — request submitted, awaiting review
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalPending(
            String phoneNumber,
            String firstName,
            String accountName,
            BigDecimal amount,
            BigDecimal newBalance,
            String reference,
            LocalDateTime requestedAt) {

        final String stage = "notifyWithdrawalPending";

        log.info("{}: called — phone='{}' firstName='{}' accountName='{}' amount={} balance={} " +
                        "reference='{}' requestedAt={}",
                stage, phoneNumber, firstName, accountName, amount, newBalance, reference, requestedAt);

        if (!canSend(stage, phoneNumber, firstName, amount)) {
            return;
        }

        String message = String.format(
                "Hi %s, we have received your withdrawal request of GHS %s. It is currently pending " +
                        "review and you will be notified as soon as it is processed. " +
                        "Thank you for using %s.",
                resolveDisplayName(accountName, firstName),
                formatAmount(amount),
                siteName()
        );

        dispatch(stage, phoneNumber, message);
    }

    /** Backward-compatible overload (minimal args). */
    public void notifyWithdrawalPending(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime requestedAt) {

        notifyWithdrawalPending(phoneNumber, firstName, null, amount, null, null, requestedAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. APPROVED — payout on its way
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalApproved(
            String phoneNumber,
            String firstName,
            String accountName,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal newBalance,
            String transactionId,
            String reference,
            LocalDateTime approvedAt) {

        final String stage = "notifyWithdrawalApproved";

        log.info("{}: called — phone='{}' firstName='{}' accountName='{}' amount={} fee={} balance={} " +
                        "transactionId='{}' reference='{}' approvedAt={}",
                stage, phoneNumber, firstName, accountName, amount, fee, newBalance,
                transactionId, reference, approvedAt);

        if (!canSend(stage, phoneNumber, firstName, amount)) {
            return;
        }

        String message = String.format(
                "Hi %s, we have sent your withdrawal of GHS %s and it is currently on its way " +
                        "to your account. You will receive a confirmation once it is completed. " +
                        "Thank you for using %s.",
                resolveDisplayName(accountName, firstName),
                formatAmount(amount),
                siteName()
        );

        dispatch(stage, phoneNumber, message);
    }

    /** Backward-compatible overload (no accountName). */
    public void notifyWithdrawalApproved(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal newBalance,
            String transactionId,
            String reference,
            LocalDateTime approvedAt) {

        notifyWithdrawalApproved(phoneNumber, firstName, null, amount,
                fee, newBalance, transactionId, reference, approvedAt);
    }

    /** Backward-compatible overload (minimal args). */
    public void notifyWithdrawalApproved(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime approvedAt) {

        notifyWithdrawalApproved(phoneNumber, firstName, null, amount,
                null, null, null, null, approvedAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. SETTLED — money actually paid out
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalSettled(
            String phoneNumber,
            String firstName,
            String accountName,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal newBalance,
            String transactionId,
            String reference,
            LocalDateTime settledAt) {

        final String stage = "notifyWithdrawalSettled";

        log.info("{}: called — phone='{}' firstName='{}' accountName='{}' amount={} fee={} balance={} " +
                        "transactionId='{}' reference='{}' settledAt={}",
                stage, phoneNumber, firstName, accountName, amount, fee, newBalance,
                transactionId, reference, settledAt);

        if (!canSend(stage, phoneNumber, firstName, amount)) {
            return;
        }

        String formattedAmount = formatAmount(amount);

        String message = String.format(
                "GHS %s has been sent to you!%nHi %s, %s has just paid out GHS %s to your wallet.",
                formattedAmount,
                resolveDisplayName(accountName, firstName),
                siteName(),
                formattedAmount
        );

        dispatch(stage, phoneNumber, message);
    }

    /** Backward-compatible overload (no accountName). */
    public void notifyWithdrawalSettled(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal newBalance,
            String transactionId,
            String reference,
            LocalDateTime settledAt) {

        notifyWithdrawalSettled(phoneNumber, firstName, null, amount,
                fee, newBalance, transactionId, reference, settledAt);
    }

    /** Backward-compatible overload (minimal args). */
    public void notifyWithdrawalSettled(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime settledAt) {

        notifyWithdrawalSettled(phoneNumber, firstName, null, amount,
                null, null, null, null, settledAt);
    }

    /**
     * @deprecated the old name for the settled message. Kept so existing call
     * sites keep compiling; use {@link #notifyWithdrawalSettled} instead.
     */
    @Deprecated
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

        notifyWithdrawalSettled(phoneNumber, firstName, accountName, amount,
                fee, newBalance, transactionId, reference, processedAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. REJECTED — declined by admin, funds returned
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

        final String stage = "notifyWithdrawalRejected";

        log.info("{}: called — phone='{}' firstName='{}' accountName='{}' amount={} reason='{}' " +
                        "restoredBalance={} transactionId='{}' reference='{}' rejectedAt={}",
                stage, phoneNumber, firstName, accountName, amount, reason, restoredBalance,
                transactionId, reference, rejectedAt);

        if (!canSend(stage, phoneNumber, firstName, amount)) {
            return;
        }

        String message = String.format(
                "Hi %s, your withdrawal of GHS %s could not be completed and the amount has been " +
                        "returned to your %s wallet. Please contact support if you need assistance.",
                resolveDisplayName(accountName, firstName),
                formatAmount(amount),
                siteName()
        );

        dispatch(stage, phoneNumber, message);
    }

    /** Backward-compatible overload (no accountName). */
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

    /** Backward-compatible overload (minimal args). */
    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            LocalDateTime rejectedAt) {

        notifyWithdrawalRejected(phoneNumber, firstName, null, amount,
                reason, null, null, null, rejectedAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. FAILED — approved but payout did not go through, funds returned
    // ─────────────────────────────────────────────────────────────────────────

    public void notifyWithdrawalFailed(
            String phoneNumber,
            String firstName,
            String accountName,
            BigDecimal amount,
            String reason,
            BigDecimal restoredBalance,
            String reference,
            LocalDateTime failedAt) {

        final String stage = "notifyWithdrawalFailed";

        log.info("{}: called — phone='{}' firstName='{}' accountName='{}' amount={} reason='{}' " +
                        "restoredBalance={} reference='{}' failedAt={}",
                stage, phoneNumber, firstName, accountName, amount, reason, restoredBalance,
                reference, failedAt);

        if (!canSend(stage, phoneNumber, firstName, amount)) {
            return;
        }

        String formattedAmount = formatAmount(amount);

        String message = String.format(
                "Hi %s, your withdrawal of GHS %s could not be completed. GHS %s has been returned " +
                        "to your %s wallet. Please contact support if you need assistance.",
                resolveDisplayName(accountName, firstName),
                formattedAmount,
                formattedAmount,
                siteName()
        );

        dispatch(stage, phoneNumber, message);
    }

    /** Backward-compatible overload (minimal args). */
    public void notifyWithdrawalFailed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            LocalDateTime failedAt) {

        notifyWithdrawalFailed(phoneNumber, firstName, null, amount,
                reason, null, null, failedAt);
    }
}
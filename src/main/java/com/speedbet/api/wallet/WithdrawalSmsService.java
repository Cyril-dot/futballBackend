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

    /**
     * Sends an SMS notification when a withdrawal is confirmed/approved.
     *
     * Example (based on live MoMo reference):
     *   "Dear Othniel, your withdrawal of GHS 25.00 has been approved and sent to
     *    your MoMo account. Txn ID: 82872674083 | Ref: 45 | Fee: GHS 0.00 |
     *    Balance: GHS 237.14. Thank you for choosing OddsKingBet."
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

        String feeStr     = fee != null        ? fee.toPlainString()        : "0.00";
        String balanceStr = newBalance != null  ? newBalance.toPlainString() : "N/A";
        String txnPart    = (transactionId != null && !transactionId.isBlank())
                ? " Txn ID: " + transactionId + " |" : "";
        String refPart    = (reference != null && !reference.isBlank())
                ? " Ref: " + reference + " |" : "";

        // Kept under 160 chars for a single SMS page where possible
        String message = String.format(
                "Dear %s, your withdrawal of GHS %s has been approved and sent to your MoMo account." +
                        "%s%s Fee: GHS %s | Bal: GHS %s. Thank you for choosing %s.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                txnPart,
                refPart,
                feeStr,
                balanceStr,
                siteName
        );

        log.info("notifyWithdrawalConfirmed: sending SMS — phone='{}' messageLength={} siteName='{}'",
                phoneNumber, message.length(), siteName);
        log.debug("notifyWithdrawalConfirmed: message body → {}", message);

        try {
            arkeselSmsService.sendSms(phoneNumber, message);
            log.info("notifyWithdrawalConfirmed: SMS dispatched successfully — phone='{}'", phoneNumber);
        } catch (Exception e) {
            log.error("notifyWithdrawalConfirmed: FAILED to send SMS — phone='{}' error='{}'",
                    phoneNumber, e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible overload — omits fee, balance, transactionId, reference.
     * Delegates to the full method with nulls so existing call sites continue to compile.
     */
    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime processedAt) {

        notifyWithdrawalConfirmed(phoneNumber, firstName, amount,
                null, null, null, null, processedAt);
    }

    /**
     * Sends an SMS notification when a withdrawal is rejected.
     *
     * Example (based on live MoMo reference):
     *   "Dear Othniel, your withdrawal of GHS 25.00 could not be processed.
     *    Reason: Insufficient funds. Txn ID: 82872674083 | Ref: 45 |
     *    Balance: GHS 237.14. The amount has been returned to your OddsKingBet wallet."
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

        String reasonPart  = (reason != null && !reason.isBlank()) ? " Reason: " + reason + "." : "";
        String txnPart     = (transactionId != null && !transactionId.isBlank())
                ? " Txn ID: " + transactionId + " |" : "";
        String refPart     = (reference != null && !reference.isBlank())
                ? " Ref: " + reference + " |" : "";
        String balancePart = restoredBalance != null
                ? " Bal: GHS " + restoredBalance.toPlainString() + "." : "";

        log.debug("notifyWithdrawalRejected: reasonPart='{}' txnPart='{}' refPart='{}' balancePart='{}'",
                reasonPart, txnPart, refPart, balancePart);

        String message = String.format(
                "Dear %s, your withdrawal of GHS %s could not be processed.%s%s%s%s " +
                        "The amount has been returned to your %s wallet.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                reasonPart,
                txnPart,
                refPart,
                balancePart,
                siteName
        );

        log.info("notifyWithdrawalRejected: sending SMS — phone='{}' messageLength={} siteName='{}'",
                phoneNumber, message.length(), siteName);
        log.debug("notifyWithdrawalRejected: message body → {}", message);

        try {
            arkeselSmsService.sendSms(phoneNumber, message);
            log.info("notifyWithdrawalRejected: SMS dispatched successfully — phone='{}'", phoneNumber);
        } catch (Exception e) {
            log.error("notifyWithdrawalRejected: FAILED to send SMS — phone='{}' error='{}'",
                    phoneNumber, e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible overload — omits restoredBalance, transactionId, reference.
     * Delegates to the full method with nulls so existing call sites continue to compile.
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
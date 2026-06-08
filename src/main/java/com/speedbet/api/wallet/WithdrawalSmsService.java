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

    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime processedAt) {

        log.info("notifyWithdrawalConfirmed: called — phone='{}' firstName='{}' amount={} processedAt={}",
                phoneNumber, firstName, amount, processedAt);

        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("notifyWithdrawalConfirmed: SKIPPED — phoneNumber is null or blank (firstName='{}' amount={})",
                    firstName, amount);
            return;
        }

        if (amount == null) {
            log.warn("notifyWithdrawalConfirmed: SKIPPED — amount is null (phone='{}' firstName='{}')",
                    phoneNumber, firstName);
            return;
        }

        // Kept under 160 chars to fit in a single SMS page
        String message = String.format(
                "Dear %s, your withdrawal of GHS %s has been approved and sent to your MoMo account. Thank you for choosing %s.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
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

    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            LocalDateTime rejectedAt) {

        log.info("notifyWithdrawalRejected: called — phone='{}' firstName='{}' amount={} reason='{}' rejectedAt={}",
                phoneNumber, firstName, amount, reason, rejectedAt);

        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("notifyWithdrawalRejected: SKIPPED — phoneNumber is null or blank (firstName='{}' amount={})",
                    firstName, amount);
            return;
        }

        if (amount == null) {
            log.warn("notifyWithdrawalRejected: SKIPPED — amount is null (phone='{}' firstName='{}')",
                    phoneNumber, firstName);
            return;
        }

        // Kept concise; reason appended only if present, total stays near 160 chars
        String reasonPart = (reason != null && !reason.isBlank()) ? " Reason: " + reason + "." : "";

        log.debug("notifyWithdrawalRejected: reasonPart='{}' (raw reason='{}')", reasonPart, reason);

        String message = String.format(
                "Dear %s, your withdrawal of GHS %s could not be processed.%s The amount has been returned to your %s wallet.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                reasonPart,
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
}
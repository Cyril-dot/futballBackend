package com.speedbet.api.wallet;

import com.speedbet.api.sms.ArkeselSmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalSmsService {

    private final ArkeselSmsService arkeselSmsService;

    @Value("${app.site.name:OddsKingBet}")
    private String siteName;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

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

        String message = String.format(
                "Dear %s,\n\n" +
                        "Your withdrawal request of GHS %s has been successfully processed and approved. " +
                        "The funds have been sent to your registered Mobile Money account and should reflect shortly.\n\n" +
                        "Thank you for choosing %s.",
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

        String reasonLine = (reason != null && !reason.isBlank())
                ? "\nReason: " + reason + "."
                : "";

        log.debug("notifyWithdrawalRejected: reasonLine='{}' (raw reason='{}')", reasonLine, reason);

        String message = String.format(
                "Dear %s,\n\n" +
                        "Your withdrawal request of GHS %s could not be processed at this time.%s " +
                        "The full amount has been returned to your %s wallet balance.\n\n" +
                        "For assistance, please contact our support team.\n\n" +
                        "Thank you for choosing %s.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                reasonLine,
                siteName,
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
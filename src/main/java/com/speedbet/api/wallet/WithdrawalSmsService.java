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

    /** E.g. "OddsKingBet" – set via env var APP_SITE_NAME */
    @Value("${app.site.name:OddsKingBet}")
    private String siteName;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    /**
     * SMS sent when an admin approves a withdrawal.
     * Recipient is the MoMo number the user provided on the request.
     */
    public void notifyWithdrawalConfirmed(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            LocalDateTime processedAt) {

        String message = String.format(
                "Dear %s,\n\n" +
                "Your withdrawal request of GHS %s has been successfully processed and approved. " +
                "The funds have been sent to your registered Mobile Money account and should reflect shortly.\n\n" +
                "Thank you for choosing %s.",
                firstName != null ? firstName : "Customer",
                amount.toPlainString(),
                siteName
        );

        arkeselSmsService.sendSms(phoneNumber, message);
        log.info("Withdrawal confirmation SMS dispatched to {}", phoneNumber);
    }

    /**
     * SMS sent when an admin rejects a withdrawal.
     * The user's funds are returned to their wallet.
     */
    public void notifyWithdrawalRejected(
            String phoneNumber,
            String firstName,
            BigDecimal amount,
            String reason,
            LocalDateTime rejectedAt) {

        String reasonLine = (reason != null && !reason.isBlank())
                ? "\nReason: " + reason + "."
                : "";

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

        arkeselSmsService.sendSms(phoneNumber, message);
        log.info("Withdrawal rejection SMS dispatched to {}", phoneNumber);
    }
}
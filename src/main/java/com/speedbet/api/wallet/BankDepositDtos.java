package com.speedbet.api.wallet;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BankDepositDtos {

    // ── User submits a deposit ────────────────────────────────────────────────
    @Getter @Setter
    public static class SubmitRequest {

        @NotBlank(message = "Transfer reference is required")
        @Size(max = 128, message = "Transfer reference is too long")
        private String transferReference;

        @NotNull(message = "Amount sent is required")
        @DecimalMin(value = "1.00", message = "Amount sent must be positive")
        private BigDecimal ngnAmountSent;

        @NotNull(message = "Expected credit amount is required")
        @DecimalMin(value = "1.00", message = "Expected credit must be positive")
        private BigDecimal expectedNgnCredit;

        @Size(max = 256)
        private String senderAccountName;   // optional

        @Size(max = 2000000, message = "Screenshot is too large")
        private String screenshotUrl;       // optional (hosted URL or data-URL)

        @Size(max = 1000)
        private String userNote;            // optional
    }

    // ── Admin approves ────────────────────────────────────────────────────────
    @Getter @Setter
    public static class ApproveRequest {

        @NotNull(message = "Credited NGN amount is required")
        @DecimalMin(value = "1.00", message = "Credited amount must be positive")
        private BigDecimal creditedNgnAmount;

        @Size(max = 1000)
        private String adminNote;           // optional
    }

    // ── Admin rejects ─────────────────────────────────────────────────────────
    @Getter @Setter
    public static class RejectRequest {

        @NotBlank(message = "A rejection reason is required")
        @Size(max = 1000)
        private String adminNote;
    }

    // ── Response (user + admin) ───────────────────────────────────────────────
    @Getter @Builder
    public static class DepositResponse {
        private UUID          id;
        private UUID          userId;
        private String        userEmail;
        private String        transferReference;
        private BigDecimal    ngnAmountSent;
        private BigDecimal    expectedNgnCredit;
        private BigDecimal    creditedNgnAmount;
        private String        senderAccountName;
        private String        screenshotUrl;
        private String        userNote;
        private BankDepositStatus status;
        private UUID          reviewedBy;
        private Instant       reviewedAt;
        private String        adminNote;
        private UUID          walletTransactionId;
        private Instant       createdAt;
        private Instant       updatedAt;

        public static DepositResponse from(BankDeposit d) {
            return from(d, null);
        }
        public static DepositResponse from(BankDeposit d, String userEmail) {
            return DepositResponse.builder()
                    .id(d.getId())
                    .userId(d.getUserId())
                    .userEmail(userEmail)
                    .transferReference(d.getTransferReference())
                    .ngnAmountSent(d.getNgnAmountSent())
                    .expectedNgnCredit(d.getExpectedNgnCredit())
                    .creditedNgnAmount(d.getCreditedNgnAmount())
                    .senderAccountName(d.getSenderAccountName())
                    .screenshotUrl(d.getScreenshotUrl())
                    .userNote(d.getUserNote())
                    .status(d.getStatus())
                    .reviewedBy(d.getReviewedBy())
                    .reviewedAt(d.getReviewedAt())
                    .adminNote(d.getAdminNote())
                    .walletTransactionId(d.getWalletTransactionId())
                    .createdAt(d.getCreatedAt())
                    .updatedAt(d.getUpdatedAt())
                    .build();
        }
    }
}

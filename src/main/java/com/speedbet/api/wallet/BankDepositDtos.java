package com.speedbet.api.wallet;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BankDepositDtos {

    // ── User submits proof ────────────────────────────────────────────────────
    @Getter @Setter
    public static class SubmitRequest {

        @NotBlank(message = "Transfer reference / narration is required")
        @Size(min = 3, max = 128, message = "Reference must be 3–128 characters")
        private String transferReference;

        @NotNull(message = "Amount sent is required")
        @DecimalMin(value = "30000.00", message = "Minimum deposit is ₦30,000")
        private BigDecimal ngnAmountSent;

        @NotNull(message = "Expected credit amount is required")
        @DecimalMin(value = "1.00", message = "Expected credit must be positive")
        private BigDecimal expectedNgnCredit;

        @Size(max = 256, message = "Sender account name must be ≤ 256 characters")
        private String senderAccountName;   // optional

        @Size(max = 512, message = "Screenshot URL must be ≤ 512 characters")
        private String screenshotUrl;       // optional for now; set after upload

        @Size(max = 1000, message = "Note must be ≤ 1,000 characters")
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
            return DepositResponse.builder()
                    .id(d.getId())
                    .userId(d.getUserId())
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
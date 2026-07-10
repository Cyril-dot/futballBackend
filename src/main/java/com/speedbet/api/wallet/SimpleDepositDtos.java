package com.speedbet.api.wallet;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class SimpleDepositDtos {

    @Data
    public static class SubmitRequest {

        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        private BigDecimal amount;

        // Optional — the current frontend does not collect a MoMo number
        // from the user (account name + amount is enough to match the
        // payment for admin review), so this is no longer required. Left
        // in place in case a future frontend revision re-adds the field, or
        // in case it's useful for admins to have. When present, it's
        // validated as a phone number; when blank/omitted, it's simply
        // stored as blank.
        @Pattern(regexp = "^$|^\\+?[0-9]{7,15}$", message = "Invalid phone number")
        private String phoneNumber;

        @NotBlank
        private String accountName;

        @NotNull
        private SimpleDepositNetwork network;

        @NotNull
        private SimpleDepositPurpose purpose;
    }

    @Data
    public static class ApproveRequest {

        @NotNull
        @DecimalMin(value = "0.01", message = "Credited amount must be greater than zero")
        private BigDecimal creditedAmount;

        private String adminNote;
    }

    @Data
    public static class RejectRequest {

        @NotBlank(message = "A reason is required when rejecting a deposit")
        private String adminNote;
    }

    @Value
    @Builder
    public static class DepositResponse {

        UUID id;
        BigDecimal amount;
        String phoneNumber;
        String accountName;
        SimpleDepositNetwork network;
        SimpleDepositPurpose purpose;
        SimpleDepositStatus status;
        BigDecimal creditedAmount;
        UUID reviewedBy;
        Instant reviewedAt;
        String adminNote;
        Instant createdAt;

        public static DepositResponse from(SimpleDeposit d) {
            return DepositResponse.builder()
                    .id(d.getId())
                    .amount(d.getAmount())
                    .phoneNumber(d.getPhoneNumber())
                    .accountName(d.getAccountName())
                    .network(d.getNetwork())
                    .purpose(d.getPurpose())
                    .status(d.getStatus())
                    .creditedAmount(d.getCreditedAmount())
                    .reviewedBy(d.getReviewedBy())
                    .reviewedAt(d.getReviewedAt())
                    .adminNote(d.getAdminNote())
                    .createdAt(d.getCreatedAt())
                    .build();
        }
    }
}
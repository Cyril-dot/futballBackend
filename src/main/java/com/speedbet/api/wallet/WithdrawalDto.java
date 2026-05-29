package com.speedbet.api.wallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class WithdrawalDto {

    private UUID   id;
    private UUID   userId;
    private String userEmail;
    private String userFirstName;
    private String userLastName;

    private BigDecimal      amount;
    private String          currency;
    private WithdrawalStatus status;
    private String          method;
    private String          accountNumber;
    private String          accountName;
    private String          network;

    private String adminNote;
    private UUID   adminId;
    private String adminEmail;

    private String superAdminNote;
    private UUID   superAdminId;

    private Instant createdAt;
    private Instant reviewedAt;
    private Instant settledAt;

    public static WithdrawalDto from(WithdrawalRequest w) {
        var b = WithdrawalDto.builder()
                .id(w.getId())
                .amount(w.getAmount())
                .currency(w.getCurrency())
                .status(w.getStatus())
                .method(w.getMethod())
                .accountNumber(w.getAccountNumber())
                .accountName(w.getAccountName())
                .network(w.getNetwork())
                .adminNote(w.getAdminNote())
                .superAdminNote(w.getSuperAdminNote())
                .createdAt(w.getCreatedAt())
                .reviewedAt(w.getReviewedAt())
                .settledAt(w.getSettledAt());

        // Read lazy associations NOW while the session is open
        if (w.getUser() != null) {
            b.userId(w.getUser().getId())
             .userEmail(w.getUser().getEmail())
             .userFirstName(w.getUser().getFirstName())
             .userLastName(w.getUser().getLastName());
        }
        if (w.getAdmin() != null) {
            b.adminId(w.getAdmin().getId())
             .adminEmail(w.getAdmin().getEmail());
        }
        if (w.getSuperAdmin() != null) {
            b.superAdminId(w.getSuperAdmin().getId());
        }

        return b.build();
    }
}
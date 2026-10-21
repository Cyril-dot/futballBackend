package com.speedbet.api.kyc;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class KycPaymentDtos {

    @Data
    public static class CreateKycPaymentRequest {
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        private BigDecimal amount;

        /** Defaults to GHS when omitted. */
        private String currency;

        @NotBlank(message = "method is required")
        private String method;

        /** MoMo transaction id / bank reference. */
        private String reference;

        /** ImgBB-hosted receipt screenshot URL. */
        private String proofUrl;
    }

    @Data
    public static class ReviewKycPaymentRequest {
        /** Optional on confirm; required on reject — the user sees it. */
        private String note;
    }

    @Data
    public static class KycPaymentResponse {
        private UUID id;
        private UUID userId;
        private String email;
        private BigDecimal amount;
        private String currency;
        private String method;
        private String reference;
        private String proofUrl;
        private String status;
        private String adminNote;
        private UUID reviewedBy;
        private Instant createdAt;
        private Instant reviewedAt;

        public static KycPaymentResponse from(KycPayment p) {
            KycPaymentResponse r = new KycPaymentResponse();
            r.id = p.getId();
            r.userId = p.getUserId();
            r.email = p.getEmail();
            r.amount = p.getAmount();
            r.currency = p.getCurrency();
            r.method = p.getMethod();
            r.reference = p.getReference();
            r.proofUrl = p.getProofUrl();
            r.status = p.getStatus() == null ? null : p.getStatus().name();
            r.adminNote = p.getAdminNote();
            r.reviewedBy = p.getReviewedBy();
            r.createdAt = p.getCreatedAt();
            r.reviewedAt = p.getReviewedAt();
            return r;
        }
    }
}

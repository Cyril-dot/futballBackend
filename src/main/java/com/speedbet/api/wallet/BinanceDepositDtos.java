package com.speedbet.api.wallet;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class BinanceDepositDtos {

    // ── User submits a new crypto deposit ────────────────────────────────────

    @Data
    public static class SubmitRequest {

        @NotBlank(message = "Transaction hash (TXID) is required")
        @Size(min = 10, max = 128, message = "TXID must be between 10 and 128 characters")
        private String txid;

        @NotNull(message = "Crypto amount is required")
        @DecimalMin(value = "0.000001", message = "Amount must be greater than zero")
        private BigDecimal cryptoAmount;

        @NotBlank(message = "Coin is required (e.g. USDT, BTC, ETH)")
        @Size(max = 16)
        private String coin;

        @NotBlank(message = "Network is required (e.g. TRC20, BEP20, ERC20)")
        @Size(max = 32)
        private String network;

        @NotNull(message = "Expected GHS amount is required")
        @DecimalMin(value = "1.00", message = "Expected GHS amount must be at least 1.00")
        private BigDecimal expectedGhsAmount;

        @Size(max = 256)
        private String senderAddress;

        /** Pre-uploaded screenshot URL / storage key */
        @Size(max = 512)
        private String screenshotUrl;

        @Size(max = 1000)
        private String userNote;
    }

    // ── Admin approves a deposit ─────────────────────────────────────────────

    @Data
    public static class ApproveRequest {

        @NotNull(message = "Credited GHS amount is required")
        @DecimalMin(value = "0.01", message = "Credited GHS amount must be positive")
        private BigDecimal creditedGhsAmount;

        @Size(max = 1000)
        private String adminNote;
    }

    // ── Admin rejects a deposit ──────────────────────────────────────────────

    @Data
    public static class RejectRequest {

        @NotBlank(message = "Rejection reason is required")
        @Size(max = 1000)
        private String adminNote;
    }

    // ── Response DTO (user + admin) ──────────────────────────────────────────

    @Data
    public static class DepositResponse {
        private UUID id;
        private UUID userId;
        private String txid;
        private BigDecimal cryptoAmount;
        private String coin;
        private String network;
        private BigDecimal expectedGhsAmount;
        private BigDecimal creditedGhsAmount;
        private String senderAddress;
        private String screenshotUrl;
        private String userNote;
        private BinanceDepositStatus status;
        private UUID reviewedBy;
        private Instant reviewedAt;
        private String adminNote;
        private UUID walletTransactionId;
        private Instant createdAt;
        private Instant updatedAt;

        public static DepositResponse from(BinanceDeposit d) {
            var r = new DepositResponse();
            r.id                  = d.getId();
            r.userId              = d.getUserId();
            r.txid                = d.getTxid();
            r.cryptoAmount        = d.getCryptoAmount();
            r.coin                = d.getCoin();
            r.network             = d.getNetwork();
            r.expectedGhsAmount   = d.getExpectedGhsAmount();
            r.creditedGhsAmount   = d.getCreditedGhsAmount();
            r.senderAddress       = d.getSenderAddress();
            r.screenshotUrl       = d.getScreenshotUrl();
            r.userNote            = d.getUserNote();
            r.status              = d.getStatus();
            r.reviewedBy          = d.getReviewedBy();
            r.reviewedAt          = d.getReviewedAt();
            r.adminNote           = d.getAdminNote();
            r.walletTransactionId = d.getWalletTransactionId();
            r.createdAt           = d.getCreatedAt();
            r.updatedAt           = d.getUpdatedAt();
            return r;
        }
    }
}
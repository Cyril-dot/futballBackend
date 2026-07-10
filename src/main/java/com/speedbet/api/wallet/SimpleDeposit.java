package com.speedbet.api.wallet;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deposits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleDeposit {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false, length = 100)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SimpleDepositNetwork network;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SimpleDepositPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SimpleDepositStatus status;

    // Set by admin at approval time; may differ from the amount the user claimed
    @Column(precision = 18, scale = 2)
    private BigDecimal creditedAmount;

    private UUID reviewedBy;

    private Instant reviewedAt;

    @Column(length = 500)
    private String adminNote;

    private UUID walletTransactionId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = SimpleDepositStatus.PENDING;
        }
    }
}
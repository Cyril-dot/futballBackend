package com.speedbet.api.payment.shinobipay;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "shinobipay_deposits",
        indexes = {
                @Index(name = "idx_shinobipay_deposits_reference", columnList = "reference", unique = true),
                @Index(name = "idx_shinobipay_deposits_user", columnList = "user_id"),
                @Index(name = "idx_shinobipay_deposits_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShinobiPayDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "intent_id")
    private String intentId;

    @Column(name = "amount_pesewas", nullable = false)
    private Long amountPesewas;

    @Column(name = "amount_ghs", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountGhs;

    private String phone;

    @Column(nullable = false)
    private String status; // PENDING, SUCCEEDED, FAILED

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = "PENDING";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

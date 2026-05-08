package com.speedbet.api.chat;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class AdminUpgradeChatDtos {

    // ─── Chat DTO ─────────────────────────────────────────────────────────────

    public record AdminUpgradeChatDto(
            UUID id,
            UUID userId,
            String userEmail,
            String userFirstName,
            AdminUpgradeChatStatus status,
            BigDecimal commissionRate,
            Instant createdAt,
            Instant updatedAt,
            int messageCount
    ) {}

    // ─── Message DTO ──────────────────────────────────────────────────────────

    public record AdminUpgradeChatMessageDto(
            UUID id,
            UUID chatId,
            UUID senderId,
            ChatSenderRole senderRole,
            String senderName,
            String content,
            Instant sentAt
    ) {}

    // ─── Requests ─────────────────────────────────────────────────────────────

    public record SendMessageRequest(
            @NotBlank @Size(max = 2000) String content
    ) {}

    public record SetCommissionRequest(
            @NotNull
            @DecimalMin(value = "0.1", message = "Commission rate must be at least 0.1%")
            @DecimalMax(value = "100.0", message = "Commission rate cannot exceed 100%")
            BigDecimal commissionRate
    ) {}
}
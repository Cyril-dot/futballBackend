package com.speedbet.api.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminDepositRow(
        Instant createdAt,
        UUID userId,
        String email,
        String firstName,
        String lastName,
        String country,
        BigDecimal amount
) {}

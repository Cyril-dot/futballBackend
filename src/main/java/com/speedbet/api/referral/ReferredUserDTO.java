package com.speedbet.api.referral;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ReferredUserDTO(
        UUID userId,
        String firstName,
        String lastName,
        String email,
        LocalDateTime joinedAt,
        BigDecimal lifetimeStake,
        BigDecimal lifetimeCommission,
        Long depositCount,
        BigDecimal totalDeposited
) {
    public String fullName() {
        return (firstName + " " + lastName).trim();
    }
}
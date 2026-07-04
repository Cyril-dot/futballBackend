package com.speedbet.api.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Unified shape for the country-split admin deposit feeds.
 *
 * Four structurally different sources map into this one shape:
 *   - BankDeposit    (Nigeria, NGN, admin-reviewed)
 *   - BinanceDeposit  (Ghana-resident depositors, GHS, admin-reviewed)
 *   - Transaction     (Paystack, GHS, instant/webhook-credited)
 *   - Transaction     (Moolre, GHS, instant/webhook-credited)
 */
public record AdminDepositDTO(
        UUID id,
        UUID userId,
        String country,   // "NIGERIA" | "GHANA"
        String provider,  // "bank_transfer" | "binance" | "paystack" | "moolre"
        BigDecimal amount,
        String currency,  // "NGN" | "GHS"
        String status,    // PENDING/APPROVED/REJECTED for reviewed types, "COMPLETED" for instant Transaction-based ones
        Instant createdAt
) {}
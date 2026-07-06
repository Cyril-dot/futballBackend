package com.speedbet.api.wallet;

import java.math.BigDecimal;
import java.time.Instant;

/** Lightweight JPQL constructor-projection: one deposit + the depositor's country. */
public record DepositRow(Instant createdAt, String country, BigDecimal amount) {}
package com.speedbet.api.wallet;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class WithdrawalRequestDto {
    @NotNull
    @DecimalMin(value = "0.01", message = "Amount must be positive")
    private BigDecimal amount;

    private String currency = "GHS";

    @NotBlank
    private String method = "mobile_money";

    @NotBlank
    private String accountNumber;

    @NotBlank
    private String accountName;

    private String network;
}

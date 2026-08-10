package com.maxcapital.withdrawals.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateWithdrawalRequest(

        @NotNull(message = "accountId is required")
        UUID accountId,

        @NotNull(message = "destinationCbu is required")
        @Pattern(regexp = "^[0-9]{22}$", message = "destinationCbu must be exactly 22 digits")
        String destinationCbu,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount
) {
}

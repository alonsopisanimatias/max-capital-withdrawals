package com.maxcapital.withdrawals.web.dto;

import jakarta.validation.constraints.Digits;
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
        // matches the numeric(18,2) column (V1__init.sql) — without this, a request with more
        // than 2 decimals passes bean validation and only fails later as an opaque 500 from the
        // DB check/rounding instead of a clean 400
        @Digits(integer = 16, fraction = 2, message = "amount must have at most 16 integer digits and 2 decimal places")
        BigDecimal amount
) {
}

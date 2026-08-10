package com.maxcapital.withdrawals.web.dto;

import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalResponse(
        UUID id,
        UUID accountId,
        String destinationCbu,
        BigDecimal amount,
        WithdrawalStatus status,
        RiskLevel riskLevel,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy
) {
    public static WithdrawalResponse from(Withdrawal withdrawal) {
        return new WithdrawalResponse(
                withdrawal.getId(),
                withdrawal.getAccountId(),
                withdrawal.getDestinationCbu(),
                withdrawal.getAmount(),
                withdrawal.getStatus(),
                withdrawal.getRiskLevel(),
                withdrawal.getCreatedAt(),
                withdrawal.getUpdatedAt(),
                withdrawal.getUpdatedBy());
    }
}

package com.maxcapital.withdrawals.web.dto;

import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.domain.Transfer;
import com.maxcapital.withdrawals.domain.TransferStatus;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.domain.WithdrawalStatusHistory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WithdrawalDetailResponse(
        UUID id,
        UUID accountId,
        String destinationCbu,
        BigDecimal amount,
        WithdrawalStatus status,
        RiskLevel riskLevel,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy,
        TransferInfo transfer,
        List<HistoryEntry> history
) {
    public record TransferInfo(TransferStatus status, String bankReference, Integer attemptCount, String lastError, Instant resolvedAt) {
    }

    // the append-only audit trail (DECISIONS.md section 2.4) — every transition a CNV-regulated
    // broker would need to reconstruct, oldest first, not just the current status
    public record HistoryEntry(WithdrawalStatus previousStatus, WithdrawalStatus newStatus, String actor, Instant occurredAt) {
    }

    public static WithdrawalDetailResponse from(Withdrawal withdrawal, Transfer transfer, List<WithdrawalStatusHistory> history) {
        TransferInfo transferInfo = transfer == null ? null : new TransferInfo(
                transfer.getStatus(), transfer.getBankReference(), transfer.getAttemptCount(), transfer.getLastError(), transfer.getResolvedAt());
        List<HistoryEntry> historyEntries = history.stream()
                .map(h -> new HistoryEntry(h.getPreviousStatus(), h.getNewStatus(), h.getActor(), h.getOccurredAt()))
                .toList();
        return new WithdrawalDetailResponse(
                withdrawal.getId(), withdrawal.getAccountId(), withdrawal.getDestinationCbu(), withdrawal.getAmount(),
                withdrawal.getStatus(), withdrawal.getRiskLevel(), withdrawal.getCreatedAt(), withdrawal.getUpdatedAt(),
                withdrawal.getUpdatedBy(), transferInfo, historyEntries);
    }
}

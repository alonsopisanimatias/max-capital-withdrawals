package com.maxcapital.withdrawals.service;

import com.maxcapital.withdrawals.domain.WithdrawalStatus;

import java.util.UUID;

/** Thrown when an operator tries to act on a withdrawal that's no longer in the state they expect. */
public class InvalidTransitionException extends RuntimeException {

    private final UUID withdrawalId;
    private final WithdrawalStatus currentStatus;
    private final String currentUpdatedBy;

    public InvalidTransitionException(String message, UUID withdrawalId, WithdrawalStatus currentStatus, String currentUpdatedBy) {
        super(message);
        this.withdrawalId = withdrawalId;
        this.currentStatus = currentStatus;
        this.currentUpdatedBy = currentUpdatedBy;
    }

    public UUID getWithdrawalId() {
        return withdrawalId;
    }

    public WithdrawalStatus getCurrentStatus() {
        return currentStatus;
    }

    public String getCurrentUpdatedBy() {
        return currentUpdatedBy;
    }
}

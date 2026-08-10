package com.maxcapital.withdrawals.domain;

public enum WithdrawalStatus {
    EVALUATING_RISK,
    PENDING_AUTHORIZATION,
    AUTHORIZED,
    REJECTED,
    PROCESSING_TRANSFER,
    EXECUTED,
    FINAL_ERROR,
    RETRYABLE_ERROR,
    MANUAL_REVIEW
}

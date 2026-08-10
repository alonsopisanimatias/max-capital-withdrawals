package com.maxcapital.withdrawals.domain;

public enum TransferStatus {
    PENDING,
    SUCCEEDED,
    FAILED_INVALID_ACCOUNT,
    FAILED_INTERNAL_ERROR,
    AWAITING_RECONCILIATION
}

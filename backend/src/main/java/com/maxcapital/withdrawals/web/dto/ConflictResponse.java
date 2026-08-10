package com.maxcapital.withdrawals.web.dto;

import com.maxcapital.withdrawals.domain.WithdrawalStatus;

/**
 * 409 body for C4: tells the losing operator what actually happened instead of a bare error,
 * so the frontend can show "already resolved by X" and refresh instead of just failing.
 */
public record ConflictResponse(String error, String message, WithdrawalStatus currentStatus, String updatedBy) {
}

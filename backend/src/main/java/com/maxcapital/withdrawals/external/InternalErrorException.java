package com.maxcapital.withdrawals.external;

/** Transient: each call is independent, not cached by idempotency key. Safe to retry. */
public class InternalErrorException extends Exception {
    public InternalErrorException(String message) {
        super(message);
    }
}

package com.maxcapital.withdrawals.external;

/** Thrown when executeTransfer is called with a key that already has a call in flight. */
public class IdempotentRequestInProgressException extends Exception {
    public IdempotentRequestInProgressException(String message) {
        super(message);
    }
}

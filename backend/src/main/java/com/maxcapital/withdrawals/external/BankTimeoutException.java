package com.maxcapital.withdrawals.external;

/**
 * The bank didn't respond in time — the transfer may or may not have been applied on
 * their side. Never retry executeTransfer blindly after this; query first (C6).
 * Named to avoid colliding with java.util.concurrent.TimeoutException.
 */
public class BankTimeoutException extends Exception {
    public BankTimeoutException(String message) {
        super(message);
    }
}

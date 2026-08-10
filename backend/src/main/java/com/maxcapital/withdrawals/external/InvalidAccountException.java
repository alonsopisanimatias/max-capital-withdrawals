package com.maxcapital.withdrawals.external;

/** Terminal: the destination CBU is invalid. Never retried automatically. */
public class InvalidAccountException extends Exception {
    public InvalidAccountException(String message) {
        super(message);
    }
}

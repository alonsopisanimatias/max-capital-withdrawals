package com.maxcapital.withdrawals.external;

public record BankQueryResult(Outcome outcome, String bankReference) {

    public enum Outcome {
        APPLIED,
        FAILED_INVALID_ACCOUNT,
        FAILED_OTHER,
        NOT_FOUND
    }

    public static BankQueryResult notFound() {
        return new BankQueryResult(Outcome.NOT_FOUND, null);
    }
}

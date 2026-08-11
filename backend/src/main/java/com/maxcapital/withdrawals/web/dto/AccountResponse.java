package com.maxcapital.withdrawals.web.dto;

import com.maxcapital.withdrawals.domain.Account;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(UUID id, String accountNumber, String holderName, BigDecimal balance, BigDecimal reservedBalance) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getAccountNumber(), account.getHolderName(), account.getBalance(), account.getReservedBalance());
    }
}

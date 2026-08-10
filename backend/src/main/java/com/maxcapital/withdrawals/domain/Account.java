package com.maxcapital.withdrawals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor
public class Account {

    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    // read-only view for callers: mutations to balance/reserved_balance go through
    // the atomic conditional UPDATEs in AccountRepository, never through this entity's setters
    // (see PLAN_TECNICO_FINAL.md fixes #2 and #6 — this field is not the source of truth for writes).
    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "reserved_balance", nullable = false)
    private BigDecimal reservedBalance;

    // construction only — no setters. The app never mutates balance/reserved_balance through
    // the entity, only through AccountRepository's atomic UPDATEs; this constructor exists for
    // seeding disposable accounts in tests, not for a create-account use case (out of scope).
    public Account(UUID id, String accountNumber, String holderName, BigDecimal balance, BigDecimal reservedBalance) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.balance = balance;
        this.reservedBalance = reservedBalance;
    }
}

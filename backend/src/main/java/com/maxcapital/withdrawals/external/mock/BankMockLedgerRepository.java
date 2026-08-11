package com.maxcapital.withdrawals.external.mock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BankMockLedgerRepository extends JpaRepository<BankMockLedgerEntry, UUID> {
}

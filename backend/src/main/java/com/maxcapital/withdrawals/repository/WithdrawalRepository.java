package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.Withdrawal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {
    // FOR UPDATE SKIP LOCKED queries for the risk/transfer pollers land here in Blocks 5 and 7 (C3)
}

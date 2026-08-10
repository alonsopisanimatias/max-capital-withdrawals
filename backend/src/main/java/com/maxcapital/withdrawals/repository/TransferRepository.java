package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByWithdrawalId(UUID withdrawalId);
    // FOR UPDATE SKIP LOCKED query for the reconciliation poller lands here in Block 7 (C6)
}

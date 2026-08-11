package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.WithdrawalStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WithdrawalStatusHistoryRepository extends JpaRepository<WithdrawalStatusHistory, UUID> {

    List<WithdrawalStatusHistory> findByWithdrawalIdOrderByOccurredAt(UUID withdrawalId);
}

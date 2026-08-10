package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.Withdrawal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    /**
     * Claims up to {@code batchSize} withdrawals still awaiting risk evaluation, oldest first.
     * SKIP LOCKED turns the table into a multi-consumer queue (C3): with 2+ instances polling
     * concurrently, each locks a disjoint set of rows instead of blocking on the same ones —
     * no leader election, no external coordination, Postgres itself arbitrates. Must be called
     * inside the same transaction that evaluates risk and writes the resulting status (see
     * RiskEvaluationPoller) so the lock covers the whole "claim + decide" step; otherwise two
     * instances could both see the same row as unclaimed between the SELECT and the UPDATE.
     */
    @Query(value = """
            SELECT * FROM withdrawal
            WHERE status = 'EVALUATING_RISK'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Withdrawal> lockNextBatchForRiskEvaluation(@Param("batchSize") int batchSize);
}

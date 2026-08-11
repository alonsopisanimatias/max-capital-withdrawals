package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.Withdrawal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID>, JpaSpecificationExecutor<Withdrawal> {

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

    /** Same SKIP LOCKED pattern (C3), for withdrawals authorized and ready to transfer. */
    @Query(value = """
            SELECT * FROM withdrawal
            WHERE status = 'AUTHORIZED'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Withdrawal> lockNextBatchForTransfer(@Param("batchSize") int batchSize);

    /**
     * Claims withdrawals stuck in PROCESSING_TRANSFER whose transfer attempt is older than the
     * grace period — either the bank call timed out (ambiguous outcome) or the process died
     * between claiming the withdrawal and actually calling the bank. Locks the WITHDRAWAL row,
     * not transfer, deliberately: the transfer poller also locks withdrawal, so both pollers
     * share one critical section per aggregate instead of two independent locking schemes that
     * could race with each other (see DECISIONS.md section 8, C6). {@code FOR UPDATE OF w}
     * restricts the lock to the withdrawal rows even though transfer is joined for the filter.
     */
    @Query(value = """
            SELECT w.* FROM withdrawal w
            JOIN transfer t ON t.withdrawal_id = w.id
            WHERE w.status = 'PROCESSING_TRANSFER'
              AND t.requested_at < now() - (:gracePeriodSeconds || ' seconds')::interval
            ORDER BY t.requested_at
            LIMIT :batchSize
            FOR UPDATE OF w SKIP LOCKED
            """, nativeQuery = true)
    List<Withdrawal> lockNextBatchForReconciliation(@Param("batchSize") int batchSize, @Param("gracePeriodSeconds") int gracePeriodSeconds);

    /**
     * The C6 guard: only actually applies an outcome (settle balance, mark FINAL_ERROR, etc.)
     * if the withdrawal is still PROCESSING_TRANSFER <b>for the same attempt</b> the caller
     * started with. Status alone isn't enough to discriminate: if attempt 1 times out and
     * reconciliation resets the withdrawal to AUTHORIZED (still consulting the same
     * `transfer` row, but now on attempt 2), the withdrawal moves back to PROCESSING_TRANSFER
     * for attempt 2 — a late result from attempt 1 arriving after that would still see
     * status = PROCESSING_TRANSFER and pass a status-only guard, applying attempt 1's outcome
     * on top of attempt 2's in-flight work. Joining {@code transfer.attempt_count} against the
     * value the caller captured before calling the bank closes that window: a stale attempt's
     * result now affects 0 rows, same as an already-resolved withdrawal does.
     * {@code transferId} is only meaningful for the EXECUTED outcome; COALESCE leaves it
     * untouched (still null) for every other transition.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE withdrawal
            SET status = :newStatus,
                updated_at = now(),
                updated_by = :actor,
                transfer_id = COALESCE(:transferId, transfer_id)
            WHERE id = :id
              AND status = 'PROCESSING_TRANSFER'
              AND (SELECT t.attempt_count FROM transfer t WHERE t.withdrawal_id = :id) = :expectedAttemptCount
            """, nativeQuery = true)
    int transitionFromProcessingTransfer(@Param("id") UUID id, @Param("newStatus") String newStatus,
                                          @Param("actor") String actor, @Param("transferId") UUID transferId,
                                          @Param("expectedAttemptCount") int expectedAttemptCount);
}

package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByWithdrawalId(UUID withdrawalId);

    /**
     * Stamps requested_at with the DATABASE's own clock, not the JVM's. The grace-period
     * comparison in {@code WithdrawalRepository.lockNextBatchForReconciliation} already uses
     * Postgres's {@code now()} on the right-hand side; if this side used {@code Instant.now()}
     * instead, clock skew between the 2+ backend instances would make the comparison meaningless
     * — an instance whose clock runs behind would see its own claims as immediately "stale".
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE transfer SET requested_at = now() WHERE id = :id", nativeQuery = true)
    void markRequestedNow(@Param("id") UUID id);

    /**
     * Guarded by {@code status = 'PENDING'}: if reconciliation already resolved this transfer
     * (to SUCCEEDED/FAILED_*) while this call's own late timeout result was still in flight,
     * this becomes a no-op instead of overwriting a real bank_reference/resolvedAt with
     * "awaiting reconciliation" — see {@link com.maxcapital.withdrawals.service.TransferOutcomeService#markAwaitingReconciliation}.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE transfer
            SET status = 'AWAITING_RECONCILIATION', last_error = :errorMessage
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    void markAwaitingReconciliationIfStillPending(@Param("id") UUID id, @Param("errorMessage") String errorMessage);

    /** Atomic increment — avoids a read-modify-write race if this were done via entity save(). */
    @Modifying
    @Transactional
    @Query(value = "UPDATE transfer SET reconciliation_attempts = reconciliation_attempts + 1 WHERE id = :id", nativeQuery = true)
    void incrementReconciliationAttempts(@Param("id") UUID id);
}

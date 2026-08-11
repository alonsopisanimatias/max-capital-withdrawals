package com.maxcapital.withdrawals.external.mock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface BankMockInFlightRepository extends JpaRepository<BankMockInFlightMarker, UUID> {

    /**
     * Atomically claims {@code idempotencyKey} for this call — the primary key is the exclusivity
     * mechanism, not an application lock, so it works the same whether the concurrent caller is
     * another thread in this JVM or a different backend instance entirely. Returns 1 if this call
     * won the claim, 0 if someone else already holds it (caller throws
     * {@link com.maxcapital.withdrawals.external.IdempotentRequestInProgressException}).
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO bank_mock_in_flight (idempotency_key, started_at) VALUES (:idempotencyKey, now()) " +
            "ON CONFLICT (idempotency_key) DO NOTHING", nativeQuery = true)
    int tryClaim(@Param("idempotencyKey") UUID idempotencyKey);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM bank_mock_in_flight WHERE idempotency_key = :idempotencyKey", nativeQuery = true)
    void release(@Param("idempotencyKey") UUID idempotencyKey);
}

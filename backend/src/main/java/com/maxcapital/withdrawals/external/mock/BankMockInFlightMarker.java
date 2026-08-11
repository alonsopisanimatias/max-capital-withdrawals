package com.maxcapital.withdrawals.external.mock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Marks one idempotency key as currently being processed by {@link BankServiceMock#executeTransfer}
 * on SOME instance — the shared equivalent of the old in-memory {@code Set<UUID> inFlight}. Never
 * constructed directly in application code: {@link BankMockInFlightRepository#tryClaim} inserts
 * the row, and the primary key uniqueness is what makes the claim atomic across instances.
 */
@Entity
@Table(name = "bank_mock_in_flight")
@Getter
@NoArgsConstructor
public class BankMockInFlightMarker {

    @Id
    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "started_at", updatable = false)
    private Instant startedAt;
}

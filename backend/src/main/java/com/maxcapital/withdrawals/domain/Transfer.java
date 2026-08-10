package com.maxcapital.withdrawals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer")
@Getter
@Setter
@NoArgsConstructor
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "withdrawal_id", nullable = false, updatable = false, unique = true)
    private UUID withdrawalId;

    // same value as withdrawal.idempotency_key — invariant across retries, see C6
    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransferStatus status;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "last_error")
    private String lastError;

    // counts failed queryByIdempotencyKey attempts during reconciliation;
    // past a threshold the withdrawal moves to MANUAL_REVIEW instead of retrying forever.
    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;
}

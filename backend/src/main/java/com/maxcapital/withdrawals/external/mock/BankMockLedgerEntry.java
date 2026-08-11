package com.maxcapital.withdrawals.external.mock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link BankServiceMock}'s ground truth for one idempotency key, persisted so every backend
 * instance sees the same outcome (see V3__bank_mock_ledger.sql and BankServiceMock's javadoc —
 * this is what fixes the split-brain in-memory version had under docker-compose C3).
 */
@Entity
@Table(name = "bank_mock_ledger")
@Getter
@NoArgsConstructor
public class BankMockLedgerEntry {

    public enum Outcome {
        APPLIED, REJECTED_INVALID_ACCOUNT, TIMEOUT_APPLIED_TRUE, TIMEOUT_APPLIED_FALSE
    }

    @Id
    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public BankMockLedgerEntry(UUID idempotencyKey, Outcome outcome, String bankReference) {
        this.idempotencyKey = idempotencyKey;
        this.outcome = outcome;
        this.bankReference = bankReference;
        this.createdAt = Instant.now();
    }
}

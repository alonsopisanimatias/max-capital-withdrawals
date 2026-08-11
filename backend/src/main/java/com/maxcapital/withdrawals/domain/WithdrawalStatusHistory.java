package com.maxcapital.withdrawals.domain;

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
 * Append-only audit trail — not required by the challenge statement, added because a
 * CNV-regulated broker would expect to reconstruct the full sequence of decisions on a
 * withdrawal, not just the last actor (see DECISIONS.md, section 2.4).
 */
@Entity
@Table(name = "withdrawal_status_history")
@Getter
@NoArgsConstructor
public class WithdrawalStatusHistory {

    @Id
    private UUID id;

    @Column(name = "withdrawal_id", nullable = false, updatable = false)
    private UUID withdrawalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", updatable = false)
    private WithdrawalStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false)
    private WithdrawalStatus newStatus;

    // operator id, or SYSTEM_RISK / SYSTEM_TRANSFER / SYSTEM_RECONCILIATION for automatic transitions
    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public WithdrawalStatusHistory(UUID id, UUID withdrawalId, WithdrawalStatus previousStatus,
                                    WithdrawalStatus newStatus, String actor, Instant occurredAt) {
        this.id = id;
        this.withdrawalId = withdrawalId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }
}

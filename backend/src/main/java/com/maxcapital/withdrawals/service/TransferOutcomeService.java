package com.maxcapital.withdrawals.service;

import com.maxcapital.withdrawals.domain.Transfer;
import com.maxcapital.withdrawals.domain.TransferStatus;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.domain.WithdrawalStatusHistory;
import com.maxcapital.withdrawals.repository.AccountRepository;
import com.maxcapital.withdrawals.repository.TransferRepository;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.repository.WithdrawalStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The five ways a transfer attempt can resolve, shared by TransferExecutionPoller (phase C)
 * and ReconciliationPoller so both apply outcomes through the exact same guarded path — see
 * {@link WithdrawalRepository#transitionFromProcessingTransfer} for why that guard exists (C6).
 * Each method is its own short REQUIRES_NEW transaction, deliberately never wrapping the slow
 * bank call itself.
 */
@Component
@RequiredArgsConstructor
public class TransferOutcomeService {

    public static final String ACTOR_SYSTEM_TRANSFER = "SYSTEM_TRANSFER";
    public static final String ACTOR_SYSTEM_RECONCILIATION = "SYSTEM_RECONCILIATION";

    /** Past this many failed reconciliation lookups, stop retrying automatically and escalate. */
    private static final int MAX_RECONCILIATION_ATTEMPTS = 5;

    private final WithdrawalRepository withdrawalRepository;
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final WithdrawalStatusHistoryRepository historyRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applySuccess(UUID withdrawalId, UUID transferId, String bankReference, String actor) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId).orElseThrow();
        int rows = withdrawalRepository.transitionFromProcessingTransfer(withdrawalId, WithdrawalStatus.EXECUTED.name(), actor, transferId);
        if (rows == 0) {
            return; // already resolved by a concurrent writer — never settle twice (C6)
        }
        accountRepository.settle(withdrawal.getAccountId(), withdrawal.getAmount());

        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        transfer.setStatus(TransferStatus.SUCCEEDED);
        transfer.setBankReference(bankReference);
        transfer.setResolvedAt(Instant.now());
        transferRepository.save(transfer);

        recordTransition(withdrawalId, WithdrawalStatus.PROCESSING_TRANSFER, WithdrawalStatus.EXECUTED, actor);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyInvalidAccount(UUID withdrawalId, UUID transferId, String errorMessage, String actor) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId).orElseThrow();
        int rows = withdrawalRepository.transitionFromProcessingTransfer(withdrawalId, WithdrawalStatus.FINAL_ERROR.name(), actor, null);
        if (rows == 0) {
            return;
        }
        accountRepository.release(withdrawal.getAccountId(), withdrawal.getAmount());

        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        transfer.setStatus(TransferStatus.FAILED_INVALID_ACCOUNT);
        transfer.setLastError(errorMessage);
        transfer.setResolvedAt(Instant.now());
        transferRepository.save(transfer);

        recordTransition(withdrawalId, WithdrawalStatus.PROCESSING_TRANSFER, WithdrawalStatus.FINAL_ERROR, actor);
    }

    /** Reserve stays held: the operator can retry, and retry needs the funds still reserved. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyInternalError(UUID withdrawalId, UUID transferId, String errorMessage, String actor) {
        int rows = withdrawalRepository.transitionFromProcessingTransfer(withdrawalId, WithdrawalStatus.RETRYABLE_ERROR.name(), actor, null);
        if (rows == 0) {
            return;
        }

        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        transfer.setStatus(TransferStatus.FAILED_INTERNAL_ERROR);
        transfer.setLastError(errorMessage);
        transfer.setResolvedAt(Instant.now());
        transferRepository.save(transfer);

        recordTransition(withdrawalId, WithdrawalStatus.PROCESSING_TRANSFER, WithdrawalStatus.RETRYABLE_ERROR, actor);
    }

    /**
     * The ambiguous case: the withdrawal stays PROCESSING_TRANSFER (no guard needed — nothing
     * about its status changes), only the transfer record reflects that reconciliation needs to
     * resolve this one later.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAwaitingReconciliation(UUID transferId, String errorMessage) {
        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        transfer.setStatus(TransferStatus.AWAITING_RECONCILIATION);
        transfer.setLastError(errorMessage);
        transferRepository.save(transfer);
    }

    /**
     * Reconciliation confirmed the bank never applied it: safe to let the transfer poller pick
     * it up again. Resets requested_at so reconciliation doesn't immediately re-flag it as
     * "stuck" again before the transfer poller gets a chance to retry (PLAN_TECNICO_FINAL.md
     * fix #6).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetForRetryAfterReconciliation(UUID withdrawalId, UUID transferId) {
        int rows = withdrawalRepository.transitionFromProcessingTransfer(withdrawalId, WithdrawalStatus.AUTHORIZED.name(), ACTOR_SYSTEM_RECONCILIATION, null);
        if (rows == 0) {
            return;
        }
        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setRequestedAt(null);
        transferRepository.save(transfer);

        recordTransition(withdrawalId, WithdrawalStatus.PROCESSING_TRANSFER, WithdrawalStatus.AUTHORIZED, ACTOR_SYSTEM_RECONCILIATION);
    }

    /**
     * Reconciliation's own lookup failed (transient error querying the bank) — counts the
     * attempt, and past {@link #MAX_RECONCILIATION_ATTEMPTS} gives up on automatic resolution
     * rather than looping forever on a withdrawal nobody can currently explain.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedReconciliationAttempt(UUID withdrawalId, UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        transfer.setReconciliationAttempts(transfer.getReconciliationAttempts() + 1);
        transferRepository.save(transfer);

        if (transfer.getReconciliationAttempts() >= MAX_RECONCILIATION_ATTEMPTS) {
            int rows = withdrawalRepository.transitionFromProcessingTransfer(withdrawalId, WithdrawalStatus.MANUAL_REVIEW.name(), ACTOR_SYSTEM_RECONCILIATION, null);
            if (rows > 0) {
                recordTransition(withdrawalId, WithdrawalStatus.PROCESSING_TRANSFER, WithdrawalStatus.MANUAL_REVIEW, ACTOR_SYSTEM_RECONCILIATION);
            }
        }
    }

    private void recordTransition(UUID withdrawalId, WithdrawalStatus previousStatus, WithdrawalStatus newStatus, String actor) {
        historyRepository.save(new WithdrawalStatusHistory(
                UUID.randomUUID(), withdrawalId, previousStatus, newStatus, actor, Instant.now()));
    }
}

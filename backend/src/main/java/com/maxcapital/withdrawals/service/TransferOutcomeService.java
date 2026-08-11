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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The six ways a transfer attempt can resolve, shared by TransferExecutionPoller (phase C)
 * and ReconciliationPoller so both apply outcomes through the exact same guarded path — see
 * {@link WithdrawalRepository#transitionFromProcessingTransfer} for why that guard exists (C6).
 * Each method is its own short REQUIRES_NEW transaction, deliberately never wrapping the slow
 * bank call itself.
 */
@Service
@RequiredArgsConstructor
public class TransferOutcomeService {

    public static final String ACTOR_SYSTEM_TRANSFER = "SYSTEM_TRANSFER";
    public static final String ACTOR_SYSTEM_RECONCILIATION = "SYSTEM_RECONCILIATION";

    /** Past this many failed reconciliation lookups, stop retrying automatically and escalate. */
    private static final int MAX_RECONCILIATION_ATTEMPTS = 5;

    /**
     * Past this many transfer attempts on the same withdrawal, stop letting reconciliation send
     * it back to AUTHORIZED for yet another try and escalate instead — without this, a bank
     * outcome that reliably times out without applying retries forever: NOT_FOUND -> AUTHORIZED
     * -> timeout -> NOT_FOUND -> ..., with nothing anywhere reading attempt_count to break it.
     */
    private static final int MAX_TRANSFER_ATTEMPTS = 5;

    private final WithdrawalRepository withdrawalRepository;
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final WithdrawalStatusHistoryRepository historyRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applySuccess(UUID withdrawalId, UUID transferId, String bankReference, String actor, int expectedAttemptCount) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId).orElseThrow();
        int rows = withdrawalRepository.transitionFromProcessingTransfer(
                withdrawalId, WithdrawalStatus.EXECUTED.name(), actor, transferId, expectedAttemptCount);
        if (rows == 0) {
            return; // already resolved by a concurrent writer, or a stale attempt — never settle twice (C6)
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
    public void applyInvalidAccount(UUID withdrawalId, UUID transferId, String errorMessage, String actor, int expectedAttemptCount) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId).orElseThrow();
        int rows = withdrawalRepository.transitionFromProcessingTransfer(
                withdrawalId, WithdrawalStatus.FINAL_ERROR.name(), actor, null, expectedAttemptCount);
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
    public void applyInternalError(UUID withdrawalId, UUID transferId, String errorMessage, String actor, int expectedAttemptCount) {
        int rows = withdrawalRepository.transitionFromProcessingTransfer(
                withdrawalId, WithdrawalStatus.RETRYABLE_ERROR.name(), actor, null, expectedAttemptCount);
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
     * The ambiguous case: the withdrawal stays PROCESSING_TRANSFER, only the transfer record
     * reflects that reconciliation needs to resolve this one later. Guarded by status = PENDING
     * (the status claimOne() sets before calling the bank): if reconciliation already resolved
     * this same transfer while this late timeout was still in flight — settling it to SUCCEEDED,
     * or moving it to FAILED_* — that resolution already moved the status away from PENDING, so
     * this write becomes a no-op instead of overwriting a real bank_reference/resolvedAt with
     * "awaiting reconciliation".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAwaitingReconciliation(UUID transferId, String errorMessage) {
        transferRepository.markAwaitingReconciliationIfStillPending(transferId, errorMessage);
    }

    /**
     * Reconciliation confirmed the bank never applied it: safe to let the transfer poller pick
     * it up again — unless this withdrawal has already burned through too many attempts, in
     * which case looping back to AUTHORIZED again would just repeat whatever keeps failing
     * (see {@link #MAX_TRANSFER_ATTEMPTS}). Resets requested_at (via
     * {@link TransferRepository#markRequestedNow}) so reconciliation doesn't immediately
     * re-flag it as "stuck" again before the transfer poller gets a chance to retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetForRetryAfterReconciliation(UUID withdrawalId, UUID transferId, int expectedAttemptCount) {
        if (expectedAttemptCount >= MAX_TRANSFER_ATTEMPTS) {
            transitionToManualReview(withdrawalId, expectedAttemptCount);
            return;
        }
        int rows = withdrawalRepository.transitionFromProcessingTransfer(
                withdrawalId, WithdrawalStatus.AUTHORIZED.name(), ACTOR_SYSTEM_RECONCILIATION, null, expectedAttemptCount);
        if (rows == 0) {
            return;
        }
        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        transfer.setStatus(TransferStatus.PENDING);
        transferRepository.save(transfer);

        recordTransition(withdrawalId, WithdrawalStatus.PROCESSING_TRANSFER, WithdrawalStatus.AUTHORIZED, ACTOR_SYSTEM_RECONCILIATION);
    }

    /**
     * Reconciliation's own lookup failed (transient error querying the bank) — counts the
     * attempt atomically, and past {@link #MAX_RECONCILIATION_ATTEMPTS} gives up on automatic
     * resolution rather than looping forever on a withdrawal nobody can currently explain.
     *
     * <p>Touches the withdrawal (native query, via {@link #transitionToManualReview}) BEFORE the
     * transfer row, matching the lock order every other path in this class uses (withdrawal
     * first). Doing it in the opposite order — saving a dirty transfer entity, then running a
     * native query, which forces Hibernate to auto-flush that pending write first — would take
     * transfer's lock before withdrawal's, and a concurrent claim (which always locks withdrawal
     * first via SKIP LOCKED) taking the opposite order is a real deadlock between two instances.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedReconciliationAttempt(UUID withdrawalId, UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        int attempts = transfer.getReconciliationAttempts() + 1;

        if (attempts >= MAX_RECONCILIATION_ATTEMPTS) {
            transitionToManualReview(withdrawalId, transfer.getAttemptCount());
        }

        transferRepository.incrementReconciliationAttempts(transferId);
    }

    private void transitionToManualReview(UUID withdrawalId, int expectedAttemptCount) {
        int rows = withdrawalRepository.transitionFromProcessingTransfer(
                withdrawalId, WithdrawalStatus.MANUAL_REVIEW.name(), ACTOR_SYSTEM_RECONCILIATION, null, expectedAttemptCount);
        if (rows > 0) {
            recordTransition(withdrawalId, WithdrawalStatus.PROCESSING_TRANSFER, WithdrawalStatus.MANUAL_REVIEW, ACTOR_SYSTEM_RECONCILIATION);
        }
    }

    private void recordTransition(UUID withdrawalId, WithdrawalStatus previousStatus, WithdrawalStatus newStatus, String actor) {
        historyRepository.save(new WithdrawalStatusHistory(
                UUID.randomUUID(), withdrawalId, previousStatus, newStatus, actor, Instant.now()));
    }
}

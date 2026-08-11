package com.maxcapital.withdrawals.poller;

import com.maxcapital.withdrawals.domain.Transfer;
import com.maxcapital.withdrawals.domain.TransferStatus;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.domain.WithdrawalStatusHistory;
import com.maxcapital.withdrawals.external.BankService;
import com.maxcapital.withdrawals.external.BankTimeoutException;
import com.maxcapital.withdrawals.external.BankTransferResult;
import com.maxcapital.withdrawals.external.IdempotentRequestInProgressException;
import com.maxcapital.withdrawals.external.InternalErrorException;
import com.maxcapital.withdrawals.external.InvalidAccountException;
import com.maxcapital.withdrawals.repository.TransferRepository;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.repository.WithdrawalStatusHistoryRepository;
import com.maxcapital.withdrawals.service.TransferOutcomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes bank transfers for AUTHORIZED withdrawals in three phases (C1 + C6):
 *
 * <p><b>Phase A</b> (this class, short REQUIRES_NEW transaction): claims a batch via SKIP
 * LOCKED, creates/reuses the transfer row with its idempotency key, moves the withdrawal to
 * PROCESSING_TRANSFER, commits — the lock is held only for this, milliseconds.
 *
 * <p><b>Phase B</b> (dispatched on {@code transferExecutionExecutor}, the actual bank call on
 * {@code bankCallExecutor} — see {@link com.maxcapital.withdrawals.config.TransferExecutorConfig}
 * for why those are two different pools): the slow bank call itself, outside any transaction and
 * any DB lock, bounded by a hard client-side timeout so an unresponsive bank can't hang a worker
 * thread forever.
 *
 * <p><b>Phase C</b> ({@link TransferOutcomeService}): writes whatever phase B produced, guarded
 * so a late result can never double-apply if reconciliation already resolved the same
 * withdrawal in the meantime.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransferExecutionPoller {

    private final WithdrawalRepository withdrawalRepository;
    private final TransferRepository transferRepository;
    private final WithdrawalStatusHistoryRepository historyRepository;
    private final BankService bankService;
    private final TransferOutcomeService outcomeService;
    private final ThreadPoolTaskExecutor transferExecutionExecutor;
    private final ThreadPoolTaskExecutor bankCallExecutor;

    @Value("${withdrawals.poller.transfer-execution.batch-size}")
    private int batchSize;

    @Value("${withdrawals.poller.transfer-execution.bank-call-timeout-seconds}")
    private int bankCallTimeoutSeconds;

    // logging only — lets the multi-instance demo show each container claiming disjoint rows
    @Value("${instance.id}")
    private String instanceId;

    // see RiskEvaluationPoller for why: self-invoking claimBatch() as a plain method call would
    // skip the Spring proxy and silently drop @Transactional(REQUIRES_NEW) on it.
    @Autowired
    @Lazy
    private TransferExecutionPoller self;

    @Scheduled(fixedDelayString = "${withdrawals.poller.transfer-execution.fixed-delay-ms}")
    public void tick() {
        for (UUID withdrawalId : self.claimBatch()) {
            try {
                transferExecutionExecutor.execute(() -> processOne(withdrawalId));
            } catch (TaskRejectedException e) {
                // the claim already committed (PROCESSING_TRANSFER persisted) even though
                // dispatch failed — without this catch, a full dispatch queue would silently
                // drop the rest of this tick's batch and blow up the @Scheduled method. Leaving
                // it here is safe: reconciliation picks the withdrawal up after the grace period,
                // same as any other stuck PROCESSING_TRANSFER.
                log.error("Could not dispatch withdrawal {} for transfer execution, dispatch queue full; " +
                        "reconciliation will pick it up after the grace period", withdrawalId, e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimBatch() {
        List<Withdrawal> claimed = withdrawalRepository.lockNextBatchForTransfer(batchSize);
        if (!claimed.isEmpty()) {
            log.info("[{}] transfer execution claimed {} withdrawal(s): {}", instanceId, claimed.size(),
                    claimed.stream().map(Withdrawal::getId).toList());
        }
        return claimed.stream().map(this::claimOne).toList();
    }

    private UUID claimOne(Withdrawal withdrawal) {
        Transfer transfer = transferRepository.findByWithdrawalId(withdrawal.getId())
                .orElseGet(() -> {
                    Transfer created = new Transfer();
                    created.setId(UUID.randomUUID());
                    created.setWithdrawalId(withdrawal.getId());
                    created.setIdempotencyKey(withdrawal.getIdempotencyKey());
                    return created;
                });
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setAttemptCount(transfer.getAttemptCount() + 1);
        transferRepository.save(transfer);
        // requested_at is stamped by the database's own clock (see markRequestedNow's javadoc),
        // not Instant.now() here — the grace-period comparison in reconciliation needs a single
        // clock across instances, not each JVM's own.
        transferRepository.markRequestedNow(transfer.getId());

        WithdrawalStatus previousStatus = withdrawal.getStatus();
        withdrawal.setStatus(WithdrawalStatus.PROCESSING_TRANSFER);
        withdrawal.setUpdatedAt(Instant.now());
        withdrawal.setUpdatedBy(TransferOutcomeService.ACTOR_SYSTEM_TRANSFER);
        withdrawalRepository.save(withdrawal);

        historyRepository.save(new WithdrawalStatusHistory(
                UUID.randomUUID(), withdrawal.getId(), previousStatus, WithdrawalStatus.PROCESSING_TRANSFER,
                TransferOutcomeService.ACTOR_SYSTEM_TRANSFER, Instant.now()));

        return withdrawal.getId();
    }

    /** Runs on transferExecutionExecutor — outside any transaction, outside any DB lock. */
    private void processOne(UUID withdrawalId) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId).orElseThrow();
        Transfer transfer = transferRepository.findByWithdrawalId(withdrawalId).orElseThrow();
        // captured before the bank call so a result can only ever apply to THIS attempt (C6) —
        // see WithdrawalRepository.transitionFromProcessingTransfer's javadoc for the race this closes
        int attemptCount = transfer.getAttemptCount();

        try {
            BankTransferResult result = callBankWithTimeout(transfer.getIdempotencyKey(), withdrawal);
            outcomeService.applySuccess(withdrawalId, transfer.getId(), result.bankReference(), TransferOutcomeService.ACTOR_SYSTEM_TRANSFER, attemptCount);
        } catch (InvalidAccountException e) {
            outcomeService.applyInvalidAccount(withdrawalId, transfer.getId(), e.getMessage(), TransferOutcomeService.ACTOR_SYSTEM_TRANSFER, attemptCount);
        } catch (InternalErrorException e) {
            outcomeService.applyInternalError(withdrawalId, transfer.getId(), e.getMessage(), TransferOutcomeService.ACTOR_SYSTEM_TRANSFER, attemptCount);
        } catch (BankTimeoutException e) {
            outcomeService.markAwaitingReconciliation(transfer.getId(), e.getMessage());
        } catch (TimeoutException e) {
            // our own client-side timeout, distinct from the bank reporting its own timeout —
            // same handling either way: we don't know what happened, reconciliation will ask.
            // Note this does NOT cancel the in-flight call on bankCallExecutor (Java can't
            // interrupt a plain blocking call without cooperation) — it occupies a bankCallExecutor
            // slot until it actually returns, which is why that pool's own size is what really
            // bounds worst-case concurrency against the bank, not this timeout.
            outcomeService.markAwaitingReconciliation(transfer.getId(), "Client-side timeout waiting for bank response");
        } catch (IdempotentRequestInProgressException e) {
            // shouldn't happen in normal operation (SKIP LOCKED means only one worker processes
            // this withdrawal at a time) — leave state as-is, reconciliation will resolve it
            // after the grace period if this repeats
            log.warn("Unexpected concurrent bank call for withdrawal {}: {}", withdrawalId, e.getMessage());
        }
    }

    private BankTransferResult callBankWithTimeout(UUID idempotencyKey, Withdrawal withdrawal)
            throws InvalidAccountException, InternalErrorException, BankTimeoutException, IdempotentRequestInProgressException, TimeoutException {
        // explicit executor argument is the fix: the single-argument supplyAsync(Supplier)
        // overload runs on ForkJoinPool.commonPool(), NOT on any pool this class controls —
        // on a small-CPU container that pool can have parallelism 1 (calls serialize behind
        // the timeout) or, on a single-CPU one, fall back to an unbounded thread-per-task
        // executor (exactly what a dedicated bounded pool exists to avoid).
        CompletableFuture<BankTransferResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return bankService.executeTransfer(idempotencyKey, withdrawal.getAccountId(), withdrawal.getDestinationCbu(), withdrawal.getAmount());
            } catch (InvalidAccountException | InternalErrorException | BankTimeoutException | IdempotentRequestInProgressException e) {
                throw new CompletionException(e);
            }
        }, bankCallExecutor.getThreadPoolExecutor()).orTimeout(bankCallTimeoutSeconds, TimeUnit.SECONDS);

        try {
            return future.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof InvalidAccountException e) throw e;
            if (cause instanceof InternalErrorException e) throw e;
            if (cause instanceof BankTimeoutException e) throw e;
            if (cause instanceof IdempotentRequestInProgressException e) throw e;
            if (cause instanceof TimeoutException e) throw e;
            throw new IllegalStateException("Unexpected failure calling the bank", cause);
        }
    }
}

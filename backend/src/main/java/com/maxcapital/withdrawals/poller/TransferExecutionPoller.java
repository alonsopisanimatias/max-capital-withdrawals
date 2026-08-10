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
 * <p><b>Phase B</b> (on {@code transferExecutionExecutor}, NO transaction, NO lock): the slow
 * bank call itself, bounded by a hard client-side timeout so an unresponsive bank can't hang a
 * worker thread forever.
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

    @Value("${withdrawals.poller.transfer-execution.batch-size}")
    private int batchSize;

    @Value("${withdrawals.poller.transfer-execution.bank-call-timeout-seconds}")
    private int bankCallTimeoutSeconds;

    // see RiskEvaluationPoller for why: self-invoking claimBatch() as a plain method call would
    // skip the Spring proxy and silently drop @Transactional(REQUIRES_NEW) on it.
    @Autowired
    @Lazy
    private TransferExecutionPoller self;

    @Scheduled(fixedDelayString = "${withdrawals.poller.transfer-execution.fixed-delay-ms}")
    public void tick() {
        for (UUID withdrawalId : self.claimBatch()) {
            transferExecutionExecutor.execute(() -> processOne(withdrawalId));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimBatch() {
        List<Withdrawal> claimed = withdrawalRepository.lockNextBatchForTransfer(batchSize);
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
        transfer.setRequestedAt(Instant.now());
        transferRepository.save(transfer);

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

        try {
            BankTransferResult result = callBankWithTimeout(transfer.getIdempotencyKey(), withdrawal);
            outcomeService.applySuccess(withdrawalId, transfer.getId(), result.bankReference(), TransferOutcomeService.ACTOR_SYSTEM_TRANSFER);
        } catch (InvalidAccountException e) {
            outcomeService.applyInvalidAccount(withdrawalId, transfer.getId(), e.getMessage(), TransferOutcomeService.ACTOR_SYSTEM_TRANSFER);
        } catch (InternalErrorException e) {
            outcomeService.applyInternalError(withdrawalId, transfer.getId(), e.getMessage(), TransferOutcomeService.ACTOR_SYSTEM_TRANSFER);
        } catch (BankTimeoutException e) {
            outcomeService.markAwaitingReconciliation(transfer.getId(), e.getMessage());
        } catch (TimeoutException e) {
            // our own client-side timeout, distinct from the bank reporting its own timeout —
            // same handling either way: we don't know what happened, reconciliation will ask
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
        CompletableFuture<BankTransferResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return bankService.executeTransfer(idempotencyKey, withdrawal.getAccountId(), withdrawal.getDestinationCbu(), withdrawal.getAmount());
            } catch (InvalidAccountException | InternalErrorException | BankTimeoutException | IdempotentRequestInProgressException e) {
                throw new CompletionException(e);
            }
        }).orTimeout(bankCallTimeoutSeconds, TimeUnit.SECONDS);

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

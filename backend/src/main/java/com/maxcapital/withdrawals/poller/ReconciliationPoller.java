package com.maxcapital.withdrawals.poller;

import com.maxcapital.withdrawals.domain.Transfer;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.external.BankQueryResult;
import com.maxcapital.withdrawals.external.BankService;
import com.maxcapital.withdrawals.repository.TransferRepository;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.service.TransferOutcomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves withdrawals stuck in PROCESSING_TRANSFER past the grace period — the only thing
 * that can ever confirm an ambiguous bank timeout (C6). {@code queryByIdempotencyKey} is
 * documented as fast, so unlike the transfer poller this doesn't need a phase-B split off the
 * transaction: claim and resolve happen in one short transaction per item.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationPoller {

    private final WithdrawalRepository withdrawalRepository;
    private final TransferRepository transferRepository;
    private final BankService bankService;
    private final TransferOutcomeService outcomeService;

    @Value("${withdrawals.poller.reconciliation.batch-size}")
    private int batchSize;

    @Value("${withdrawals.poller.reconciliation.grace-period-seconds}")
    private int gracePeriodSeconds;

    // logging only — lets the multi-instance demo show each container claiming disjoint rows
    @Value("${instance.id}")
    private String instanceId;

    // see RiskEvaluationPoller for why: self-invoking claimBatch() as a plain method call would
    // skip the Spring proxy and silently drop @Transactional(REQUIRES_NEW) on it.
    @Autowired
    @Lazy
    private ReconciliationPoller self;

    @Scheduled(fixedDelayString = "${withdrawals.poller.reconciliation.fixed-delay-ms}")
    public void tick() {
        for (UUID withdrawalId : self.claimBatch()) {
            reconcileOne(withdrawalId);
        }
    }

    /**
     * Claiming here isn't just locking: it also bumps {@code transfer.requested_at} to now,
     * inside the same transaction that holds the row lock. Without that write, the lock would
     * simply release when this short transaction commits and the row — still matching the same
     * "older than the grace period" filter — could be claimed again by another instance before
     * {@link #reconcileOne} finishes. Bumping the timestamp here is what the transfer poller
     * gets for free by writing PROCESSING_TRANSFER in its own claim step; reconciliation has no
     * separate status to move to, so it reuses requested_at as that same kind of claim marker.
     * If the actual resolution below never completes, the row simply becomes eligible again
     * after another full grace period — self-healing, not a stuck claim. Stamped via the
     * database's own clock ({@link TransferRepository#markRequestedNow}), not the JVM's — see
     * its javadoc for why that matters across 2+ instances.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimBatch() {
        List<Withdrawal> claimed = withdrawalRepository.lockNextBatchForReconciliation(batchSize, gracePeriodSeconds);
        if (!claimed.isEmpty()) {
            log.info("[{}] reconciliation claimed {} withdrawal(s): {}", instanceId, claimed.size(),
                    claimed.stream().map(Withdrawal::getId).toList());
        }
        List<UUID> ids = new ArrayList<>();
        for (Withdrawal withdrawal : claimed) {
            transferRepository.findByWithdrawalId(withdrawal.getId())
                    .ifPresent(transfer -> transferRepository.markRequestedNow(transfer.getId()));
            ids.add(withdrawal.getId());
        }
        return ids;
    }

    private void reconcileOne(UUID withdrawalId) {
        Transfer transfer = transferRepository.findByWithdrawalId(withdrawalId).orElseThrow();
        // same attempt-scoping as the transfer poller: this resolution only applies to the
        // attempt that was actually in flight when reconciliation looked it up
        int attemptCount = transfer.getAttemptCount();
        try {
            BankQueryResult result = bankService.queryByIdempotencyKey(transfer.getIdempotencyKey());
            switch (result.outcome()) {
                case APPLIED -> outcomeService.applySuccess(
                        withdrawalId, transfer.getId(), result.bankReference(), TransferOutcomeService.ACTOR_SYSTEM_RECONCILIATION, attemptCount);
                case FAILED_INVALID_ACCOUNT -> outcomeService.applyInvalidAccount(
                        withdrawalId, transfer.getId(), "Reconciliation: bank reports invalid account", TransferOutcomeService.ACTOR_SYSTEM_RECONCILIATION, attemptCount);
                case FAILED_OTHER -> outcomeService.applyInternalError(
                        withdrawalId, transfer.getId(), "Reconciliation: bank reports failure", TransferOutcomeService.ACTOR_SYSTEM_RECONCILIATION, attemptCount);
                case NOT_FOUND -> outcomeService.resetForRetryAfterReconciliation(withdrawalId, transfer.getId(), attemptCount);
            }
        } catch (RuntimeException e) {
            log.warn("Reconciliation lookup failed for withdrawal {}: {}", withdrawalId, e.getMessage());
            outcomeService.recordFailedReconciliationAttempt(withdrawalId, transfer.getId());
        }
    }
}

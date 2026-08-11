package com.maxcapital.withdrawals.poller;

import com.maxcapital.withdrawals.domain.Account;
import com.maxcapital.withdrawals.domain.Transfer;
import com.maxcapital.withdrawals.domain.TransferStatus;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.external.mock.TestBankService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C6's real test: the bank's timeout is genuinely ambiguous (it may or may not have applied),
 * and only reconciliation — never a blind retry — is allowed to resolve it. grace-period-seconds
 * is overridden to 2s in application-test.yml so these tests don't need to sleep 30s.
 */
class ReconciliationPollerTest extends WithdrawalTestSupport {

    @Autowired
    private TransferExecutionPoller transferExecutionPoller;

    @Autowired
    private ReconciliationPoller reconciliationPoller;

    @Test
    void timeoutThatDidApplyIsResolvedToExecutedByReconciliation() throws Exception {
        UUID accountId = seedAccount(new BigDecimal("100000.00"));
        Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.TIMEOUT_APPLIED_TRUE);

        waitUntilTransferStatus(authorized.getId(), TransferStatus.AWAITING_RECONCILIATION,
                Duration.ofSeconds(20), transferExecutionPoller::tick);

        Thread.sleep(2100); // past the 2s test grace period
        Withdrawal result = waitUntilStatus(authorized.getId(), WithdrawalStatus.EXECUTED,
                Duration.ofSeconds(20), reconciliationPoller::tick);

        assertThat(result.getTransferId()).isNotNull();

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("99900.00"));

        Transfer transfer = transferRepository.findByWithdrawalId(authorized.getId()).orElseThrow();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(transfer.getBankReference()).isNotBlank();
    }

    @Test
    void timeoutThatNeverAppliedIsResetAndSucceedsOnAutomaticRetry() throws Exception {
        UUID accountId = seedAccount(new BigDecimal("100000.00"));
        Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.TIMEOUT_APPLIED_FALSE);

        waitUntilTransferStatus(authorized.getId(), TransferStatus.AWAITING_RECONCILIATION,
                Duration.ofSeconds(20), transferExecutionPoller::tick);

        Thread.sleep(2100);
        // never applied -> safe to retry: back to AUTHORIZED, transfer reset to PENDING
        waitUntilStatus(authorized.getId(), WithdrawalStatus.AUTHORIZED, Duration.ofSeconds(20), reconciliationPoller::tick);
        Transfer resetTransfer = transferRepository.findByWithdrawalId(authorized.getId()).orElseThrow();
        assertThat(resetTransfer.getStatus()).isEqualTo(TransferStatus.PENDING);

        // reserve was never touched by the timeout itself — only ever released/settled on a
        // real terminal outcome, so it's still exactly where it needs to be for a retry
        Account midway = accountRepository.findById(accountId).orElseThrow();
        assertThat(midway.getReservedBalance()).isEqualByComparingTo(new BigDecimal("100.00"));

        // the automatic retry (transfer poller picks AUTHORIZED back up) now succeeds
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.SUCCESS);
        waitUntilStatus(authorized.getId(), WithdrawalStatus.EXECUTED, Duration.ofSeconds(20), transferExecutionPoller::tick);

        Account finalAccount = accountRepository.findById(accountId).orElseThrow();
        assertThat(finalAccount.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finalAccount.getBalance()).isEqualByComparingTo(new BigDecimal("99900.00"));

        // exactly two real calls were ever made to the bank for this withdrawal (the timed-out
        // one and the retry), both sharing the same idempotency key (C6) — never more than that
        assertThat(testBankService.executionCount(authorized.getIdempotencyKey())).isEqualTo(2);
    }

    /**
     * C6's other edge: reconciliation's own lookup can itself keep failing (bank query endpoint
     * down), not just come back NOT_FOUND/APPLIED/FAILED. Past MAX_RECONCILIATION_ATTEMPTS
     * (5, see TransferOutcomeService) that stops looping forever and escalates to MANUAL_REVIEW
     * — which must have a real operator-driven exit (WithdrawalService#resolveManualReview),
     * closing finding #7 from the senior review (previously unreachable AND a dead end).
     */
    @Test
    void repeatedReconciliationLookupFailuresEscalateToManualReviewWithOperatorExit() throws Exception {
        UUID accountId = seedAccount(new BigDecimal("100000.00"));
        Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.TIMEOUT_APPLIED_FALSE);
        testBankService.failQueriesFor(authorized.getIdempotencyKey());

        waitUntilTransferStatus(authorized.getId(), TransferStatus.AWAITING_RECONCILIATION,
                Duration.ofSeconds(20), transferExecutionPoller::tick);

        Withdrawal result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Thread.sleep(2100); // past the 2s test grace period, so this withdrawal is claimable again
            reconciliationPoller.tick();
            result = withdrawalRepository.findById(authorized.getId()).orElseThrow();
            if (result.getStatus() == WithdrawalStatus.MANUAL_REVIEW) {
                break;
            }
        }
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(WithdrawalStatus.MANUAL_REVIEW);

        // still ambiguous, so the reserve stays held until an operator closes it
        Account midway = accountRepository.findById(accountId).orElseThrow();
        assertThat(midway.getReservedBalance()).isEqualByComparingTo(new BigDecimal("100.00"));

        Withdrawal resolved = withdrawalService.resolveManualReview(authorized.getId(), "OPERATOR_TEST");
        assertThat(resolved.getStatus()).isEqualTo(WithdrawalStatus.FINAL_ERROR);

        Account released = accountRepository.findById(accountId).orElseThrow();
        assertThat(released.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(released.getBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    /**
     * C3 for the reconciliation poller specifically: {@code lockNextBatchForReconciliation}'s
     * SKIP LOCKED must give each of 2 simulated instances a disjoint claim WITHIN one round —
     * never two instances resolving the exact same withdrawal at the same instant. This is
     * deliberately NOT the same invariant {@code RiskEvaluationPollerTest} and
     * {@code TransferExecutionPollerTest} assert for their own claims (exactly-once call count):
     * those claims flip the withdrawal's status as part of the claim itself, so once claimed a
     * row can never be claimed again. Reconciliation's claim only bumps {@code requested_at}
     * (see {@link ReconciliationPoller#claimBatch()}'s javadoc) — a resolution that's slow
     * enough can legitimately make the row eligible again after another full grace period, and
     * that's an intentional self-healing property, not a bug. So what's actually guaranteed —
     * and what this test checks — is that the FINAL state is always correct (never
     * double-applied, guarded by {@code WithdrawalRepository#transitionFromProcessingTransfer}),
     * not that the bank query only ever runs once.
     */
    @Test
    void concurrentPollersResolveEachStuckWithdrawalWithoutDoubleApplying() throws Exception {
        int withdrawalCount = 12; // more than the batch size (5), forces multiple batches
        List<Withdrawal> stuckWithdrawals = new ArrayList<>();
        for (int i = 0; i < withdrawalCount; i++) {
            UUID accountId = seedAccount(new BigDecimal("100000.00"));
            Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
            testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.TIMEOUT_APPLIED_FALSE);
            waitUntilTransferStatus(authorized.getId(), TransferStatus.AWAITING_RECONCILIATION,
                    Duration.ofSeconds(20), transferExecutionPoller::tick);
            stuckWithdrawals.add(authorized);
        }

        Thread.sleep(2100); // past the 2s test grace period: all 12 are now claimable

        // simulates 2 backend instances polling concurrently (C3)
        int pollersSimulated = 2;
        int ticksPerPoller = 15;
        ExecutorService executor = Executors.newFixedThreadPool(pollersSimulated);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(pollersSimulated);
        // surfaced explicitly instead of swallowed — see RiskEvaluationPollerTest's equivalent test
        ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < pollersSimulated; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int t = 0; t < ticksPerPoller; t++) {
                        reconciliationPoller.tick();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpectedFailures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("both pollers should finish within 30s").isTrue();
        executor.shutdown();
        assertThat(unexpectedFailures).as("no poller thread should throw").isEmpty();

        for (Withdrawal withdrawal : stuckWithdrawals) {
            // at least once (it did get resolved), but NOT necessarily exactly once — see the
            // javadoc above for why a stricter assertion here would be testing a guarantee the
            // design doesn't actually make
            assertThat(testBankService.queryCount(withdrawal.getIdempotencyKey()))
                    .as("withdrawal %s should have been reconciled at least once", withdrawal.getId())
                    .isGreaterThanOrEqualTo(1);

            // what actually has to hold regardless of how many times it was queried: the FINAL
            // state is correct and was never double-applied (a never-applied timeout resolves
            // back to AUTHORIZED exactly once, reserve untouched throughout)
            Withdrawal reloaded = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).as("never-applied timeout resolves back to AUTHORIZED").isEqualTo(WithdrawalStatus.AUTHORIZED);

            Transfer transfer = transferRepository.findByWithdrawalId(withdrawal.getId()).orElseThrow();
            assertThat(transfer.getStatus()).as("transfer reset to PENDING for the automatic retry").isEqualTo(TransferStatus.PENDING);

            Account account = accountRepository.findById(withdrawal.getAccountId()).orElseThrow();
            assertThat(account.getReservedBalance())
                    .as("reserve for %s untouched by reconciliation itself", withdrawal.getId())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    private void waitUntilTransferStatus(UUID withdrawalId, TransferStatus target, Duration timeout, Runnable tick) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            tick.run();
            var transfer = transferRepository.findByWithdrawalId(withdrawalId);
            if (transfer.isPresent() && transfer.get().getStatus() == target) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for transfer of withdrawal " + withdrawalId + " to reach " + target);
    }
}

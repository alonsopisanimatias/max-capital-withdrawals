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

class TransferExecutionPollerTest extends WithdrawalTestSupport {

    @Autowired
    private TransferExecutionPoller transferExecutionPoller;

    @Test
    void successfulTransferSettlesBalanceAndMarksExecuted() throws Exception {
        UUID accountId = seedAccount(new BigDecimal("100000.00"));
        Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.SUCCESS);

        Withdrawal result = waitUntilStatus(authorized.getId(), WithdrawalStatus.EXECUTED,
                Duration.ofSeconds(20), transferExecutionPoller::tick);

        assertThat(result.getTransferId()).isNotNull();

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("99900.00"));

        Transfer transfer = transferRepository.findByWithdrawalId(authorized.getId()).orElseThrow();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCEEDED);
        assertThat(transfer.getBankReference()).isNotBlank();
        assertThat(transfer.getId()).isEqualTo(result.getTransferId());
    }

    @Test
    void invalidAccountEndsInFinalErrorAndReleasesReservedBalance() throws Exception {
        UUID accountId = seedAccount(new BigDecimal("100000.00"));
        Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.INVALID_ACCOUNT);

        waitUntilStatus(authorized.getId(), WithdrawalStatus.FINAL_ERROR, Duration.ofSeconds(20), transferExecutionPoller::tick);

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("100000.00")); // untouched, only released

        Transfer transfer = transferRepository.findByWithdrawalId(authorized.getId()).orElseThrow();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED_INVALID_ACCOUNT);
    }

    @Test
    void internalErrorEndsInRetryableErrorAndKeepsReservedBalance() throws Exception {
        UUID accountId = seedAccount(new BigDecimal("100000.00"));
        Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.INTERNAL_ERROR);

        waitUntilStatus(authorized.getId(), WithdrawalStatus.RETRYABLE_ERROR, Duration.ofSeconds(20), transferExecutionPoller::tick);

        // reserve is untouched: the operator can still retry and the funds need to still be held
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getReservedBalance()).isEqualByComparingTo(new BigDecimal("100.00"));

        Transfer transfer = transferRepository.findByWithdrawalId(authorized.getId()).orElseThrow();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED_INTERNAL_ERROR);
    }

    @Test
    void timeoutLeavesWithdrawalProcessingAndMarksTransferAwaitingReconciliation() throws Exception {
        UUID accountId = seedAccount(new BigDecimal("100000.00"));
        Withdrawal authorized = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
        testBankService.forKey(authorized.getIdempotencyKey(), TestBankService.ProgrammedOutcome.TIMEOUT_APPLIED_TRUE);

        // C1's real point, exercised here indirectly: an ambiguous bank outcome never gets
        // silently treated as success or failure — the withdrawal stays exactly where a human
        // (or reconciliation) can still account for it, never auto-resolved to something wrong.
        Withdrawal reloaded = waitUntilTransferStatus(authorized.getId(), TransferStatus.AWAITING_RECONCILIATION,
                Duration.ofSeconds(20), transferExecutionPoller::tick);
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.PROCESSING_TRANSFER);
    }

    /**
     * C3 for the transfer poller specifically: {@code lockNextBatchForTransfer}'s SKIP LOCKED
     * must give each of 2 simulated instances a disjoint claim, never letting the same AUTHORIZED
     * withdrawal be picked up and executed twice. Mirrors
     * {@code RiskEvaluationPollerTest#concurrentPollersNeverEvaluateTheSameWithdrawalTwice}, but
     * verified all the way through the actual bank call ({@code executionCount}), not just the claim.
     */
    @Test
    void concurrentPollersNeverExecuteTheSameTransferTwice() throws Exception {
        int withdrawalCount = 12; // more than the batch size (5), forces multiple batches
        List<Withdrawal> authorizedWithdrawals = new ArrayList<>();
        for (int i = 0; i < withdrawalCount; i++) {
            UUID accountId = seedAccount(new BigDecimal("100000.00"));
            Withdrawal withdrawal = createAuthorizedWithdrawal(accountId, new BigDecimal("100.00"));
            testBankService.forKey(withdrawal.getIdempotencyKey(), TestBankService.ProgrammedOutcome.SUCCESS);
            authorizedWithdrawals.add(withdrawal);
        }

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
                        transferExecutionPoller.tick();
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

        for (Withdrawal withdrawal : authorizedWithdrawals) {
            waitUntilStatus(withdrawal.getId(), WithdrawalStatus.EXECUTED, Duration.ofSeconds(20), transferExecutionPoller::tick);
            assertThat(testBankService.executionCount(withdrawal.getIdempotencyKey()))
                    .as("withdrawal %s should have been executed exactly once", withdrawal.getId())
                    .isEqualTo(1);
        }
    }

    /** Same polling idea as waitUntilStatus, but watching the transfer row instead of the withdrawal. */
    private Withdrawal waitUntilTransferStatus(UUID withdrawalId, TransferStatus target, Duration timeout, Runnable tick) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            tick.run();
            var transfer = transferRepository.findByWithdrawalId(withdrawalId);
            if (transfer.isPresent() && transfer.get().getStatus() == target) {
                return withdrawalRepository.findById(withdrawalId).orElseThrow();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for transfer of withdrawal " + withdrawalId + " to reach " + target);
    }
}

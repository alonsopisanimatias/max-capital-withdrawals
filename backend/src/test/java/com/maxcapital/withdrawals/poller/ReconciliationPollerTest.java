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
import java.util.UUID;

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

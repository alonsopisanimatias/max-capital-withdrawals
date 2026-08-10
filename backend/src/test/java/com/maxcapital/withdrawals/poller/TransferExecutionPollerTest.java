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

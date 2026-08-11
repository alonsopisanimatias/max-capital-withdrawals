package com.maxcapital.withdrawals.poller;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import com.maxcapital.withdrawals.domain.Account;
import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.external.mock.TestBankService;
import com.maxcapital.withdrawals.external.mock.TestRiskService;
import com.maxcapital.withdrawals.repository.AccountRepository;
import com.maxcapital.withdrawals.repository.TransferRepository;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared setup for the transfer-execution/reconciliation poller tests (C1/C6). */
abstract class WithdrawalTestSupport extends AbstractIntegrationTest {

    protected static final String CBU = "1234567890123456789012";

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected WithdrawalRepository withdrawalRepository;

    @Autowired
    protected TransferRepository transferRepository;

    @Autowired
    protected WithdrawalService withdrawalService;

    @Autowired
    protected RiskEvaluationPoller riskEvaluationPoller;

    @Autowired
    protected TestRiskService testRiskService;

    @Autowired
    protected TestBankService testBankService;

    @BeforeEach
    void resetTestDoubles() {
        testRiskService.reset();
        testBankService.reset();
    }

    protected UUID seedAccount(BigDecimal balance) {
        String shortSuffix = UUID.randomUUID().toString().substring(0, 8);
        Account account = accountRepository.save(new Account(
                UUID.randomUUID(), "ACC-" + shortSuffix, "Transfer Poller Test", balance, BigDecimal.ZERO));
        return account.getId();
    }

    /**
     * Drives a fresh withdrawal all the way to AUTHORIZED via a real (forced LOW) risk
     * evaluation. Retries processBatch() rather than assuming one call suffices — see
     * {@link #waitUntilStatus(UUID, WithdrawalStatus, Duration, Runnable)} for why.
     */
    protected Withdrawal createAuthorizedWithdrawal(UUID accountId, BigDecimal amount) throws Exception {
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, amount);
        return waitUntilStatus(withdrawal.getId(), WithdrawalStatus.AUTHORIZED, Duration.ofSeconds(20), riskEvaluationPoller::processBatch);
    }

    /** Phase B/C run asynchronously on the transfer executor — poll instead of asserting immediately. */
    protected Withdrawal waitUntilStatus(UUID withdrawalId, WithdrawalStatus target, Duration timeout) throws InterruptedException {
        return waitUntilStatus(withdrawalId, target, timeout, () -> { });
    }

    /**
     * Same as above, but re-invokes {@code tick} on every iteration instead of relying on a
     * single earlier call. Other test classes share this same Postgres instance and leave their
     * own AUTHORIZED/PROCESSING_TRANSFER withdrawals behind, so a claim batch (LIMIT batchSize,
     * oldest first) can genuinely not include this test's withdrawal on the first tick — exactly
     * the same "keeps trying" behavior @Scheduled provides for real, just driven manually since
     * scheduling is disabled in tests.
     */
    protected Withdrawal waitUntilStatus(UUID withdrawalId, WithdrawalStatus target, Duration timeout, Runnable tick) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        Withdrawal last = null;
        while (Instant.now().isBefore(deadline)) {
            tick.run();
            last = withdrawalRepository.findById(withdrawalId).orElseThrow();
            if (last.getStatus() == target) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for withdrawal " + withdrawalId + " to reach " + target
                + " (last seen: " + (last != null ? last.getStatus() : "?") + ")");
    }
}

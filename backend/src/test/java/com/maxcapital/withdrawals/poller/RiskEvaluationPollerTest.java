package com.maxcapital.withdrawals.poller;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import com.maxcapital.withdrawals.domain.Account;
import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.external.mock.TestRiskService;
import com.maxcapital.withdrawals.repository.AccountRepository;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEvaluationPollerTest extends AbstractIntegrationTest {

    private static final String CBU = "1234567890123456789012";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private RiskEvaluationPoller poller;

    @Autowired
    private TestRiskService testRiskService;

    @BeforeEach
    void resetRiskService() {
        testRiskService.reset();
    }

    private UUID seedAccount() {
        // account_number is varchar(20); a full UUID doesn't fit, an 8-char prefix does
        String shortSuffix = UUID.randomUUID().toString().substring(0, 8);
        Account account = accountRepository.save(new Account(
                UUID.randomUUID(), "ACC-" + shortSuffix, "Risk Poller Test", new BigDecimal("100000.00"), BigDecimal.ZERO));
        return account.getId();
    }

    @Test
    void lowOrMediumRiskIsAutoAuthorized() throws Exception {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("100.00"));

        Withdrawal reloaded = waitUntilEvaluated(withdrawal.getId());
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.AUTHORIZED);
        assertThat(reloaded.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(reloaded.getRiskEvaluatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedBy()).isEqualTo("SYSTEM_RISK");
    }

    @Test
    void highRiskGoesToPendingAuthorizationInsteadOfAutoApproving() throws Exception {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.HIGH);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("100.00"));

        Withdrawal reloaded = waitUntilEvaluated(withdrawal.getId());
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.PENDING_AUTHORIZATION);
        assertThat(reloaded.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void riskServiceFailureIsFailSafeToPendingAuthorization() throws Exception {
        UUID accountId = seedAccount();
        testRiskService.forAccountFails(accountId);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("100.00"));

        Withdrawal reloaded = waitUntilEvaluated(withdrawal.getId());
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.PENDING_AUTHORIZATION);
        assertThat(reloaded.getRiskLevel()).isNull(); // never resolved — the point of the fail-safe
    }

    /**
     * Retries processBatch() instead of assuming one call suffices — other test classes share
     * this same Postgres instance and can leave older EVALUATING_RISK withdrawals ahead of this
     * one in the claim batch (LIMIT batchSize, oldest first).
     */
    private Withdrawal waitUntilEvaluated(UUID withdrawalId) {
        return waitUntilEvaluated(withdrawalId, poller::processBatch);
    }

    private Withdrawal waitUntilEvaluated(UUID withdrawalId, Runnable tick) {
        long deadline = System.currentTimeMillis() + java.time.Duration.ofSeconds(20).toMillis();
        Withdrawal reloaded;
        do {
            tick.run();
            reloaded = withdrawalRepository.findById(withdrawalId).orElseThrow();
        } while (reloaded.getStatus() == WithdrawalStatus.EVALUATING_RISK && System.currentTimeMillis() < deadline);
        return reloaded;
    }

    /**
     * Regression guard for the self-invocation pitfall documented on {@link RiskEvaluationPoller#tick()}:
     * every other test here drives processBatch() directly, which never touches tick() at all —
     * if `self.processBatch()` inside tick() silently regressed back to `this.processBatch()`
     * (skipping the Spring proxy and dropping @Transactional(REQUIRES_NEW)), none of them would
     * notice. This one goes through the real @Scheduled entry point instead.
     */
    @Test
    void tickGoesThroughTheProxyAndStillEvaluatesRisk() throws Exception {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("100.00"));

        Withdrawal reloaded = waitUntilEvaluated(withdrawal.getId(), poller::tick);
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.AUTHORIZED);
        assertThat(reloaded.getRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void concurrentPollersNeverEvaluateTheSameWithdrawalTwice() throws Exception {
        int withdrawalCount = 12; // more than the batch size (5), forces multiple batches
        List<UUID> accountIds = new ArrayList<>();
        for (int i = 0; i < withdrawalCount; i++) {
            UUID accountId = seedAccount();
            testRiskService.forAccount(accountId, RiskLevel.LOW);
            withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("100.00"));
            accountIds.add(accountId);
        }

        // simulates 2 backend instances polling concurrently (C3)
        int pollersSimulated = 2;
        // generous headroom: other test classes sharing this Postgres instance may leave their
        // own EVALUATING_RISK stragglers ahead of these 12 in the claim order
        int ticksPerPoller = 15;
        ExecutorService executor = Executors.newFixedThreadPool(pollersSimulated);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(pollersSimulated);
        // surfaced explicitly instead of swallowed: submit()'s Future absorbs any exception
        // thrown inside the task silently unless something calls get() on it — without this,
        // a real bug in processBatch() would just make the assertions below fail confusingly
        // (or not at all) instead of showing the actual exception. Same pattern as
        // AccountReservationConcurrencyTest, the project's other concurrency test.
        ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < pollersSimulated; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int t = 0; t < ticksPerPoller; t++) {
                        poller.processBatch();
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

        for (UUID accountId : accountIds) {
            assertThat(testRiskService.invocationCount(accountId))
                    .as("account %s should have been evaluated exactly once", accountId)
                    .isEqualTo(1);
        }
        assertThat(withdrawalRepository.findAll().stream()
                .filter(w -> accountIds.contains(w.getAccountId())))
                .allSatisfy(w -> assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.AUTHORIZED));
    }
}

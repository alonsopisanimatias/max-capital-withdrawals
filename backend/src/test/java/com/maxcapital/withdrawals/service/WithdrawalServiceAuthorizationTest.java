package com.maxcapital.withdrawals.service;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import com.maxcapital.withdrawals.domain.Account;
import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.external.mock.TestRiskService;
import com.maxcapital.withdrawals.poller.RiskEvaluationPoller;
import com.maxcapital.withdrawals.repository.AccountRepository;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WithdrawalServiceAuthorizationTest extends AbstractIntegrationTest {

    private static final String CBU = "1234567890123456789012";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private RiskEvaluationPoller riskEvaluationPoller;

    @Autowired
    private TestRiskService testRiskService;

    @BeforeEach
    void resetRiskService() {
        testRiskService.reset();
    }

    private UUID seedAccount() {
        String shortSuffix = UUID.randomUUID().toString().substring(0, 8);
        Account account = accountRepository.save(new Account(
                UUID.randomUUID(), "ACC-" + shortSuffix, "Authorization Test", new BigDecimal("100000.00"), BigDecimal.ZERO));
        return account.getId();
    }

    /**
     * Drives a fresh withdrawal to PENDING_AUTHORIZATION the same way production does: via HIGH
     * risk. Retries processBatch() instead of assuming one call suffices — other test classes
     * share this same Postgres instance and can leave older EVALUATING_RISK withdrawals ahead
     * of this one in the claim batch (LIMIT batchSize, oldest first).
     */
    private Withdrawal createPendingAuthorization(UUID accountId) throws Exception {
        testRiskService.forAccount(accountId, RiskLevel.HIGH);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("500.00"));

        long deadline = System.currentTimeMillis() + java.time.Duration.ofSeconds(20).toMillis();
        Withdrawal reloaded;
        do {
            riskEvaluationPoller.processBatch();
            reloaded = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
        } while (reloaded.getStatus() == WithdrawalStatus.EVALUATING_RISK && System.currentTimeMillis() < deadline);

        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.PENDING_AUTHORIZATION);
        return reloaded;
    }

    @Test
    void authorizeTransitionsPendingToAuthorized() throws Exception {
        UUID accountId = seedAccount();
        Withdrawal pending = createPendingAuthorization(accountId);

        Withdrawal authorized = withdrawalService.authorize(pending.getId(), "operator-juan");

        assertThat(authorized.getStatus()).isEqualTo(WithdrawalStatus.AUTHORIZED);
        assertThat(authorized.getUpdatedBy()).isEqualTo("operator-juan");
    }

    @Test
    void rejectTransitionsToRejectedAndReleasesReservedBalance() throws Exception {
        UUID accountId = seedAccount();
        Withdrawal pending = createPendingAuthorization(accountId);

        withdrawalService.reject(pending.getId(), "operator-maria");

        Withdrawal reloaded = withdrawalRepository.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.REJECTED);
        assertThat(reloaded.getUpdatedBy()).isEqualTo("operator-maria");

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void authorizingAWithdrawalNotPendingAuthorizationFailsWithInvalidTransition() throws Exception {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("100.00"));
        // still EVALUATING_RISK: the poller hasn't run yet

        assertThrows(InvalidTransitionException.class, () -> withdrawalService.authorize(withdrawal.getId(), "operator-juan"));
    }

    /**
     * C4: two operators racing on the same withdrawal. @Version must resolve this
     * deterministically — exactly one of authorize/reject succeeds, the other fails with
     * ObjectOptimisticLockingFailureException, never both applied.
     */
    @Test
    void concurrentAuthorizeAndRejectOnSameWithdrawal_exactlyOneWins() throws Exception {
        UUID accountId = seedAccount();
        Withdrawal pending = createPendingAuthorization(accountId);
        UUID withdrawalId = pending.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> authorizeTask = () -> {
            start.await();
            withdrawalService.authorize(withdrawalId, "operator-juan");
            return "AUTHORIZED";
        };
        Callable<String> rejectTask = () -> {
            start.await();
            withdrawalService.reject(withdrawalId, "operator-maria");
            return "REJECTED";
        };

        Future<String> authorizeResult = executor.submit(authorizeTask);
        Future<String> rejectResult = executor.submit(rejectTask);
        start.countDown();

        int successes = 0;
        int conflicts = 0;
        for (Future<String> future : java.util.List.of(authorizeResult, rejectResult)) {
            try {
                future.get(30, TimeUnit.SECONDS);
                successes++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat(successes).as("exactly one request should win the race").isEqualTo(1);
        assertThat(conflicts).as("exactly one request should lose the race").isEqualTo(1);

        // final state is one or the other, never both applied, and reserved balance is
        // consistent with whichever one actually won (released if REJECTED, held if AUTHORIZED)
        Withdrawal reloaded = withdrawalRepository.findById(withdrawalId).orElseThrow();
        assertThat(reloaded.getStatus()).isIn(WithdrawalStatus.AUTHORIZED, WithdrawalStatus.REJECTED);

        Account account = accountRepository.findById(accountId).orElseThrow();
        if (reloaded.getStatus() == WithdrawalStatus.REJECTED) {
            assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        } else {
            assertThat(account.getReservedBalance()).isEqualByComparingTo(pending.getAmount());
        }
    }
}

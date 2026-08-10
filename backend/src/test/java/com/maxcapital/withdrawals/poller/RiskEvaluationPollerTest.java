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

        poller.processBatch();

        Withdrawal reloaded = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
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

        poller.processBatch();

        Withdrawal reloaded = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.PENDING_AUTHORIZATION);
        assertThat(reloaded.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void riskServiceFailureIsFailSafeToPendingAuthorization() throws Exception {
        UUID accountId = seedAccount();
        testRiskService.forAccountFails(accountId);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU, new BigDecimal("100.00"));

        poller.processBatch();

        Withdrawal reloaded = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WithdrawalStatus.PENDING_AUTHORIZATION);
        assertThat(reloaded.getRiskLevel()).isNull(); // never resolved — the point of the fail-safe
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
        int ticksPerPoller = 5; // enough to drain 12 rows in batches of 5
        ExecutorService executor = Executors.newFixedThreadPool(pollersSimulated);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(pollersSimulated);

        for (int i = 0; i < pollersSimulated; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int t = 0; t < ticksPerPoller; t++) {
                        poller.processBatch();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("both pollers should finish within 30s").isTrue();
        executor.shutdown();

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

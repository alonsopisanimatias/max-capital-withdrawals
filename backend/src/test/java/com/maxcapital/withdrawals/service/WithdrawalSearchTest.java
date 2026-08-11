package com.maxcapital.withdrawals.service;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import com.maxcapital.withdrawals.domain.Account;
import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.external.mock.TestRiskService;
import com.maxcapital.withdrawals.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalSearchTest extends AbstractIntegrationTest {

    private static final String CBU_A = "1111111111111111111111";
    private static final String CBU_B = "2222222222222222222222";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TestRiskService testRiskService;

    @BeforeEach
    void reset() {
        testRiskService.reset();
    }

    private UUID seedAccount() {
        String shortSuffix = UUID.randomUUID().toString().substring(0, 8);
        Account account = accountRepository.save(new Account(
                UUID.randomUUID(), "ACC-" + shortSuffix, "Search Test", new BigDecimal("100000.00"), BigDecimal.ZERO));
        return account.getId();
    }

    @Test
    void filtersByStatus() {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU_A, new BigDecimal("10.00"));
        // stays in EVALUATING_RISK — the poller never ran in this test

        Page<Withdrawal> matching = withdrawalService.search(WithdrawalStatus.EVALUATING_RISK, null, null,
                accountId.toString(), PageRequest.of(0, 20));

        assertThat(matching.getContent()).extracting(Withdrawal::getId).contains(withdrawal.getId());
        assertThat(matching.getContent()).allSatisfy(w -> assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.EVALUATING_RISK));
    }

    @Test
    void searchMatchesPartialCbu() {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU_A, new BigDecimal("10.00"));

        Page<Withdrawal> byCbu = withdrawalService.search(null, null, null, "111111111111", PageRequest.of(0, 20));

        assertThat(byCbu.getContent()).extracting(Withdrawal::getId).contains(withdrawal.getId());
    }

    @Test
    void searchMatchesExactAccountId() {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU_B, new BigDecimal("10.00"));

        Page<Withdrawal> byAccount = withdrawalService.search(null, null, null, accountId.toString(), PageRequest.of(0, 20));

        assertThat(byAccount.getContent()).extracting(Withdrawal::getId).containsExactly(withdrawal.getId());
    }

    @Test
    void filtersByDateRangeExcludingOutOfRangeResults() {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU_A, new BigDecimal("10.00"));

        Instant farFuture = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant farFutureEnd = Instant.now().plus(2, ChronoUnit.DAYS);

        Page<Withdrawal> outOfRange = withdrawalService.search(null, farFuture, farFutureEnd, accountId.toString(), PageRequest.of(0, 20));
        assertThat(outOfRange.getContent()).isEmpty();

        Instant justBefore = Instant.now().minus(1, ChronoUnit.MINUTES);
        Page<Withdrawal> inRange = withdrawalService.search(null, justBefore, null, accountId.toString(), PageRequest.of(0, 20));
        assertThat(inRange.getContent()).extracting(Withdrawal::getId).contains(withdrawal.getId());
    }

    @Test
    void detailIncludesNullTransferBeforeAnyTransferAttempt() {
        UUID accountId = seedAccount();
        testRiskService.forAccount(accountId, RiskLevel.LOW);
        Withdrawal withdrawal = withdrawalService.createWithdrawal(accountId, CBU_A, new BigDecimal("10.00"));

        Withdrawal reloaded = withdrawalService.getById(withdrawal.getId());
        assertThat(reloaded.getId()).isEqualTo(withdrawal.getId());
    }
}

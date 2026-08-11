package com.maxcapital.withdrawals.external;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import com.maxcapital.withdrawals.external.mock.BankMockInFlightRepository;
import com.maxcapital.withdrawals.external.mock.BankMockLedgerRepository;
import com.maxcapital.withdrawals.external.mock.BankServiceMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises only the forcing convention and the idempotency caching contract — not the
 * random-outcome distribution or the realistic latency, which would make this slow and
 * flaky. The random path is exercised indirectly by the poller-level concurrency tests
 * in later blocks, using {@link com.maxcapital.withdrawals.external.mock.TestBankService}.
 *
 * <p>Runs against real Postgres (extends {@link AbstractIntegrationTest}) because
 * {@link BankServiceMock}'s ground truth now lives in {@code bank_mock_ledger} /
 * {@code bank_mock_in_flight} (see its javadoc for why), not in an in-process map — there's no
 * pure-Java version of this logic left to unit-test in isolation anymore.
 */
class BankServiceMockTest extends AbstractIntegrationTest {

    @Autowired
    private BankMockLedgerRepository ledgerRepository;

    @Autowired
    private BankMockInFlightRepository inFlightRepository;

    // near-zero latency: exercises the exact same forcing/caching logic as the production
    // mock (3-10s) without paying the real delay in the test suite.
    private BankServiceMock bankService;

    @BeforeEach
    void setUp() {
        bankService = new BankServiceMock(ledgerRepository, inFlightRepository, 0, 5);
    }

    @Test
    void forcedInternalErrorIsNeverCached_soARetryCanSucceed() throws Exception {
        UUID key = UUID.randomUUID();
        BigDecimal forcedInternalError = new BigDecimal("100.13");
        BigDecimal forcedSuccess = new BigDecimal("100.50");

        assertThrows(InternalErrorException.class,
                () -> bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedInternalError));

        // same key, forced-success amount on retry: since InternalError isn't cached,
        // this must be allowed to succeed instead of replaying the previous failure.
        BankTransferResult result = bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedSuccess);
        assertThat(result.bankReference()).isNotBlank();
    }

    @Test
    void forcedInvalidAccountIsTerminalAndCached() {
        UUID key = UUID.randomUUID();
        BigDecimal forcedInvalidAccount = new BigDecimal("100.16");

        assertThatThrownBy(() -> bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedInvalidAccount))
                .isInstanceOf(InvalidAccountException.class);

        // replay with the same key must throw the same terminal outcome again, instantly (no new latency roll)
        assertThatThrownBy(() -> bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedInvalidAccount))
                .isInstanceOf(InvalidAccountException.class);

        assertThat(bankService.queryByIdempotencyKey(key).outcome()).isEqualTo(BankQueryResult.Outcome.FAILED_INVALID_ACCOUNT);
    }

    @Test
    void forcedTimeoutAppliedTrue_reconciliationSeesItAsApplied() {
        UUID key = UUID.randomUUID();
        BigDecimal forcedTimeoutApplied = new BigDecimal("100.14");

        assertThatThrownBy(() -> bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedTimeoutApplied))
                .isInstanceOf(BankTimeoutException.class);

        BankQueryResult query = bankService.queryByIdempotencyKey(key);
        assertThat(query.outcome()).isEqualTo(BankQueryResult.Outcome.APPLIED);
        assertThat(query.bankReference()).isNotBlank();
    }

    @Test
    void forcedTimeoutAppliedFalse_reconciliationSeesItAsNotFoundAndSafeToRetry() throws Exception {
        UUID key = UUID.randomUUID();
        BigDecimal forcedTimeoutNotApplied = new BigDecimal("100.15");

        assertThatThrownBy(() -> bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedTimeoutNotApplied))
                .isInstanceOf(BankTimeoutException.class);

        assertThat(bankService.queryByIdempotencyKey(key).outcome()).isEqualTo(BankQueryResult.Outcome.NOT_FOUND);

        // reconciliation concluded it's safe to retry: a fresh, forced-success attempt must succeed
        BankTransferResult result = bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", new BigDecimal("100.50"));
        assertThat(result.bankReference()).isNotBlank();
    }

    @Test
    void successIsTerminalAndCached_sameKeyNeverTransfersTwice() throws Exception {
        UUID key = UUID.randomUUID();
        BigDecimal forcedSuccess = new BigDecimal("100.50");

        BankTransferResult first = bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedSuccess);
        BankTransferResult replay = bankService.executeTransfer(key, UUID.randomUUID(), "1234567890123456789012", forcedSuccess);

        assertThat(replay.bankReference()).isEqualTo(first.bankReference());
    }

    @Test
    void unknownKeyQueryReturnsNotFound() {
        assertThat(bankService.queryByIdempotencyKey(UUID.randomUUID()).outcome())
                .isEqualTo(BankQueryResult.Outcome.NOT_FOUND);
    }
}

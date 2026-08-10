package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import com.maxcapital.withdrawals.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is the single most important test in the project: it exercises C5 directly, isolated
 * from the state machine and pollers, which makes failures easy to attribute to the atomic
 * UPDATE itself rather than to something upstream.
 */
class AccountReservationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void twentyConcurrentReservationsNeverExceedAvailableBalance() throws InterruptedException {
        BigDecimal startingBalance = new BigDecimal("50000.00");
        BigDecimal amountPerRequest = new BigDecimal("3000.00");
        int concurrentRequests = 20;
        // 50000 / 3000 = 16.67 -> exactly 16 requests should succeed, 4 should be rejected
        int expectedSuccesses = 16;

        Account account = accountRepository.save(new Account(
                UUID.randomUUID(), "ACC-CONCURRENCY-TEST", "Concurrency Test Account",
                startingBalance, BigDecimal.ZERO));
        UUID accountId = account.getId();

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch allThreadsReady = new CountDownLatch(concurrentRequests);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch allThreadsDone = new CountDownLatch(concurrentRequests);
        AtomicInteger successCount = new AtomicInteger();
        // surfaced explicitly instead of swallowed: a silently-eaten exception here once hid a
        // real bug (read-only transaction rejecting the UPDATE) behind a confusing "0 successes".
        ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    allThreadsReady.countDown();
                    startSignal.await();
                    int rowsAffected = accountRepository.tryReserve(accountId, amountPerRequest);
                    if (rowsAffected == 1) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpectedFailures.add(t);
                } finally {
                    allThreadsDone.countDown();
                }
            });
        }

        allThreadsReady.await();
        startSignal.countDown(); // release all 20 threads at once to maximize the race window
        boolean finished = allThreadsDone.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("all 20 requests should complete within 30s").isTrue();
        assertThat(unexpectedFailures).as("no thread should throw").isEmpty();
        assertThat(successCount.get()).isEqualTo(expectedSuccesses);

        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        BigDecimal expectedReserved = amountPerRequest.multiply(BigDecimal.valueOf(expectedSuccesses));
        assertThat(reloaded.getReservedBalance()).isEqualByComparingTo(expectedReserved);
        // the actual invariant C5 protects: reservations never exceed the real balance
        assertThat(reloaded.getReservedBalance()).isLessThanOrEqualTo(reloaded.getBalance());
    }
}

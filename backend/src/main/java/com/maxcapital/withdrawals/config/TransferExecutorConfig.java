package com.maxcapital.withdrawals.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Two separate pools, on purpose, not one:
 *
 * <p>{@code transferExecutionExecutor} dispatches phase B/C per claimed withdrawal — those
 * threads spend almost all their time blocked in {@code Future.join()}, so they're cheap and
 * there can be more of them than the real concurrency ceiling against the bank.
 *
 * <p>{@code bankCallExecutor} is that ceiling: the ONLY pool that ever calls
 * {@code BankService.executeTransfer}. This has to be a distinct pool from the dispatcher —
 * submitting the bank call back onto {@code transferExecutionExecutor} and blocking the
 * dispatcher thread on it would self-starve once every dispatcher thread is simultaneously
 * waiting for a bank-call slot that can only open up on that same exhausted pool. Sizing this
 * one (not the dispatcher) is what {@code hikari.maximum-pool-size} in application.yml is
 * dimensioned against — see DECISIONS.md section 3.
 */
@Configuration
public class TransferExecutorConfig {

    @Bean
    public ThreadPoolTaskExecutor transferExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(15);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("transfer-dispatch-");
        executor.initialize();
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor bankCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("bank-call-");
        executor.initialize();
        return executor;
    }
}

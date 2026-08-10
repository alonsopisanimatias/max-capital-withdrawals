package com.maxcapital.withdrawals.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated, bounded pool for the bank call (phase B of TransferExecutionPoller) — deliberately
 * NOT the scheduling pool, and NOT unbounded: the scheduler must stay free to keep claiming
 * batches, and an unbounded executor under load is how you get an uncontrolled number of
 * concurrent calls against the bank. This pool's size is also what hikari.maximum-pool-size
 * (application.yml) is dimensioned against — see PLAN_TECNICO_FINAL.md fix #1.
 */
@Configuration
public class TransferExecutorConfig {

    @Bean
    public ThreadPoolTaskExecutor transferExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("transfer-exec-");
        executor.initialize();
        return executor;
    }
}

package com.maxcapital.withdrawals.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Split out from the main application class so tests can disable the pollers' background
 * ticks (scheduling.enabled=false in the "test" profile) and drive them explicitly instead —
 * otherwise a @Scheduled tick firing mid-test races with the test's own direct calls.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

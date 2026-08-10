package com.maxcapital.withdrawals;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.PullPolicy;

/**
 * Base class for tests that need a real Postgres: FOR UPDATE SKIP LOCKED and the
 * conditional-UPDATE balance reservation can't be validated faithfully against H2.
 * "test" profile swaps in TestRiskService/TestBankService (instant, controllable) instead
 * of the realistic sleepy mocks, which are only meant for manual/demo runs.
 *
 * <p>Singleton container pattern (deliberately NOT {@code @Testcontainers}/{@code @Container}):
 * that JUnit extension ties start/stop to each test CLASS's lifecycle, but a static field
 * declared here is the same field shared by every subclass — one class's afterAll was
 * stopping the container while a different subclass's tests were still using it, causing
 * intermittent connection failures. Starting it once in a static initializer and letting
 * Testcontainers' own JVM shutdown hook clean it up avoids that entirely.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withImagePullPolicy(PullPolicy.alwaysPull());

    static {
        POSTGRES.start();
    }
}

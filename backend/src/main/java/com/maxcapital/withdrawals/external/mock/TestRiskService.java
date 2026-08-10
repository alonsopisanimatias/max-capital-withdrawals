package com.maxcapital.withdrawals.external.mock;

import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.external.RiskEvaluationException;
import com.maxcapital.withdrawals.external.RiskService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Instant, fully controllable stand-in used by automated tests. Defaults to LOW for every
 * account; program a specific outcome per account with {@link #forAccount}, or a blanket
 * rule with {@link #always}. Also counts invocations per account so pollers under
 * concurrency tests can assert "evaluated exactly once".
 */
@Component
@Profile("test")
public class TestRiskService implements RiskService {

    private final ConcurrentHashMap<UUID, Function<BigDecimal, RiskLevel>> perAccountOutcome = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicInteger> invocationCounts = new ConcurrentHashMap<>();
    private volatile Function<BigDecimal, RiskLevel> defaultOutcome = amount -> RiskLevel.LOW;

    @Override
    public RiskLevel evaluate(UUID accountId, BigDecimal amount) throws RiskEvaluationException {
        invocationCounts.computeIfAbsent(accountId, id -> new AtomicInteger()).incrementAndGet();
        Function<BigDecimal, RiskLevel> outcome = perAccountOutcome.getOrDefault(accountId, defaultOutcome);
        RiskLevel result = outcome.apply(amount);
        if (result == null) {
            throw new RiskEvaluationException("Programmed failure for account " + accountId);
        }
        return result;
    }

    public void forAccount(UUID accountId, RiskLevel level) {
        perAccountOutcome.put(accountId, amount -> level);
    }

    /** Pass null to make evaluation fail (RiskEvaluationException) for this account. */
    public void forAccountFails(UUID accountId) {
        perAccountOutcome.put(accountId, amount -> null);
    }

    public void always(RiskLevel level) {
        defaultOutcome = amount -> level;
    }

    public int invocationCount(UUID accountId) {
        return invocationCounts.getOrDefault(accountId, new AtomicInteger()).get();
    }

    public void reset() {
        perAccountOutcome.clear();
        invocationCounts.clear();
        defaultOutcome = amount -> RiskLevel.LOW;
    }
}

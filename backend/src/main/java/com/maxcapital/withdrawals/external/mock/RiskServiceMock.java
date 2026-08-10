package com.maxcapital.withdrawals.external.mock;

import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.external.RiskEvaluationException;
import com.maxcapital.withdrawals.external.RiskService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Local stand-in for the risk evaluation service. Real behavior (1-3s delay, ~15% failure
 * rate, weighted risk levels) is used for manual/demo runs; specific outcomes can be forced
 * via the amount's last two decimal digits for exploratory testing without touching code:
 *   .96 -> LOW, .97 -> MEDIUM, .98 -> forces a RiskEvaluationException, .99 -> HIGH.
 * Poller-level automated tests use {@link TestRiskService} instead (see its profile), which
 * is instant and fully controllable and tracks invocation counts; this class's own forcing
 * convention is unit-tested directly with the near-zero-latency constructor below.
 */
@Component
@Profile("!test")
public class RiskServiceMock implements RiskService {

    private final int minLatencyMs;
    private final int maxLatencyMs;

    public RiskServiceMock() {
        this(1000, 3000);
    }

    /** For tests that need to exercise the real forcing logic without paying the realistic delay. */
    public RiskServiceMock(int minLatencyMs, int maxLatencyMs) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    @Override
    public RiskLevel evaluate(UUID accountId, BigDecimal amount) throws RiskEvaluationException {
        int forcingDigits = forcingDigits(amount);
        simulateLatency();

        return switch (forcingDigits) {
            case 96 -> RiskLevel.LOW;
            case 97 -> RiskLevel.MEDIUM;
            case 98 -> throw new RiskEvaluationException("Forced failure via amount convention (.98)");
            case 99 -> RiskLevel.HIGH;
            default -> randomOutcome();
        };
    }

    private RiskLevel randomOutcome() throws RiskEvaluationException {
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < 0.15) {
            throw new RiskEvaluationException("Risk service unavailable");
        }
        double levelRoll = ThreadLocalRandom.current().nextDouble();
        if (levelRoll < 0.70) {
            return RiskLevel.LOW;
        } else if (levelRoll < 0.90) {
            return RiskLevel.MEDIUM;
        } else {
            return RiskLevel.HIGH;
        }
    }

    private void simulateLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minLatencyMs, maxLatencyMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int forcingDigits(BigDecimal amount) {
        return amount.remainder(BigDecimal.ONE)
                .movePointRight(2)
                .abs()
                .intValue();
    }
}

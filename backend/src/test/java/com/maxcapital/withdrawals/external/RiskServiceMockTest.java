package com.maxcapital.withdrawals.external;

import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.external.mock.RiskServiceMock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskServiceMockTest {

    private final RiskServiceMock riskService = new RiskServiceMock(0, 5);

    @Test
    void forcingConventionSelectsEachRiskLevel() throws Exception {
        assertThat(riskService.evaluate(UUID.randomUUID(), new BigDecimal("100.96"))).isEqualTo(RiskLevel.LOW);
        assertThat(riskService.evaluate(UUID.randomUUID(), new BigDecimal("100.97"))).isEqualTo(RiskLevel.MEDIUM);
        assertThat(riskService.evaluate(UUID.randomUUID(), new BigDecimal("100.99"))).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void forcingConventionCanForceAFailure() {
        assertThrows(RiskEvaluationException.class,
                () -> riskService.evaluate(UUID.randomUUID(), new BigDecimal("100.98")));
    }
}

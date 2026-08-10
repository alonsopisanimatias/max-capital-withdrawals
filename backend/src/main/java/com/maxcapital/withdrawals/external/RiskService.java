package com.maxcapital.withdrawals.external;

import com.maxcapital.withdrawals.domain.RiskLevel;

import java.math.BigDecimal;
import java.util.UUID;

public interface RiskService {

    /**
     * Evaluates the risk of a withdrawal. Takes 1s+ and fails ~15% of the time in the
     * real (mocked) service — callers must never invoke this from a request thread (C2).
     */
    RiskLevel evaluate(UUID accountId, BigDecimal amount) throws RiskEvaluationException;
}

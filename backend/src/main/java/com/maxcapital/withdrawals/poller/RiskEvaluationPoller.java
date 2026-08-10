package com.maxcapital.withdrawals.poller;

import com.maxcapital.withdrawals.domain.RiskLevel;
import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.domain.WithdrawalStatusHistory;
import com.maxcapital.withdrawals.external.RiskEvaluationException;
import com.maxcapital.withdrawals.external.RiskService;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.repository.WithdrawalStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates risk for withdrawals in EVALUATING_RISK, off the request thread (C2), and never
 * lets the same withdrawal be picked up by two instances at once (C3, via SKIP LOCKED).
 *
 * <p>The claim (SKIP LOCKED) and the resulting status write happen in the SAME transaction,
 * so the row stays locked for the full ~1-3s risk call — a deliberate trade-off documented in
 * PLAN_TECNICO_FINAL.md: bounded batch size keeps this defensible without needing the two-phase
 * split the transfer poller uses (that split exists for C6's timeout ambiguity, which risk
 * evaluation doesn't have — it's a read-only check with no external side effect to reconcile).
 *
 * <p>Fail-safe: any failure evaluating risk (including the service being unavailable) routes
 * to PENDING_AUTHORIZATION, never to an automatic AUTHORIZED — a human decides when risk is
 * unknown, not the absence of an answer.
 */
@Component
@RequiredArgsConstructor
public class RiskEvaluationPoller {

    private static final String ACTOR_SYSTEM_RISK = "SYSTEM_RISK";

    private final WithdrawalRepository withdrawalRepository;
    private final WithdrawalStatusHistoryRepository historyRepository;
    private final RiskService riskService;

    @Value("${withdrawals.poller.risk-evaluation.batch-size}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${withdrawals.poller.risk-evaluation.fixed-delay-ms}")
    public void tick() {
        processBatch();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processBatch() {
        List<Withdrawal> claimed = withdrawalRepository.lockNextBatchForRiskEvaluation(batchSize);
        for (Withdrawal withdrawal : claimed) {
            evaluateOne(withdrawal);
        }
    }

    private void evaluateOne(Withdrawal withdrawal) {
        WithdrawalStatus previousStatus = withdrawal.getStatus();
        WithdrawalStatus newStatus;

        try {
            RiskLevel risk = riskService.evaluate(withdrawal.getAccountId(), withdrawal.getAmount());
            withdrawal.setRiskLevel(risk);
            withdrawal.setRiskEvaluatedAt(Instant.now());
            newStatus = (risk == RiskLevel.HIGH) ? WithdrawalStatus.PENDING_AUTHORIZATION : WithdrawalStatus.AUTHORIZED;
        } catch (RiskEvaluationException e) {
            // fail-safe: an unknown risk is treated as high risk, never as automatic approval
            newStatus = WithdrawalStatus.PENDING_AUTHORIZATION;
        }

        withdrawal.setStatus(newStatus);
        withdrawal.setUpdatedAt(Instant.now());
        withdrawal.setUpdatedBy(ACTOR_SYSTEM_RISK);
        withdrawalRepository.save(withdrawal);

        historyRepository.save(new WithdrawalStatusHistory(
                UUID.randomUUID(), withdrawal.getId(), previousStatus, newStatus, ACTOR_SYSTEM_RISK, Instant.now()));
    }
}

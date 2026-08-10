package com.maxcapital.withdrawals.service;

import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.domain.WithdrawalStatusHistory;
import com.maxcapital.withdrawals.repository.AccountRepository;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.repository.WithdrawalStatusHistoryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private static final String ACTOR_CLIENT = "CLIENT";

    private final AccountRepository accountRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final WithdrawalStatusHistoryRepository historyRepository;

    /**
     * Authorizes a withdrawal pending manual review. The precondition check (status must be
     * PENDING_AUTHORIZATION) defends against acting on the wrong state at all; @Version is
     * what actually resolves a race between two operators deterministically (C4) — whichever
     * request's UPDATE commits first wins, the other fails the version check on flush and
     * throws ObjectOptimisticLockingFailureException (mapped to 409 in GlobalExceptionHandler).
     */
    @Transactional
    public Withdrawal authorize(UUID withdrawalId, String operatorId) {
        Withdrawal withdrawal = loadForTransition(withdrawalId);
        WithdrawalStatus previousStatus = withdrawal.getStatus();

        withdrawal.setStatus(WithdrawalStatus.AUTHORIZED);
        withdrawal.setUpdatedAt(Instant.now());
        withdrawal.setUpdatedBy(operatorId);
        withdrawalRepository.save(withdrawal);

        recordTransition(withdrawal.getId(), previousStatus, WithdrawalStatus.AUTHORIZED, operatorId);
        return withdrawal;
    }

    /**
     * Rejects a withdrawal and releases its reserved balance in the SAME transaction as the
     * status transition — if these were split (e.g. release in a separate REQUIRES_NEW), the
     * loser of a C4 race could still release reserve for a withdrawal an operator just
     * authorized, since REQUIRES_NEW wouldn't roll back alongside the failed status update.
     */
    @Transactional
    public Withdrawal reject(UUID withdrawalId, String operatorId) {
        Withdrawal withdrawal = loadForTransition(withdrawalId);
        WithdrawalStatus previousStatus = withdrawal.getStatus();

        withdrawal.setStatus(WithdrawalStatus.REJECTED);
        withdrawal.setUpdatedAt(Instant.now());
        withdrawal.setUpdatedBy(operatorId);
        withdrawalRepository.save(withdrawal);
        accountRepository.release(withdrawal.getAccountId(), withdrawal.getAmount());

        recordTransition(withdrawal.getId(), previousStatus, WithdrawalStatus.REJECTED, operatorId);
        return withdrawal;
    }

    private Withdrawal loadForTransition(UUID withdrawalId) {
        Withdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal not found: " + withdrawalId));
        if (withdrawal.getStatus() != WithdrawalStatus.PENDING_AUTHORIZATION) {
            throw new InvalidTransitionException(
                    "Withdrawal " + withdrawalId + " is not pending authorization (current status: " + withdrawal.getStatus() + ")",
                    withdrawalId, withdrawal.getStatus(), withdrawal.getUpdatedBy());
        }
        return withdrawal;
    }

    private void recordTransition(UUID withdrawalId, WithdrawalStatus previousStatus, WithdrawalStatus newStatus, String actor) {
        historyRepository.save(new WithdrawalStatusHistory(
                UUID.randomUUID(), withdrawalId, previousStatus, newStatus, actor, Instant.now()));
    }

    /**
     * Reserves the requested amount and creates the withdrawal in EVALUATING_RISK. The
     * reservation and the insert happen in the same transaction: if anything after the
     * reservation fails, the reservation itself rolls back too (no leaked reserved balance).
     */
    @Transactional
    public Withdrawal createWithdrawal(UUID accountId, String destinationCbu, BigDecimal amount) {
        int rowsReserved = accountRepository.tryReserve(accountId, amount);
        if (rowsReserved == 0) {
            if (!accountRepository.existsById(accountId)) {
                throw new EntityNotFoundException("Account not found: " + accountId);
            }
            throw new InsufficientBalanceException("Insufficient available balance for account " + accountId);
        }

        Instant now = Instant.now();
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setId(UUID.randomUUID());
        withdrawal.setAccountId(accountId);
        withdrawal.setDestinationCbu(destinationCbu);
        withdrawal.setAmount(amount);
        withdrawal.setStatus(WithdrawalStatus.EVALUATING_RISK);
        withdrawal.setIdempotencyKey(UUID.randomUUID());
        withdrawal.setCreatedAt(now);
        withdrawal.setUpdatedAt(now);
        withdrawal.setUpdatedBy(ACTOR_CLIENT);
        withdrawalRepository.save(withdrawal);
        recordTransition(withdrawal.getId(), null, WithdrawalStatus.EVALUATING_RISK, ACTOR_CLIENT);

        return withdrawal;
    }
}

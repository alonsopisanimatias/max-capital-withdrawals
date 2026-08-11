package com.maxcapital.withdrawals.external.mock;

import com.maxcapital.withdrawals.external.BankQueryResult;
import com.maxcapital.withdrawals.external.BankService;
import com.maxcapital.withdrawals.external.BankTimeoutException;
import com.maxcapital.withdrawals.external.BankTransferResult;
import com.maxcapital.withdrawals.external.IdempotentRequestInProgressException;
import com.maxcapital.withdrawals.external.InternalErrorException;
import com.maxcapital.withdrawals.external.InvalidAccountException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instant, fully controllable stand-in used by automated tests. Applies the same caching
 * semantics as {@link BankServiceMock} (success/invalid-account terminal and cached,
 * internal-error not cached, timeout pre-decides and stores ground truth) so C6 tests
 * exercise the real contract, just without the 3-10s sleep. Program the FIRST-attempt
 * outcome per idempotency key with {@link #forKey}; defaults to success.
 */
@Component
@Profile("test")
public class TestBankService implements BankService {

    public enum ProgrammedOutcome {
        SUCCESS, INVALID_ACCOUNT, INTERNAL_ERROR, TIMEOUT_APPLIED_TRUE, TIMEOUT_APPLIED_FALSE
    }

    private sealed interface StoredOutcome permits Applied, RejectedInvalidAccount, TimeoutGroundTruth {
    }

    private record Applied(String bankReference) implements StoredOutcome {
    }

    private record RejectedInvalidAccount() implements StoredOutcome {
    }

    private record TimeoutGroundTruth(boolean applied, String bankReferenceIfApplied) implements StoredOutcome {
    }

    private final Map<UUID, ProgrammedOutcome> programmedOutcomes = new ConcurrentHashMap<>();
    private final Map<UUID, StoredOutcome> store = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AtomicInteger> executionCounts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> queryCounts = new ConcurrentHashMap<>();
    private final Set<UUID> failingQueries = ConcurrentHashMap.newKeySet();

    @Override
    public BankTransferResult executeTransfer(UUID idempotencyKey, UUID accountId, String destinationCbu, BigDecimal amount)
            throws InvalidAccountException, InternalErrorException, BankTimeoutException, IdempotentRequestInProgressException {

        BankTransferResult replay = tryReplayTerminalOutcome(idempotencyKey);
        if (replay != null) {
            return replay;
        }
        if (!inFlight.add(idempotencyKey)) {
            throw new IdempotentRequestInProgressException("Already in flight: " + idempotencyKey);
        }
        try {
            executionCounts.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
            ProgrammedOutcome outcome = programmedOutcomes.getOrDefault(idempotencyKey, ProgrammedOutcome.SUCCESS);
            switch (outcome) {
                case INTERNAL_ERROR -> throw new InternalErrorException("Programmed internal error");
                case TIMEOUT_APPLIED_TRUE -> {
                    store.put(idempotencyKey, new TimeoutGroundTruth(true, generateBankReference()));
                    throw new BankTimeoutException("Programmed timeout, applied=true");
                }
                case TIMEOUT_APPLIED_FALSE -> {
                    store.put(idempotencyKey, new TimeoutGroundTruth(false, null));
                    throw new BankTimeoutException("Programmed timeout, applied=false");
                }
                case INVALID_ACCOUNT -> {
                    store.put(idempotencyKey, new RejectedInvalidAccount());
                    throw new InvalidAccountException("Programmed invalid account");
                }
                case SUCCESS -> {
                    String reference = generateBankReference();
                    store.put(idempotencyKey, new Applied(reference));
                    return new BankTransferResult(reference);
                }
            }
            throw new IllegalStateException("unreachable");
        } finally {
            inFlight.remove(idempotencyKey);
        }
    }

    @Override
    public BankQueryResult queryByIdempotencyKey(UUID idempotencyKey) {
        queryCounts.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
        if (failingQueries.contains(idempotencyKey)) {
            throw new RuntimeException("Programmed reconciliation lookup failure for " + idempotencyKey);
        }
        StoredOutcome stored = store.get(idempotencyKey);
        if (stored == null) {
            return BankQueryResult.notFound();
        }
        return switch (stored) {
            case Applied a -> new BankQueryResult(BankQueryResult.Outcome.APPLIED, a.bankReference());
            case RejectedInvalidAccount ignored -> new BankQueryResult(BankQueryResult.Outcome.FAILED_INVALID_ACCOUNT, null);
            case TimeoutGroundTruth t when t.applied() -> new BankQueryResult(BankQueryResult.Outcome.APPLIED, t.bankReferenceIfApplied());
            case TimeoutGroundTruth ignored -> BankQueryResult.notFound();
        };
    }

    private BankTransferResult tryReplayTerminalOutcome(UUID idempotencyKey) throws InvalidAccountException {
        StoredOutcome stored = store.get(idempotencyKey);
        if (stored == null) {
            return null;
        }
        return switch (stored) {
            case Applied a -> new BankTransferResult(a.bankReference());
            case RejectedInvalidAccount ignored -> throw new InvalidAccountException("Cached: destination account is invalid");
            case TimeoutGroundTruth t when t.applied() -> new BankTransferResult(t.bankReferenceIfApplied());
            case TimeoutGroundTruth ignored -> {
                store.remove(idempotencyKey);
                yield null;
            }
        };
    }

    /** Programs the outcome of the FIRST executeTransfer attempt for this key. Retries after a
     *  non-cached outcome (INTERNAL_ERROR) reuse this same programmed outcome unless reprogrammed. */
    public void forKey(UUID idempotencyKey, ProgrammedOutcome outcome) {
        programmedOutcomes.put(idempotencyKey, outcome);
    }

    public int executionCount(UUID idempotencyKey) {
        return executionCounts.getOrDefault(idempotencyKey, new AtomicInteger()).get();
    }

    public int queryCount(UUID idempotencyKey) {
        return queryCounts.getOrDefault(idempotencyKey, new AtomicInteger()).get();
    }

    /** Makes every queryByIdempotencyKey call for this key throw, simulating a reconciliation
     *  lookup that keeps failing (e.g. bank query endpoint down) until MANUAL_REVIEW kicks in. */
    public void failQueriesFor(UUID idempotencyKey) {
        failingQueries.add(idempotencyKey);
    }

    private String generateBankReference() {
        return "BANK-TEST-" + UUID.randomUUID();
    }

    public void reset() {
        programmedOutcomes.clear();
        store.clear();
        inFlight.clear();
        executionCounts.clear();
        queryCounts.clear();
        failingQueries.clear();
    }
}

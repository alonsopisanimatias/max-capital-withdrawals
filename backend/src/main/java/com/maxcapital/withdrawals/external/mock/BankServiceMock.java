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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Local stand-in for the bank transfer service. Real behavior (3-10s delay) is used for
 * manual/demo runs; specific outcomes can be forced via the amount's last two decimal
 * digits: .13 -> InternalErrorException, .14 -> timeout where the transfer DID apply on
 * the bank's side, .15 -> timeout where it did NOT apply, .16 -> InvalidAccountException,
 * .50 -> guaranteed success (useful to deterministically retry after a forced failure).
 *
 * <p>Caching semantics per idempotency key (this is the part that makes C6 testable):
 * <ul>
 *   <li>Success and InvalidAccountException are terminal and cached — replaying the same
 *       key returns/throws the same outcome without re-executing anything.</li>
 *   <li>InternalErrorException is NOT cached — every call is an independent attempt, so a
 *       manual retry can succeed. Caching it would trap the operator in a permanent error
 *       loop.</li>
 *   <li>A timeout pre-decides internally whether the transfer applied or not <b>before</b>
 *       throwing, and stores that ground truth for {@link #queryByIdempotencyKey}, which is
 *       the only thing allowed to resolve it — {@code executeTransfer} never re-decides an
 *       ambiguous timeout on its own.</li>
 *   <li>A second call while the first is still in flight throws
 *       {@link IdempotentRequestInProgressException} instead of running twice concurrently.</li>
 * </ul>
 * Automated tests use {@link TestBankService} instead — see its profile.
 */
@Component
@Profile("!test")
public class BankServiceMock implements BankService {

    private sealed interface StoredOutcome permits Applied, RejectedInvalidAccount, TimeoutGroundTruth {
    }

    private record Applied(String bankReference) implements StoredOutcome {
    }

    private record RejectedInvalidAccount() implements StoredOutcome {
    }

    private record TimeoutGroundTruth(boolean applied, String bankReferenceIfApplied) implements StoredOutcome {
    }

    private final Map<UUID, StoredOutcome> store = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final int minLatencyMs;
    private final int maxLatencyMs;

    public BankServiceMock() {
        this(3000, 10000);
    }

    /** For tests that need to exercise the real forcing/caching logic without paying the realistic delay. */
    public BankServiceMock(int minLatencyMs, int maxLatencyMs) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    @Override
    public BankTransferResult executeTransfer(UUID idempotencyKey, UUID accountId, String destinationCbu, BigDecimal amount)
            throws InvalidAccountException, InternalErrorException, BankTimeoutException, IdempotentRequestInProgressException {

        BankTransferResult replay = tryReplayTerminalOutcome(idempotencyKey);
        if (replay != null) {
            return replay;
        }

        if (!inFlight.add(idempotencyKey)) {
            throw new IdempotentRequestInProgressException("A transfer for this idempotency key is already in flight: " + idempotencyKey);
        }
        try {
            int forcingDigits = forcingDigits(amount);
            simulateLatency();
            resolveOutcome(idempotencyKey, forcingDigits);
            // resolveOutcome always either returns normally after storing Applied, or throws
            StoredOutcome stored = store.get(idempotencyKey);
            return new BankTransferResult(((Applied) stored).bankReference());
        } finally {
            inFlight.remove(idempotencyKey);
        }
    }

    @Override
    public BankQueryResult queryByIdempotencyKey(UUID idempotencyKey) {
        StoredOutcome stored = store.get(idempotencyKey);
        if (stored == null) {
            return BankQueryResult.notFound();
        }
        return switch (stored) {
            case Applied a -> new BankQueryResult(BankQueryResult.Outcome.APPLIED, a.bankReference());
            case RejectedInvalidAccount ignored -> new BankQueryResult(BankQueryResult.Outcome.FAILED_INVALID_ACCOUNT, null);
            case TimeoutGroundTruth t when t.applied() -> new BankQueryResult(BankQueryResult.Outcome.APPLIED, t.bankReferenceIfApplied());
            case TimeoutGroundTruth ignored -> BankQueryResult.notFound(); // never really applied: safe to retry, same as no record
        };
    }

    /** Terminal outcomes (and applied timeouts) replay without re-executing; returns null if a fresh attempt is needed. */
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
                // never really applied: treat as if there was no prior attempt, allow a fresh one
                store.remove(idempotencyKey);
                yield null;
            }
        };
    }

    private void resolveOutcome(UUID idempotencyKey, int forcingDigits)
            throws InvalidAccountException, InternalErrorException, BankTimeoutException {
        switch (forcingDigits) {
            case 13 -> throw new InternalErrorException("Forced internal error via amount convention (.13)");
            case 14 -> {
                storeTimeoutGroundTruth(idempotencyKey, true);
                throw new BankTimeoutException("Forced timeout, applied=true via amount convention (.14)");
            }
            case 15 -> {
                storeTimeoutGroundTruth(idempotencyKey, false);
                throw new BankTimeoutException("Forced timeout, applied=false via amount convention (.15)");
            }
            case 16 -> {
                store.put(idempotencyKey, new RejectedInvalidAccount());
                throw new InvalidAccountException("Forced invalid account via amount convention (.16)");
            }
            case 50 -> store.put(idempotencyKey, new Applied(generateBankReference()));
            default -> resolveRandomOutcome(idempotencyKey);
        }
    }

    private void resolveRandomOutcome(UUID idempotencyKey) throws InvalidAccountException, InternalErrorException, BankTimeoutException {
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < 0.05) {
            throw new InternalErrorException("Bank internal error");
        } else if (roll < 0.08) {
            boolean applied = ThreadLocalRandom.current().nextBoolean();
            storeTimeoutGroundTruth(idempotencyKey, applied);
            throw new BankTimeoutException("Bank did not respond in time");
        } else if (roll < 0.10) {
            store.put(idempotencyKey, new RejectedInvalidAccount());
            throw new InvalidAccountException("Destination account is invalid");
        }
        store.put(idempotencyKey, new Applied(generateBankReference()));
    }

    private void storeTimeoutGroundTruth(UUID idempotencyKey, boolean applied) {
        String reference = applied ? generateBankReference() : null;
        store.put(idempotencyKey, new TimeoutGroundTruth(applied, reference));
    }

    private String generateBankReference() {
        return "BANK-" + UUID.randomUUID();
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

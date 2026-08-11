package com.maxcapital.withdrawals.external.mock;

import com.maxcapital.withdrawals.external.BankQueryResult;
import com.maxcapital.withdrawals.external.BankService;
import com.maxcapital.withdrawals.external.BankTimeoutException;
import com.maxcapital.withdrawals.external.BankTransferResult;
import com.maxcapital.withdrawals.external.IdempotentRequestInProgressException;
import com.maxcapital.withdrawals.external.InternalErrorException;
import com.maxcapital.withdrawals.external.InvalidAccountException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Local stand-in for the bank transfer service. Real behavior (3-10s delay) is used for
 * manual/demo runs; specific outcomes can be forced via the amount's last two decimal
 * digits: .13 -> InternalErrorException, .14 -> timeout where the transfer DID apply on
 * the bank's side, .15 -> timeout where it did NOT apply, .16 -> InvalidAccountException,
 * .50 -> guaranteed success (useful to deterministically retry after a forced failure).
 *
 * <p>Ground truth is persisted in {@code bank_mock_ledger} / {@code bank_mock_in_flight}
 * (via {@link BankMockLedgerRepository} / {@link BankMockInFlightRepository}), not held in an
 * in-process map. A real bank has exactly one ground truth regardless of who's asking; an
 * in-memory map gave this mock a different one PER BACKEND INSTANCE. Under the docker-compose
 * C3 demo (2+ instances), instance A could cache an outcome in its own heap that instance B's
 * reconciliation — landing on a different JVM entirely — would never see, and would then
 * misread as NOT_FOUND. Persisting it is what makes {@link #queryByIdempotencyKey} actually
 * answer for "the bank" instead of for "whichever instance happened to call executeTransfer".
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
 *       {@link IdempotentRequestInProgressException} instead of running twice concurrently —
 *       enforced by {@link BankMockInFlightRepository#tryClaim}'s primary-key uniqueness, so
 *       it holds across instances too, not just within one JVM.</li>
 * </ul>
 * Automated tests use {@link TestBankService} instead — see its profile.
 */
@Component
@Profile("!test")
public class BankServiceMock implements BankService {

    private final BankMockLedgerRepository ledgerRepository;
    private final BankMockInFlightRepository inFlightRepository;
    private final int minLatencyMs;
    private final int maxLatencyMs;

    @Autowired
    public BankServiceMock(BankMockLedgerRepository ledgerRepository, BankMockInFlightRepository inFlightRepository) {
        this(ledgerRepository, inFlightRepository, 3000, 10000);
    }

    /** For tests that need to exercise the real forcing/caching logic without paying the realistic delay. */
    public BankServiceMock(BankMockLedgerRepository ledgerRepository, BankMockInFlightRepository inFlightRepository,
                            int minLatencyMs, int maxLatencyMs) {
        this.ledgerRepository = ledgerRepository;
        this.inFlightRepository = inFlightRepository;
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

        if (inFlightRepository.tryClaim(idempotencyKey) == 0) {
            throw new IdempotentRequestInProgressException("A transfer for this idempotency key is already in flight: " + idempotencyKey);
        }
        try {
            int forcingDigits = forcingDigits(amount);
            simulateLatency();
            return resolveOutcome(idempotencyKey, forcingDigits);
        } finally {
            inFlightRepository.release(idempotencyKey);
        }
    }

    @Override
    public BankQueryResult queryByIdempotencyKey(UUID idempotencyKey) {
        return ledgerRepository.findById(idempotencyKey)
                .map(BankServiceMock::toQueryResult)
                .orElseGet(BankQueryResult::notFound);
    }

    private static BankQueryResult toQueryResult(BankMockLedgerEntry entry) {
        return switch (entry.getOutcome()) {
            case APPLIED, TIMEOUT_APPLIED_TRUE -> new BankQueryResult(BankQueryResult.Outcome.APPLIED, entry.getBankReference());
            case REJECTED_INVALID_ACCOUNT -> new BankQueryResult(BankQueryResult.Outcome.FAILED_INVALID_ACCOUNT, null);
            case TIMEOUT_APPLIED_FALSE -> BankQueryResult.notFound(); // never really applied: safe to retry, same as no record
        };
    }

    /** Terminal outcomes (and applied timeouts) replay without re-executing; returns null if a fresh attempt is needed. */
    private BankTransferResult tryReplayTerminalOutcome(UUID idempotencyKey) throws InvalidAccountException {
        Optional<BankMockLedgerEntry> existing = ledgerRepository.findById(idempotencyKey);
        if (existing.isEmpty()) {
            return null;
        }
        BankMockLedgerEntry entry = existing.get();
        return switch (entry.getOutcome()) {
            case APPLIED, TIMEOUT_APPLIED_TRUE -> new BankTransferResult(entry.getBankReference());
            case REJECTED_INVALID_ACCOUNT -> throw new InvalidAccountException("Cached: destination account is invalid");
            case TIMEOUT_APPLIED_FALSE -> {
                // never really applied: treat as if there was no prior attempt, allow a fresh one
                ledgerRepository.deleteById(idempotencyKey);
                yield null;
            }
        };
    }

    private BankTransferResult resolveOutcome(UUID idempotencyKey, int forcingDigits)
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
                ledgerRepository.save(new BankMockLedgerEntry(idempotencyKey, BankMockLedgerEntry.Outcome.REJECTED_INVALID_ACCOUNT, null));
                throw new InvalidAccountException("Forced invalid account via amount convention (.16)");
            }
            case 50 -> {
                return storeApplied(idempotencyKey);
            }
            default -> {
                return resolveRandomOutcome(idempotencyKey);
            }
        }
    }

    private BankTransferResult resolveRandomOutcome(UUID idempotencyKey) throws InvalidAccountException, InternalErrorException, BankTimeoutException {
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < 0.05) {
            throw new InternalErrorException("Bank internal error");
        } else if (roll < 0.08) {
            boolean applied = ThreadLocalRandom.current().nextBoolean();
            storeTimeoutGroundTruth(idempotencyKey, applied);
            throw new BankTimeoutException("Bank did not respond in time");
        } else if (roll < 0.10) {
            ledgerRepository.save(new BankMockLedgerEntry(idempotencyKey, BankMockLedgerEntry.Outcome.REJECTED_INVALID_ACCOUNT, null));
            throw new InvalidAccountException("Destination account is invalid");
        }
        return storeApplied(idempotencyKey);
    }

    private BankTransferResult storeApplied(UUID idempotencyKey) {
        String reference = generateBankReference();
        ledgerRepository.save(new BankMockLedgerEntry(idempotencyKey, BankMockLedgerEntry.Outcome.APPLIED, reference));
        return new BankTransferResult(reference);
    }

    private void storeTimeoutGroundTruth(UUID idempotencyKey, boolean applied) {
        BankMockLedgerEntry.Outcome outcome = applied
                ? BankMockLedgerEntry.Outcome.TIMEOUT_APPLIED_TRUE
                : BankMockLedgerEntry.Outcome.TIMEOUT_APPLIED_FALSE;
        String reference = applied ? generateBankReference() : null;
        ledgerRepository.save(new BankMockLedgerEntry(idempotencyKey, outcome, reference));
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

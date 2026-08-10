package com.maxcapital.withdrawals.external;

import java.math.BigDecimal;
import java.util.UUID;

public interface BankService {

    /**
     * Executes a transfer. Takes 3-10s in the real (mocked) service — never call this from a
     * request thread or while holding a DB row lock (C1). Same idempotencyKey never re-applies
     * a transfer that already succeeded or terminally failed; see the mock's javadoc for the
     * exact per-outcome caching semantics.
     */
    BankTransferResult executeTransfer(UUID idempotencyKey, UUID accountId, String destinationCbu, BigDecimal amount)
            throws InvalidAccountException, InternalErrorException, BankTimeoutException, IdempotentRequestInProgressException;

    /**
     * Fast lookup used by the reconciliation flow after a timeout — never re-calls executeTransfer
     * blindly (C6). Returns NOT_FOUND if the bank never received/applied the transfer.
     */
    BankQueryResult queryByIdempotencyKey(UUID idempotencyKey);
}

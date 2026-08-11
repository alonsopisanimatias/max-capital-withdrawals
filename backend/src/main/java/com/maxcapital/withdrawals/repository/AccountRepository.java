package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Atomically reserves {@code amount} against available balance (C5). No SELECT FOR
     * UPDATE needed: this is a single conditional UPDATE, and Postgres already serializes
     * concurrent UPDATEs on the same row — under READ COMMITTED, a blocked writer re-checks
     * the WHERE clause against the just-committed row once the lock is released (EvalPlanQual),
     * so the balance check can never observe stale data. Returns 0 rows affected (not an
     * exception) when there isn't enough available balance — the caller decides what to do.
     *
     * {@code @Transactional} is required here (not just {@code @Modifying}): Spring Data JPA
     * repositories default to a read-only transaction, and Postgres rejects an UPDATE inside
     * one — without this, every call silently fails with 0 rows affected instead of a clear
     * error, which is exactly the kind of thing that only shows up under concurrency tests.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE account
            SET reserved_balance = reserved_balance + :amount
            WHERE id = :accountId
              AND (balance - reserved_balance) >= :amount
            """, nativeQuery = true)
    int tryReserve(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

    /**
     * Releases a previously reserved amount without touching the real balance — used when a
     * withdrawal ends in REJECTED or FINAL_ERROR. Guarded by {@code reserved_balance >= :amount}
     * so a duplicate release (e.g. reconciliation running twice) can never drive it negative;
     * callers must also gate this behind a conditional status-transition UPDATE on withdrawal
     * so the effect is applied at most once (see DECISIONS.md section 8, C6).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE account
            SET reserved_balance = reserved_balance - :amount
            WHERE id = :accountId
              AND reserved_balance >= :amount
            """, nativeQuery = true)
    int release(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

    /**
     * Debits the real balance and releases the reservation in the SAME statement — used when
     * a withdrawal reaches EXECUTED. Doing both in one UPDATE closes the window a two-step
     * release-then-debit would leave open between transactions.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE account
            SET balance = balance - :amount,
                reserved_balance = reserved_balance - :amount
            WHERE id = :accountId
              AND reserved_balance >= :amount
            """, nativeQuery = true)
    int settle(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);
}

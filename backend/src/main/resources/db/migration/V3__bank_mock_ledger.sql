-- BankServiceMock stands in for the real bank, so it needs exactly one ground truth shared by
-- every backend instance — a real bank obviously doesn't have a different memory per caller.
-- Storing it in-process (a Map in the mock) broke that with 2+ instances (docker-compose C3
-- demo): instance A could cache an outcome in its own heap that instance B's reconciliation,
-- landing on a different JVM, would never see, and would then wrongly treat as NOT_FOUND. This
-- table is that shared ground truth (see DECISIONS.md section 8, and BankServiceMock's javadoc).
create table bank_mock_ledger (
    idempotency_key uuid primary key,
    outcome         varchar(30) not null,
    bank_reference  varchar(100),
    created_at      timestamptz not null default now()
);

-- Mirrors the mock's in-memory "inFlight" set: a second concurrent executeTransfer for the same
-- idempotency key must see the first is still running (IdempotentRequestInProgressException),
-- across instances too. The primary key is the exclusivity mechanism — the repository claims a
-- row with INSERT ... ON CONFLICT DO NOTHING and checks rows-affected, no application-level lock.
create table bank_mock_in_flight (
    idempotency_key uuid primary key,
    started_at      timestamptz not null default now()
);

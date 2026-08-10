-- demo accounts for manual testing and the C5 concurrency scenario;
-- acc-002 is seeded close to its limit so a burst of concurrent withdrawals visibly hits it.
insert into account (id, account_number, holder_name, balance, reserved_balance) values
    ('11111111-1111-1111-1111-111111111111', 'ACC-001', 'Juan Perez', 1000000.00, 0),
    ('22222222-2222-2222-2222-222222222222', 'ACC-002', 'Maria Gomez', 50000.00, 0),
    ('33333333-3333-3333-3333-333333333333', 'ACC-003', 'Carlos Ruiz', 250000.00, 0);

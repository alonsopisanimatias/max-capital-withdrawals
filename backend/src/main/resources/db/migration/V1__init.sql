-- no optimistic-locking version column: concurrent writes go through the conditional
-- atomic UPDATEs in AccountRepository (C5), which Postgres already serializes at the row
-- level — an unused @Version here would just be dead weight, see DECISIONS.md section 7.
create table account (
    id               uuid primary key,
    account_number   varchar(20) not null unique,
    holder_name      varchar(120) not null,
    balance          numeric(18, 2) not null check (balance >= 0),
    reserved_balance numeric(18, 2) not null default 0 check (reserved_balance >= 0),
    constraint chk_account_reserved_within_balance check (balance >= reserved_balance)
);

create table withdrawal (
    id                 uuid primary key,
    account_id         uuid not null references account (id),
    destination_cbu    varchar(22) not null check (destination_cbu ~ '^[0-9]{22}$'),
    amount             numeric(18, 2) not null check (amount > 0),
    status             varchar(30) not null,
    risk_level         varchar(10),
    risk_evaluated_at  timestamptz,
    idempotency_key    uuid not null unique,
    transfer_id        uuid,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    updated_by         varchar(100),
    version            bigint not null default 0
);

create index idx_withdrawal_status_created_at on withdrawal (status, created_at);
create index idx_withdrawal_account_id on withdrawal (account_id);
create index idx_withdrawal_destination_cbu on withdrawal (destination_cbu);

create table transfer (
    id               uuid primary key,
    withdrawal_id    uuid not null unique references withdrawal (id),
    idempotency_key  uuid not null unique,
    status           varchar(30) not null,
    bank_reference   varchar(100),
    attempt_count    integer not null default 0,
    requested_at     timestamptz,
    resolved_at      timestamptz,
    last_error       text,
    reconciliation_attempts integer not null default 0
);

create index idx_transfer_status_requested_at on transfer (status, requested_at);

alter table withdrawal
    add constraint fk_withdrawal_transfer foreign key (transfer_id) references transfer (id);

-- append-only audit trail: what a CNV-regulated broker would expect to be able to show
-- ("give me the full sequence of decisions on this withdrawal"), not just the last actor.
create table withdrawal_status_history (
    id             uuid primary key,
    withdrawal_id  uuid not null references withdrawal (id),
    previous_status varchar(30),
    new_status     varchar(30) not null,
    actor          varchar(100) not null,
    occurred_at    timestamptz not null default now()
);

create index idx_withdrawal_status_history_withdrawal_id on withdrawal_status_history (withdrawal_id);

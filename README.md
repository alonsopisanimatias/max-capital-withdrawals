# Max Capital — Withdrawals Portal

Technical challenge for a Full Stack Engineer position: a withdrawals portal where a client requests a
withdrawal, the system evaluates risk asynchronously, an operator authorizes or rejects the ones that need
manual review, and the money is transferred through an (mocked) external bank service.

## Stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.3.4, Gradle 8.10 (wrapper included) |
| Persistence | PostgreSQL 16, Spring Data JPA, Flyway migrations |
| Tests | JUnit 5, Spring Boot Test, Testcontainers 1.21.3 (real Postgres, no H2) |
| Frontend | React 18, TypeScript 5.6, Vite 5.4, TanStack Query 5 |

The Gradle project lives in `backend/` (`backend/gradlew`), so every Gradle command below is run from that
directory.

## Prerequisites

- JDK 21 (the Gradle toolchain targets Java 21)
- Docker Desktop running (needed for Postgres, and required by the test suite)
- Node.js 18+ (only for the frontend)

## Running it locally

`docker-compose.yml` currently brings up **Postgres only** — the backend and frontend are not part of the
compose file yet. For now the backend runs locally against the containerized database.

### 1. Start Postgres

From the repository root:

```bash
docker compose up -d
```

Database `withdrawals`, user `withdrawals`, password `withdrawals`, published on `localhost:5432`. Check it is
accepting connections:

```bash
docker compose exec postgres pg_isready -U withdrawals
```

### 2. Run the backend

```bash
cd backend
./gradlew bootRun          # Windows: .\gradlew.bat bootRun
```

The defaults in `backend/src/main/resources/application.yml` already point at the compose database, so no
environment variables are needed. Override them if your setup differs:

| Variable | Default |
| --- | --- |
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `withdrawals` |
| `DB_USER` | `withdrawals` |
| `DB_PASSWORD` | `withdrawals` |
| `SERVER_PORT` | `8080` |
| `INSTANCE_ID` | `local` (log-only tag, used later to tell compose instances apart) |

Flyway runs the migrations on startup and seeds three demo accounts (see below).

### 3. Health check

There is no actuator endpoint yet. The quickest way to confirm the app is up and serving is an intentionally
invalid request — a `400` with a `VALIDATION_ERROR` body means the web layer and the JSON mapping are alive:

```bash
curl -i -X POST http://localhost:8080/api/withdrawals \
  -H "Content-Type: application/json" \
  -d '{}'
```

### 4. Frontend (optional, skeleton only)

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

Right now this renders a placeholder page. It is not wired to the API yet, so there is no visual demo of the
withdrawals flow — use `curl` (below) plus the database to follow what the backend is doing.

## Tests

The test suite uses Testcontainers, which starts its own throwaway Postgres. It does **not** use the
`docker-compose.yml` database — only a running Docker daemon is required.

```bash
cd backend
./gradlew test         # Windows: .\gradlew.bat test
```

HTML report: `backend/build/reports/tests/test/index.html`.

## Trying the API by hand

### Seed accounts (`V2__seed_data.sql`)

| ID | Account number | Holder | Balance |
| --- | --- | --- | --- |
| `11111111-1111-1111-1111-111111111111` | ACC-001 | Juan Perez | 1,000,000.00 |
| `22222222-2222-2222-2222-222222222222` | ACC-002 | Maria Gomez | 50,000.00 |
| `33333333-3333-3333-3333-333333333333` | ACC-003 | Carlos Ruiz | 250,000.00 |

ACC-002 is deliberately seeded close to a realistic limit so a burst of concurrent withdrawals visibly hits
insufficient balance.

### Create a withdrawal

```bash
curl -i -X POST http://localhost:8080/api/withdrawals \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "11111111-1111-1111-1111-111111111111",
    "destinationCbu": "0170099220000067797151",
    "amount": 1500.99
  }'
```

`destinationCbu` must be exactly 22 digits and `amount` must be positive. The response is `201` with the
withdrawal in `EVALUATING_RISK` and `riskLevel: null` — risk is evaluated off the request thread by a
background poller, so the status changes a couple of seconds later. There is no `GET` endpoint yet, so check
the outcome in the database:

```bash
docker compose exec postgres psql -U withdrawals -d withdrawals \
  -c "select id, status, risk_level, updated_by from withdrawal order by created_at desc limit 5;"
```

`LOW`/`MEDIUM` risk moves the withdrawal to `AUTHORIZED` automatically; `HIGH` risk — and any failure of the
risk service, which is treated as high risk on purpose — moves it to `PENDING_AUTHORIZATION`, where an
operator has to resolve it:

```bash
curl -i -X POST http://localhost:8080/api/withdrawals/<withdrawal-id>/authorize \
  -H "X-Operator-Id: operator-1"
```

### Forcing outcomes from the mocks

The risk and bank mocks are random by default (realistic latency and failure rates), but a specific outcome
can be forced from the **last two decimal digits of the amount**, so scenarios can be reproduced by hand
without touching code.

`RiskServiceMock` (1–3 s latency, otherwise ~15% failures and weighted levels):

| Amount ends in | Outcome |
| --- | --- |
| `.96` | risk `LOW` |
| `.97` | risk `MEDIUM` |
| `.98` | risk evaluation throws (fail-safe path → `PENDING_AUTHORIZATION`) |
| `.99` | risk `HIGH` → `PENDING_AUTHORIZATION` |

`BankServiceMock` (3–10 s latency, otherwise ~5% internal errors / ~3% timeouts / ~2% invalid account):

| Amount ends in | Outcome |
| --- | --- |
| `.13` | internal error (not cached per idempotency key — a retry can succeed) |
| `.14` | timeout where the transfer **did** apply on the bank side |
| `.15` | timeout where the transfer did **not** apply |
| `.16` | invalid destination account (terminal, cached per idempotency key) |
| `.50` | guaranteed success (handy to retry deterministically after a forced failure) |

So `1500.99` forces a high-risk withdrawal that lands in manual authorization, and `1500.50` would force a
clean bank transfer. Note that the transfer-execution poller is not built yet, so the bank conventions are
currently only observable through the mock's own tests, not end to end. Automated tests use
`TestRiskService`/`TestBankService` (instant and fully controllable) under the `test` profile instead of these
mocks.

## Endpoints available today

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/withdrawals` | Reserves balance atomically and creates the withdrawal in `EVALUATING_RISK`. `409 INSUFFICIENT_BALANCE`, `404 NOT_FOUND`, `400 VALIDATION_ERROR`. |
| `POST` | `/api/withdrawals/{id}/authorize` | Requires header `X-Operator-Id`. Only valid from `PENDING_AUTHORIZATION`. |
| `POST` | `/api/withdrawals/{id}/reject` | Requires header `X-Operator-Id`. Releases the reserved balance in the same transaction. |

Both operator actions return `409` when the withdrawal is no longer in the expected state
(`INVALID_TRANSITION`) or when two operators race for the same withdrawal and optimistic locking rejects the
loser (`CONFLICT`); the response body includes the real current status and who set it. Query endpoints for the
backoffice grid do not exist yet.

## Design decisions

The reasoning behind the data model, the concurrency strategy (atomic reservation, `SKIP LOCKED` pollers,
optimistic locking) and the trade-offs taken is in [`DECISIONS.md`](DECISIONS.md) — this file only covers how
to run the project.

## Project status

Active development. Built so far: data model and migrations, external service mocks, atomic balance
reservation and withdrawal creation, the risk evaluation poller with `SKIP LOCKED` and its fail-safe, and
authorize/reject with optimistic locking.

Not built yet: transfer execution and reconciliation pollers, query endpoints, the backoffice UI, and backend
and frontend services in `docker-compose.yml`. See `DECISIONS.md` and `git log` for the detail.

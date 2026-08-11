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

### Option A — everything, via docker compose

```bash
docker compose up -d --build
```

This builds and starts Postgres, **two backend instances** (`backend-1` on `localhost:8081`, `backend-2` on
`localhost:8082`) and the frontend (`localhost:5173`, static build served by nginx, proxying `/api` to
`backend-1` — see [Project status](#project-status)). Each backend logs its `INSTANCE_ID`, so you can watch
the two claim disjoint rows from the pollers (`docker compose logs -f backend-1 backend-2 | grep claimed`).
Health:

```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8082/actuator/health
```

Talk to either instance interchangeably for the API examples below — they share the same Postgres. Tear down
with `docker compose down` (add `-v` to also drop the seeded data volume).

### Option B — backend running locally against a containerized Postgres

Useful for iterating on the backend without rebuilding the image each time.

```bash
docker compose up -d postgres
cd backend
./gradlew bootRun          # Windows: .\gradlew.bat bootRun
```

Database `withdrawals`, user `withdrawals`, password `withdrawals`, published on `localhost:5432`. The
defaults in `backend/src/main/resources/application.yml` already point at it, so no environment variables are
needed. Override them if your setup differs:

| Variable | Default |
| --- | --- |
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `withdrawals` |
| `DB_USER` | `withdrawals` |
| `DB_PASSWORD` | `withdrawals` |
| `SERVER_PORT` | `8080` |
| `INSTANCE_ID` | `local` (tags the poller claim logs; `backend-1`/`backend-2` under compose) |

Flyway runs the migrations on startup and seeds three demo accounts (see below).

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/accounts   # three rows means the whole stack is wired
```

### Frontend

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

`npm run dev` proxies `/api` to `http://localhost:8080` by default (a locally-run backend, Option B above) —
override with `VITE_API_PROXY_TARGET` if you're pointing at `docker compose`'s `8081`/`8082` instead:

```bash
VITE_API_PROXY_TARGET=http://localhost:8081 npm run dev
```

Under `docker compose up` (Option A), no proxy env var is needed — nginx handles it (`frontend/nginx.conf`).

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

`destinationCbu` must be exactly 22 digits and `amount` must be positive, with at most 16 integer digits and
2 decimal places (matching the `numeric(18,2)` column). The response is `201` with the
withdrawal in `EVALUATING_RISK` and `riskLevel: null` — risk is evaluated off the request thread by a
background poller, so the status changes a couple of seconds later. Follow it with the detail endpoint, which
also carries the transfer attempt once there is one:

```bash
curl -s http://localhost:8080/api/withdrawals/<withdrawal-id>
curl -s "http://localhost:8080/api/withdrawals?size=5&sort=createdAt,desc"   # or the whole grid
```

The database is still the fastest way to eyeball several at once while a load test is running:

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

From `AUTHORIZED` onwards nothing has to be called by hand: the transfer-execution poller claims the
withdrawal on its next tick (every second), moves it to `PROCESSING_TRANSFER`, calls the bank and resolves it
as `EXECUTED`, `RETRYABLE_ERROR` or `FINAL_ERROR`. If the bank times out, the withdrawal stays in
`PROCESSING_TRANSFER` until the reconciliation poller asks the bank what really happened (30 s grace period,
checked every 5 s). Only `RETRYABLE_ERROR` needs an operator again — the reserve is still held, so the same
withdrawal can be sent back to `AUTHORIZED` for another attempt with the same idempotency key:

```bash
curl -i -X POST http://localhost:8080/api/withdrawals/<withdrawal-id>/retry \
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

So `1500.99` forces a high-risk withdrawal that lands in manual authorization, and `1500.50` forces a clean
bank transfer once that withdrawal reaches `AUTHORIZED`. Both tables read the same two digits, so one amount
can only force one of the two legs — the other stays random. Automated tests use
`TestRiskService`/`TestBankService` (instant and fully controllable) under the `test` profile instead of these
mocks.

### A full run, end to end, to `EXECUTED`

`.96` forces low risk, which skips manual authorization entirely, so one POST is enough to watch a withdrawal
go all the way through without calling anything else:

```bash
# 1. create it — forced LOW risk
curl -s -X POST http://localhost:8080/api/withdrawals \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "11111111-1111-1111-1111-111111111111",
    "destinationCbu": "0170099220000067797151",
    "amount": 2500.96
  }'

# 2. ~1-3s later the risk poller has evaluated it: LOW risk, so it is already AUTHORIZED
curl -s http://localhost:8080/api/withdrawals/<withdrawal-id>

# 3. no further call needed — the transfer poller picks up AUTHORIZED rows on its next tick,
#    so the same GET shows PROCESSING_TRANSFER and then, after the bank's 3-10s, EXECUTED
#    with the bank reference under "transfer"
curl -s http://localhost:8080/api/withdrawals/<withdrawal-id>
```

The bank leg here is the random one (`96` is not a bank forcing digit), so roughly nine out of ten runs end in
`EXECUTED`; the rest exercise the error and timeout paths, which is the point of leaving it random. To force a
specific bank outcome instead, create the withdrawal with a `.13`/`.14`/`.15`/`.16` amount — risk is then the
random leg, so authorize it by hand if it lands in `PENDING_AUTHORIZATION`.

## Endpoints available today

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/withdrawals` | Reserves balance atomically and creates the withdrawal in `EVALUATING_RISK`. `409 INSUFFICIENT_BALANCE`, `404 NOT_FOUND`, `400 VALIDATION_ERROR`. |
| `GET` | `/api/withdrawals` | Paged search for the backoffice grid. See the query params below. |
| `GET` | `/api/withdrawals/{id}` | Full detail plus the transfer attempt (status, bank reference, attempt count, last error, resolved at) when one exists, and the full audit trail (`history`: previous/new status, actor, timestamp for every transition). `404 NOT_FOUND`. |
| `POST` | `/api/withdrawals/{id}/authorize` | Requires header `X-Operator-Id`. Only valid from `PENDING_AUTHORIZATION`. |
| `POST` | `/api/withdrawals/{id}/reject` | Requires header `X-Operator-Id`. Releases the reserved balance in the same transaction. |
| `POST` | `/api/withdrawals/{id}/retry` | Requires header `X-Operator-Id`. Only valid from `RETRYABLE_ERROR`; puts it back in `AUTHORIZED` so the transfer poller retries with the same idempotency key (the reserve was never released). |
| `POST` | `/api/withdrawals/{id}/resolve-manual-review` | Requires header `X-Operator-Id`. Only valid from `MANUAL_REVIEW` (reconciliation gave up asking the bank); closes it to `FINAL_ERROR` and releases the reserved balance. |
| `GET` | `/api/accounts` | Seeded accounts with balance and reserved balance. Convenience for manual testing and the UI's account picker, not part of the evaluated domain. |

Query params for `GET /api/withdrawals`, all optional and combinable:

| Param | Meaning |
| --- | --- |
| `status` | Exact match on one `WithdrawalStatus` (e.g. `PENDING_AUTHORIZATION`). |
| `dateFrom` / `dateTo` | ISO-8601 instants (`2026-08-10T00:00:00Z`), inclusive, matched against `createdAt`. |
| `search` | Partial match on the destination CBU, or an exact account id when the term parses as a UUID. |
| `page` / `size` / `sort` | Spring's standard pagination. Defaults: `size=20`, `sort=createdAt` ascending; e.g. `sort=createdAt,desc`. |

The four operator actions return `409` when the withdrawal is no longer in the expected state
(`INVALID_TRANSITION`) or when two operators race for the same withdrawal and optimistic locking rejects the
loser (`CONFLICT`); the response body includes the real current status and who set it.

## Design decisions

The reasoning behind the data model, the concurrency strategy (atomic reservation, `SKIP LOCKED` pollers,
optimistic locking) and the trade-offs taken is in [`DECISIONS.md`](DECISIONS.md) — this file only covers how
to run the project.

## Project status

Active development. The backend flow is complete end to end and runs for real under `docker compose up` with
two instances: a withdrawal goes from creation through risk evaluation, manual authorization when needed,
bank transfer and reconciliation of ambiguous timeouts, all the way to `EXECUTED` (or a terminal/retryable
error), without any manual step beyond the operator decisions. Built so far: data model and migrations,
external service mocks (with their idempotency ground truth persisted in Postgres, shared across instances —
see `DECISIONS.md` section 8), atomic balance reservation and withdrawal creation, the three `SKIP LOCKED`
pollers (risk evaluation with its fail-safe, transfer execution with its claim/call/apply split and
client-side timeout, reconciliation with its grace period), authorize/reject/retry/resolve-manual-review
with optimistic locking, the query endpoints backing the backoffice grid, and the two-instance
`docker-compose.yml`.

The frontend (`frontend/src/`) is wired to the real API: a filterable/paginated grid, a detail panel with
the full audit trail, the operator actions (authorize/reject/retry/resolve manual review, gated behind an
operator id header), a create-withdrawal form with client-side validation matching the backend's, polling
so background poller-driven transitions show up without a manual refresh, and explicit handling of 409
conflicts (C4) showing who resolved it first instead of a bare error. Verified against the real stack in a
browser (screenshots + a scripted click-through), not just against `curl`. One deliberate simplification:
status updates are polled (`refetchInterval`, every 4s) instead of pushed via SSE/WebSockets — the
difference is UX/efficiency, not correctness (the data is never stale for more than one poll interval, and
every mutation also invalidates the relevant queries immediately), and polling doesn't add a persistent
connection to manage across two backend instances.

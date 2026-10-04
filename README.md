# seat-reservation-service

Atomic seat reservation API for a high-contention on-sale stampede. Java 21 / Spring Boot 3 /
MySQL 8, plain JDBC (no ORM) so the exact statement that makes each atomic decision is visible
and auditable. See [WRITEUP.md](WRITEUP.md) for the design rationale, consistency trade-offs,
and AI usage disclosure.

**Live URL:** not yet deployed — see [Deploying](#deploying) below. Everything here runs locally
via Docker Compose today; `burst.js` works unchanged against whatever `BASE_URL` you point it at
once a live URL exists.

## Quickstart

```bash
docker compose up --build
```

This builds the service, starts MySQL, waits for the DB healthcheck, and brings the app up on
`http://localhost:8080`. The schema (`src/main/resources/schema.sql`) is applied automatically on
every startup (idempotent `CREATE TABLE IF NOT EXISTS`).

Check it's alive:

```bash
curl http://localhost:8080/actuator/health/readiness   # fails closed if MySQL is unreachable
curl http://localhost:8080/actuator/health/liveness
```

## Running the burst test

```bash
./burst.sh http://localhost:8080
# or: make burst
# quick ~1k-request smoke version: make burst-smoke   (SCALE=0.02 ./burst.sh ...)
```

No `npm install` needed — `burst.js` uses only Node's built-in `fetch` (Node 18+). It:

1. Creates a fresh show with a dedicated seat pool per test group (so results are unambiguous).
2. Pre-issues a bearer token per simulated buyer (setup phase, not timed).
3. Fires ~21,400 concurrent `POST /shows/{id}/reserve` requests through a 300-wide concurrency
   pool: a 5-seat **hot-seat storm** (500 buyers per seat), ~17k requests of **general
   over-subscribed demand**, 500 **idempotent-retry** pairs (same key fired twice concurrently),
   200 **idempotency-conflict** pairs (same key, different seat), and 50 users each firing 10
   concurrent requests against a **limit=4** show.
4. Prints the outcome distribution, verifies every correctness property per group (exactly one
   winner per hot seat, zero 5xx, idempotency/limit invariants hold), and reconciles
   `available + held + confirmed == total` via `GET /shows/{id}`.

Tune it with env vars: `SCALE` (default `1`, use e.g. `0.05` for a fast local run), `CONCURRENCY`
(default `300`), `BASE_URL` (first CLI arg or env var).

Last local run (`docker compose up` + `./burst.sh`): **21,400 requests, 0 server errors, PASS**
on every correctness check. Re-run it yourself — it's deterministic in structure, not in timing.

## API

All money is integer paise. All mutating endpoints are idempotent/safe to retry as documented.

### Auth (dev-only identity stand-in)

```
POST /auth/token
{ "user_id": "alice" }
-> { "token": "<jwt>", "expires_in": 3600 }
```

Every endpoint below that requires auth reads the caller's identity **only** from this token's
verified subject claim (`Authorization: Bearer <token>`) — a `user_id` field in a request body is
always ignored.

### Shows (admin-ish, no auth required for the exercise)

```
POST /shows
{ "name": "friday-night", "seats": ["A1","A2","A3"], "price_paise": 25000, "per_user_limit": 4 }
-> 201 { id, name, price_paise, per_user_limit, total_seats, seats: [...], counts: {...} }

GET /shows/{id}
-> 200 { ..., seats: [{seat_number, status}], counts: {available, held, confirmed, total} }
```

`per_user_limit` is optional (default `4`). `held` is always `0` in this implementation — see
[WRITEUP.md](WRITEUP.md#holds--expiry) for why (explicit-cancel model, not time-boxed holds).

### Reserve

```
POST /shows/{id}/reserve
Authorization: Bearer <token>
{ "seats": ["A12"], "idempotency_key": "<client-generated>" }
```

(`idempotency_key` may also be sent as the `Idempotency-Key` header; the body field wins if both
are present.)

- `201` — newly confirmed: `{ reservation_id, show_id, user_id, seats, amount_paise, status }`
- `200` — idempotent replay of an existing reservation (same key, same seats)
- `409 seat_taken` — one or more requested seats were already confirmed (all-or-nothing: nothing
  is held, body lists exactly which seats)
- `409 user_limit_exceeded` — would exceed `per_user_limit` for this user on this show
- `409 idempotency_conflict` — same key reused with a different seat set
- `400` — unknown seat, empty seat list, or missing idempotency key
- `401` — missing/invalid bearer token
- `429 retry_required` — exhausted retries against transient DB contention (rare; safe to retry)

### Cancel

```
POST /reservations/{id}/cancel
Authorization: Bearer <token>
```

Only the owning user may cancel (`403` otherwise). Idempotent: cancelling an already-cancelled
reservation just returns its current state. Released seats become immediately re-bookable.

## Observability

- **Health:** `GET /actuator/health/liveness` (process up) and `GET /actuator/health/readiness`
  (fails closed — includes a real MySQL connectivity check, not just "the process is running").
- **Metrics:** `GET /actuator/prometheus` — `reservations_confirmed_total`,
  `reservations_declined_total{reason="seat_taken|user_limit_exceeded|idempotent_replay|idempotency_conflict"}`,
  `reservations_cancelled_total`, `seats_available{show_id,show_name}` (gauge, recomputed from the
  DB on every scrape so it can't drift from `GET /shows/{id}`).
- **Logs:** structured, one correlation id per request (`X-Request-Id` — reused if the caller
  supplies one, otherwise minted and echoed back), present on every log line via MDC. Set
  `SPRING_PROFILES_ACTIVE=json` for machine-parseable JSON logs (via logstash-logback-encoder);
  plain-text pattern logs otherwise.

## Configuration

All overridable via environment variable (defaults are dev-friendly, not production secrets):

| Var | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `3306` / `seat_reservation` / `root` / *(empty)* | MySQL connection |
| `DB_POOL_SIZE` | `50` | HikariCP max pool size |
| `JWT_SECRET` | dev placeholder | **Change this in any real deployment.** HS256 signing key for `/auth/token` |
| `DEFAULT_PER_USER_LIMIT` | `4` | Used when a show is created without `per_user_limit` |

## Deploying

The service is container-first (`Dockerfile`) and reads `PORT` + `DB_*` from the environment, so
it should deploy as-is to Render, Fly.io, or Railway as a Docker service plus a managed MySQL
instance:

1. Provision a MySQL instance. Render and Railway both offer managed MySQL directly; Fly.io's
   native managed DB is Postgres, so on Fly you'd either run MySQL yourself as a second Fly app
   with a volume, or point `DB_*` at an external managed MySQL (e.g. PlanetScale).
2. Point the app's `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` at it, set a real
   `JWT_SECRET`, and deploy the repo's `Dockerfile` as a web service listening on `$PORT`.
3. Confirm `/actuator/health/readiness` is healthy before pointing traffic/burst tests at it.

## Project layout

```
src/main/java/com/paytmmoney/seatreservation/
  auth/          JWT issuance + verification, @AuthUser resolver
  show/          show + seat read/write (JdbcTemplate)
  reservation/   the atomic reserve/cancel flow, idempotency, per-user quota
  metrics/       Prometheus counters/gauges
  common/        request-id filter, error mapping, transient-retry helper
burst.js         the stampede load test / correctness verifier
```

# WRITEUP

## The atomic decision

Three separate atomic decisions, each a single conditional SQL statement whose WHERE clause
encodes "only do this if the world is still in the state I think it's in." No read-then-write gap
anywhere in the hot path.

**1. Idempotency — `reservations` table, `UNIQUE(user_id, idempotency_key)`.**
`ReservationRepository.tryInsert` does a plain `INSERT` first, before touching seats or quota. If
two requests race with the same key, the loser's `INSERT` fails on the unique constraint
(`DuplicateKeyException`), not after a prior "does this exist?" read — there is no window where
both could believe they're first. The loser then looks up the winner's row and compares seat sets:
same seats → return the winner's reservation (replay, `200`); different seats → `409
idempotency_conflict`.

**2. Per-user limit — `user_show_quota(show_id, user_id, held_count)`.**
`UPDATE user_show_quota SET held_count = held_count + ? WHERE show_id=? AND user_id=? AND
held_count + ? <= ?`. The row lock InnoDB takes for the duration of this `UPDATE` is what
serializes two concurrent requests from the same user — the second one's `UPDATE` simply blocks
until the first commits or rolls back, then evaluates the limit check against the post-commit
value. Zero rows affected means the limit would be exceeded.

**3. Seat ownership — `seats(show_id, seat_number)` primary key.**
`UPDATE seats SET status='CONFIRMED', ... WHERE show_id=? AND seat_number=? AND
status='AVAILABLE'`. Exactly one concurrent `UPDATE` against a given `(show_id, seat_number)` row
can ever flip `AVAILABLE → CONFIRMED`; every other one affects zero rows. This is the one the
500-person hot-seat storm actually exercises, and it held at 21,400 concurrent requests locally
(see README's burst results) — the invariant isn't "the two overlapping statements will probably
interleave okay," it's "MySQL will not let two `UPDATE`s both report 1 row affected for the same
row."

All three live inside one `@Transactional` method (`ReservationService.reserve`), so a failure at
step 2 or 3 rolls back step 1 too — a seat-taken or limit-exceeded decline never permanently
consumes the idempotency key, and a legitimate retry (same key, after the inventory changes) is
free to succeed later.

**Multi-seat requests and deadlock avoidance.** A request for `["A12","A13"]` processes seats in
sorted (lexicographic) order, and the quota row is always touched before any seat row. That's one
consistent global lock-acquisition order across every transaction in the system, which is what
rules out the classic two-transactions-locking-in-opposite-order deadlock for overlapping
multi-seat requests.

**Partial requests: all-or-nothing, not best-effort.** If `["A12","A13"]` and only `A12` is free,
the whole request is declined (`409 seat_taken`, body names which seat(s) were unavailable) and
`A12` is released back to available — the user gets nothing rather than a single seat they didn't
actually want next to someone else's party of four they were trying to seat together. Documented
trade-off: a best-effort mode (confirm whatever's available, report the rest as declined) would
serve solo buyers slightly better during a storm, but silently gives a different user a worse
version of what they asked for, which felt like the wrong default for a seating product.

### A real concurrency bug the burst test caught (and the fix)

Early load-testing surfaced two genuine MySQL/InnoDB issues that the sequential-logic unit-level
reasoning above does not predict:

- **REPEATABLE READ snapshot bug.** When two transactions race to `INSERT` the same `(user_id,
  idempotency_key)`, the loser blocks on the unique index, then fails with a duplicate-key error
  once the winner commits — so far so good. But under MySQL's default `REPEATABLE READ`, the
  loser's *subsequent plain `SELECT`* to look up the winner's row still uses the snapshot from
  **before** the winner committed, and sees nothing — `Optional.empty()` where there is
  provably a row. Fix: `ReservationService.reserve` and `.cancel` run at `READ_COMMITTED`, where
  every statement gets a fresh read view.
- **Deadlock on concurrent first-time row creation.** `INSERT ... ON DUPLICATE KEY UPDATE` for a
  not-yet-existing `user_show_quota` row, fired by several concurrent requests from the same user
  (e.g. the per-user-limit test: one user, 10 parallel requests), is a documented InnoDB deadlock
  trigger — two sessions racing the same not-yet-existing key both try to upgrade to an exclusive
  lock on the "duplicate" in different order. Fix: switched that statement to `INSERT IGNORE`
  (no update-lock upgrade on the losing side) plus a `TransientRetry` wrapper around both
  controller endpoints that retries the whole request (a fresh transaction each attempt) up to 8
  times with jittered exponential backoff on any `ConcurrencyFailureException`. If retries are
  ever exhausted, the client gets `429 retry_required` — a decline, never a `500`.

Both were found by `burst.js` returning real `500`s during local load-testing, not by reasoning
about the code in the abstract — which is the whole point of the "deploy & observe" half of this
exercise.

## Idempotency details

- **Where the key is stored:** `reservations.idempotency_key`, scoped per user via
  `UNIQUE(user_id, idempotency_key)` — not globally unique, so two different users can coincidentally
  pick the same key string without colliding.
- **How exactly-once is enforced:** the unique constraint itself, checked by a single `INSERT`
  (see above), not an application-level "check then write."
- **Same key, different body:** compared by seat set (order-independent) against the stored
  reservation; a mismatch is `409 idempotency_conflict`, and the original reservation is
  untouched.
- **Key source:** body field `idempotency_key` or `Idempotency-Key` header; body wins if both are
  present (documented in README, not specified by the brief either way).
- **Failed attempts don't burn the key:** because the insert, quota check, and seat confirms share
  one transaction, a decline rolls back the insert too.

## Holds & expiry

Chose **explicit cancel** (`POST /reservations/{id}/cancel`) over a time-boxed auto-expiring hold.
Seats move directly `AVAILABLE → CONFIRMED` — there's no transient `HELD` seat state in this
implementation (`GET /shows/{id}` will only ever show `available` or `confirmed`, and `held` is
always `0` in the counts). Reasoning: the brief's own success response for `/reserve` already
returns `"status": "confirmed"` with no payment step in between, so a separate hold phase would be
unused machinery; the per-user limit is enforced the same way regardless (via
`user_show_quota.held_count`, which really means "currently committed to this user" here). A
release (cancel) is guarded on `reservation_id`: `UPDATE seats ... WHERE reservation_id = ?`,
so a cancel can never resurrect a seat that's since been confirmed under a different, later
reservation — the guard is on identity of the specific reservation, not just current status.
Cancel itself is idempotent (cancelling twice just returns the current state, no error), and two
concurrent cancels on the same id serialize via `SELECT ... FOR UPDATE` on the reservation row.

What a hold-based model would add, if this went further: a `hold_expires_at` column, a periodic
sweep (or lazy expiry-on-read) flipping expired `HELD` seats back to `AVAILABLE`, and a payment/
confirm step between hold and confirm. Worth doing before this became a real product; out of scope
for what the brief's sample responses actually describe.

## Consistency vs. availability under a partition

This is a single MySQL primary, no read replicas, no cross-region anything — so there isn't a
partition to choose a side of in the CAP sense within the data layer itself. The honest answer to
"what happens under partition" here is at the edges:

- **App ↔ DB partition:** the readiness probe (`/actuator/health/readiness`) fails closed the
  moment the DB health indicator can't reach MySQL, so a load balancer stops routing new traffic
  to an instance that can't make correct decisions, rather than letting it accept requests it
  can't safely serialize. In-flight requests either complete (connection still good) or time out
  and surface as `429`/`5xx` to the caller — never as a silently-wrong confirmation.
  This is a consistency-over-availability choice: an instance that can't see the DB would rather
  stop serving than guess.
- **Client ↔ app partition (the realistic failure mode here):** a buyer's request succeeds
  server-side but the response is lost in transit. This is exactly what the idempotency key
  exists for — the client retries with the same key and gets the same reservation back instead of
  a duplicate charge or a confusing decline. Chose to make correctness-under-retry the actual
  deployed answer to "network partition," rather than something the write-up gestures at.
- If this became a true multi-primary / multi-region system, the honest next question is what
  owns the authoritative seat-state row during a split — the design here (single relational
  source of truth with row-level locking) doesn't extend to that without a different
  architecture (e.g. partitioning shows by region/shard with a single writer per shard, or an
  explicit consensus layer). Not pretending otherwise.

## Observability — what would page someone at 2am

- **`reservations_declined_total{reason="seat_taken"}` rate spiking well past what inventory
  would predict**, alongside `seats_available` *not* dropping — would mean the conditional-UPDATE
  path is failing far more than it should relative to actual contention (a schema regression, a
  botched migration dropping the unique index, a bad deploy). Seat-taken alone during a real
  storm is expected and healthy; seat-taken with no matching drop in availability is not.
- **`reservations_declined_total{reason="idempotency_conflict"}` or `retry_required` (429) rate
  rising** — a client-side retry storm (bad idempotency-key generation reusing keys, or a client
  bug hammering the server) or genuine DB contention beyond what retries can absorb.
- **Any `5xx`, at all** — the correctness bar is zero on this, deliberately, so even one should
  page. In this design a 5xx means something outside the modeled failure modes (an actual bug, DB
  down despite readiness saying otherwise, OOM).
- **`seats_available` sum across hot shows hitting 0 while `reservations_confirmed_total` growth
  has flatlined** — sold out, not an incident, but worth distinguishing from the alert above by
  dashboard, not just a raw gauge threshold.
- **Readiness flapping (`/actuator/health/readiness` UP/DOWN cycling)** — DB connectivity or pool
  exhaustion; check `DB_POOL_SIZE` against actual concurrent load before assuming the DB itself is
  unhealthy.

## AI usage

Built end-to-end in a single Claude Code session, with the human directing scope and architecture
decisions and the AI doing essentially all of the implementation, debugging, and load-testing
under that direction. Specific breakdown:

**Directed (my decisions):** the stack (Java/Spring/MySQL — the only languages/frameworks I
actually know, stated explicitly rather than letting the AI pick something unfamiliar to me);
explicit-cancel over time-boxed holds would have been a coin-flip either way, but I asked for the
simpler of the two given the brief's own sample response already shows `"status": "confirmed"`
with no intermediate hold; all-or-nothing over best-effort for partial multi-seat requests; where
to put the project on disk and that it should be its own git repo with real incremental history.

**Decided by the AI, under that direction:** the exact schema (which table gets the unique
constraint that does the real work, the primary-key choice on `seats`), the specific SQL in every
repository method, the JWT-based dev-identity stand-in (I asked for "identity from a token, not
the body" — the AI chose JWT plus a throwaway `/auth/token` issuance endpoint as the simplest way
to demonstrate that honestly without building a real IdP), the isolation-level fix and the
deadlock fix described above (both found by the AI running `burst.js` against the running service
locally, reading the actual stack traces, and reasoning out the InnoDB-specific cause — not
something I diagnosed and handed over), and the structure of `burst.js` itself (isolated seat
pools per correctness property, so the test's own output is unambiguous about which property
failed).

**What I'd want to be able to defend live, having gone through this:** the three atomic-decision
SQL statements above, why `READ_COMMITTED` specifically fixes the snapshot bug (not just "it
fixed it"), and the lock-ordering argument for why multi-seat requests don't deadlock each other.
Those are the parts worth re-deriving out loud rather than reciting.

## What's next

- Deploy to a public host (Render/Fly/Railway) and re-run `burst.js` against the live URL — not
  done yet; see README's Deploying section for exactly what's needed.
- A real identity provider instead of the dev-only `/auth/token` stand-in.
- Hold-based model with expiry, if a payment step ever sits between "pick seats" and "confirmed."
- Structured-log shipping to somewhere queryable (currently stdout-only, correlation id present
  but no aggregation configured) and a small Grafana dashboard over the existing Prometheus
  metrics rather than raw `/actuator/prometheus` scraping by hand.
- Load-test against a managed MySQL instance at the actual deploy target's network latency —
  everything above was verified against a local MySQL; connection-pool sizing and retry/backoff
  constants were tuned for that, and may need re-tuning for a hosted DB with real round-trip time.

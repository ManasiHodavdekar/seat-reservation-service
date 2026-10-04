#!/usr/bin/env node
/**
 * On-sale stampede simulator for the seat reservation service.
 *
 * Fires ~20k concurrent reservation requests at a fresh show, split into isolated test groups
 * so each correctness property can be checked unambiguously (no cross-contamination between,
 * say, "declined because of seat contention" and "declined because of the per-user limit"):
 *
 *   - hot_seat_storm   : N buyers all fighting over the same few seats -> exactly 1 winner each
 *   - normal_demand     : many buyers against a modest general inventory -> heavy, realistic contention
 *   - idempotent_retry  : same (user, key, seats) fired twice concurrently -> one 201 + one 200, same reservation_id
 *   - idempotency_conflict : same key, second call uses a different seat -> 409, original untouched
 *   - per_user_limit    : one user fires 10 concurrent requests for 10 distinct seats on a limit=4 show
 *
 * Usage:
 *   node burst.js [BASE_URL]
 *   BASE_URL=https://your-app.onrender.com node burst.js
 *   SCALE=0.05 node burst.js            # quick ~1k-request smoke run
 *   CONCURRENCY=400 node burst.js        # tune in-flight request count
 *
 * No npm install required - uses Node's built-in fetch (Node 18+).
 */

const BASE_URL = (process.argv[2] || process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const SCALE = Number(process.env.SCALE || 1);
const CONCURRENCY = Number(process.env.CONCURRENCY || 300);

const HOT_SEATS = 5;
const HOT_SEAT_STORM_SIZE = Math.max(10, Math.round(500 * SCALE));
const NORMAL_BUYERS = Math.max(50, Math.round(8500 * SCALE));
const NORMAL_SEAT_POOL = Math.max(20, Math.round(295 * SCALE));
const RETRY_USERS = Math.max(10, Math.round(500 * SCALE));
const CONFLICT_USERS = Math.max(10, Math.round(200 * SCALE));
const LIMIT_USERS = Math.max(5, Math.round(50 * SCALE));
const PER_USER_LIMIT = 4;

function log(msg) {
    console.log(`[burst] ${msg}`);
}

async function jsonFetch(path, opts = {}) {
    const started = Date.now();
    try {
        const res = await fetch(`${BASE_URL}${path}`, {
            ...opts,
            headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
        });
        let body = null;
        const text = await res.text();
        if (text) {
            try { body = JSON.parse(text); } catch { body = text; }
        }
        return { status: res.status, body, ms: Date.now() - started, networkError: null };
    } catch (err) {
        return { status: 0, body: null, ms: Date.now() - started, networkError: String(err) };
    }
}

async function issueToken(userId) {
    const r = await jsonFetch('/auth/token', { method: 'POST', body: JSON.stringify({ user_id: userId }) });
    if (r.status !== 200 || !r.body?.token) {
        throw new Error(`failed to issue token for ${userId}: status=${r.status} body=${JSON.stringify(r.body)}`);
    }
    return r.body.token;
}

/** Runs `tasks` (array of zero-arg async functions) with at most `concurrency` in flight at once. */
async function runPool(tasks, concurrency) {
    const results = new Array(tasks.length);
    let next = 0;
    let done = 0;
    const total = tasks.length;

    async function worker() {
        while (true) {
            const i = next++;
            if (i >= tasks.length) return;
            results[i] = await tasks[i]();
            done++;
            if (done % 2000 === 0 || done === total) {
                process.stdout.write(`\r[burst] progress: ${done}/${total}`);
            }
        }
    }

    const workers = Array.from({ length: Math.min(concurrency, tasks.length) }, worker);
    await Promise.all(workers);
    process.stdout.write('\n');
    return results;
}

function reserve(token, showId, seats, idempotencyKey) {
    return jsonFetch(`/shows/${showId}/reserve`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
        body: JSON.stringify({ seats, idempotency_key: idempotencyKey }),
    });
}

function outcomeOf(r) {
    if (r.networkError) return 'network_error';
    if (r.status === 201) return 'confirmed';
    if (r.status === 200) return 'idempotent_replay';
    if (r.status === 409 && r.body?.error) return `declined_${r.body.error}`;
    if (r.status >= 500) return `server_error_${r.status}`;
    if (r.status >= 400) return `declined_${r.status}`;
    return `unexpected_${r.status}`;
}

async function main() {
    log(`target: ${BASE_URL}`);
    log(`plan: hot_seat_storm=${HOT_SEATS}x${HOT_SEAT_STORM_SIZE}, normal_demand=${NORMAL_BUYERS}x2 over ${NORMAL_SEAT_POOL} seats, ` +
        `idempotent_retry=${RETRY_USERS}x2, idempotency_conflict=${CONFLICT_USERS}x2, per_user_limit=${LIMIT_USERS}x10`);

    const health = await jsonFetch('/actuator/health/readiness');
    if (health.status !== 200) {
        console.error(`Readiness check failed (status ${health.status}) - is the service up? Body: ${JSON.stringify(health.body)}`);
        process.exit(1);
    }
    log('readiness check OK');

    // ---- Build seat inventory --------------------------------------------------------------
    const hotSeats = Array.from({ length: HOT_SEATS }, (_, i) => `HOT${i + 1}`);
    const normalSeats = Array.from({ length: NORMAL_SEAT_POOL }, (_, i) => `N${i + 1}`);
    const retrySeats = Array.from({ length: RETRY_USERS }, (_, i) => `R${i + 1}`);
    const conflictSeatsA = Array.from({ length: CONFLICT_USERS }, (_, i) => `CA${i + 1}`);
    const conflictSeatsB = Array.from({ length: CONFLICT_USERS }, (_, i) => `CB${i + 1}`);
    const limitSeats = Array.from({ length: LIMIT_USERS * 10 }, (_, i) => `L${i + 1}`);
    const allSeats = [...hotSeats, ...normalSeats, ...retrySeats, ...conflictSeatsA, ...conflictSeatsB, ...limitSeats];

    log(`creating show with ${allSeats.length} seats...`);
    const showRes = await jsonFetch('/shows', {
        method: 'POST',
        body: JSON.stringify({
            name: `burst-${Date.now()}`,
            seats: allSeats,
            price_paise: 25000,
            per_user_limit: PER_USER_LIMIT,
        }),
    });
    if (showRes.status !== 201) {
        console.error(`Failed to create show: ${showRes.status} ${JSON.stringify(showRes.body)}`);
        process.exit(1);
    }
    const showId = showRes.body.id;
    log(`show id: ${showId}`);

    // ---- Pre-issue tokens (not part of the timed burst) ------------------------------------
    const distinctUsers = [];
    for (let s = 0; s < HOT_SEATS; s++) {
        for (let b = 0; b < HOT_SEAT_STORM_SIZE; b++) distinctUsers.push(`hot${s}_buyer${b}`);
    }
    for (let b = 0; b < NORMAL_BUYERS; b++) distinctUsers.push(`normal_buyer${b}`);
    for (let u = 0; u < RETRY_USERS; u++) distinctUsers.push(`retry_user${u}`);
    for (let u = 0; u < CONFLICT_USERS; u++) distinctUsers.push(`conflict_user${u}`);
    for (let u = 0; u < LIMIT_USERS; u++) distinctUsers.push(`limit_user${u}`);

    log(`issuing ${distinctUsers.length} bearer tokens (setup phase, not timed)...`);
    const tokenByUser = new Map();
    await runPool(distinctUsers.map(u => async () => {
        tokenByUser.set(u, await issueToken(u));
    }), CONCURRENCY);

    // ---- Build the full task list (every task tagged with its test group) -----------------
    const tasks = [];
    const tags = [];

    // Hot seat storm: HOT_SEAT_STORM_SIZE distinct buyers all fighting over each hot seat.
    for (let s = 0; s < HOT_SEATS; s++) {
        const seat = hotSeats[s];
        for (let b = 0; b < HOT_SEAT_STORM_SIZE; b++) {
            const user = `hot${s}_buyer${b}`;
            tags.push({ group: 'hot_seat_storm', seat, user });
            tasks.push(() => reserve(tokenByUser.get(user), showId, [seat], `hotkey-${seat}-${user}`));
        }
    }

    // Normal demand: each buyer fires 2 requests for a random seat out of the small general pool.
    for (let b = 0; b < NORMAL_BUYERS; b++) {
        const user = `normal_buyer${b}`;
        for (let r = 0; r < 2; r++) {
            const seat = normalSeats[Math.floor(Math.random() * normalSeats.length)];
            tags.push({ group: 'normal_demand', seat, user });
            tasks.push(() => reserve(tokenByUser.get(user), showId, [seat], `normkey-${user}-${r}`));
        }
    }

    // Idempotent retry: the exact same request (same key, same seat) fired twice concurrently.
    const retryPairIndex = new Map(); // user -> index of first task, to pair up results later
    for (let u = 0; u < RETRY_USERS; u++) {
        const user = `retry_user${u}`;
        const seat = retrySeats[u];
        const key = `retrykey-${user}`;
        retryPairIndex.set(user, tasks.length);
        tags.push({ group: 'idempotent_retry', seat, user });
        tasks.push(() => reserve(tokenByUser.get(user), showId, [seat], key));
        tags.push({ group: 'idempotent_retry', seat, user });
        tasks.push(() => reserve(tokenByUser.get(user), showId, [seat], key));
    }

    // Idempotency conflict: first booking succeeds, second reuses the key with a DIFFERENT seat.
    // Both seats are from dedicated, otherwise-uncontested pools so the only possible decline
    // reason is the idempotency check itself, never incidental seat contention.
    // (Both still fired concurrently - whichever lands first wins the key; the other must 409.)
    for (let u = 0; u < CONFLICT_USERS; u++) {
        const user = `conflict_user${u}`;
        const seatA = conflictSeatsA[u];
        const seatB = conflictSeatsB[u];
        const key = `conflictkey-${user}`;
        tags.push({ group: 'idempotency_conflict_first', seat: seatA, user });
        tasks.push(() => reserve(tokenByUser.get(user), showId, [seatA], key));
        tags.push({ group: 'idempotency_conflict_second', seat: seatB, user });
        tasks.push(() => reserve(tokenByUser.get(user), showId, [seatB], key));
    }

    // Per-user limit: one user, 10 concurrent requests for 10 DISTINCT seats (limit=4).
    for (let u = 0; u < LIMIT_USERS; u++) {
        const user = `limit_user${u}`;
        for (let i = 0; i < 10; i++) {
            const seat = limitSeats[u * 10 + i];
            tags.push({ group: 'per_user_limit', seat, user });
            tasks.push(() => reserve(tokenByUser.get(user), showId, [seat], `limitkey-${user}-${i}`));
        }
    }

    log(`firing ${tasks.length} reservation requests at concurrency=${CONCURRENCY} ...`);
    const start = Date.now();
    const results = await runPool(tasks, CONCURRENCY);
    const elapsedMs = Date.now() - start;
    log(`burst complete in ${elapsedMs}ms (${(tasks.length / (elapsedMs / 1000)).toFixed(1)} req/s)`);

    // ---- Aggregate outcome distribution -----------------------------------------------------
    const outcomeCounts = {};
    let serverErrors = 0;
    for (const r of results) {
        const o = outcomeOf(r);
        outcomeCounts[o] = (outcomeCounts[o] || 0) + 1;
        if (o.startsWith('server_error') || o === 'network_error') serverErrors++;
    }

    console.log('\n=== Outcome distribution (all requests) ===');
    for (const [k, v] of Object.entries(outcomeCounts).sort((a, b) => b[1] - a[1])) {
        console.log(`  ${k.padEnd(28)} ${v}`);
    }

    // ---- Per-group correctness checks -------------------------------------------------------
    const failures = [];

    // 1) Hot seat storm: exactly one 201 per hot seat, rest declined (seat_taken or user_limit if any overlap).
    for (let s = 0; s < HOT_SEATS; s++) {
        const seat = hotSeats[s];
        const idxs = tags.map((t, i) => (t.group === 'hot_seat_storm' && t.seat === seat ? i : -1)).filter(i => i >= 0);
        const wins = idxs.filter(i => results[i].status === 201).length;
        const serverErrs = idxs.filter(i => results[i].status === 0 || results[i].status >= 500).length;
        if (wins !== 1) failures.push(`hot seat ${seat}: expected exactly 1 winner, got ${wins}`);
        if (serverErrs !== 0) failures.push(`hot seat ${seat}: ${serverErrs} requests errored (network/5xx)`);
    }

    // 2) Idempotent retry pairs: exactly one 201 + one 200, same reservation_id, zero 409s.
    let retryOk = 0;
    for (const [user, firstIdx] of retryPairIndex) {
        const a = results[firstIdx];
        const b = results[firstIdx + 1];
        const statuses = [a.status, b.status].sort();
        const sameReservation = a.body?.reservation_id && a.body?.reservation_id === b.body?.reservation_id;
        if (statuses[0] === 200 && statuses[1] === 201 && sameReservation) {
            retryOk++;
        } else {
            failures.push(`idempotent retry for ${user}: statuses=${statuses.join(',')} sameReservation=${sameReservation}`);
        }
    }
    log(`idempotent_retry: ${retryOk}/${RETRY_USERS} pairs resolved correctly`);

    // 3) Idempotency conflict: first group should be mostly 201 (modulo hot-seat overlap n/a here since seatA is dedicated),
    //    second group (different seat, same key) must be 409 idempotency_conflict for every request that lost the key race.
    let conflictOk = 0;
    {
        const byUser = new Map();
        tags.forEach((t, i) => {
            if (t.group === 'idempotency_conflict_first' || t.group === 'idempotency_conflict_second') {
                if (!byUser.has(t.user)) byUser.set(t.user, {});
                byUser.get(t.user)[t.group] = results[i];
            }
        });
        for (const [user, r] of byUser) {
            const first = r.idempotency_conflict_first;
            const second = r.idempotency_conflict_second;
            const oneWonTheKey = (first.status === 201) !== (second.status === 201); // exactly one inserted the reservation
            const loserIsConflict = (first.status === 201 ? second : first).body?.error === 'idempotency_conflict';
            if (oneWonTheKey && loserIsConflict) conflictOk++;
            else failures.push(`idempotency_conflict user ${user}: first=${first.status} second=${second.status}`);
        }
        log(`idempotency_conflict: ${conflictOk}/${CONFLICT_USERS} resolved correctly`);
    }

    // 4) Per-user limit: each limit user ends with exactly PER_USER_LIMIT confirmed, rest declined by limit.
    let limitOk = 0;
    for (let u = 0; u < LIMIT_USERS; u++) {
        const user = `limit_user${u}`;
        const idxs = tags.map((t, i) => (t.group === 'per_user_limit' && t.user === user ? i : -1)).filter(i => i >= 0);
        const confirmedCount = idxs.filter(i => results[i].status === 201).length;
        const limitDeclined = idxs.filter(i => results[i].body?.error === 'user_limit_exceeded').length;
        if (confirmedCount === PER_USER_LIMIT && confirmedCount + limitDeclined === idxs.length) {
            limitOk++;
        } else {
            failures.push(`per_user_limit user ${user}: confirmed=${confirmedCount} limitDeclined=${limitDeclined} of ${idxs.length}`);
        }
    }
    log(`per_user_limit: ${limitOk}/${LIMIT_USERS} users correctly capped at ${PER_USER_LIMIT}`);

    // 5) Zero 5xx / network errors across the ENTIRE burst.
    if (serverErrors > 0) {
        failures.push(`${serverErrors} requests hit a network error or 5xx - correctness bar requires zero`);
    }

    // ---- Final reconciliation ---------------------------------------------------------------
    const stateRes = await jsonFetch(`/shows/${showId}`);
    const counts = stateRes.body?.counts;
    console.log('\n=== Final reconciliation (GET /shows/{id}) ===');
    console.log(JSON.stringify(counts));
    if (!counts || counts.available + counts.held + counts.confirmed !== counts.total) {
        failures.push(`reconciliation invariant broken: ${JSON.stringify(counts)}`);
    } else {
        log(`reconciliation OK: available(${counts.available}) + held(${counts.held}) + confirmed(${counts.confirmed}) == total(${counts.total})`);
    }

    console.log('\n=== Verdict ===');
    if (failures.length === 0) {
        console.log(`PASS - all correctness checks held across ${tasks.length} concurrent requests.`);
        process.exit(0);
    } else {
        console.log(`FAIL - ${failures.length} issue(s):`);
        failures.slice(0, 50).forEach(f => console.log(`  - ${f}`));
        if (failures.length > 50) console.log(`  ... and ${failures.length - 50} more`);
        process.exit(1);
    }
}

main().catch(err => {
    console.error('burst script crashed:', err);
    process.exit(1);
});

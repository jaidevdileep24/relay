# ADR-0002: `FOR UPDATE SKIP LOCKED` claim with a time lease

**Status:** Accepted

## Context

Several dispatcher threads, and eventually several instances, pull from the same
`delivery` table. They must never send the same delivery at the same time, and they
must not serialise behind each other's locks.

## Decision

```sql
SELECT id FROM delivery
 WHERE state = 'PENDING' AND next_attempt_at <= now()
 ORDER BY next_attempt_at
 LIMIT :n
 FOR UPDATE SKIP LOCKED
```

Inside the same short transaction, each claimed row's `next_attempt_at` is pushed
forward by a **lease**, then the transaction commits. The row locks are gone, but the
rows are no longer "due", so nobody else claims them. When the result is recorded, the
row becomes `SUCCEEDED` / `FAILED` or gets its real retry time. If the worker dies, the
lease expires and the row comes back on its own.

The queue is served by a **partial index** `ON delivery(next_attempt_at) WHERE state =
'PENDING'`, so the index is the size of the backlog, not the size of all history.

## Consequences

- N workers pull disjoint batches with no coordinator and no extra infrastructure.
- Crash recovery is free: there is no "in progress" state to clean up, only a lease that
  expires.
- The lease has to cover the worst-case send, or a slow send can be claimed twice. Since
  [ADR-0011](0011-continuous-drain-dispatcher.md), rows are claimed only for free
  threads, so the lease covers one send (`2 × http-timeout` plus one round of margin).
- A send that outlives its lease *can* be duplicated. That is accepted
  ([ADR-0004](0004-at-least-once-delivery.md)).
- Measured: 0 duplicate sends across every benchmark run, including 10,000 deliveries on
  64 threads.

## Alternatives considered

- **Plain `FOR UPDATE`.** Workers queue behind each other's locks, so there is no
  parallelism.
- **Holding the lock for the whole send.** See [ADR-0003](0003-no-http-inside-a-transaction.md).
- **Advisory locks / a `claimed_by` column.** More moving parts, and a dead worker's
  claims need a reaper. The lease is the reaper.

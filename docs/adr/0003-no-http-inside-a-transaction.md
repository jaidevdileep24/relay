# ADR-0003: Never make an HTTP call inside a database transaction

**Status:** Accepted

## Context

The simplest dispatcher opens a transaction, locks a delivery, sends it, records the
result and commits. That holds a pooled connection for the whole network round trip,
which can be up to the 10 s timeout. With a pool of 30, thirty slow receivers take every
connection, and ingest, the API and the dispatcher all stall. One tenant's broken
endpoint becomes everyone's outage.

## Decision

Dispatch is three separate steps:

```
TX 1   claim + lease + snapshot       (milliseconds, holds row locks)
----   HTTP send                      (no transaction, no connection)
----   failure classification (P5)    (no transaction - it is a network call too)
TX 2   record attempt + advance state (milliseconds)
```

TX 1 builds an immutable `DispatchTask` snapshot (URL, secret, payload, ids), so the send
step never touches a lazy JPA proxy and cannot accidentally open a session.

## Consequences

- Connection hold time is independent of receiver latency. A pool of 30 serves 32 workers
  plus the API at full load (benchmarked).
- The same rule applies to the LLM classifier (P5): a model call is a network call, so it
  runs between the send and TX 2, never inside either.
- There is a window between send and record. If the process dies there, the receiver got
  the webhook but no attempt was recorded, and the lease expiry causes a resend. That is
  at-least-once by design ([ADR-0004](0004-at-least-once-delivery.md)).

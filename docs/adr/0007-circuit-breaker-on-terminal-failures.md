# ADR-0007: The circuit breaker counts dead deliveries, not failed attempts

**Status:** Accepted

## Context

An endpoint that is gone for good (domain expired, service retired) would otherwise burn
the full retry budget on every new event forever, wasting worker time and filling the
attempt log.

## Decision

`endpoint.consecutive_failures` counts deliveries that **exhausted all retries** in a
row. Any success resets it to zero. At the threshold (default 5) the endpoint is
disabled, and its still-queued deliveries move to `DISABLED`. They are not deleted, so
they stay visible and replayable.

- The counter is incremented with a single `UPDATE … RETURNING`, not read-modify-write
  in Java, so concurrent workers cannot lose each other's increments.
- The reset is guarded with `WHERE consecutive_failures <> 0`, so the common case (a
  success on a healthy endpoint) is not a write.
- `disableIfEnabled` is conditional, so only one worker "trips" the breaker and logs it.

## Consequences

- A flaky endpoint (fails, then recovers on retry) never trips the breaker. Only one that
  actually loses deliveries does.
- Re-enabling (`POST …/endpoints/{id}/enable`) clears the counter. Bulk replay re-queues
  what was stranded.

> **Gotcha recorded for future maintainers:** `@Modifying` must *not* be put on the
> `UPDATE … RETURNING` query. It makes Spring Data call `executeUpdate()`, which rejects
> the result set, and the breaker silently stops tripping.

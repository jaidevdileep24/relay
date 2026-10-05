# ADR-0009: Per-endpoint token bucket, per process (Redis deferred)

**Status:** Accepted, with a known limit

## Context

One tenant's burst must not monopolise the dispatch threads. A small receiver handed our
full throughput falls over, and we would then record that as *its* failure.

## Decision

An in-memory token bucket per endpoint (`burst` 50, refill 50/s), with the refill
computed lazily on read. A refusal is **not a failed attempt**: no attempt row, no
`attempt_count` increment and no retry budget spent. The delivery is simply deferred
(jittered) and claimed again later.

## Consequences

- Back-pressure never trips the breaker. Otherwise a busy endpoint could disable itself
  with zero requests sent.
- **N instances allow N × the configured rate.** That is accepted for now: the fix is a
  shared counter in Redis, a new stateful dependency, and it is not worth adding until a
  multi-instance deployment shows the cap is actually being exceeded.
- Idle buckets are evicted so the map does not grow with every endpoint ever seen.

## Revisit when

Relay runs as more than one instance, or a receiver reports being overrun.

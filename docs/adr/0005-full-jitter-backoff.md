# ADR-0005: Exponential backoff with full jitter

**Status:** Accepted

## Context

When a popular receiver goes down, thousands of deliveries fail within the same second.
With deterministic backoff (1 s, 2 s, 4 s…) they all retry at the same instant, the
recovering receiver sees a synchronised spike every interval, and it falls over again.

## Decision

The delay for attempt *n* is uniformly random in `[0, min(maxDelay, base × 2ⁿ)]` ("full
jitter", per the AWS architecture blog analysis). The defaults are base 1 s, cap 1 h and
8 attempts. Rate-limit deferrals ([ADR-0009](0009-per-process-rate-limiter.md)) are
jittered too.

## Consequences

- Retries spread evenly across the window instead of arriving as a wave. The
  distribution was verified uniform over 20,000 samples in `FullJitterRetryPolicyTest`.
- Individual retries are less predictable. Some come almost immediately, which is fine
  because the total budget is unchanged.
- `base << n` is shift-capped at 32. Java masks a `long` shift to 6 bits, so `x << 64`
  would silently become `x << 0`.

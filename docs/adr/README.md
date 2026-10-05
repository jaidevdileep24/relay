# Architecture Decision Records

Each record is one decision: the context that forced it, what was chosen, what it costs,
and what was rejected.

| # | Decision |
|---|---|
| [0001](0001-transactional-outbox-in-postgres.md) | Transactional outbox in Postgres, not a message broker |
| [0002](0002-skip-locked-queue-with-leases.md) | `FOR UPDATE SKIP LOCKED` claim with a time lease |
| [0003](0003-no-http-inside-a-transaction.md) | Never make an HTTP call inside a database transaction |
| [0004](0004-at-least-once-delivery.md) | At-least-once delivery, never exactly-once |
| [0005](0005-full-jitter-backoff.md) | Exponential backoff with full jitter |
| [0006](0006-hmac-signatures.md) | HMAC-SHA256 over `id.timestamp.body`, versioned |
| [0007](0007-circuit-breaker-on-terminal-failures.md) | Circuit breaker counts dead deliveries, not failed attempts |
| [0008](0008-replay-reuses-delivery-row.md) | Replay reuses the delivery row and bumps `replay_count` |
| [0009](0009-per-process-rate-limiter.md) | Per-endpoint token bucket, per process (Redis deferred) |
| [0010](0010-ai-failure-triage.md) | LLM failure triage behind a never-throwing, cached, optional interface |
| [0011](0011-continuous-drain-dispatcher.md) | Continuous "claim for free threads" dispatcher (benchmark-driven) |
| [0012](0012-ssrf-guard.md) | SSRF guard on every user-supplied URL, at create *and* at send |

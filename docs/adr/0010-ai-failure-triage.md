# ADR-0010: LLM failure triage behind a never-throwing, cached, optional interface

**Status:** Accepted

## Context

Status codes lie. A receiver answering `200 OK` with
`{"ok":false,"error":"signature verification failed"}` looks like a success to a
`switch` on the status. A `500` whose body says "invalid API key" will never succeed on
retry, yet backoff spends 8 attempts over an hour finding that out. The signal is in
unstructured body text.

## Decision

A `FailureClassifier` interface returns a category (`AUTH`, `CLIENT_ERROR`, `RATE_LIMITED`,
`SERVER_ERROR`, `NETWORK`, `UNKNOWN`), a retryable flag and an optional `Retry-After`. Implementations are
stacked, outermost first:

```
Hybrid          no response body? answer from the status code, never pay a model
 ├─ Caching     same failure classified once, not once per delivery
 │   └─ Ollama (dev, llama3.2) | Claude (prod, structured outputs)
 └─ Heuristic   fallback whenever the model cannot answer
```

Rules:

- **Never throw, never run inside a transaction.** Every implementation returns
  `UNKNOWN` on any error, and `UNKNOWN` falls back to the normal retry policy.
- **The retry budget still governs.** "Retryable" cannot buy extra attempts. A
  receiver-supplied `Retry-After` is clamped to `max-retry-after-secs` and cannot extend
  a delivery past its last attempt, because that value comes from a third party and a
  hostile receiver could otherwise park a delivery forever.
- **`UNKNOWN` is never cached.** It is a failure to answer, not an answer.
- **Fingerprints hash a normalised body** (digits and hex runs collapsed). Raw error
  bodies carry request ids and timestamps, which would give the cache a 0% hit rate.
- **Off by default** (`relay.ai.provider: none`). With it off, the dispatcher behaves
  exactly as it did before P5.

## Consequences

- Non-retryable failures stop after one attempt instead of eight, and `429`s honour the
  receiver's own pacing.
- Model spend is close to zero in steady state: one call per distinct failure shape.
- Verified against a live local model: a body reading "signature verification failed"
  classifies as `AUTH`, non-retryable.
- **Limit:** the dispatcher treats every `2xx` as success and never classifies it. That
  matches the Standard Webhooks contract (receivers signal failure with a non-2xx). Reading
  `200 {"ok":false}` as a failure would be an explicit opt-in, and is not built.

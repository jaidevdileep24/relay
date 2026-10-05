# Architecture

Relay accepts events and guarantees they reach other people's HTTPS endpoints, with
retries, signatures, a circuit breaker, a dead-letter queue and a full audit trail.

## System overview

```mermaid
flowchart LR
    subgraph Producer
        C[Client app]
    end

    subgraph Relay["Relay (Spring Boot)"]
        API["REST API<br/>/api/v1/applications/..."]
        ING["IngestService<br/>(the outbox write)"]
        SCH["DispatchScheduler<br/>drain() loop"]
        W["Worker pool<br/>32 threads"]
        RL["Token bucket<br/>per endpoint"]
        SIG["HMAC signer"]
        SSRF["SSRF guard"]
        AI["FailureClassifier<br/>Hybrid → Cache → LLM | Heuristic"]
    end

    subgraph PG["Postgres 16"]
        M[(message)]
        D[(delivery<br/>= the queue)]
        A[(delivery_attempt<br/>= audit log)]
        FC[(failure_classification<br/>= cache)]
    end

    R1[Receiver A]
    R2[Receiver B]
    LLM["Ollama / Claude"]

    C -- "POST /messages<br/>Idempotency-Key" --> API
    API --> ING
    ING -- "ONE transaction" --> M & D
    API -. "202 Accepted" .-> C

    SCH -- "TX1: SKIP LOCKED claim + lease" --> D
    SCH --> W
    W --> RL
    W --> SSRF
    W --> SIG
    W -- "signed POST<br/>(no transaction open)" --> R1 & R2
    W -- "on failure" --> AI
    AI --> FC
    AI -.-> LLM
    W -- "TX2: record attempt,<br/>advance state" --> A & D
```

## The two invariants everything else rests on

1. **Ingest writes the message and every delivery row in one transaction**
   ([ADR-0001](adr/0001-transactional-outbox-in-postgres.md)). Ingest never sends HTTP.
2. **No network call inside a transaction**
   ([ADR-0003](adr/0003-no-http-inside-a-transaction.md)). That covers webhook sends and
   LLM calls alike.

## Dispatch, one delivery

```mermaid
sequenceDiagram
    autonumber
    participant S as drain() loop
    participant DB as Postgres
    participant W as Worker thread
    participant R as Receiver
    participant C as Classifier

    S->>S: wait for a free worker (+ ≤10 ms linger)
    S->>DB: TX1 SELECT … FOR UPDATE SKIP LOCKED LIMIT freeThreads
    S->>DB: TX1 next_attempt_at = now() + lease, COMMIT
    Note over S,DB: locks released, rows invisible until lease expires
    S->>W: DispatchTask snapshot (url, secret, payload)
    W->>W: rate limiter: allowed? (else defer, not an attempt)
    W->>W: SSRF re-check, sign id.timestamp.body
    W->>R: POST, 10 s timeout, redirects refused
    R-->>W: status + body
    alt failure
        W->>C: classify(status, body)  -- outside any transaction
        C-->>W: category, retryable, Retry-After?
    end
    W->>DB: TX2 INSERT delivery_attempt, UPDATE delivery state
    W->>S: release worker permit
```

## Delivery state machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: ingest (fan-out)
    PENDING --> PENDING: failed, budget left<br/>(jittered backoff or Retry-After)
    PENDING --> PENDING: rate-limited<br/>(deferred, no attempt recorded)
    PENDING --> SUCCEEDED: 2xx
    PENDING --> FAILED: retries exhausted, or<br/>classified non-retryable
    PENDING --> DISABLED: endpoint breaker tripped
    FAILED --> PENDING: replay (replay_count + 1)
    DISABLED --> PENDING: replay (replay_count + 1)
    SUCCEEDED --> [*]
```

`FAILED` + `DISABLED` together form the **dead-letter queue**. Only they are replayable:
a `PENDING` row may be leased by a worker at this moment
([ADR-0008](adr/0008-replay-reuses-delivery-row.md)).

## Data model

```mermaid
erDiagram
    application ||--o{ endpoint : owns
    application ||--o{ message : sends
    endpoint ||--o{ endpoint_event_type : "subscribes to (empty = all)"
    message ||--o{ delivery : "fans out to"
    endpoint ||--o{ delivery : receives
    delivery ||--o{ delivery_attempt : "audit log"

    application {
        uuid id PK
        text name
    }
    endpoint {
        uuid id PK
        text url
        text secret
        bool enabled
        int consecutive_failures
    }
    message {
        uuid id PK
        text event_type
        jsonb payload
        text idempotency_key "unique per application"
    }
    delivery {
        bigint id PK
        text state
        int attempt_count
        int replay_count
        timestamptz next_attempt_at "partial index WHERE PENDING"
    }
    delivery_attempt {
        bigint id PK
        int attempt_number
        int replay_count
        int http_status
        text failure_category
    }
```

The id strategy is deliberately mixed:

- **UUIDs** for application, endpoint and message. They appear in public URLs, and
  sequential ids would leak volume.
- **`BIGSERIAL`** for delivery and delivery_attempt. These are internal and very
  high-volume, and monotonic ids keep the indexes tight. Delivery ids come from a pooled
  sequence, so fan-out INSERTs batch.

## Package layout

```
com.dileep.relay
├── api/          controllers, DTOs (records), GlobalExceptionHandler, Pages (sort allowlist)
├── domain/       Application · Endpoint · Message · Delivery · DeliveryAttempt · DbTime
├── repository/   Spring Data JPA; DeliveryRepository holds the SKIP LOCKED claim
├── service/      interfaces + impl/; SsrfGuard
├── dispatch/     DispatchScheduler · DispatchWorker · HttpSender · HmacSignatureSigner
├── retry/        FullJitterRetryPolicy (Strategy)
├── ratelimit/    TokenBucketRateLimiter
├── ai/           FailureClassifier + Hybrid / Caching / Ollama / Claude / Heuristic
└── config/       RelayProperties (relay.*), OpenApiConfig
```

Layer rule: `api → service → repository`. Controllers never touch repositories, and
entities never cross the API boundary (DTOs do).

## Where it would go next

| Pressure | Response | Status |
|---|---|---|
| More than one instance | `SKIP LOCKED` already supports it; move the rate limiter to Redis | [ADR-0009](adr/0009-per-process-rate-limiter.md) |
| Per-delivery recording cost | batch TX2 across completions | [ADR-0011](adr/0011-continuous-drain-dispatcher.md) |
| Downstream consumers of events | relay the outbox into Kafka (P6) | roadmap |
| Attempt table growth | partition `delivery_attempt` by month, drop old partitions | roadmap |

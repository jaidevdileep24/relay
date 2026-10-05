# Benchmarks

Two halves of the system, measured separately, because they fail differently.

- **Ingest:** `POST /messages`. This is the outbox write: one transaction inserts the
  message and fans it out into delivery rows. Measured with **k6**, end to end over HTTP.
- **Dispatch:** claim, then send, then record. Measured with a JUnit benchmark against a
  real Postgres (Testcontainers), with the receiver replaced by a stub that sleeps for a
  fixed latency. This isolates the engine from network noise, and no traffic leaves the
  machine.

**Environment:** one laptop, 12 cores and 15 GB RAM. The app (Java 21, Spring Boot 3.5),
Postgres 16 (Docker) and the load generator all run on the same machine and compete for
the same CPUs. Absolute numbers are therefore pessimistic. The before/after ratios are
the point.

---

## Ingest (k6, open model)

`constant-arrival-rate`: requests arrive at the target rate whether or not earlier ones
have finished, so server slowdowns show up as latency and dropped iterations rather than
being hidden. Each message fans out to 1–3 endpoints (2 on average). 30 s per step, after
a 15 s warm-up.

### Before: 838 req/s ceiling

| target rate | achieved | p50 | p95 | p99 | errors |
|---:|---:|---:|---:|---:|---:|
| 200/s | 200/s | 10.9 ms | 17.5 ms | 21.8 ms | 0 |
| 500/s | 499/s | 13.8 ms | 27.5 ms | 76.0 ms | 0 |
| 1000/s | **838/s** | 38.0 ms | 904 ms | **3.52 s** | 0 (4,491 dropped) |

### What was wrong

Two avoidable queries on the hottest path:

1. **A SELECT before every message INSERT.** `Message` has an application-assigned UUID
   and no `@Version`, so Spring Data cannot tell it is new. `save()` falls back to
   `merge()`, and `merge()` reads the row first. Fixed by implementing `Persistable`.
2. **One INSERT round trip per delivery row.** `IDENTITY` ids force Hibernate to run each
   insert alone to read the generated key back, which disables JDBC batching. Fixed by
   switching to a pooled sequence (`V5__delivery_id_batching.sql`, `allocationSize = 50`)
   plus `hibernate.jdbc.batch_size` and the driver's `reWriteBatchedInserts`. Fan-out is
   now one multi-row INSERT.

### After: ~1,300 req/s ceiling, flat latency to 1,000 req/s

| target rate | achieved | p50 | p95 | p99 | errors |
|---:|---:|---:|---:|---:|---:|
| 200/s | 200/s | 11.0 ms | 15.9 ms | 19.7 ms | 0 |
| 500/s | 499/s | 12.0 ms | 18.3 ms | 23.7 ms | 0 |
| **1000/s** | **999/s** | **10.1 ms** | **17.1 ms** | **25.0 ms** | **0** |
| 1500/s | 1,305/s | 27.4 ms | 645 ms | 922 ms | 0 (5,111 dropped) |
| 2000/s | 1,031/s | 403 ms | 929 ms | 1.55 s | 0 (dropped) |

At 1000/s, p99 went from **3.52 s to 25 ms**. Around **1,300 req/s** is the knee. That is
**~2,600 delivery rows/s** written transactionally, because each message fans out to two
deliveries on average.

### Overload behaviour

Pushed to 3× the sustainable rate, about 0.5% of requests waited longer than Hikari's 5 s
`connection-timeout` and were returned as **500**. That is the wrong signal: it tells the
client its request was bad. These requests now return **503 with `Retry-After: 1`**.

The 503 path was verified by forcing the pool down to 2 connections with a 250 ms timeout
and firing 600 concurrent requests. Results: 228 × 202, 372 × 503, **0 × 500**.

---

## Dispatch (JUnit + Testcontainers)

The backlog is seeded with `next_attempt_at` set one day ahead, then released in a single
UPDATE, so the clock starts with everything due at once. The rate limiter is off because
it is a per-endpoint policy, and this measures the engine itself.

### Before: 13.6 deliveries/s, no matter how fast the receiver is

The poll ran `claim 16 → send all 16 → wait for the slowest → sleep 1 s → repeat`, so the
ceiling was `batch-size / poll-interval` = 16/s. There was also head-of-line blocking: a
single receiver near the 10 s timeout held all 8 threads idle until it finished.

### After: continuous claiming

`DispatchWorker.drain()` claims rows only when a worker thread is free, and only as many
as are free. It keeps going without pausing for as long as rows are due. A claimed row
therefore never waits in a queue, and its lease only has to cover one send. The claimer
lingers up to 10 ms for a quarter of the pool to free up, which turns many one-row claim
transactions into fewer, larger ones.

| config | receiver latency | throughput | vs before | duplicates |
|---|---:|---:|---:|---:|
| before: batch + 1 s poll, 8 threads | 50 ms | **13.6/s** | 1× | 0 |
| drain, 8 threads | 50 ms | 121/s | 8.9× | 0 |
| drain, 32 threads (**shipped default**) | 50 ms | **390/s** | **29×** | 0 |
| drain, 64 threads, pool 40 | 50 ms | 562/s | 41× | 0 |
| drain, 32 threads (**shipped default**) | 200 ms | 140/s | — | 0 |

**Sanity check (Little's law).** 32 threads against a 200 ms receiver can do at most
32 / 0.2 = 160/s. Measured: 140/s, which is 87% of the theoretical limit. The engine now
waits on receivers, not on itself. The remaining gap at low latency is per-delivery
database work in the recording transaction (attempt INSERT + delivery UPDATE).

**Zero duplicates in every run.** `SKIP LOCKED` plus the lease does what it should.

---

## Reproduce

```bash
# Dispatch engine (no network, own Postgres via Testcontainers)
mvn test -Dbench -Dtest=DispatchThroughputBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
    -Dbench.deliveries=10000 -Dbench.latencyMs=50 -Drelay.dispatch.worker-threads=32

# Ingest: throwaway database, dispatcher OFF, so nothing is ever sent anywhere
docker compose exec postgres psql -U relay -c "CREATE DATABASE relay_loadtest"
mvn -DskipTests package
java -jar target/relay-0.0.1-SNAPSHOT.jar \
    --spring.datasource.url='jdbc:postgresql://localhost:5433/relay_loadtest?reWriteBatchedInserts=true' \
    --relay.dispatch.enabled=false --server.port=8089 --logging.level.com.dileep.relay=WARN
docker run --rm --network host -v "$PWD/loadtest:/scripts" grafana/k6 \
    run -e BASE=http://localhost:8089 -e RATE=1000 -e DURATION=30s /scripts/ingest.js
docker compose exec postgres psql -U relay -c "DROP DATABASE relay_loadtest"
```

## Not measured yet

- **Multiple instances.** `SKIP LOCKED` is built for this, but the rate limiter is per
  process (see [ADR-0009](adr/0009-per-process-rate-limiter.md)).
- **Real network receivers.** The stub sender removes TLS handshakes and connection setup.
  `HttpClient` reuses connections, so this mostly affects the first send to each host.
- **Long-run backlog growth.** Partial-index size and vacuum behaviour over hours of retries.

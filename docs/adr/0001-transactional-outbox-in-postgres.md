# ADR-0001: Transactional outbox in Postgres, not a message broker

**Status:** Accepted

## Context

A caller hands Relay an event and expects it to reach every subscribed endpoint, even
if Relay crashes a millisecond after answering. The obvious design is "write to the DB,
then publish to Kafka/RabbitMQ". That is a **dual write**: if the process dies between
the two, the event is either stored but never sent, or sent but never stored. No retry
logic can recover from that.

## Decision

Ingest writes the `message` row **and one `delivery` row per subscribed endpoint in a
single Postgres transaction**. The `delivery` table *is* the queue. Ingest never sends
HTTP; it returns `202 Accepted` the moment the transaction commits.

## Consequences

- An event is either fully accepted (message + all deliveries) or not at all. There is
  no partial state to reconcile.
- One stateful dependency (Postgres) instead of two. Simpler to run, back up and reason
  about.
- The queue is queryable with SQL, which is what makes the DLQ listing, attempt history
  and replay ([ADR-0008](0008-replay-reuses-delivery-row.md)) cheap to build.
- Throughput is bounded by Postgres write capacity. Measured: ~1,300 messages/s
  (~2,600 delivery rows/s) on a laptop ([benchmarks](../benchmarks.md)). That is far
  beyond a portfolio workload, and well past what most SaaS webhook senders need.
- Polling the table costs a cheap indexed query every 250 ms while the queue is empty.

## Alternatives considered

- **Kafka/RabbitMQ directly.** Dual-write problem, plus an extra system to operate. Still
  on the roadmap (P6), but *behind* the outbox (relay outbox rows into Kafka), never
  instead of it.
- **CDC (Debezium) off the WAL.** Removes polling, but adds Kafka Connect for a problem
  that polling already solves at this scale.

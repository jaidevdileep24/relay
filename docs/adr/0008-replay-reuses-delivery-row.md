# ADR-0008: Replay reuses the delivery row and bumps `replay_count`

**Status:** Accepted

## Context

After an outage, an operator wants to "re-send everything that failed in the last hour".
The natural move is to insert fresh delivery rows. But
`UNIQUE (message_id, endpoint_id)` on `delivery` is what makes ingest fan-out
idempotent, so a second row per pair is impossible by design.

## Decision

Replay resets the existing row (`state = PENDING`, `attempt_count = 0`,
`next_attempt_at = now()`) and increments **`replay_count`**. Each attempt row stores the
`replay_count` it was made under, so `(replay_count, attempt_number)` identifies an
attempt uniquely across generations.

Only `FAILED` and `DISABLED` rows are replayable, and the guard is in SQL
(`WHERE state IN (…)`):

- A `PENDING` row may be leased by a worker right now, and resetting it underneath would
  duplicate the send.
- A double-clicked replay is harmless, because the second click matches zero rows.

Bulk replay is one `UPDATE` statement, so it cannot half-apply. It maintains `version`
and `updated_at` by hand because bulk updates bypass `@Version` and `@PreUpdate`.

## Consequences

- The audit trail stays complete: "attempt 1 of replay 2" is distinguishable from the
  original attempt 1.
- Routes are nested under the owning application, and a wrong owner returns **404, never
  403**, because 403 would confirm the row exists. Delivery ids are `BIGSERIAL` and
  therefore guessable.

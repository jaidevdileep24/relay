# ADR-0004: At-least-once delivery, never exactly-once

**Status:** Accepted

## Context

Exactly-once delivery over HTTP to a third party is impossible. If the receiver
processes a request and the connection drops before we read the `200`, we cannot know
whether it succeeded. Our only options are to send again (possible duplicate) or not
(possible loss).

## Decision

Relay guarantees **at-least-once**. Every send of a message carries the same
`webhook-id` header, stable across retries, replays and lease expiries, and receivers
**dedupe on it**. This is documented for receivers in
[verifying-webhooks.md](../verifying-webhooks.md).

Ingest is idempotent on the caller's side too: an `Idempotency-Key` header is enforced
by a unique partial index. A repeat with the same body returns the original message
(`202`). A repeat with a *different* body returns `422`, because answering `202` would
tell the caller their second event was accepted when it was silently dropped.

## Consequences

- No lost events, at the cost of occasional duplicates. That is the same contract Stripe,
  GitHub and Svix offer.
- The system never pretends otherwise, so no part of the design depends on a send
  happening exactly once.

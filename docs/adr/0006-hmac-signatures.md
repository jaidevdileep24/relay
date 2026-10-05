# ADR-0006: HMAC-SHA256 over `id.timestamp.body`, versioned

**Status:** Accepted

## Context

Receivers expose a public URL. Without signatures, anyone who learns it can post forged
events. Signing only the body is not enough either: a captured request can be replayed
forever.

## Decision

Follow the shape of the [Standard Webhooks](https://www.standardwebhooks.com/) spec:

- headers: `webhook-id`, `webhook-timestamp`, `webhook-signature`
- signed content: `{webhook-id}.{webhook-timestamp}.{raw body}`
- signature: `v1,` + base64(HMAC-SHA256(content, secret))
- secret: `whsec_` + 32 bytes from `SecureRandom`. Returned **once** on endpoint creation
  and `null` on every read after that.

Receivers reject timestamps older than 5 minutes.

## Consequences

- Forgery requires the secret, and replays expire after the tolerance window.
- The `v1,` prefix lets the algorithm rotate later: send `v1,… v2,…` side by side while
  receivers migrate.
- A new `Mac` instance is created per signature. `Mac` is stateful and not thread-safe, and
  32 worker threads share the signer.

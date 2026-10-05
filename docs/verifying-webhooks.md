# Verifying Relay webhooks

Every webhook Relay sends is signed. This page is for the **receiver** — the
person writing the endpoint that Relay calls.

A signature nobody checks is decoration. If you skip verification, anyone who
learns your endpoint URL can post whatever they like to it.

---

## What arrives

```http
POST /your/webhook HTTP/1.1
Content-Type: application/json
User-Agent: Relay/1.0
webhook-id: 5c4a4559-27c3-44d3-8a9e-f86c099760cd
webhook-timestamp: 1757808000
webhook-signature: v1,k8H2mQ3pR7xY1wL5nT9cV4bA6sD0fG8jK2mN5pQ7rS9=
webhook-event-type: invoice.paid

{"amount":4200,"currency":"INR"}
```

| Header | Meaning |
|---|---|
| `webhook-id` | stable id for this message — **dedupe on this** |
| `webhook-timestamp` | Unix seconds at send time |
| `webhook-signature` | `v1,` + base64 HMAC-SHA256 |
| `webhook-event-type` | e.g. `invoice.paid` |

Your signing secret was shown **once**, when the endpoint was created:
`whsec_vJxfCGAuKTNmSxl4atbtmEvrojAn8SLLf5WBiYo1B48`. Relay cannot show it again.

---

## How to verify

Recompute the signature and compare.

**The signed string is three parts joined by dots:**

```
{webhook-id}.{webhook-timestamp}.{raw request body}
```

Then `HMAC-SHA256(signed_string, secret)`, base64-encoded, prefixed with `v1,`.

### Node.js

```js
const crypto = require('crypto');

function verify(req, secret) {
  const id = req.headers['webhook-id'];
  const timestamp = req.headers['webhook-timestamp'];
  const signature = req.headers['webhook-signature'];

  // 1. Reject anything outside a 5-minute window (replay protection)
  const age = Math.abs(Date.now() / 1000 - Number(timestamp));
  if (!Number.isFinite(age) || age > 300) return false;

  // 2. Recompute over the RAW body, not a re-serialised object
  const signed = `${id}.${timestamp}.${req.rawBody}`;
  const expected = 'v1,' + crypto.createHmac('sha256', secret)
                                 .update(signed)
                                 .digest('base64');

  // 3. Constant-time compare
  const a = Buffer.from(signature || '');
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
```

### Python

```python
import base64, hashlib, hmac, time

def verify(headers, raw_body: bytes, secret: str) -> bool:
    ts = headers.get("webhook-timestamp", "")
    signature = headers.get("webhook-signature", "")

    try:
        if abs(time.time() - int(ts)) > 300:
            return False
    except ValueError:
        return False

    signed = f'{headers.get("webhook-id", "")}.{ts}.'.encode() + raw_body
    digest = hmac.new(secret.encode(), signed, hashlib.sha256).digest()
    expected = "v1," + base64.b64encode(digest).decode()

    return hmac.compare_digest(signature, expected)
```

### Java

```java
String signed = id + "." + timestamp + "." + rawBody;

Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
String expected = "v1," + Base64.getEncoder()
        .encodeToString(mac.doFinal(signed.getBytes(UTF_8)));

boolean ok = MessageDigest.isEqual(
        expected.getBytes(UTF_8), signature.getBytes(UTF_8));
```

---

## Four ways people get this wrong

**1. Signing a re-serialised body.** Frameworks parse JSON then hand you an
object. Re-serialising it changes key order and whitespace, and the signature
will never match. You need the **raw bytes** — `express.raw()`,
`request.get_data()`, `@RequestBody String`.

**2. Skipping the timestamp check.** The signature alone proves *authenticity*,
not *freshness*. Without the window, a captured request is a coupon anyone can
redeem forever — replay one `payment.succeeded` five hundred times and see what
happens. Relay's tolerance is **300 seconds**.

**3. Comparing with `==`.** String comparison short-circuits on the first
differing byte, so response time leaks how much of the signature was right. Use
`timingSafeEqual` / `compare_digest` / `MessageDigest.isEqual`.

**4. Assuming exactly-once.** Relay is **at-least-once by design**. A receiver
that succeeds but whose response is lost will be retried. Key your processing on
`webhook-id` and make repeats a no-op.

---

## Responding

| You return | Relay does |
|---|---|
| **2xx** | marks delivered, stops |
| 3xx | treats as failure — a redirect is a misconfiguration, and is not followed |
| 4xx / 5xx | retries with exponential backoff + jitter, up to 8 attempts over ~4 minutes |
| nothing within 10s | times out, counts as a failure |

**Return 2xx immediately, then process asynchronously.** Doing real work before
responding burns your timeout budget, and slow receivers get retried — which
means more load, not less.

After **5 consecutive deliveries** exhaust all their retries, Relay disables the
endpoint entirely and stops sending. Any queued deliveries are retired. You'll
need to re-enable it once the endpoint is healthy.

---

## Checklist

- [ ] Read the **raw** body before any JSON parsing
- [ ] Reject timestamps outside ±300s
- [ ] Constant-time comparison
- [ ] Dedupe on `webhook-id`
- [ ] Return 2xx fast, process later
- [ ] Store the secret somewhere secret — it is shown only once

# ADR-0012: SSRF guard on every user-supplied URL, at create *and* at send

**Status:** Accepted

## Context

Users give Relay a URL and Relay calls it from inside our network. That is a textbook
SSRF primitive. `https://169.254.169.254/latest/meta-data/` would hand out cloud IAM
credentials, and `https://localhost:8080/actuator/...` would hit our own internals.

## Decision

`SsrfGuard` rejects a URL unless:

- the scheme is `https`, with no credentials in the URL and the port in range
- **every** address the host resolves to is public. Loopback, private (RFC 1918),
  link-local, any-local and multicast are all rejected. Checking only the first answer is
  bypassable by a DNS record listing a public IP first and `127.0.0.1` second.

It runs when the endpoint is created **and again at send time**, because DNS can change
in between. The HTTP client **refuses redirects**, since a public URL could `302` to
loopback.

## Consequences

- The demo and load test cannot point endpoints at `localhost`. That is deliberate: there
  is no "dev bypass" flag, because such flags have a way of ending up on in production.
  The dispatcher is benchmarked with a stub sender instead.
- **Known gaps**, documented in the class:
  - CGNAT `100.64/10` and IPv6 ULA `fc00::/7` are not yet rejected.
  - There is a DNS-rebinding window between the check and the connect. The full fix
    pins the resolved IP for the connection.

# ADR-0011: Continuous "claim for free threads" dispatcher

**Status:** Accepted. Supersedes the batch-and-wait poll loop. Driven by a benchmark.

## Context

The original loop was: claim 16 rows → send all 16 on 8 threads → wait for the slowest
→ sleep 1 s → repeat. The benchmark ([benchmarks](../benchmarks.md)) measured
**13.6 deliveries/s with a 50 ms receiver**. The ceiling was `batch / poll-interval`, and
it had nothing to do with how fast receivers or the database were. On top of that, one
receiver near the 10 s timeout held every thread idle (head-of-line blocking).

## Decision

`DispatchWorker.drain()`, called by the scheduler:

1. Wait until at least one worker thread is free (a `Semaphore` with one permit per
   thread).
2. Linger up to 10 ms for a quarter of the pool to free up, so claims are batched rather
   than one row per transaction.
3. Claim exactly as many rows as there are free threads, and hand each one over
   immediately. The permit is released when that send is recorded.
4. Repeat until nothing is due. Only then does the 250 ms poll interval apply.

`pollAndDispatch()` (claim, send, join) is kept for tests, which need a call that
returns only after every result is recorded.

## Consequences

| | 50 ms receiver |
|---|---:|
| before (8 threads) | 13.6/s |
| drain (8 threads) | 121/s |
| drain (32 threads, default) | 390/s |
| drain (64 threads) | 562/s |

- At 200 ms receiver latency, 32 threads reach 140/s, 87% of the theoretical
  32 / 0.2 s = 160/s. The engine is now limited by receivers, as it should be.
- A claimed row never waits in an executor queue, so the lease shrinks to one send.
- A slow receiver occupies one thread, not the whole batch.
- Remaining cost per delivery is the recording transaction (attempt INSERT + delivery
  UPDATE). The next step, if needed, is batching those across completions.

## Alternatives considered

- **Keep batching, drop the sleep when a batch is full.** This fixes the idle second, but
  not head-of-line blocking.
- **Virtual threads, one per delivery.** Plausible on Java 21. The semaphore would still
  be needed to cap concurrent connections, so it changes little at this scale.

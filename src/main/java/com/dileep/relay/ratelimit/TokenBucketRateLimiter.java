package com.dileep.relay.ratelimit;

import com.dileep.relay.config.RelayProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One token bucket per endpoint, held in memory.
 *
 * <p>A bucket holds up to {@code burst} permits and refills at
 * {@code permitsPerSecond}. The burst allowance matters: traffic arrives in
 * clumps because the dispatcher polls in batches, and a limiter with no burst
 * would defer most of every batch even when the long-run rate is well under
 * the cap.
 *
 * <p>Refill is computed from elapsed time on read rather than by a background
 * thread - no timer to schedule, and an idle bucket costs nothing.
 *
 * <p><b>Known limitation:</b> the state is per process, so N instances allow
 * N times the configured rate. That is a deliberate trade for this phase; the
 * fix is a shared counter in Redis, and it is not worth the dependency until
 * the load test says the cap is actually being hit. Documented rather than
 * hidden.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

	/**
	 * Buckets are created on demand and swept when the map grows past this.
	 * Without a sweep the map is a slow leak keyed by endpoint id - unbounded
	 * in exactly the way a long-running dispatcher cannot afford.
	 */
	private static final int SWEEP_THRESHOLD = 10_000;

	private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();
	private final RelayProperties.RateLimit config;

	public TokenBucketRateLimiter(RelayProperties properties) {
		this.config = properties.getRateLimit();
	}

	@Override
	public boolean tryAcquire(UUID endpointId) {
		if (!config.isEnabled()) {
			return true;
		}
		if (buckets.size() > SWEEP_THRESHOLD) {
			sweepIdle();
		}
		return buckets.computeIfAbsent(endpointId, id -> new Bucket(config.getBurst()))
				.tryAcquire(config.getPermitsPerSecond(), config.getBurst());
	}

	/** Drops buckets that have been full and untouched for long enough to be uninteresting. */
	private void sweepIdle() {
		long cutoff = System.nanoTime() - IDLE_NANOS;
		buckets.values().removeIf(bucket -> bucket.isIdleSince(cutoff));
	}

	private static final long IDLE_NANOS = 5L * 60 * 1_000_000_000L;

	/**
	 * Guarded by {@code synchronized} rather than a CAS loop: the critical
	 * section is a few arithmetic operations, and contention is per endpoint,
	 * not global.
	 */
	private static final class Bucket {
		private double tokens;
		private long lastRefillNanos;

		Bucket(int burst) {
			this.tokens = burst;
			this.lastRefillNanos = System.nanoTime();
		}

		synchronized boolean tryAcquire(double permitsPerSecond, int burst) {
			long now = System.nanoTime();
			double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
			lastRefillNanos = now;

			tokens = Math.min(burst, tokens + elapsedSeconds * permitsPerSecond);
			if (tokens >= 1.0) {
				tokens -= 1.0;
				return true;
			}
			return false;
		}

		synchronized boolean isIdleSince(long cutoffNanos) {
			return lastRefillNanos < cutoffNanos;
		}
	}
}

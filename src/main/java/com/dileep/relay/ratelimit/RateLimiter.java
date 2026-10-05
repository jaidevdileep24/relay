package com.dileep.relay.ratelimit;

import java.util.UUID;

/**
 * Caps how fast we send to any single endpoint.
 *
 * <p>Without this, one tenant ingesting ten thousand events monopolises the
 * dispatcher's threads and every other tenant's webhooks queue behind them.
 * It also protects the receiver: a small endpoint handed our full throughput
 * falls over, which we then record as its failure and count against its
 * breaker.
 *
 * <p>A refusal is <b>not</b> a failed attempt. The caller must defer the
 * delivery - no attempt row, no attempt-count increment, no retry budget
 * consumed. Rate limiting is back-pressure, not an error.
 */
public interface RateLimiter {

	/**
	 * @return true if this send may proceed now, consuming one permit
	 */
	boolean tryAcquire(UUID endpointId);
}

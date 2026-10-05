package com.dileep.relay.retry;

import java.time.Duration;
import java.util.Optional;

/**
 * Decides when (or whether) a failed delivery should be attempted again.
 *
 * <p>Strategy pattern: swapping the implementation changes retry behaviour
 * with no change to the dispatcher. It also makes the policy trivially
 * unit-testable in isolation - you can compute 10,000 delays and assert their
 * distribution without touching a database or a socket.
 *
 * <p><b>Implementations must apply jitter.</b> A deterministic delay causes
 * every delivery that failed at the same moment to retry at the same moment,
 * turning your retries into a self-inflicted DDoS on a server that is already
 * struggling.
 */
public interface RetryPolicy {

	/**
	 * @param attemptNumber how many attempts have already been made (1-based:
	 *                      pass 1 after the first attempt failed)
	 * @return the delay before the next attempt, or {@link Optional#empty()}
	 *         if the delivery should be marked permanently FAILED
	 */
	Optional<Duration> nextDelay(int attemptNumber);
}

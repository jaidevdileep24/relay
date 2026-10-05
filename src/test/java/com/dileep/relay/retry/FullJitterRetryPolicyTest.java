package com.dileep.relay.retry;

import com.dileep.relay.config.RelayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test - no Spring, no database, no sockets.
 *
 * <p>That is the payoff of the Strategy pattern: the retry policy can be asked
 * for 20,000 delays and have their distribution asserted in milliseconds.
 */
class FullJitterRetryPolicyTest {

	private static FullJitterRetryPolicy policyWith(int maxAttempts, long baseMs, long maxMs) {
		RelayProperties properties = new RelayProperties();
		properties.getRetry().setMaxAttempts(maxAttempts);
		properties.getRetry().setBaseDelayMs(baseMs);
		properties.getRetry().setMaxDelayMs(maxMs);
		return new FullJitterRetryPolicy(properties);
	}

	private static FullJitterRetryPolicy defaultPolicy() {
		return new FullJitterRetryPolicy(new RelayProperties());
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
	@DisplayName("delay always falls inside [0, base * 2^n]")
	void delayStaysInsideItsWindow(int attempt) {
		FullJitterRetryPolicy policy = defaultPolicy();
		long window = 1000L << attempt;

		for (int i = 0; i < 2000; i++) {
			Optional<Duration> delay = policy.nextDelay(attempt);
			assertThat(delay).isPresent();
			assertThat(delay.get().toMillis()).isBetween(0L, window);
		}
	}

	@Test
	@DisplayName("stops at maxAttempts - the delivery becomes FAILED")
	void stopsAtMaxAttempts() {
		FullJitterRetryPolicy policy = defaultPolicy();

		assertThat(policy.nextDelay(7)).isPresent();
		assertThat(policy.nextDelay(8)).isEmpty();
		assertThat(policy.nextDelay(9)).isEmpty();
		assertThat(policy.nextDelay(100)).isEmpty();
	}

	@Test
	@DisplayName("rejects a non-positive attempt number instead of silently misbehaving")
	void rejectsAttemptBelowOne() {
		FullJitterRetryPolicy policy = defaultPolicy();

		assertThatThrownBy(() -> policy.nextDelay(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.nextDelay(-3))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("jitter actually spreads: 500 deliveries failing together get 500 different delays")
	void jitterProducesSpread() {
		FullJitterRetryPolicy policy = defaultPolicy();

		Set<Long> distinct = new HashSet<>();
		for (int i = 0; i < 500; i++) {
			distinct.add(policy.nextDelay(5).orElseThrow().toMillis());
		}

		// Deterministic backoff would collapse this to exactly 1 value and
		// synchronise every retry into a single spike on the receiver.
		assertThat(distinct).hasSizeGreaterThan(400);
	}

	@Test
	@DisplayName("distribution is uniform: the mean sits near the middle of the window")
	void distributionIsUniform() {
		FullJitterRetryPolicy policy = defaultPolicy();
		long window = 1000L << 4;   // attempt 4 -> 0..16000ms

		long total = 0;
		int samples = 20_000;
		for (int i = 0; i < samples; i++) {
			total += policy.nextDelay(4).orElseThrow().toMillis();
		}
		double mean = (double) total / samples;

		// Uniform over [0, window] has mean window/2. A skewed or broken
		// generator shows up here immediately.
		assertThat(mean).isCloseTo(window / 2.0, org.assertj.core.data.Offset.offset(window * 0.03));
	}

	@Test
	@DisplayName("the max-delay cap bounds the window once the exponential passes it")
	void capBoundsTheWindow() {
		// base 1s, cap 5s, plenty of attempts: by attempt 5 the exponential
		// (32s) is far past the cap and every delay must fit inside 5s.
		FullJitterRetryPolicy policy = policyWith(20, 1000, 5000);

		for (int i = 0; i < 2000; i++) {
			assertThat(policy.nextDelay(5).orElseThrow().toMillis()).isBetween(0L, 5000L);
			assertThat(policy.nextDelay(12).orElseThrow().toMillis()).isBetween(0L, 5000L);
		}
	}

	@Test
	@DisplayName("windows widen as attempts increase")
	void windowsWiden() {
		FullJitterRetryPolicy policy = defaultPolicy();

		long maxEarly = 0;
		long maxLate = 0;
		for (int i = 0; i < 3000; i++) {
			maxEarly = Math.max(maxEarly, policy.nextDelay(1).orElseThrow().toMillis());
			maxLate = Math.max(maxLate, policy.nextDelay(6).orElseThrow().toMillis());
		}

		assertThat(maxEarly).isLessThanOrEqualTo(2_000L);
		assertThat(maxLate).isGreaterThan(50_000L);
	}
}

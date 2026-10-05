package com.dileep.relay.ratelimit;

import com.dileep.relay.config.RelayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** No Spring context: the bucket is plain arithmetic and should be testable as such. */
class TokenBucketRateLimiterTest {

	private static TokenBucketRateLimiter limiter(double perSecond, int burst, boolean enabled) {
		RelayProperties properties = new RelayProperties();
		RelayProperties.RateLimit config = properties.getRateLimit();
		config.setEnabled(enabled);
		config.setPermitsPerSecond(perSecond);
		config.setBurst(burst);
		return new TokenBucketRateLimiter(properties);
	}

	@Test
	@DisplayName("a fresh bucket allows exactly its burst, then refuses")
	void burstThenRefuse() {
		TokenBucketRateLimiter limiter = limiter(1, 5, true);
		UUID endpoint = UUID.randomUUID();

		for (int i = 0; i < 5; i++) {
			assertThat(limiter.tryAcquire(endpoint)).as("permit %d", i).isTrue();
		}
		// Refill is 1/sec, so the sixth cannot be covered by elapsed time.
		assertThat(limiter.tryAcquire(endpoint)).isFalse();
	}

	@Test
	@DisplayName("permits come back as time passes")
	void refillsOverTime() throws Exception {
		TokenBucketRateLimiter limiter = limiter(100, 1, true);
		UUID endpoint = UUID.randomUUID();

		assertThat(limiter.tryAcquire(endpoint)).isTrue();
		assertThat(limiter.tryAcquire(endpoint)).isFalse();

		Thread.sleep(50);                       // 100/sec => ~5 permits accrued

		assertThat(limiter.tryAcquire(endpoint)).isTrue();
	}

	@Test
	@DisplayName("buckets are per endpoint - one tenant cannot spend another's permits")
	void bucketsAreIndependent() {
		TokenBucketRateLimiter limiter = limiter(1, 2, true);
		UUID noisy = UUID.randomUUID();
		UUID quiet = UUID.randomUUID();

		limiter.tryAcquire(noisy);
		limiter.tryAcquire(noisy);
		assertThat(limiter.tryAcquire(noisy)).isFalse();

		// The whole point of the feature: the quiet endpoint is unaffected.
		assertThat(limiter.tryAcquire(quiet)).isTrue();
	}

	@Test
	@DisplayName("disabled means always allow")
	void disabledAllowsEverything() {
		TokenBucketRateLimiter limiter = limiter(1, 1, false);
		UUID endpoint = UUID.randomUUID();

		for (int i = 0; i < 100; i++) {
			assertThat(limiter.tryAcquire(endpoint)).isTrue();
		}
	}

	@Test
	@DisplayName("concurrent callers never hand out more than the burst")
	void doesNotOverGrantUnderConcurrency() throws Exception {
		// A CAS-free bucket guarded by synchronized still has to be right when
		// 32 dispatcher threads hit the same endpoint at once.
		int burst = 10;
		TokenBucketRateLimiter limiter = limiter(0.0001, burst, true);   // refill ~nothing
		UUID endpoint = UUID.randomUUID();

		int threads = 32;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startTogether = new CountDownLatch(1);
		CountDownLatch allDone = new CountDownLatch(threads);
		AtomicInteger granted = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					startTogether.await();
					if (limiter.tryAcquire(endpoint)) {
						granted.incrementAndGet();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					allDone.countDown();
				}
			});
		}

		startTogether.countDown();
		assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(granted.get()).isEqualTo(burst);
	}
}

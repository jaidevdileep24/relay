package com.dileep.relay.repository;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.domain.Application;
import com.dileep.relay.domain.Endpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The circuit-breaker counter, and the concurrency bug it originally had.
 *
 * <p>The first implementation did read-modify-write in Java
 * ({@code endpoint.setConsecutiveFailures(endpoint.getConsecutiveFailures() + 1)}).
 * Under load that silently lost increments and threw
 * {@code StaleObjectStateException} from {@code @Version} - observed 12 times
 * in 90 seconds of real traffic. Doing the arithmetic in SQL removes the gap
 * between the read and the write.
 */
class EndpointRepositoryIT extends AbstractIntegrationTest {

	@Autowired EndpointRepository endpointRepository;
	@Autowired ApplicationRepository applicationRepository;
	@Autowired PlatformTransactionManager transactionManager;

	private Endpoint endpoint;

	@BeforeEach
	void cleanAndSeed() {
		endpointRepository.deleteAll();
		applicationRepository.deleteAll();

		Application application = applicationRepository.save(new Application("Acme"));
		endpoint = endpointRepository.save(
				new Endpoint(application, "https://a.example.com/hook", "whsec_test", null));
	}

	private int increment(TransactionTemplate tx) {
		Integer value = tx.execute(s -> endpointRepository.incrementAndGetConsecutiveFailures(endpoint.getId()));
		return value == null ? -1 : value;
	}

	private int reset(TransactionTemplate tx) {
		Integer value = tx.execute(s -> endpointRepository.resetConsecutiveFailures(endpoint.getId()));
		return value == null ? -1 : value;
	}

	@Test
	@DisplayName("increment returns the new value each time")
	void incrementReturnsNewValue() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		assertThat(increment(tx)).isEqualTo(1);
		assertThat(increment(tx)).isEqualTo(2);
		assertThat(increment(tx)).isEqualTo(3);
	}

	@Test
	@DisplayName("32 concurrent increments all land - none are lost")
	void concurrentIncrementsAreNotLost() throws Exception {
		int threads = 32;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startTogether = new CountDownLatch(1);
		CountDownLatch allDone = new CountDownLatch(threads);
		AtomicInteger failures = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					startTogether.await();
					new TransactionTemplate(transactionManager).executeWithoutResult(
							s -> endpointRepository.incrementAndGetConsecutiveFailures(endpoint.getId()));
				} catch (Exception e) {
					failures.incrementAndGet();
				} finally {
					allDone.countDown();
				}
			});
		}

		startTogether.countDown();                       // release them all at once
		assertThat(allDone.await(60, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(failures.get()).isZero();
		// Read-modify-write would land somewhere well short of 32 here.
		assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow()
				.getConsecutiveFailures()).isEqualTo(threads);
	}

	@Test
	@DisplayName("every returned value is distinct - each caller sees its own increment")
	void eachCallerGetsADistinctValue() throws Exception {
		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startTogether = new CountDownLatch(1);
		List<Integer> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		CountDownLatch allDone = new CountDownLatch(threads);

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					startTogether.await();
					Integer value = new TransactionTemplate(transactionManager).execute(
							s -> endpointRepository.incrementAndGetConsecutiveFailures(endpoint.getId()));
					seen.add(value);
				} catch (Exception ignored) {
					// counted by the size assertion below
				} finally {
					allDone.countDown();
				}
			});
		}

		startTogether.countDown();
		assertThat(allDone.await(60, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		// 16 callers, values 1..16, no duplicates - the signature of an atomic
		// increment. Read-modify-write would hand the same number to several.
		assertThat(seen).hasSize(threads).doesNotHaveDuplicates();
		assertThat(seen).containsExactlyInAnyOrderElementsOf(
				java.util.stream.IntStream.rangeClosed(1, threads).boxed().toList());
	}

	@Test
	@DisplayName("reset clears the counter, and is a no-op when already zero")
	void resetClearsTheCounter() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		tx.executeWithoutResult(s -> endpointRepository.incrementAndGetConsecutiveFailures(endpoint.getId()));
		tx.executeWithoutResult(s -> endpointRepository.incrementAndGetConsecutiveFailures(endpoint.getId()));

		int firstReset = reset(tx);
		assertThat(firstReset).isEqualTo(1);
		assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow().getConsecutiveFailures()).isZero();

		// Already zero: the guard makes it touch no rows rather than write again.
		int secondReset = reset(tx);
		assertThat(secondReset).isZero();
	}

	@Test
	@DisplayName("disableIfEnabled returns 1 exactly once, however many callers race")
	void disableTripsExactlyOnce() throws Exception {
		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startTogether = new CountDownLatch(1);
		CountDownLatch allDone = new CountDownLatch(threads);
		AtomicInteger winners = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					startTogether.await();
					Integer rows = new TransactionTemplate(transactionManager).execute(
							s -> endpointRepository.disableIfEnabled(endpoint.getId(), "breaker tripped"));
					if (rows != null && rows == 1) {
						winners.incrementAndGet();
					}
				} catch (Exception ignored) {
					// a loser, which is the expected outcome for 15 of them
				} finally {
					allDone.countDown();
				}
			});
		}

		startTogether.countDown();
		assertThat(allDone.await(60, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		// This is what stops 16 threads all logging a trip and all running the
		// bulk update: only the one whose UPDATE matched enabled=true wins.
		assertThat(winners.get()).isEqualTo(1);

		Endpoint reloaded = endpointRepository.findById(endpoint.getId()).orElseThrow();
		assertThat(reloaded.isEnabled()).isFalse();
		assertThat(reloaded.getDisabledReason()).isEqualTo("breaker tripped");
	}

	@Test
	@DisplayName("isEnabled reflects the current flag")
	void isEnabledReflectsCurrentState() {
		assertThat(endpointRepository.isEnabled(endpoint.getId())).isTrue();

		new TransactionTemplate(transactionManager).executeWithoutResult(
				s -> endpointRepository.disableIfEnabled(endpoint.getId(), "off"));

		assertThat(endpointRepository.isEnabled(endpoint.getId())).isFalse();
	}
}

package com.dileep.relay.repository;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.domain.Application;
import com.dileep.relay.domain.Delivery;
import com.dileep.relay.domain.DeliveryState;
import com.dileep.relay.domain.Endpoint;
import com.dileep.relay.domain.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatcher's claim query - the single most important query in the system.
 *
 * <p>The concurrency test here is the reason this project refuses H2: H2 has no
 * {@code SKIP LOCKED}, so it would happily let a broken claim query pass and
 * only fail once two workers were running in production.
 */
class DeliveryRepositoryIT extends AbstractIntegrationTest {

	@Autowired DeliveryRepository deliveryRepository;
	@Autowired MessageRepository messageRepository;
	@Autowired EndpointRepository endpointRepository;
	@Autowired ApplicationRepository applicationRepository;
	@Autowired PlatformTransactionManager transactionManager;

	private Application application;
	private Endpoint endpoint;

	@BeforeEach
	void cleanAndSeed() {
		deliveryRepository.deleteAll();
		messageRepository.deleteAll();
		endpointRepository.deleteAll();
		applicationRepository.deleteAll();

		application = applicationRepository.save(new Application("Acme"));
		endpoint = endpointRepository.save(
				new Endpoint(application, "https://a.example.com/hook", "whsec_test", null));
	}

	/** One delivery, due at the given time, in whatever state. */
	private Delivery seedDelivery(DeliveryState state, Instant dueAt) {
		Message message = messageRepository.save(
				new Message(application, "invoice.paid", "{\"n\":1}", null));
		Delivery delivery = new Delivery(message, endpoint);
		delivery.setState(state);
		delivery.setNextAttemptAt(dueAt);
		return deliveryRepository.save(delivery);
	}

	@Test
	@DisplayName("claims only rows that are PENDING and due")
	void claimsOnlyDuePendingRows() {
		Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
		Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

		Delivery due = seedDelivery(DeliveryState.PENDING, past);
		seedDelivery(DeliveryState.PENDING, future);        // not due yet
		seedDelivery(DeliveryState.SUCCEEDED, past);        // terminal
		seedDelivery(DeliveryState.FAILED, past);           // terminal
		seedDelivery(DeliveryState.DISABLED, past);         // terminal

		List<Long> claimed = newTx().execute(s -> deliveryRepository.claimDueDeliveries(100));

		assertThat(claimed).containsExactly(due.getId());
	}

	@Test
	@DisplayName("respects the batch size")
	void respectsBatchSize() {
		Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
		for (int i = 0; i < 10; i++) {
			seedDelivery(DeliveryState.PENDING, past);
		}

		List<Long> claimed = newTx().execute(s -> deliveryRepository.claimDueDeliveries(4));

		assertThat(claimed).hasSize(4);
	}

	@Test
	@DisplayName("returns the oldest-due rows first")
	void ordersByNextAttemptAt() {
		Instant now = Instant.now();
		Delivery third = seedDelivery(DeliveryState.PENDING, now.minus(1, ChronoUnit.MINUTES));
		Delivery first = seedDelivery(DeliveryState.PENDING, now.minus(3, ChronoUnit.MINUTES));
		Delivery second = seedDelivery(DeliveryState.PENDING, now.minus(2, ChronoUnit.MINUTES));

		List<Long> claimed = newTx().execute(s -> deliveryRepository.claimDueDeliveries(10));

		assertThat(claimed).containsExactly(first.getId(), second.getId(), third.getId());
	}

	@Test
	@DisplayName("SKIP LOCKED: two concurrent workers never claim the same row")
	void twoWorkersClaimDisjointSets() throws Exception {
		Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
		for (int i = 0; i < 10; i++) {
			seedDelivery(DeliveryState.PENDING, past);
		}

		CountDownLatch workerOneHasClaimed = new CountDownLatch(1);
		CountDownLatch workerTwoIsDone = new CountDownLatch(1);
		AtomicReference<List<Long>> idsOne = new AtomicReference<>(List.of());
		AtomicReference<List<Long>> idsTwo = new AtomicReference<>(List.of());

		// Worker one claims 5 and HOLDS its transaction open, so its row locks
		// are still held while worker two runs.
		Thread one = new Thread(() -> newTx().executeWithoutResult(status -> {
			idsOne.set(deliveryRepository.claimDueDeliveries(5));
			workerOneHasClaimed.countDown();
			await(workerTwoIsDone);
		}));

		Thread two = new Thread(() -> {
			await(workerOneHasClaimed);
			newTx().executeWithoutResult(status -> idsTwo.set(deliveryRepository.claimDueDeliveries(5)));
			workerTwoIsDone.countDown();
		});

		one.start();
		two.start();
		one.join(30_000);
		two.join(30_000);

		// Without SKIP LOCKED, worker two would block until worker one's
		// transaction committed - no parallelism at all. With it, worker two
		// steps straight over the locked rows and takes the next five.
		assertThat(idsOne.get()).hasSize(5);
		assertThat(idsTwo.get()).hasSize(5);
		assertThat(idsOne.get()).doesNotContainAnyElementsOf(idsTwo.get());

		Set<Long> union = new HashSet<>(idsOne.get());
		union.addAll(idsTwo.get());
		assertThat(union).hasSize(10);
	}

	@Test
	@DisplayName("countByMessageId backs the idempotency replay")
	void countsDeliveriesForAMessage() {
		Message message = messageRepository.save(
				new Message(application, "invoice.paid", "{}", "key-1"));
		Endpoint other = endpointRepository.save(
				new Endpoint(application, "https://b.example.com/hook", "whsec_test_2", null));

		deliveryRepository.save(new Delivery(message, endpoint));
		deliveryRepository.save(new Delivery(message, other));

		assertThat(deliveryRepository.countByMessageId(message.getId())).isEqualTo(2);
		assertThat(deliveryRepository.countByMessageId(UUID.randomUUID())).isZero();
	}

	private TransactionTemplate newTx() {
		return new TransactionTemplate(transactionManager);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for the other worker");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}

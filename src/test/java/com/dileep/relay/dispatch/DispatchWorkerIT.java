package com.dileep.relay.dispatch;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.domain.Application;
import com.dileep.relay.domain.Delivery;
import com.dileep.relay.domain.DeliveryAttempt;
import com.dileep.relay.domain.DeliveryState;
import com.dileep.relay.domain.Endpoint;
import com.dileep.relay.domain.Message;
import com.dileep.relay.repository.ApplicationRepository;
import com.dileep.relay.repository.DeliveryAttemptRepository;
import com.dileep.relay.repository.DeliveryRepository;
import com.dileep.relay.repository.EndpointRepository;
import com.dileep.relay.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatcher's state machine and circuit breaker, driven by a stub sender
 * so no HTTP leaves the machine and the outcome of every attempt is chosen by
 * the test.
 *
 * <p>{@code max-attempts: 2} keeps each delivery two attempts from terminal,
 * and {@code failure-threshold: 3} trips the breaker after three of them.
 */
@Import(DispatchWorkerIT.StubSenderConfig.class)
@TestPropertySource(properties = {
		"relay.retry.max-attempts=2",
		"relay.retry.base-delay-ms=1",
		"relay.breaker.failure-threshold=3",
		"relay.rate-limit.enabled=false"
})
class DispatchWorkerIT extends AbstractIntegrationTest {

	/** Lets a test decide what the "network" returns, with no sockets involved. */
	static class StubSender implements HttpSender {
		final AtomicReference<SendResult> next =
				new AtomicReference<>(new SendResult(500, "boom", null, 5));

		@Override
		public SendResult send(DispatchTask task) {
			return next.get();
		}
	}

	@TestConfiguration
	static class StubSenderConfig {
		@Bean
		@Primary
		StubSender stubSender() {
			return new StubSender();
		}
	}

	@Autowired DispatchWorker worker;
	@Autowired ApplicationContext context;
	@Autowired StubSender stubSender;
	@Autowired DeliveryRepository deliveryRepository;
	@Autowired DeliveryAttemptRepository attemptRepository;
	@Autowired MessageRepository messageRepository;
	@Autowired EndpointRepository endpointRepository;
	@Autowired ApplicationRepository applicationRepository;

	private Application application;
	private Endpoint endpoint;

	@BeforeEach
	void cleanAndSeed() {
		attemptRepository.deleteAll();
		deliveryRepository.deleteAll();
		messageRepository.deleteAll();
		endpointRepository.deleteAll();
		applicationRepository.deleteAll();

		application = applicationRepository.save(new Application("Acme"));
		endpoint = endpointRepository.save(
				new Endpoint(application, "https://a.example.com/hook", "whsec_test", null));
		stubSender.next.set(new SendResult(500, "boom", null, 5));
	}

	private Delivery seedDelivery() {
		Message message = messageRepository.save(
				new Message(application, "invoice.paid", "{\"n\":1}", null));
		return deliveryRepository.save(new Delivery(message, endpoint));
	}

	/** Drives one delivery all the way to a terminal state. */
	private void runUntilTerminal(int maxPolls) {
		for (int i = 0; i < maxPolls; i++) {
			worker.pollAndDispatch();
		}
	}

	@Test
	@DisplayName("the worker bean exists even with the scheduler switched off")
	void workerIsAvailableWithoutTheScheduler() {
		// relay.dispatch.enabled=false must silence the 1-second poll without
		// deleting the bean under test - the two used to be the same flag, and
		// every test in this class failed to wire.
		assertThat(worker).isNotNull();
		assertThat(context.getBeanNamesForType(DispatchScheduler.class)).isEmpty();
	}

	@Test
	@DisplayName("a 2xx marks the delivery SUCCEEDED after one attempt")
	void successOnFirstAttempt() {
		Delivery delivery = seedDelivery();
		stubSender.next.set(new SendResult(200, "ok", null, 12));

		worker.pollAndDispatch();

		Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
		assertThat(reloaded.getState()).isEqualTo(DeliveryState.SUCCEEDED);
		assertThat(reloaded.getAttemptCount()).isEqualTo(1);
		assertThat(reloaded.getLastError()).isNull();
		assertThat(attemptRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("every attempt is recorded, success or failure")
	void everyAttemptIsAudited() {
		seedDelivery();

		runUntilTerminal(4);

		List<DeliveryAttempt> attempts = attemptRepository.findAll();
		assertThat(attempts).hasSize(2);                       // max-attempts = 2
		assertThat(attempts).extracting(DeliveryAttempt::getAttemptNumber)
				.containsExactlyInAnyOrder(1, 2);
		assertThat(attempts).allSatisfy(a -> assertThat(a.getHttpStatus()).isEqualTo(500));
	}

	@Test
	@DisplayName("3xx counts as a failure - only 2xx is success")
	void redirectIsAFailure() {
		Delivery delivery = seedDelivery();
		stubSender.next.set(new SendResult(302, "moved", null, 5));

		worker.pollAndDispatch();

		assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getState())
				.isEqualTo(DeliveryState.PENDING);             // rescheduled, not succeeded
	}

	@Test
	@DisplayName("a transport error with no status is recorded and retried")
	void transportErrorIsRecorded() {
		Delivery delivery = seedDelivery();
		stubSender.next.set(new SendResult(null, null, "HttpConnectTimeoutException: timed out", 10_000));

		worker.pollAndDispatch();

		DeliveryAttempt attempt = attemptRepository.findAll().get(0);
		assertThat(attempt.getHttpStatus()).isNull();
		assertThat(attempt.getErrorMessage()).contains("timed out");
		assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getLastError())
				.contains("timed out");
	}

	@Test
	@DisplayName("running out of attempts marks the delivery FAILED")
	void exhaustingAttemptsIsTerminal() {
		Delivery delivery = seedDelivery();

		runUntilTerminal(4);

		Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
		assertThat(reloaded.getState()).isEqualTo(DeliveryState.FAILED);
		assertThat(reloaded.getAttemptCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("the breaker trips after 3 terminally-failed deliveries and disables the endpoint")
	void breakerTripsAtThreshold() {
		for (int i = 0; i < 3; i++) {
			seedDelivery();
			runUntilTerminal(4);
		}

		Endpoint reloaded = endpointRepository.findById(endpoint.getId()).orElseThrow();
		assertThat(reloaded.isEnabled()).isFalse();
		assertThat(reloaded.getConsecutiveFailures()).isGreaterThanOrEqualTo(3);
		assertThat(reloaded.getDisabledReason()).contains("consecutive failed deliveries");
	}

	@Test
	@DisplayName("the breaker does not trip below the threshold")
	void breakerHoldsBelowThreshold() {
		for (int i = 0; i < 2; i++) {
			seedDelivery();
			runUntilTerminal(4);
		}

		Endpoint reloaded = endpointRepository.findById(endpoint.getId()).orElseThrow();
		assertThat(reloaded.isEnabled()).isTrue();
		assertThat(reloaded.getConsecutiveFailures()).isEqualTo(2);
	}

	@Test
	@DisplayName("one success clears the counter - the breaker is consecutive, not cumulative")
	void successResetsTheCounter() {
		seedDelivery();
		runUntilTerminal(4);
		seedDelivery();
		runUntilTerminal(4);
		assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow()
				.getConsecutiveFailures()).isEqualTo(2);

		stubSender.next.set(new SendResult(200, "ok", null, 8));
		seedDelivery();
		worker.pollAndDispatch();

		Endpoint reloaded = endpointRepository.findById(endpoint.getId()).orElseThrow();
		assertThat(reloaded.getConsecutiveFailures()).isZero();
		assertThat(reloaded.isEnabled()).isTrue();
	}

	@Test
	@DisplayName("when the breaker trips, queued deliveries are retired rather than retried")
	void trippingRetiresQueuedDeliveries() {
		// Three deliveries carry the endpoint to the threshold; a fourth is
		// still queued when it trips.
		for (int i = 0; i < 3; i++) {
			seedDelivery();
			runUntilTerminal(4);
		}
		Delivery queued = seedDelivery();

		runUntilTerminal(4);

		assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow().isEnabled()).isFalse();

		// It must not be left PENDING - that would keep hammering an endpoint
		// the breaker has already cut off.
		DeliveryState state = deliveryRepository.findById(queued.getId()).orElseThrow().getState();
		assertThat(state).isIn(DeliveryState.DISABLED, DeliveryState.FAILED);
	}

	@Test
	@DisplayName("a disabled endpoint's deliveries stop being claimed")
	void disabledEndpointStopsBeingClaimed() {
		for (int i = 0; i < 3; i++) {
			seedDelivery();
			runUntilTerminal(4);
		}
		assertThat(endpointRepository.findById(endpoint.getId()).orElseThrow().isEnabled()).isFalse();

		long attemptsBefore = attemptRepository.count();
		worker.pollAndDispatch();
		worker.pollAndDispatch();

		assertThat(attemptRepository.count()).isEqualTo(attemptsBefore);
	}
}

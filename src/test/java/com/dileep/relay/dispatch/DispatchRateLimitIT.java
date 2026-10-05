package com.dileep.relay.dispatch;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.domain.Application;
import com.dileep.relay.domain.Delivery;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limiting is back-pressure, not failure.
 *
 * <p>The bucket is configured so nothing can ever acquire a permit, which makes
 * the deferral path the only path.
 */
@Import(DispatchRateLimitIT.CountingSenderConfig.class)
@TestPropertySource(properties = {
		"relay.rate-limit.enabled=true",
		"relay.rate-limit.burst=0",
		"relay.rate-limit.permits-per-second=0.00001",
		"relay.rate-limit.defer-ms=1000",
		"relay.retry.max-attempts=8"
})
class DispatchRateLimitIT extends AbstractIntegrationTest {

	/** Counts sends so the test can assert none happened. */
	static class CountingSender implements HttpSender {
		final AtomicInteger sends = new AtomicInteger();

		@Override
		public SendResult send(DispatchTask task) {
			sends.incrementAndGet();
			return new SendResult(200, "ok", null, 5);
		}
	}

	@TestConfiguration
	static class CountingSenderConfig {
		@Bean
		@Primary
		CountingSender countingSender() {
			return new CountingSender();
		}
	}

	@Autowired DispatchWorker worker;
	@Autowired CountingSender sender;
	@Autowired DeliveryRepository deliveryRepository;
	@Autowired DeliveryAttemptRepository attemptRepository;
	@Autowired MessageRepository messageRepository;
	@Autowired EndpointRepository endpointRepository;
	@Autowired ApplicationRepository applicationRepository;

	private Delivery delivery;

	@BeforeEach
	void cleanAndSeed() {
		attemptRepository.deleteAll();
		deliveryRepository.deleteAll();
		messageRepository.deleteAll();
		endpointRepository.deleteAll();
		applicationRepository.deleteAll();
		sender.sends.set(0);

		Application application = applicationRepository.save(new Application("Acme"));
		Endpoint endpoint = endpointRepository.save(
				new Endpoint(application, "https://a.example.com/hook", "whsec_test", null));
		Message message = messageRepository.save(
				new Message(application, "invoice.paid", "{\"n\":1}", null));
		delivery = deliveryRepository.save(new Delivery(message, endpoint));
	}

	@Test
	@DisplayName("a rate-limited delivery is never sent")
	void noSendHappens() {
		worker.pollAndDispatch();

		assertThat(sender.sends.get()).isZero();
	}

	@Test
	@DisplayName("a deferral costs no attempt, no retry budget, no audit row")
	void deferralIsNotAnAttempt() {
		worker.pollAndDispatch();

		Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();

		// If back-pressure counted as a failure, a busy endpoint would burn its
		// retries and trip its own breaker without a single request leaving here.
		assertThat(reloaded.getState()).isEqualTo(DeliveryState.PENDING);
		assertThat(reloaded.getAttemptCount()).isZero();
		assertThat(reloaded.getLastError()).isNull();
		assertThat(attemptRepository.count()).isZero();
	}

	@Test
	@DisplayName("a deferred delivery is pushed into the future, not left due")
	void deferralReschedules() {
		worker.pollAndDispatch();

		Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();

		// Left due, the next poll would claim it again immediately and spin.
		assertThat(reloaded.getNextAttemptAt()).isAfter(Instant.now());
	}
}

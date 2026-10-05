package com.dileep.relay.dispatch;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.ai.FailureCategory;
import com.dileep.relay.ai.FailureClassification;
import com.dileep.relay.ai.FailureClassifier;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the classification actually changes in the dispatcher.
 *
 * <p>{@code max-attempts: 8} leaves plenty of budget, so anything that goes
 * terminal here did so because of the classification rather than exhaustion.
 */
@Import(DispatchClassificationIT.StubConfig.class)
@TestPropertySource(properties = {
		"relay.retry.max-attempts=8",
		"relay.retry.base-delay-ms=1000",
		"relay.rate-limit.enabled=false",
		"relay.breaker.failure-threshold=99",
		// the stub below replaces FailureClassifierConfig's bean by name
		"spring.main.allow-bean-definition-overriding=true"
})
class DispatchClassificationIT extends AbstractIntegrationTest {

	static class StubSender implements HttpSender {
		final AtomicReference<SendResult> next =
				new AtomicReference<>(new SendResult(500, "boom", null, 5));

		@Override
		public SendResult send(DispatchTask task) {
			return next.get();
		}
	}

	static class StubClassifier implements FailureClassifier {
		final AtomicReference<FailureClassification> next =
				new AtomicReference<>(FailureClassification.of(FailureCategory.SERVER_ERROR, "stub"));

		@Override
		public FailureClassification classify(com.dileep.relay.ai.FailureContext context) {
			return next.get();
		}
	}

	@TestConfiguration
	static class StubConfig {
		@Bean @Primary StubSender stubSender() { return new StubSender(); }

		/**
		 * Named {@code failureClassifier} deliberately: that replaces
		 * {@link com.dileep.relay.ai.FailureClassifierConfig}'s bean definition
		 * outright, rather than adding a second candidate of the same type.
		 */
		@Bean StubClassifier failureClassifier() { return new StubClassifier(); }
	}

	@Autowired DispatchWorker worker;
	@Autowired StubSender sender;
	@Autowired StubClassifier classifier;
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

		sender.next.set(new SendResult(500, "boom", null, 5));
		classifier.next.set(FailureClassification.of(FailureCategory.SERVER_ERROR, "stub"));

		Application application = applicationRepository.save(new Application("Acme"));
		Endpoint endpoint = endpointRepository.save(
				new Endpoint(application, "https://a.example.com/hook", "whsec_test", null));
		Message message = messageRepository.save(
				new Message(application, "invoice.paid", "{\"n\":1}", null));
		delivery = deliveryRepository.save(new Delivery(message, endpoint));
	}

	private Delivery reload() {
		return deliveryRepository.findById(delivery.getId()).orElseThrow();
	}

	@Test
	@DisplayName("a permanent failure goes terminal on the first attempt, with 7 retries unspent")
	void nonRetryableStopsImmediately() {
		classifier.next.set(FailureClassification.of(FailureCategory.AUTH, "invalid signature"));

		worker.pollAndDispatch();

		// This is the entire value of the phase: a rejected signature retried
		// eight times over an hour fails eight times.
		assertThat(reload().getState()).isEqualTo(DeliveryState.FAILED);
		assertThat(reload().getAttemptCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("a retryable failure still uses the normal backoff")
	void retryableReschedules() {
		classifier.next.set(FailureClassification.of(FailureCategory.SERVER_ERROR, "upstream down"));

		worker.pollAndDispatch();

		assertThat(reload().getState()).isEqualTo(DeliveryState.PENDING);
	}

	@Test
	@DisplayName("a receiver's Retry-After overrides our own backoff")
	void retryAfterIsHonoured() {
		classifier.next.set(FailureClassification.rateLimited(Duration.ofMinutes(30), "quota exceeded"));

		worker.pollAndDispatch();

		// base-delay is 1s, so anything past a few minutes can only have come
		// from the receiver's own instruction.
		assertThat(reload().getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMinutes(20)));
	}

	@Test
	@DisplayName("Retry-After cannot outlive the retry budget")
	void retryAfterCannotResurrectAnExhaustedDelivery() {
		// Spend the budget: max-attempts is 8, so attempt 8 is the last one.
		delivery.setAttemptCount(7);
		deliveryRepository.save(delivery);
		classifier.next.set(FailureClassification.rateLimited(Duration.ofMinutes(5), "quota"));

		worker.pollAndDispatch();

		// A receiver must not be able to keep a delivery alive indefinitely by
		// asking us to come back later.
		assertThat(reload().getState()).isEqualTo(DeliveryState.FAILED);
	}

	@Test
	@DisplayName("the classification is written to the audit trail")
	void classificationIsAudited() {
		classifier.next.set(FailureClassification.of(FailureCategory.CLIENT_ERROR, "unknown event type"));

		worker.pollAndDispatch();

		DeliveryAttempt attempt = attemptRepository.findAll().get(0);
		assertThat(attempt.getFailureCategory()).isEqualTo(FailureCategory.CLIENT_ERROR);
		assertThat(attempt.getFailureReasoning()).isEqualTo("unknown event type");
	}

	@Test
	@DisplayName("a success records no classification at all")
	void successIsNotClassified() {
		sender.next.set(new SendResult(200, "ok", null, 5));

		worker.pollAndDispatch();

		DeliveryAttempt attempt = attemptRepository.findAll().get(0);
		assertThat(attempt.getFailureCategory()).isNull();
		assertThat(reload().getState()).isEqualTo(DeliveryState.SUCCEEDED);
	}

	@Test
	@DisplayName("stopping early is credited as retries saved, and the verdict sits on the delivery")
	void earlyStopIsCreditedAsRetriesSaved() {
		classifier.next.set(FailureClassification.of(FailureCategory.AUTH, "invalid signature"));

		worker.pollAndDispatch();

		// Attempt 1 of 8 failed permanently: attempts 2..8 were never made.
		assertThat(reload().getRetriesSaved()).isEqualTo(7);
		assertThat(reload().getLastFailureCategory()).isEqualTo(FailureCategory.AUTH);
		assertThat(reload().getLastFailureReasoning()).isEqualTo("invalid signature");
	}

	@Test
	@DisplayName("a retryable failure saves nothing; running out of budget saves nothing")
	void onlyAPermanentVerdictSavesRetries() {
		classifier.next.set(FailureClassification.of(FailureCategory.SERVER_ERROR, "upstream down"));
		worker.pollAndDispatch();
		assertThat(reload().getRetriesSaved()).isZero();
		assertThat(reload().getLastFailureCategory()).isEqualTo(FailureCategory.SERVER_ERROR);

		// Last attempt, permanent verdict: no budget was left to save.
		Delivery last = reload();
		last.setAttemptCount(7);
		last.setNextAttemptAt(Instant.now().minusSeconds(1));
		deliveryRepository.save(last);
		classifier.next.set(FailureClassification.of(FailureCategory.AUTH, "invalid signature"));
		worker.pollAndDispatch();
		assertThat(reload().getRetriesSaved()).isZero();
	}

	@Test
	@DisplayName("a success clears the previous diagnosis")
	void successClearsDiagnosis() {
		classifier.next.set(FailureClassification.of(FailureCategory.SERVER_ERROR, "upstream down"));
		worker.pollAndDispatch();

		Delivery retry = reload();
		retry.setNextAttemptAt(Instant.now().minusSeconds(1));
		deliveryRepository.save(retry);
		sender.next.set(new SendResult(200, "ok", null, 5));
		worker.pollAndDispatch();

		assertThat(reload().getState()).isEqualTo(DeliveryState.SUCCEEDED);
		assertThat(reload().getLastFailureCategory()).isNull();
	}
}

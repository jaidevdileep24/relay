package com.dileep.relay.service;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.api.dto.CreateMessageRequest;
import com.dileep.relay.api.dto.MessageResponse;
import com.dileep.relay.api.error.NotFoundException;
import com.dileep.relay.domain.Application;
import com.dileep.relay.domain.Delivery;
import com.dileep.relay.domain.DeliveryState;
import com.dileep.relay.domain.Endpoint;
import com.dileep.relay.repository.ApplicationRepository;
import com.dileep.relay.repository.DeliveryRepository;
import com.dileep.relay.repository.EndpointRepository;
import com.dileep.relay.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox write, against real Postgres.
 */
class IngestServiceIT extends AbstractIntegrationTest {

	@Autowired IngestService ingestService;
	@Autowired ApplicationRepository applicationRepository;
	@Autowired EndpointRepository endpointRepository;
	@Autowired MessageRepository messageRepository;
	@Autowired DeliveryRepository deliveryRepository;
	@Autowired ObjectMapper objectMapper;

	private Application application;

	@BeforeEach
	void cleanAndSeed() {
		deliveryRepository.deleteAll();
		messageRepository.deleteAll();
		endpointRepository.deleteAll();
		applicationRepository.deleteAll();

		application = applicationRepository.save(new Application("Acme"));
	}

	private Endpoint endpoint(String url, String... eventTypes) {
		Endpoint e = new Endpoint(application, url, "whsec_test_" + UUID.randomUUID(), null);
		if (eventTypes.length > 0) {
			e.setEventTypes(Set.of(eventTypes));
		}
		return endpointRepository.save(e);
	}

	private CreateMessageRequest request(String eventType, String json) throws Exception {
		return new CreateMessageRequest(eventType, objectMapper.readTree(json));
	}

	@Test
	@DisplayName("one transaction writes the message and every delivery row")
	void writesMessageAndDeliveriesTogether() throws Exception {
		endpoint("https://a.example.com/hook");
		endpoint("https://b.example.com/hook");

		MessageResponse response = ingestService.ingest(
				application.getId(), request("invoice.paid", "{\"amount\":4200}"), null);

		assertThat(response.deliveriesCreated()).isEqualTo(2);
		assertThat(messageRepository.count()).isEqualTo(1);
		assertThat(deliveryRepository.count()).isEqualTo(2);

		// Every delivery starts claimable: PENDING, zero attempts, due now.
		List<Delivery> deliveries = deliveryRepository.findAll();
		assertThat(deliveries).allSatisfy(d -> {
			assertThat(d.getState()).isEqualTo(DeliveryState.PENDING);
			assertThat(d.getAttemptCount()).isZero();
			assertThat(d.getNextAttemptAt()).isNotNull();
		});
	}

	@Test
	@DisplayName("fan-out respects each endpoint's event-type subscription")
	void fansOutOnlyToSubscribedEndpoints() throws Exception {
		endpoint("https://a.example.com/hook", "invoice.paid");
		endpoint("https://b.example.com/hook", "invoice.paid", "user.created");
		endpoint("https://c.example.com/hook");   // empty set = all events

		assertThat(ingestService.ingest(application.getId(),
				request("invoice.paid", "{}"), null).deliveriesCreated()).isEqualTo(3);

		assertThat(ingestService.ingest(application.getId(),
				request("user.created", "{}"), null).deliveriesCreated()).isEqualTo(2);

		assertThat(ingestService.ingest(application.getId(),
				request("order.shipped", "{}"), null).deliveriesCreated()).isEqualTo(1);
	}

	@Test
	@DisplayName("a disabled endpoint receives nothing")
	void skipsDisabledEndpoints() throws Exception {
		endpoint("https://live.example.com/hook");
		Endpoint dead = endpoint("https://dead.example.com/hook");
		dead.setEnabled(false);
		endpointRepository.save(dead);

		MessageResponse response = ingestService.ingest(
				application.getId(), request("invoice.paid", "{}"), null);

		assertThat(response.deliveriesCreated()).isEqualTo(1);
	}

	@Test
	@DisplayName("repeating an idempotency key returns the original and creates nothing")
	void idempotencyKeyReplaysTheOriginal() throws Exception {
		endpoint("https://a.example.com/hook");
		endpoint("https://b.example.com/hook");

		MessageResponse first = ingestService.ingest(
				application.getId(), request("invoice.paid", "{\"amount\":10}"), "order-777");

		MessageResponse replay = ingestService.ingest(
				application.getId(), request("invoice.paid", "{\"amount\":10}"), "order-777");

		assertThat(replay.id()).isEqualTo(first.id());
		assertThat(replay.deliveriesCreated()).isEqualTo(2);   // reports what exists, not zero
		assertThat(replay.payload()).isEqualTo(first.payload());

		// The point of the whole exercise: no duplicate webhook was queued.
		assertThat(messageRepository.count()).isEqualTo(1);
		assertThat(deliveryRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("different idempotency keys are different messages")
	void differentKeysCreateDifferentMessages() throws Exception {
		endpoint("https://a.example.com/hook");

		MessageResponse first = ingestService.ingest(
				application.getId(), request("invoice.paid", "{}"), "key-1");
		MessageResponse second = ingestService.ingest(
				application.getId(), request("invoice.paid", "{}"), "key-2");

		assertThat(second.id()).isNotEqualTo(first.id());
		assertThat(messageRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("no idempotency key means every call is a new message")
	void nullKeyAlwaysCreates() throws Exception {
		endpoint("https://a.example.com/hook");

		ingestService.ingest(application.getId(), request("invoice.paid", "{}"), null);
		ingestService.ingest(application.getId(), request("invoice.paid", "{}"), null);

		assertThat(messageRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("an unknown application is a 404, and writes nothing")
	void unknownApplicationThrowsNotFound() throws Exception {
		CreateMessageRequest req = request("invoice.paid", "{}");
		UUID missing = UUID.randomUUID();

		assertThatThrownBy(() -> ingestService.ingest(missing, req, null))
				.isInstanceOf(NotFoundException.class);

		assertThat(messageRepository.count()).isZero();
	}

	@Test
	@DisplayName("an application with no endpoints still records the message")
	void noEndpointsStillStoresTheMessage() throws Exception {
		MessageResponse response = ingestService.ingest(
				application.getId(), request("invoice.paid", "{}"), null);

		assertThat(response.deliveriesCreated()).isZero();
		assertThat(messageRepository.count()).isEqualTo(1);
		assertThat(deliveryRepository.count()).isZero();
	}

	@Test
	@DisplayName("16 concurrent requests with the same key create exactly one message")
	void concurrentSameKeyCreatesOneMessage() throws Exception {
		endpoint("https://a.example.com/hook");
		endpoint("https://b.example.com/hook");

		int threads = 16;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		java.util.concurrent.CountDownLatch startTogether = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch allDone = new java.util.concurrent.CountDownLatch(threads);
		List<UUID> returnedIds = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();

		CreateMessageRequest req = request("invoice.paid", "{\"amount\":99}");

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					startTogether.await();
					returnedIds.add(ingestService.ingest(application.getId(), req, "same-key").id());
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					allDone.countDown();
				}
			});
		}

		startTogether.countDown();
		assertThat(allDone.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		// Losers of the race hit the unique index, then re-read the winner in a
		// fresh transaction - so every caller gets the same answer, and none
		// gets an error.
		assertThat(errors.get()).isZero();
		assertThat(returnedIds).hasSize(threads);
		assertThat(new java.util.HashSet<>(returnedIds)).hasSize(1);

		assertThat(messageRepository.count()).isEqualTo(1);
		assertThat(deliveryRepository.count()).isEqualTo(2);   // not 32
	}

	@Test
	@DisplayName("the JSON payload survives a round trip through JSONB")
	void payloadRoundTripsThroughJsonb() throws Exception {
		endpoint("https://a.example.com/hook");
		String json = "{\"amount\":4200,\"nested\":{\"currency\":\"INR\"},\"tags\":[\"a\",\"b\"]}";

		MessageResponse response = ingestService.ingest(
				application.getId(), request("invoice.paid", json), null);

		assertThat(response.payload()).isEqualTo(objectMapper.readTree(json));
		assertThat(messageRepository.findAll().get(0).getPayload())
				.contains("4200")
				.contains("INR");
	}
}

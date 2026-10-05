package com.dileep.relay.service;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.ai.FailureCategory;
import com.dileep.relay.api.dto.DeliveryAttemptResponse;
import com.dileep.relay.api.dto.DeliveryResponse;
import com.dileep.relay.api.dto.PageResponse;
import com.dileep.relay.api.error.ConflictException;
import com.dileep.relay.api.error.NotFoundException;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dead-letter queue read path and replay.
 *
 * <p>Two applications are seeded throughout, because most of what matters here
 * is that one cannot see or touch the other's rows.
 */
class DeliveryServiceIT extends AbstractIntegrationTest {

	@Autowired DeliveryService deliveryService;
	@Autowired EndpointService endpointService;
	@Autowired DeliveryRepository deliveryRepository;
	@Autowired DeliveryAttemptRepository attemptRepository;
	@Autowired MessageRepository messageRepository;
	@Autowired EndpointRepository endpointRepository;
	@Autowired ApplicationRepository applicationRepository;

	private Application ours;
	private Application theirs;
	private Endpoint ourEndpoint;

	private static final Pageable FIRST_PAGE = PageRequest.of(0, 20, Sort.by("createdAt").descending());

	@BeforeEach
	void cleanAndSeed() {
		attemptRepository.deleteAll();
		deliveryRepository.deleteAll();
		messageRepository.deleteAll();
		endpointRepository.deleteAll();
		applicationRepository.deleteAll();

		ours = applicationRepository.save(new Application("Ours"));
		theirs = applicationRepository.save(new Application("Theirs"));
		ourEndpoint = endpointRepository.save(
				new Endpoint(ours, "https://a.example.com/hook", "whsec_test", null));
	}

	private Delivery seed(Application application, Endpoint endpoint, DeliveryState state) {
		Message message = messageRepository.save(
				new Message(application, "invoice.paid", "{\"n\":1}", null));
		Delivery delivery = new Delivery(message, endpoint);
		delivery.setState(state);
		return deliveryRepository.save(delivery);
	}

	// ---------------------------------------------------------------- listing

	@Test
	@DisplayName("lists only the calling application's deliveries")
	void listIsScopedToTheApplication() {
		seed(ours, ourEndpoint, DeliveryState.FAILED);
		Endpoint theirEndpoint = endpointRepository.save(
				new Endpoint(theirs, "https://b.example.com/hook", "whsec_other", null));
		seed(theirs, theirEndpoint, DeliveryState.FAILED);

		PageResponse<DeliveryResponse> page =
				deliveryService.listByApplication(ours.getId(), null, FIRST_PAGE);

		assertThat(page.totalElements()).isEqualTo(1);
	}

	@Test
	@DisplayName("a null state returns every state; a set state filters")
	void stateFilterIsOptional() {
		seed(ours, ourEndpoint, DeliveryState.FAILED);
		seed(ours, ourEndpoint, DeliveryState.SUCCEEDED);
		seed(ours, ourEndpoint, DeliveryState.PENDING);

		assertThat(deliveryService.listByApplication(ours.getId(), null, FIRST_PAGE)
				.totalElements()).isEqualTo(3);

		PageResponse<DeliveryResponse> failed =
				deliveryService.listByApplication(ours.getId(), DeliveryState.FAILED, FIRST_PAGE);

		assertThat(failed.totalElements()).isEqualTo(1);
		assertThat(failed.content()).singleElement()
				.extracting(DeliveryResponse::state).isEqualTo(DeliveryState.FAILED);
	}

	@Test
	@DisplayName("paging metadata survives the trip through PageResponse")
	void pagingMetadataIsCarried() {
		for (int i = 0; i < 5; i++) {
			seed(ours, ourEndpoint, DeliveryState.FAILED);
		}

		PageResponse<DeliveryResponse> page = deliveryService.listByApplication(
				ours.getId(), null, PageRequest.of(1, 2, Sort.by("createdAt").descending()));

		assertThat(page.content()).hasSize(2);
		assertThat(page.page()).isEqualTo(1);
		assertThat(page.size()).isEqualTo(2);
		assertThat(page.totalElements()).isEqualTo(5);
		assertThat(page.totalPages()).isEqualTo(3);
	}

	// --------------------------------------------------------------- attempts

	@Test
	@DisplayName("attempts come back newest first")
	void attemptsAreNewestFirst() {
		Delivery delivery = seed(ours, ourEndpoint, DeliveryState.FAILED);
		attemptRepository.save(new DeliveryAttempt(delivery, 1, 0, 500, "boom", null, 5, FailureCategory.SERVER_ERROR, "HTTP 500"));
		attemptRepository.save(new DeliveryAttempt(delivery, 2, 0, 502, "boom again", null, 7, FailureCategory.SERVER_ERROR, "HTTP 502"));

		PageResponse<DeliveryAttemptResponse> attempts =
				deliveryService.listAttempts(ours.getId(), delivery.getId(), PageRequest.of(0, 20));

		assertThat(attempts.content()).extracting(DeliveryAttemptResponse::attemptNumber)
				.containsExactly(2, 1);
	}

	@Test
	@DisplayName("another application's delivery is a 404, not a 403")
	void attemptsAreTenancyChecked() {
		Delivery delivery = seed(ours, ourEndpoint, DeliveryState.FAILED);

		// Same id, wrong owner. Must be indistinguishable from "does not exist",
		// or the response confirms which sequential ids are real.
		assertThatThrownBy(() ->
				deliveryService.listAttempts(theirs.getId(), delivery.getId(), PageRequest.of(0, 20)))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Delivery not found");

		assertThatThrownBy(() ->
				deliveryService.listAttempts(theirs.getId(), 999_999L, PageRequest.of(0, 20)))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Delivery not found");
	}

	// ----------------------------------------------------------------- replay

	@Test
	@DisplayName("replaying a FAILED delivery re-queues it and bumps the replay generation")
	void replayRequeuesAndBumpsGeneration() {
		Delivery delivery = seed(ours, ourEndpoint, DeliveryState.FAILED);
		delivery.setAttemptCount(8);
		delivery.setLastError("HTTP 500");
		deliveryRepository.save(delivery);

		DeliveryResponse response = deliveryService.replay(ours.getId(), delivery.getId());

		assertThat(response.state()).isEqualTo(DeliveryState.PENDING);
		assertThat(response.attemptCount()).isZero();          // retry budget restored
		assertThat(response.lastError()).isNull();

		Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
		assertThat(reloaded.getReplayCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("replay keeps the old attempts and separates them by generation")
	void replayPreservesTheAuditTrail() {
		Delivery delivery = seed(ours, ourEndpoint, DeliveryState.FAILED);
		attemptRepository.save(new DeliveryAttempt(delivery, 1, 0, 500, "boom", null, 5, FailureCategory.SERVER_ERROR, "HTTP 500"));

		deliveryService.replay(ours.getId(), delivery.getId());

		Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
		attemptRepository.save(new DeliveryAttempt(reloaded, 1, reloaded.getReplayCount(), 200, "ok", null, 4, null, null));

		// Two attempts both numbered 1. Without replay_count they would be
		// indistinguishable in the audit trail.
		PageResponse<DeliveryAttemptResponse> attempts =
				deliveryService.listAttempts(ours.getId(), delivery.getId(), PageRequest.of(0, 20));

		assertThat(attempts.content()).hasSize(2);
		assertThat(attempts.content()).extracting(DeliveryAttemptResponse::replayCount)
				.containsExactly(1, 0);                        // newest generation first
	}

	@Test
	@DisplayName("a PENDING delivery cannot be replayed - a worker may hold its lease")
	void pendingCannotBeReplayed() {
		Delivery delivery = seed(ours, ourEndpoint, DeliveryState.PENDING);

		assertThatThrownBy(() -> deliveryService.replay(ours.getId(), delivery.getId()))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("PENDING");
	}

	@Test
	@DisplayName("a SUCCEEDED delivery cannot be replayed - there is nothing to retry")
	void succeededCannotBeReplayed() {
		Delivery delivery = seed(ours, ourEndpoint, DeliveryState.SUCCEEDED);

		assertThatThrownBy(() -> deliveryService.replay(ours.getId(), delivery.getId()))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("SUCCEEDED");
	}

	@Test
	@DisplayName("replay is tenancy checked too")
	void replayIsTenancyChecked() {
		Delivery delivery = seed(ours, ourEndpoint, DeliveryState.FAILED);

		assertThatThrownBy(() -> deliveryService.replay(theirs.getId(), delivery.getId()))
				.isInstanceOf(NotFoundException.class);
	}

	// ------------------------------------------------------------ bulk replay

	@Test
	@DisplayName("bulk replay re-queues every terminal delivery and leaves the rest alone")
	void bulkReplayTouchesOnlyTerminalRows() {
		Delivery failed = seed(ours, ourEndpoint, DeliveryState.FAILED);
		Delivery disabled = seed(ours, ourEndpoint, DeliveryState.DISABLED);
		Delivery pending = seed(ours, ourEndpoint, DeliveryState.PENDING);
		Delivery succeeded = seed(ours, ourEndpoint, DeliveryState.SUCCEEDED);

		int replayed = deliveryService
				.replayFailedForEndpoint(ours.getId(), ourEndpoint.getId()).replayed();

		assertThat(replayed).isEqualTo(2);
		assertThat(deliveryRepository.findById(failed.getId()).orElseThrow().getState())
				.isEqualTo(DeliveryState.PENDING);
		assertThat(deliveryRepository.findById(disabled.getId()).orElseThrow().getState())
				.isEqualTo(DeliveryState.PENDING);

		// Untouched: PENDING may be leased right now, SUCCEEDED is done.
		assertThat(deliveryRepository.findById(pending.getId()).orElseThrow().getReplayCount()).isZero();
		assertThat(deliveryRepository.findById(succeeded.getId()).orElseThrow().getState())
				.isEqualTo(DeliveryState.SUCCEEDED);
	}

	@Test
	@DisplayName("bulk replay maintains version by hand - a bulk UPDATE bypasses @Version")
	void bulkReplayBumpsVersion() {
		Delivery failed = seed(ours, ourEndpoint, DeliveryState.FAILED);
		long versionBefore = deliveryRepository.findById(failed.getId()).orElseThrow().getVersion();

		deliveryService.replayFailedForEndpoint(ours.getId(), ourEndpoint.getId());

		assertThat(deliveryRepository.findById(failed.getId()).orElseThrow().getVersion())
				.isGreaterThan(versionBefore);
	}

	@Test
	@DisplayName("bulk replay is refused while the endpoint is still disabled")
	void bulkReplayNeedsAnEnabledEndpoint() {
		seed(ours, ourEndpoint, DeliveryState.FAILED);
		endpointService.disable(ourEndpoint.getId(), "breaker tripped");

		// Replaying into a disabled endpoint would re-trip the breaker and
		// strand the same backlog a second time.
		assertThatThrownBy(() ->
				deliveryService.replayFailedForEndpoint(ours.getId(), ourEndpoint.getId()))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("disabled");
	}

	@Test
	@DisplayName("enable clears the breaker so the backlog can be replayed")
	void enableClearsTheBreakerAndUnblocksReplay() {
		seed(ours, ourEndpoint, DeliveryState.FAILED);
		endpointRepository.incrementAndGetConsecutiveFailures(ourEndpoint.getId());
		endpointService.disable(ourEndpoint.getId(), "breaker tripped");

		endpointService.enable(ours.getId(), ourEndpoint.getId());

		Endpoint reloaded = endpointRepository.findById(ourEndpoint.getId()).orElseThrow();
		assertThat(reloaded.isEnabled()).isTrue();
		assertThat(reloaded.getDisabledReason()).isNull();
		// Must reset, or the breaker re-trips on the first failure of the replay.
		assertThat(reloaded.getConsecutiveFailures()).isZero();

		assertThat(deliveryService.replayFailedForEndpoint(ours.getId(), ourEndpoint.getId())
				.replayed()).isEqualTo(1);
	}

	@Test
	@DisplayName("nothing to replay is a success, not an error")
	void bulkReplayOfNothingIsZero() {
		assertThat(deliveryService.replayFailedForEndpoint(ours.getId(), ourEndpoint.getId())
				.replayed()).isZero();
	}

	@Test
	@DisplayName("bulk replay will not reach another application's endpoint")
	void bulkReplayIsTenancyChecked() {
		assertThatThrownBy(() ->
				deliveryService.replayFailedForEndpoint(theirs.getId(), ourEndpoint.getId()))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Endpoint not found");
	}

	@Test
	@DisplayName("an unknown endpoint is a 404")
	void bulkReplayUnknownEndpoint() {
		assertThatThrownBy(() ->
				deliveryService.replayFailedForEndpoint(ours.getId(), UUID.randomUUID()))
				.isInstanceOf(NotFoundException.class);
	}
}

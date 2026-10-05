package com.dileep.relay.dispatch;

import com.dileep.relay.ai.FailureClassification;
import com.dileep.relay.ai.FailureClassifier;
import com.dileep.relay.ai.FailureContext;
import com.dileep.relay.config.RelayProperties;
import com.dileep.relay.domain.*;
import com.dileep.relay.repository.DeliveryAttemptRepository;
import com.dileep.relay.repository.DeliveryRepository;
import com.dileep.relay.repository.EndpointRepository;
import com.dileep.relay.ratelimit.RateLimiter;
import com.dileep.relay.retry.RetryPolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Polls for due deliveries and sends them. This is the engine of the system.
 *
 * <p><b>The transaction boundary is the thing to get right.</b> The claim query
 * holds row locks until its transaction commits. If you make the HTTP call
 * inside that transaction you hold a database connection open for the length of
 * a network request - twenty slow endpoints and the connection pool is gone,
 * taking the whole application with it.
 *
 * <p>So the loop is three separate steps:
 * <pre>
 *   TX 1  claim a batch          (short, locks released on commit)
 *   ----  send over HTTP         (NO transaction, slow, parallel)
 *   TX 2  record results         (short, per delivery)
 * </pre>
 *
 * <p>This bean always exists. What the {@code relay.dispatch.enabled} flag
 * switches off is {@link DispatchScheduler}, the timer that calls it - not the
 * worker itself. Keeping those separate is what lets a test disable the 1-second
 * poll (so it cannot claim rows mid-assertion) and still drive
 * {@link #pollAndDispatch()} by hand.
 */
@Component
public class DispatchWorker {

	private static final Logger log = LoggerFactory.getLogger(DispatchWorker.class);

	private static final long CLAIM_LINGER_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

	private final DeliveryRepository deliveryRepository;
	private final DeliveryAttemptRepository attemptRepository;
	private final EndpointRepository endpointRepository;
	private final HttpSender httpSender;
	private final RetryPolicy retryPolicy;
	private final RateLimiter rateLimiter;
	private final FailureClassifier classifier;
	private final RelayProperties properties;
	private final TransactionTemplate transactionTemplate;
	private final ExecutorService executor;
	/** One permit per worker thread; a permit is held for the life of one send. */
	private final Semaphore freeWorkers;

	public DispatchWorker(DeliveryRepository deliveryRepository,
	                      DeliveryAttemptRepository attemptRepository,
	                      EndpointRepository endpointRepository,
	                      HttpSender httpSender,
	                      RetryPolicy retryPolicy,
	                      RateLimiter rateLimiter,
	                      FailureClassifier classifier,
	                      RelayProperties properties,
	                      TransactionTemplate transactionTemplate) {
		this.deliveryRepository = deliveryRepository;
		this.attemptRepository = attemptRepository;
		this.endpointRepository = endpointRepository;
		this.httpSender = httpSender;
		this.retryPolicy = retryPolicy;
		this.rateLimiter = rateLimiter;
		this.classifier = classifier;
		this.properties = properties;
		this.transactionTemplate = transactionTemplate;
		this.executor =  Executors.newFixedThreadPool(properties.getDispatch().getWorkerThreads());
		this.freeWorkers = new Semaphore(properties.getDispatch().getWorkerThreads());
	}

	/**
	 * Continuous dispatch: keeps every worker thread busy until nothing is due.
	 * This is what {@link DispatchScheduler} runs.
	 *
	 * <p>Whenever a thread frees up, claim exactly as many rows as there are
	 * free threads and hand them straight over. Two things this fixes over the
	 * batch-and-wait loop in {@link #pollAndDispatch()}, both measured:
	 * <ul>
	 *   <li>no idle poll interval between batches while a backlog exists - that
	 *       alone capped the dispatcher at {@code batchSize} per second;</li>
	 *   <li>no head-of-line blocking - one slow receiver used to hold the whole
	 *       batch, and every other thread, until it timed out.</li>
	 * </ul>
	 *
	 * <p>Rows are never claimed ahead of a free thread, so a claimed row never
	 * waits in a queue and its lease only has to cover one send.
	 *
	 * @return how many deliveries were handed to workers
	 */
	public int drain() {
		int dispatched = 0;
		while (!executor.isShutdown()) {
			int free = awaitFreeWorkers();
			if (free == 0) {
				break;
			}
			List<DispatchTask> tasks = claimBatch(Math.min(free, properties.getDispatch().getBatchSize()));
			if (tasks.isEmpty()) {
				break;
			}
			for (DispatchTask task : tasks) {
				freeWorkers.acquireUninterruptibly();
				try {
					CompletableFuture.runAsync(() -> dispatchOne(task), executor)
							.whenComplete((ignored, error) -> freeWorkers.release());
				} catch (RejectedExecutionException shuttingDown) {
					// Lease expires and another instance (or the next start) picks it up.
					freeWorkers.release();
				}
			}
			dispatched += tasks.size();
		}
		return dispatched;
	}

	/**
	 * Blocks until at least one worker is free; returns how many are, or 0 if
	 * interrupted.
	 *
	 * <p>Then lingers up to {@link #CLAIM_LINGER_NANOS} for a quarter of the
	 * pool to free up. Claiming the instant a single thread frees means one
	 * claim transaction per send, on the one thread that claims - and that
	 * thread became the ceiling. Waiting a few milliseconds turns many
	 * one-row claims into a few larger ones.
	 */
	private int awaitFreeWorkers() {
		try {
			freeWorkers.acquire();
			freeWorkers.release();
			int target = Math.max(1, properties.getDispatch().getWorkerThreads() / 4);
			long until = System.nanoTime() + CLAIM_LINGER_NANOS;
			while (freeWorkers.availablePermits() < target && System.nanoTime() < until) {
				Thread.sleep(1);
			}
			return freeWorkers.availablePermits();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return 0;
		}
	}

	/**
	 * One synchronous round: claim a batch, send it, wait for all of it.
	 * Tests drive the worker through this because it returns only once every
	 * result is recorded. Production runs {@link #drain()}.
	 */
	public void pollAndDispatch() {
        List<DispatchTask> tasks = claimBatch(properties.getDispatch().getBatchSize());
		if(tasks.isEmpty()) {
			log.debug("No deliveries to dispatch");
			return;
		}

		log.debug("claimed {} deliveries to dispatch", tasks.size());

		CompletableFuture<?>[] futures = tasks.stream()
				.map(task -> CompletableFuture.runAsync(() -> dispatchOne(task), executor))
				.toArray(CompletableFuture[]::new);

		CompletableFuture.allOf(futures).join();
	}
	/** TX 1: claim due rows, lease them, and snapshot what the sender needs. */
	private List<DispatchTask> claimBatch(int limit) {
		return transactionTemplate.execute( status -> {
			List<Long> claimedIds = deliveryRepository.claimDueDeliveries(limit);
			if (claimedIds.isEmpty()) {
				return List.of();
			}

			Instant leaseUntil = DbTime.now().plus(leaseDuration(claimedIds.size()));

			return deliveryRepository.findForDispatch(claimedIds).stream().map(delivery ->  {
				delivery.setNextAttemptAt(leaseUntil);
				return toTask(delivery);
			}).toList();
		});
	}

	/**
	 * How long a claimed row stays invisible to other workers.
	 *
	 * <p>The lease has to cover the time a task spends <i>waiting in the executor
	 * queue</i>, not just the time its own HTTP call takes. With a batch of 100
	 * and 8 threads the last task starts roughly twelve send-rounds late; a lease
	 * sized for one send would have expired long before, letting a second
	 * instance claim and re-send a delivery that is still in flight here.
	 * At-least-once tolerates that, but there is no reason to manufacture it.
	 *
	 * <p>One "round" is costed at twice the HTTP timeout, because the connect
	 * timeout and the request timeout can each burn the full budget.
	 */
	private Duration leaseDuration(int claimed) {
		RelayProperties.Dispatch dispatch = properties.getDispatch();
		int rounds = (claimed + dispatch.getWorkerThreads() - 1) / dispatch.getWorkerThreads();
		long worstCaseSendMs = dispatch.getHttpTimeoutMs() * 2L;
		return Duration.ofMillis((rounds + 1) * worstCaseSendMs);
	}

	private void dispatchOne(DispatchTask task) {
		try {
			if (!rateLimiter.tryAcquire(task.endpointId())) {
				defer(task);
				return;
			}
			SendResult result = httpSender.send(task);

			// Classification sits here, between the send and the recording
			// transaction, because it is another network call. Inside TX 2 it
			// would hold a pooled connection for the length of a model round
			// trip - the same mistake as sending HTTP inside the claim.
			FailureClassification classification = result.isSuccess()
					? null
					: classifier.classify(new FailureContext(
							result.httpStatus(), result.responseBody(),
							result.errorMessage(), task.eventType()));

			handleResult(task, result, classification);
		} catch (Exception e) {
			log.error("failed to record result for delivery {}", task.deliveryId(), e);
		}
	}

	/**
	 * Puts a rate-limited delivery back on the queue without touching its
	 * history. No attempt row, no attempt-count increment, no retry budget
	 * spent - we never contacted the endpoint, so nothing was attempted.
	 * Treating back-pressure as a failure would let a busy endpoint burn
	 * through its retries and trip its own breaker.
	 *
	 * <p>The wait is jittered for the same reason retries are: without it every
	 * deferred delivery in the batch returns at the same instant and the next
	 * poll rate-limits the identical set again.
	 */
	private void defer(DispatchTask task) {
		long deferMs = properties.getRateLimit().getDeferMs();
		long jittered = deferMs / 2 + ThreadLocalRandom.current().nextLong(deferMs / 2 + 1);

		transactionTemplate.executeWithoutResult(status ->
				deliveryRepository.findById(task.deliveryId()).ifPresent(delivery ->
						delivery.setNextAttemptAt(DbTime.now().plusMillis(jittered))));

		log.debug("rate limited endpoint {}; delivery {} deferred {}ms",
				task.endpointId(), task.deliveryId(), jittered);
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
	/**
	 * TX 2: always record the attempt, then advance the state machine.
	 *
	 * @param classification null on success, and never null on failure. It is
	 *                       computed by the caller before this transaction opens.
	 */
	void handleResult(DispatchTask task, SendResult result, FailureClassification classification) {
		transactionTemplate.executeWithoutResult(status -> {
			Delivery delivery = deliveryRepository.findById(task.deliveryId()).orElseThrow();

			attemptRepository.save(new DeliveryAttempt(
					delivery,
					task.attemptNumber(),
					task.replayCount(),
					result.httpStatus(),
					result.responseBody(),
					result.errorMessage(),
					result.durationMs(),
					classification == null ? null : classification.category(),
					classification == null ? null : classification.reasoning()
			));

			delivery.setAttemptCount(task.attemptNumber());

			// getId() on a lazy proxy reads the foreign key already in memory -
			// it is the one getter that never triggers initialisation.
			UUID endpointId = delivery.getEndpoint().getId();

			if (result.isSuccess()) {
				delivery.setState(DeliveryState.SUCCEEDED);
				delivery.setLastError(null);
				delivery.setDiagnosis(null, null);
				endpointRepository.resetConsecutiveFailures(endpointId);
				return;
			}

			delivery.setLastError(describe(result));
			if (classification != null) {
				delivery.setDiagnosis(classification.category(), classification.reasoning());
			}

			// The retry budget still governs. A classifier saying "retryable"
			// cannot buy extra attempts, and a receiver-supplied Retry-After
			// cannot extend a delivery past its last one - otherwise a hostile
			// receiver could keep a delivery alive indefinitely.
			Optional<Duration> policyDelay = retryPolicy.nextDelay(task.attemptNumber());

			// A permanent failure skips the remaining budget entirely. Retrying a
			// rejected signature eight more times over an hour fails eight more
			// times; this is the whole point of classifying.
			boolean giveUp = policyDelay.isEmpty()
					|| (classification != null && !classification.retryable());

			if (!giveUp) {
				// The breaker may have tripped while this delivery was in
				// flight. Rescheduling it now would keep hammering an endpoint
				// we have just cut off, so retire it instead.
				if (Boolean.FALSE.equals(endpointRepository.isEnabled(endpointId))) {
					delivery.setState(DeliveryState.DISABLED);
				} else {
					// Honour the receiver's own pacing when it gave one; our
					// jittered backoff is a guess, theirs is an instruction.
					Duration delay = classification != null && classification.hasRetryAfter()
							? classification.retryAfter()
							: policyDelay.get();
					delivery.setNextAttemptAt(DbTime.now().plus(delay));
				}
				return;
			}

			if (classification != null && !classification.retryable()) {
				// Budget was left (otherwise policyDelay would be empty) and we
				// chose not to spend it. That is the saving worth showing.
				if (policyDelay.isPresent()) {
					delivery.addRetriesSaved(properties.getRetry().getMaxAttempts() - task.attemptNumber());
				}
				log.info("delivery {} abandoned early: {} - {}",
						task.deliveryId(), classification.category(), classification.reasoning());
			}

			// Out of retries. This delivery is terminal, and it counts against
			// the endpoint's breaker. The counter is incremented in SQL rather
			// than read-modify-written here, so concurrent workers cannot lose
			// each other's increments.
			delivery.setState(DeliveryState.FAILED);
			int failures = endpointRepository.incrementAndGetConsecutiveFailures(endpointId);

			if (failures >= properties.getBreaker().getFailureThreshold()) {
				String reason = failures + " consecutive failed deliveries; last error: " + describe(result);
				if (endpointRepository.disableIfEnabled(endpointId, reason) == 1) {
					int stranded = deliveryRepository.disablePendingForEndpoint(endpointId);
					log.warn("breaker tripped for endpoint {} after {} consecutive failures; {} queued deliveries disabled",
							endpointId, failures, stranded);
				}
			}
 		});
	}

	private static DispatchTask toTask(Delivery delivery) {
		Message message = delivery.getMessage();
		Endpoint endpoint = delivery.getEndpoint();
		return new DispatchTask(
				delivery.getId(),
				delivery.getAttemptCount() + 1,
				delivery.getReplayCount(),
				message.getId(),
				endpoint.getId(),
				message.getEventType(),
				message.getPayload(),
				endpoint.getUrl(),
				endpoint.getSecret()
		);
	}



	private static String describe(SendResult result) {
		return result.errorMessage() != null
				? result.errorMessage()
				: "HTTP " + result.httpStatus();
	}


}

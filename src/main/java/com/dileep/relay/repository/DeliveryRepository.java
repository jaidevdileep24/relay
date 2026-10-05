package com.dileep.relay.repository;

import com.dileep.relay.domain.Delivery;
import com.dileep.relay.domain.DeliveryState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

	/**
	 * Claim a batch of due deliveries for this worker.
	 *
	 * <p><b>This query is the heart of the dispatcher.</b> Three things make it work:
	 *
	 * <ul>
	 *   <li>{@code FOR UPDATE} - row-locks what it returns, so no other worker
	 *       can take the same rows.</li>
	 *   <li>{@code SKIP LOCKED} - instead of <i>waiting</i> for rows another
	 *       worker already locked, skip straight past them. This is what lets
	 *       N workers pull disjoint batches with zero coordination. Without it,
	 *       workers serialize behind each other and you get no parallelism.</li>
	 *   <li>Partial index {@code idx_delivery_queue} - the index only contains
	 *       PENDING rows, so it stays the size of the backlog rather than the
	 *       size of all history.</li>
	 * </ul>
	 *
	 * <p>Must be called inside a transaction; the locks are held until commit.
	 * Keep that transaction short and <b>never make the HTTP call inside it</b> -
	 * doing so holds a database connection open for the length of a network
	 * request and will exhaust the pool.
	 *
	 * <p>It returns ids, not entities, on purpose. Entities here come back with
	 * {@code message} and {@code endpoint} as lazy proxies, and the dispatcher
	 * has to read both to build its snapshot - two extra SELECTs per row, all of
	 * them issued while these locks are held. Ids plus one
	 * {@link #findForDispatch(List)} is a constant three queries per poll
	 * instead of {@code 2n + 1}.
	 */
	@Query(value = """
		SELECT id FROM delivery
		WHERE state = 'PENDING'
		  AND next_attempt_at <= now()
		ORDER BY next_attempt_at
		LIMIT :batchSize
		FOR UPDATE SKIP LOCKED
		""", nativeQuery = true)
	List<Long> claimDueDeliveries(@Param("batchSize") int batchSize);

	/**
	 * Loads claimed deliveries with everything the sender needs, in one query.
	 *
	 * <p>The {@code JOIN FETCH}es are the point: without them each
	 * {@code getMessage()} / {@code getEndpoint()} in the dispatcher triggers its
	 * own SELECT, and the claim transaction - the one holding row locks - grows
	 * linearly with the batch size.
	 *
	 * <p>Returned in claim order, oldest-due first.
	 */
	@Query("""
		SELECT d FROM Delivery d
		JOIN FETCH d.message
		JOIN FETCH d.endpoint
		WHERE d.id IN :ids
		ORDER BY d.nextAttemptAt
		""")
	List<Delivery> findForDispatch(@Param("ids") List<Long> ids);

	/**
	 * Marks an endpoint's still-queued deliveries DISABLED when its breaker
	 * trips. They are kept rather than deleted - they stay in the audit trail
	 * and are replayable once the endpoint is fixed.
	 *
	 * <p>{@code flushAutomatically} matters: the caller has just set the
	 * delivery that tripped the breaker to FAILED in memory, and that change
	 * must reach the database before this bulk UPDATE runs - otherwise the row
	 * is still PENDING here and would be stamped DISABLED instead.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE Delivery d
		   SET d.state = 'DISABLED'
		 WHERE d.endpoint.id = :endpointId
		   AND d.state = 'PENDING'
		""")
	int disablePendingForEndpoint(@Param("endpointId") UUID endpointId);

	/**
	 * Bulk replay: puts every terminal delivery for one endpoint back on the
	 * queue. This is the operation you actually want after an outage - an
	 * endpoint was broken for an hour, it is fixed, re-send the hour.
	 *
	 * <p>Written as one statement rather than a loop of entity mutations so the
	 * whole thing is a single round trip and cannot half-apply. That means
	 * {@code version} and {@code updated_at} have to be maintained by hand:
	 * a bulk UPDATE bypasses both {@code @Version} and {@code @PreUpdate}, and
	 * leaving the version untouched would let a stale in-memory copy overwrite
	 * the replay.
	 *
	 * <p>{@code state IN ('FAILED','DISABLED')} is the guard that makes this
	 * safe to run twice, and keeps it off PENDING rows - a PENDING row may be
	 * leased by a worker right now, and resetting it under the worker would
	 * duplicate the send.
	 *
	 * <p>{@code clearAutomatically} because the rows it rewrote may already be
	 * in the persistence context holding pre-replay values.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
		UPDATE delivery
		   SET state           = 'PENDING',
		       attempt_count   = 0,
		       replay_count    = replay_count + 1,
		       next_attempt_at = now(),
		       last_error      = NULL,
		       version         = version + 1,
		       updated_at      = now()
		 WHERE endpoint_id = :endpointId
		   AND state IN ('FAILED', 'DISABLED')
		""", nativeQuery = true)
	int replayTerminalForEndpoint(@Param("endpointId") UUID endpointId);

	Page<Delivery> findByMessageId(UUID messageId, Pageable pageable);

	Page<Delivery> findByEndpointIdAndState(UUID endpointId, DeliveryState state, Pageable pageable);

	Page<Delivery> findByMessageApplicationId(UUID applicationId, Pageable pageable);

	Page<Delivery> findByMessageApplicationIdAndState(UUID applicationId, DeliveryState state, Pageable pageable);

	long countByMessageId(UUID messageId);

	interface TriageSavings {
		long getStoppedEarly();
		long getRetriesSaved();
	}

	/** How many deliveries triage stopped early, and how many attempts that skipped. */
	@Query(value = """
		SELECT count(*) FILTER (WHERE d.retries_saved > 0) AS stoppedEarly,
		       coalesce(sum(d.retries_saved), 0)           AS retriesSaved
		  FROM delivery d
		  JOIN message m ON m.id = d.message_id
		 WHERE m.application_id = :applicationId
		""", nativeQuery = true)
	TriageSavings triageSavings(@Param("applicationId") UUID applicationId);
}

package com.dileep.relay.repository;

import com.dileep.relay.domain.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

	List<Endpoint> findByApplicationId(UUID applicationId);

	/**
	 * Enabled endpoints for an application, with event types eagerly fetched.
	 *
	 * <p>The {@code JOIN FETCH} matters: fan-out reads {@code eventTypes} for
	 * every endpoint, and without it you get one extra query per endpoint
	 * (the N+1 problem) on the hottest path in the system.
	 */
	@Query("""
		SELECT DISTINCT e FROM Endpoint e
		LEFT JOIN FETCH e.eventTypes
		WHERE e.application.id = :applicationId
		  AND e.enabled = true
		""")
	List<Endpoint> findEnabledByApplicationId(@Param("applicationId") UUID applicationId);

	/**
	 * Atomic increment of the breaker counter, returning the new value.
	 *
	 * <p>Read-modify-write in Java loses updates: several dispatcher threads
	 * finishing deliveries for the same endpoint all read the same number, all
	 * compute the same successor, and all but one write is discarded (with an
	 * {@code OptimisticLockException} from {@code @Version}). Letting Postgres
	 * do the arithmetic closes the gap - the row is locked for the duration of
	 * the statement, so there is nothing to race.
	 *
	 * <p><b>Do not add {@code @Modifying} here.</b> It looks like it belongs on
	 * an UPDATE, but it makes Spring Data call {@code executeUpdate()}, which
	 * returns the row count and rejects the result set {@code RETURNING}
	 * produces: <i>"A result was returned when none was expected."</i> Left as a
	 * plain {@code @Query}, the statement is read through {@code getSingleResult()}
	 * and the new counter value comes back. Hibernate cannot work out the query
	 * spaces of a native statement, so it flushes the persistence context before
	 * running it anyway.
	 */
	@Query(value = """
		UPDATE endpoint
		   SET consecutive_failures = consecutive_failures + 1,
		       updated_at = now()
		 WHERE id = :id
		RETURNING consecutive_failures
		""", nativeQuery = true)
	int incrementAndGetConsecutiveFailures(@Param("id") UUID id);

	/**
	 * Cleared by any success - the counter is consecutive, not cumulative.
	 * The {@code <> 0} guard makes the common case (already zero) a no-op
	 * rather than a write on every successful delivery.
	 */
	@Modifying(flushAutomatically = true)
	@Query("UPDATE Endpoint e SET e.consecutiveFailures = 0 WHERE e.id = :id AND e.consecutiveFailures <> 0")
	int resetConsecutiveFailures(@Param("id") UUID id);

	/**
	 * Trips the breaker, returning 1 only for the thread that actually flipped
	 * the flag. Same idea as the idempotency fix: let the database arbitrate
	 * instead of check-then-act in Java, so exactly one caller strands the
	 * queued deliveries and writes the log line.
	 */
	@Modifying(flushAutomatically = true)
	@Query("UPDATE Endpoint e SET e.enabled = false, e.disabledReason = :reason WHERE e.id = :id AND e.enabled = true")
	int disableIfEnabled(@Param("id") UUID id, @Param("reason") String reason);

	/**
	 * Current enabled flag, read fresh.
	 *
	 * <p>Needed on the failure path: a delivery already in flight when the
	 * breaker trips must not be rescheduled, or it would keep hammering an
	 * endpoint the breaker has just cut off. The flag cannot be carried on
	 * {@code DispatchTask} because it may change after the task is built.
	 */
	@Query("SELECT e.enabled FROM Endpoint e WHERE e.id = :id")
	Boolean isEnabled(@Param("id") UUID id);
}

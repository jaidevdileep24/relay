package com.dileep.relay.repository;

import com.dileep.relay.domain.FailureClassificationCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FailureClassificationRepository extends JpaRepository<FailureClassificationCache, String> {

	/**
	 * Records a cache hit without loading the row into the persistence context.
	 *
	 * <p>Arithmetic in SQL for the same reason the breaker counter is: several
	 * dispatcher threads hit the same fingerprint at once, and read-modify-write
	 * in Java loses increments.
	 */
	@Modifying
	@Transactional        // a modifying query needs one, and the caller is not proxied
	@Query("""
		UPDATE FailureClassificationCache c
		   SET c.hitCount = c.hitCount + 1,
		       c.lastUsedAt = CURRENT_TIMESTAMP
		 WHERE c.fingerprint = :fingerprint
		""")
	int recordHit(@Param("fingerprint") String fingerprint);

	interface CacheStats {
		long getEntries();
		long getHits();
	}

	/** Global, not per application: one cached verdict serves every tenant. */
	@Query(value = "SELECT count(*) AS entries, coalesce(sum(hit_count), 0) AS hits FROM failure_classification",
			nativeQuery = true)
	CacheStats cacheStats();
}

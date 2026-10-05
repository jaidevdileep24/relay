package com.dileep.relay.repository;

import com.dileep.relay.domain.DeliveryAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

	/**
	 * Newest attempt first. Sorting by attempt number alone stopped being
	 * deterministic once replay existed: a replayed delivery starts again at
	 * attempt 1, so the generation has to lead the sort or the two "attempt 1"
	 * rows come back in whatever order the database felt like.
	 */
	Page<DeliveryAttempt> findByDeliveryIdOrderByReplayCountDescAttemptNumberDesc(
			Long deliveryId, Pageable pageable);

	interface CategoryCount {
		String getCategory();
		long getCount();
	}

	/** Failed attempts per triage category, for one application. */
	@Query(value = """
		SELECT a.failure_category AS category, count(*) AS count
		  FROM delivery_attempt a
		  JOIN delivery d ON d.id = a.delivery_id
		  JOIN message  m ON m.id = d.message_id
		 WHERE m.application_id = :applicationId
		   AND a.failure_category IS NOT NULL
		 GROUP BY a.failure_category
		 ORDER BY count(*) DESC
		""", nativeQuery = true)
	List<CategoryCount> countFailuresByCategory(@Param("applicationId") UUID applicationId);
}

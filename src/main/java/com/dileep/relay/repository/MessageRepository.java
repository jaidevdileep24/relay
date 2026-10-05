package com.dileep.relay.repository;

import com.dileep.relay.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

	/** Used to return the original message when an idempotency key repeats. */
	Optional<Message> findByApplicationIdAndIdempotencyKey(UUID applicationId, String idempotencyKey);
}

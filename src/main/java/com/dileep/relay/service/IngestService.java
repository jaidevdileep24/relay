package com.dileep.relay.service;

import com.dileep.relay.api.dto.CreateMessageRequest;
import com.dileep.relay.api.dto.MessageResponse;

import java.util.UUID;

/**
 * Accepts an event and durably records the intent to deliver it.
 *
 * <p>This is the transactional-outbox write. The whole point is that the
 * Message row and every Delivery row land in <b>one</b> database transaction,
 * so a crash can never leave an event that was accepted but has no record of
 * needing delivery.
 */
public interface IngestService {

	/**
	 * @param idempotencyKey optional; when the same key repeats for the same
	 *                       application, return the original result instead of
	 *                       creating a second message
	 */
	MessageResponse ingest(UUID applicationId, CreateMessageRequest request, String idempotencyKey);
}

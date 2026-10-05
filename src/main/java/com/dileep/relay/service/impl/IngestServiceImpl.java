package com.dileep.relay.service.impl;

import com.dileep.relay.api.dto.CreateMessageRequest;
import com.dileep.relay.api.dto.MessageResponse;
import com.dileep.relay.api.error.NotFoundException;
import com.dileep.relay.api.error.UnprocessableException;
import com.dileep.relay.api.error.ValidationException;
import com.dileep.relay.domain.Application;
import com.dileep.relay.domain.Delivery;
import com.dileep.relay.domain.Message;
import com.dileep.relay.repository.ApplicationRepository;
import com.dileep.relay.repository.DeliveryRepository;
import com.dileep.relay.repository.EndpointRepository;
import com.dileep.relay.repository.MessageRepository;
import com.dileep.relay.service.IngestService;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE OUTBOX WRITE. Everything else in the system depends on this being correct.
 */
@Service
public class IngestServiceImpl implements IngestService {

	private final ApplicationRepository applicationRepository;
	private final EndpointRepository endpointRepository;
	private final MessageRepository messageRepository;
	private final DeliveryRepository deliveryRepository;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;

	public IngestServiceImpl(ApplicationRepository applicationRepository,
	                         EndpointRepository endpointRepository,
	                         MessageRepository messageRepository,
	                         DeliveryRepository deliveryRepository,
	                         ObjectMapper objectMapper,
	                         TransactionTemplate transactionTemplate) {
		this.applicationRepository = applicationRepository;
		this.endpointRepository = endpointRepository;
		this.messageRepository = messageRepository;
		this.deliveryRepository = deliveryRepository;
		this.objectMapper = objectMapper;
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public MessageResponse ingest(UUID applicationId, CreateMessageRequest request, String idempotencyKey) {
		validatePayload(request.payload());
		boolean keyed = idempotencyKey != null && !idempotencyKey.isBlank();

		if(!keyed) {
			return transactionTemplate.execute(status ->  doIngest(applicationId, request, null));
		}

		Optional<MessageResponse> existing = findByKey(applicationId, idempotencyKey, request);
		if(existing.isPresent()) {
			return existing.get();
		}

		try {
			return  transactionTemplate.execute(status -> doIngest( applicationId, request, idempotencyKey));
		} catch (DataIntegrityViolationException e) {
			// Lost the race: a concurrent request with the same key committed
			// first. The unique index is the real enforcement - the check above
			// is only a fast path. Re-read in a NEW transaction and return the
			// winner, so both callers get the same answer.
			return findByKey(applicationId, idempotencyKey, request).orElseThrow(() -> e);
		}
	}

	/**
	 * The original message for this key, if any. A key reused with a DIFFERENT
	 * request is refused rather than answered with the original: replying 202
	 * with the first message would tell the caller their second event was
	 * accepted when it was silently dropped.
	 */
	private Optional<MessageResponse> findByKey(UUID applicationId, String idempotencyKey, CreateMessageRequest request) {
		return messageRepository.findByApplicationIdAndIdempotencyKey(applicationId, idempotencyKey)
				.map(message -> {
					JsonNode stored = readPayload(message);
					if (!message.getEventType().equals(request.eventType())
							|| !stored.equals(NUMERIC_AWARE, request.payload())) {
						throw new UnprocessableException(
								"Idempotency-Key '%s' was already used with a different request".formatted(idempotencyKey));
					}
					return toResponse(message, stored, (int) deliveryRepository.countByMessageId(message.getId()));
				});
	}

	/**
	 * JSONB normalises numbers ({@code 1e2} comes back as {@code 100}), so a
	 * plain node equality would call an identical retry "different".
	 */
	private static final java.util.Comparator<JsonNode> NUMERIC_AWARE = (a, b) ->
			a.isNumber() && b.isNumber()
					? a.decimalValue().compareTo(b.decimalValue())
					: a.equals(b) ? 0 : 1;

	/**
	 * A webhook body is a JSON object or array. {@code "payload": null} slips
	 * past {@code @NotNull} as a NullNode, and U+0000 anywhere - value or key -
	 * is valid JSON that JSONB refuses at INSERT, which used to be a 500.
	 */
	private static void validatePayload(JsonNode payload) {
		if (payload == null || !(payload.isObject() || payload.isArray())) {
			throw new ValidationException("payload must be a JSON object or array");
		}
		if (containsNul(payload)) {
			throw new ValidationException("payload must not contain the NUL character (\\u0000)");
		}
	}

	private static boolean containsNul(JsonNode node) {
		if (node.isTextual()) {
			return node.textValue().indexOf('\0') >= 0;
		}
		if (node.isObject()) {
			for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
				Map.Entry<String, JsonNode> field = it.next();
				if (field.getKey().indexOf('\0') >= 0 || containsNul(field.getValue())) {
					return true;
				}
			}
			return false;
		}
		if (node.isArray()) {
			for (JsonNode element : node) {
				if (containsNul(element)) {
					return true;
				}
			}
		}
		return false;
	}

	private MessageResponse doIngest(UUID applicationId, CreateMessageRequest request, String idempotencyKey) {
		Application application = applicationRepository.findById(applicationId)
				.orElseThrow(() -> new NotFoundException("Application", applicationId));

		Message message = messageRepository.save(
				new Message(application, request.eventType(), request.payload().toString(), idempotencyKey));

		List<Delivery> deliveries = endpointRepository.findEnabledByApplicationId(applicationId).stream()
				.filter(endpoint -> endpoint.subscribesTo(request.eventType()))
				.map(endpoint -> new Delivery(message, endpoint))
				.toList();

		deliveryRepository.saveAll(deliveries);

		return toResponse(message, request.payload(), deliveries.size());
	}
	private JsonNode readPayload(Message message) {
		try {
			return objectMapper.readTree(message.getPayload());
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to deserialize payload for message " + message.getId(), e);
		}
	}

	private  static MessageResponse toResponse(Message message, JsonNode payload, int deliveriesCreated) {
		return new MessageResponse(
				message.getId(),
				message.getEventType(),
				payload,
				deliveriesCreated,
				message.getCreatedAt()
		);
	}
}

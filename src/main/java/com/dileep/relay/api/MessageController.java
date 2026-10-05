package com.dileep.relay.api;

import com.dileep.relay.api.dto.CreateMessageRequest;
import com.dileep.relay.api.dto.MessageResponse;
import com.dileep.relay.api.error.ValidationException;
import com.dileep.relay.service.IngestService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/messages")
public class MessageController {

	/**
	 * The key goes into a unique btree index, and Postgres rejects index rows
	 * over ~2.7kB - a long key used to surface as a 500. 255 is what Stripe allows.
	 */
	static final int MAX_IDEMPOTENCY_KEY = 255;

	private final IngestService ingestService;

	public MessageController(IngestService ingestService) {
		this.ingestService = ingestService;
	}

	/**
	 * Accepts an event. Returns 202 Accepted, not 201 - we are promising to
	 * attempt delivery, not that delivery has happened.
	 */
	@PostMapping
	@ApiResponse(responseCode = "202", description = "Event accepted and queued for delivery")
	public ResponseEntity<MessageResponse> ingest(
			@PathVariable UUID applicationId,
			@Valid @RequestBody CreateMessageRequest request,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

		if (idempotencyKey != null && idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
			throw new ValidationException("Idempotency-Key must be at most %d characters".formatted(MAX_IDEMPOTENCY_KEY));
		}
		MessageResponse response = ingestService.ingest(applicationId, request, idempotencyKey);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
	}
}

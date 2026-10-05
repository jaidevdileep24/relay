package com.dileep.relay.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
		UUID id,
		String eventType,
		JsonNode payload,
		int deliveriesCreated,
		Instant createdAt
) {}

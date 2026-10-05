package com.dileep.relay.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * @param secret returned ONLY on creation, never on subsequent reads.
 */
public record EndpointResponse(
		UUID id,
		String url,
		String description,
		boolean enabled,
		String disabledReason,
		Set<String> eventTypes,
		String secret,
		Instant createdAt
) {}

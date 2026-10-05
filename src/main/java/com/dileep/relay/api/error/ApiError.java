package com.dileep.relay.api.error;

import java.time.Instant;
import java.util.Map;

/**
 * RFC-7807-ish error body. One consistent shape for every failure means
 * clients can parse errors without special-casing each endpoint.
 */
public record ApiError(
		int status,
		String error,
		String message,
		Map<String, String> fieldErrors,
		Instant timestamp
) {
	public static ApiError of(int status, String error, String message) {
		return new ApiError(status, error, message, Map.of(), Instant.now());
	}
}

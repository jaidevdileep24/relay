package com.dileep.relay.api.dto;

import com.dileep.relay.ai.FailureCategory;

import java.time.Instant;

/**
 * @param failureCategory what the AI triage decided, null when the attempt succeeded
 * @param replayCount which replay generation this attempt belongs to. Attempt
 *                    numbers restart at 1 on every replay, so this is what
 *                    tells two "attempt 1" rows apart.
 */
public record DeliveryAttemptResponse(
		Long id,
		int attemptNumber,
		int replayCount,
		Integer httpStatus,
		String responseBody,
		String errorMessage,
		int durationMs,
		Instant attemptedAt,
		FailureCategory failureCategory,
		String failureReasoning
) {}

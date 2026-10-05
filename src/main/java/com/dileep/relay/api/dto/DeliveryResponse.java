package com.dileep.relay.api.dto;

import com.dileep.relay.ai.FailureCategory;
import com.dileep.relay.domain.DeliveryState;

import java.time.Instant;
import java.util.UUID;

/**
 * @param diagnosis    triage verdict on the latest failed attempt; null if none
 *                     or after a success
 * @param diagnosisWhy the classifier's one-line reasoning
 * @param retriesSaved attempts skipped because the failure was judged permanent
 */
public record DeliveryResponse(
		Long id,
		UUID messageId,
		UUID endpointId,
		DeliveryState state,
		int attemptCount,
		Instant nextAttemptAt,
		String lastError,
		FailureCategory diagnosis,
		String diagnosisWhy,
		int retriesSaved,
		Instant createdAt
) {}

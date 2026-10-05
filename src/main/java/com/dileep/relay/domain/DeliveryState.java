package com.dileep.relay.domain;

/**
 * Lifecycle of a single (message x endpoint) delivery.
 *
 * <pre>
 *   PENDING ──success──▶ SUCCEEDED   (terminal)
 *      │
 *      ├──retryable failure──▶ PENDING  (attempt_count++, next_attempt_at pushed out)
 *      │
 *      ├──attempts exhausted──▶ FAILED  (terminal, replayable)
 *      │
 *      └──permanent failure───▶ DISABLED (terminal; endpoint auto-disabled)
 * </pre>
 *
 * Only PENDING rows are visible to the dispatcher's queue query.
 */
public enum DeliveryState {
	PENDING,
	SUCCEEDED,
	FAILED,
	DISABLED
}

package com.dileep.relay.ai;

/**
 * Everything a classifier is allowed to see about a failed attempt.
 *
 * <p>What this leaves out matters more than what it holds: it gets serialized
 * into a prompt and sent to a third party. No endpoint URL, no signing secret,
 * no application or customer id - excluded by construction rather than stripped
 * later, because stripping is something you forget exactly once.
 *
 * @param httpStatus null when no response arrived at all (timeout, DNS, TLS)
 */
public record FailureContext(
		Integer httpStatus,
		String responseBody,
		String errorMessage,
		String eventType
) {

	/** True when there is body text worth paying a model to read. */
	public boolean hasBody() {
		return responseBody != null && !responseBody.isBlank();
	}
}

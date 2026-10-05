package com.dileep.relay.dispatch;

/**
 * Outcome of a single HTTP attempt.
 *
 * @param httpStatus   null when no response was received (timeout, DNS, TLS)
 * @param responseBody truncated response body, for the audit trail and for
 *                     the AI classifier to read later
 * @param errorMessage transport-level error, null on any HTTP response
 * @param durationMs   wall-clock duration of the attempt
 */
public record SendResult(
		Integer httpStatus,
		String responseBody,
		String errorMessage,
		int durationMs
) {
	/** 2xx is success. Everything else - including 3xx - is a failure. */
	public boolean isSuccess() {
		return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
	}
}
